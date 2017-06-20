package com.hiddenswitch.proto3.net.impl.util;

import co.paralleluniverse.fibers.Suspendable;
import com.hiddenswitch.proto3.net.Logic;
import com.hiddenswitch.proto3.net.common.*;
import com.hiddenswitch.proto3.net.util.RpcClient;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.sync.Sync;
import net.demilich.metastone.NotificationProxy;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.Player;
import net.demilich.metastone.game.TurnState;
import net.demilich.metastone.game.actions.ActionType;
import net.demilich.metastone.game.actions.GameAction;
import net.demilich.metastone.game.cards.Card;
import net.demilich.metastone.game.decks.DeckFormat;
import net.demilich.metastone.game.events.GameEvent;
import net.demilich.metastone.game.logic.GameLogic;
import net.demilich.metastone.game.spells.trigger.SpellTrigger;
import net.demilich.metastone.game.spells.trigger.Trigger;
import net.demilich.metastone.game.targeting.IdFactory;
import net.demilich.metastone.game.visuals.TriggerFired;
import org.apache.commons.lang3.RandomStringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A networked game context from the server's point of view.
 * <p>
 * In addition to storing game state, this class also stores references to {@link Client} objects that (1) get notified
 * when game state changes and how, and (2) allow this class to {@link net.demilich.metastone.game.behaviour.Behaviour#requestAction(GameContext,
 * Player, List)} and {@link net.demilich.metastone.game.behaviour.Behaviour#mulligan(GameContext, Player, List)} over a
 * network.
 * <p>
 * This class also automatically adds support for persistence effects written on cards using a {@link
 * PersistenceTrigger}.
 */
public class ServerGameContext extends GameContext {
	private final String gameId;
	private Map<Player, Client> listenerMap = new HashMap<>();
	private final Map<CallbackId, GameplayRequest> requestCallbacks = new HashMap<>();
	private boolean isRunning = true;
	private final transient HashSet<Handler<ServerGameContext>> onGameEndHandlers = new HashSet<>();
	private final List<Trigger> gameTriggers = new ArrayList<>();
	private final transient RpcClient<Logic> logic;

	/**
	 * {@inheritDoc}
	 * <p>
	 * Additionally, this class uses the provided {@link RpcClient} to implement persistence effects.
	 *
	 * @param player1    The first player.
	 * @param player2    The second player.
	 * @param deckFormat The legal cards that can be played.
	 * @param gameId     The game ID that corresponds to this game context.
	 * @param logic      The {@link RpcClient} on which this trigger will make {@link Logic} requests.
	 */
	public ServerGameContext(Player player1, Player player2, DeckFormat deckFormat, String gameId, RpcClient<Logic> logic) {
		// The player's IDs are set here
		super(player1, player2, new GameLogicAsync(), deckFormat);
		if (player1.getId() == player2.getId()
				|| player1.getId() == IdFactory.UNASSIGNED
				|| player2.getId() == IdFactory.UNASSIGNED) {
			player1.setId(IdFactory.PLAYER_1);
			player2.setId(IdFactory.PLAYER_2);
		}
		NotificationProxy.init(new NullNotifier());
		this.gameId = gameId;
		this.logic = logic;

		enablePersistenceEffects();
	}

	/**
	 * Enables this match to track persistence effects.
	 *
	 * @see com.hiddenswitch.proto3.net.impl.util.PersistenceTrigger for more about how this method is used.
	 */
	private void enablePersistenceEffects() {
		this.getGameTriggers().add(new PersistenceTrigger(logic, this, this.gameId));
	}

	public GameLogicAsync getNetworkGameLogic() {
		return (GameLogicAsync) getLogic();
	}

	public void setUpdateListener(Player player, Client listener) {
		listenerMap.put(player, listener);
	}

	@Override
	public void init() throws UnsupportedOperationException {
		throw new UnsupportedOperationException("ServerGameContext::init is unsupported.");
	}

	@Override
	@Suspendable
	protected void startTurn(int playerId) {
		super.startTurn(playerId);
		GameState state = new GameState(this, TurnState.TURN_IN_PROGRESS);
		getListenerMap().get(getPlayer1()).onUpdate(state);
		getListenerMap().get(getPlayer2()).onUpdate(state);
	}

	@Suspendable
	public void endTurn() {
		logger.debug("Ending turn: " + getActivePlayer().getId());
		super.endTurn();
		this.onGameStateChanged();
		logger.debug("Active player changed to: " + getActivePlayerId());
		getListenerMap().get(getPlayer1()).onTurnEnd(getActivePlayer(), getTurn(), getTurnState());
		getListenerMap().get(getPlayer2()).onTurnEnd(getActivePlayer(), getTurn(), getTurnState());
	}

	private Player getNonActivePlayer() {
		return getOpponent(getActivePlayer());
	}

	@Override
	public void play() throws UnsupportedOperationException {
		throw new UnsupportedOperationException("ServerGameContext::play should not be called. Use ::networkPlay instead.");
	}

	@Suspendable
	public void networkPlay() {
		logger.debug("Game starts: " + getPlayer1().getName() + " VS. " + getPlayer2().getName());
		int startingPlayerId = getLogic().determineBeginner(PLAYER_1, PLAYER_2);
		setActivePlayerId(getPlayer(startingPlayerId).getId());
		logger.debug(getActivePlayer().getName() + " begins");

		updateActivePlayers();

		// Make sure the players are initialized before sending the original player updates.
		getNetworkGameLogic().initializePlayer(IdFactory.PLAYER_1);
		getNetworkGameLogic().initializePlayer(IdFactory.PLAYER_2);

		setLocalPlayer1();
		setLocalPlayer2();
		updateClientsWithGameState();

		Future<Void> init1 = Future.future();
		Future<Void> init2 = Future.future();

		getNetworkGameLogic().initAsync(getActivePlayerId(), true, p -> init1.complete());
		getNetworkGameLogic().initAsync(getOpponent(getActivePlayer()).getId(), false, p -> init2.complete());

		// Mulligan simultaneously now
		CompositeFuture.join(init1, init2).setHandler(cf -> {
			Recursive<Runnable> playTurnLoop = new Recursive<>();
			playTurnLoop.func = () -> {
				if (!isRunning) {
					endGame();
					return;
				}

				// Check if the game has been decided right at the end of the player's turn
				if (gameDecided()) {
					endGame();
					return;
				}

				startTurn(getActivePlayerId());
				Recursive<Handler<Boolean>> actionLoop = new Recursive<>();

				actionLoop.func = hasMoreActions -> {
					if (!isRunning) {
						endGame();
						return;
					}
					if (hasMoreActions) {
						networkedPlayTurn(actionLoop.func);
					} else {
						if (getTurn() > GameLogic.TURN_LIMIT
								|| gameDecided()) {
							endGame();
						} else {
							playTurnLoop.func.run();
						}
					}
				};

				networkedPlayTurn(actionLoop.func);
			};

			// Start the active player's turn once the game is initialized.
			playTurnLoop.func.run();
		});
	}

	protected void setLocalPlayer2() {
		getListenerMap().get(getPlayer2()).setPlayers(getPlayer2(), getPlayer1());
	}

	protected void setLocalPlayer1() {
		getListenerMap().get(getPlayer1()).setPlayers(getPlayer1(), getPlayer2());
	}

	protected void updateActivePlayers() {
		getListenerMap().get(getActivePlayer()).onActivePlayer(getActivePlayer());
		getListenerMap().get(getNonActivePlayer()).onActivePlayer(getActivePlayer());
	}

	@Override
	public boolean takeActionInTurn() throws UnsupportedOperationException {
		throw new UnsupportedOperationException("ServerGameContext::playTurn should not be called.");
	}

	@Suspendable
	protected void networkedPlayTurn(Handler<Boolean> callback) {
		if (!isRunning) {
			return;
		}

		try {
			setActionsThisTurn(getActionsThisTurn() + 1);

			if (getActionsThisTurn() > 99) {
				logger.warn("Turn has been forcefully ended after {} actions", getActionsThisTurn());
				endTurn();
				callback.handle(false);
				return;
			}

			if (getLogic().hasAutoHeroPower(getActivePlayerId())) {
				performAction(getActivePlayerId(), getAutoHeroPowerAction());
				callback.handle(true);
				return;
			}

			List<GameAction> validActions = getValidActions();
			if (validActions.size() == 0) {
				endTurn();
				callback.handle(false);
				return;
			}

			NetworkBehaviour networkBehaviour = (NetworkBehaviour) getActivePlayer().getBehaviour();
			networkBehaviour.requestActionAsync(this, getActivePlayer(), getValidActions(), action -> {
				if (action == null) {
					throw new RuntimeException("Behaviour " + getActivePlayer().getBehaviour().getName() + " selected NULL action while "
							+ getValidActions().size() + " actions were available");
				}
				performAction(getActivePlayerId(), action);
				callback.handle(action.getActionType() != ActionType.END_TURN);
			});
		} catch (NullPointerException e) {
			if (isRunning) {
				throw e;
			}
		}
	}

	@Override
	@Suspendable
	protected void onGameStateChanged() {
		updateClientsWithGameState();
	}

	public void updateClientsWithGameState() {
		GameState state = getGameStateCopy();
		getListenerMap().get(getPlayer1()).onUpdate(state);
		getListenerMap().get(getPlayer2()).onUpdate(state);
	}

	@Override
	@Suspendable
	public void fireGameEvent(GameEvent gameEvent) {
		getEventStack().push(gameEvent);
		getListenerMap().get(getPlayer1()).onNotification(gameEvent);
		getListenerMap().get(getPlayer2()).onNotification(gameEvent);
		super.fireGameEvent(gameEvent, gameTriggers);
		getEventStack().pop();
		if (getEventStack().isEmpty()) {
			getListenerMap().get(getPlayer1()).lastEvent();
			getListenerMap().get(getPlayer2()).lastEvent();
		}
	}

	@Override
	@Suspendable
	public void onSpellTriggerFired(SpellTrigger trigger) {
		TriggerFired triggerFired = new TriggerFired(this, trigger);
		getListenerMap().get(getPlayer1()).onNotification(triggerFired);
		getListenerMap().get(getPlayer2()).onNotification(triggerFired);
	}

	/**
	 * Request an action from a {@link Client} that corresponds to the given {@code playerId}.
	 *
	 * @param state    The game state to send.
	 * @param playerId The player ID to request from.
	 * @param actions  The valid actions to choose from.
	 * @param callback A handler for the response.
	 */
	@Suspendable
	@Override
	public void networkRequestAction(GameState state, int playerId, List<GameAction> actions, Handler<GameAction> callback) {
		String id = RandomStringUtils.randomAscii(8);
		logger.debug("Requesting action with callback {} for playerId {}", id, playerId);
		requestCallbacks.put(new CallbackId(id, playerId), new GameplayRequest(GameplayRequestType.ACTION, state, actions, callback));
		getListenerMap().get(getPlayer(playerId)).onRequestAction(id, state, actions);
	}

	/**
	 * Request an action from the {@link Client} that corresponds to the given {@code player}.
	 *
	 * @param player       The player to request from.
	 * @param starterCards The cards the player started with.
	 * @param callback     A handler for the response.
	 */
	@Override
	public void networkRequestMulligan(Player player, List<Card> starterCards, Handler<List<Card>> callback) {
		logger.debug("Requesting mulligan for playerId {} hashCode {}", player.getId(), player.hashCode());
		String id = RandomStringUtils.randomAscii(8);
		requestCallbacks.put(new CallbackId(id, player.getId()), new GameplayRequest(GameplayRequestType.MULLIGAN, starterCards, callback));
		getListenerMap().get(player).onMulligan(id, getGameStateCopy(), starterCards, player.getId());
	}

	/**
	 * Handles the chosen game action from a client.
	 *
	 * @param messageId The ID of the message used to request the action.
	 * @param action    The action chosen.
	 */
	@Suspendable
	@SuppressWarnings("unchecked")
	public void onActionReceived(String messageId, GameAction action) {
		logger.debug("Accepting action for callback {}", messageId);
		final Handler handler = requestCallbacks.get(CallbackId.of(messageId)).handler;
		requestCallbacks.remove(CallbackId.of(messageId));
		Sync.fiberHandler((Handler<GameAction>) handler).handle(action);
		logger.debug("Action executed for callback {}", messageId);
	}

	/**
	 * Handles the cards that the player chose to discard.
	 *
	 * @param messageId      The ID of the message used to request the mulligan.
	 * @param player         The player that requested the mulligan.
	 * @param discardedCards The cards the player discarded.
	 */
	@SuppressWarnings("unchecked")
	public void onMulliganReceived(String messageId, Player player, List<Card> discardedCards) {
		logger.debug("Mulligan received from {}", player.getName());
		final Handler handler = requestCallbacks.get(CallbackId.of(messageId)).handler;
		requestCallbacks.remove(CallbackId.of(messageId));
		((Handler<List<Card>>) handler).handle(discardedCards);
	}

	@Override
	public void sendGameOver(Player recipient, Player winner) {
		getListenerMap().get(recipient).onGameEnd(winner);
	}

	@Override
	protected void notifyPlayersGameOver() {
		for (Player player : getPlayers()) {
			NetworkBehaviour networkBehaviour = (NetworkBehaviour) player.getBehaviour();
			networkBehaviour.onGameOverAuthoritative(this, player.getId(), getWinner() != null ? getWinner().getId() : -1);
		}
	}

	@Override
	public String toString() {
		return String.format("[ServerGameContext gameId=%s turn=%d]", getGameId(), getTurn());
	}

	@Override
	public String getGameId() {
		return gameId;
	}

	public Map<Player, Client> getListenerMap() {
		return Collections.unmodifiableMap(listenerMap);
	}

	@Suspendable
	@SuppressWarnings("unchecked")
	public void onPlayerReconnected(Player player, Client client) {
		// Update the client
		setUpdateListener(player, client);

		// Don't replace the player object! We don't need it
		// Resynchronize the game states
		if (player.getId() == PLAYER_1) {
			setLocalPlayer1();
		} else if (player.getId() == PLAYER_2) {
			setLocalPlayer2();
		}

		updateActivePlayers();
		onGameStateChanged();
		retryRequests(player);
	}

	@Suspendable
	@SuppressWarnings("unchecked")
	private void retryRequests(Player player) {
		List<Map.Entry<CallbackId, GameplayRequest>> requests = requestCallbacks.entrySet().stream().filter(e -> e.getKey().playerId == player.getId()).collect(Collectors.toList());
		if (requests.size() > 0) {
			requestCallbacks.entrySet().removeIf(e -> e.getKey().playerId == player.getId());
			requests.forEach(e -> {
				final GameplayRequest request = e.getValue();
				switch (request.type) {
					case ACTION:
						networkRequestAction(request.state, e.getKey().playerId, request.actions, request.handler);
						break;
					case MULLIGAN:
						networkRequestMulligan(getPlayer(e.getKey().playerId), request.starterCards, request.handler);
						break;
					default:
						logger.error("Unknown gameplay request was pending.");
						break;
				}
			});
		}
	}

	@Override
	@Suspendable
	public void endGame() {
		super.endGame();
		onGameEndHandlers.forEach(h -> {
			h.handle(this);
		});
	}

	@Suspendable
	public void handleEndGame(Handler<ServerGameContext> handler) {
		onGameEndHandlers.add(handler);
	}

	@Suspendable
	public void kill() {
		if (!gameDecided()) {
			endGame();
		}
		isRunning = false;
		// Clear out even more stuff
		dispose();
	}

	@Override
	public void dispose() {
		super.dispose();
		// Clear out the request callbacks
		requestCallbacks.clear();
		// Clear the listeners
		listenerMap.clear();
		onGameEndHandlers.clear();
	}

	public List<Trigger> getGameTriggers() {
		return gameTriggers;
	}

	public GameAction getActionForMessage(String messageId, int actionIndex) {
		return requestCallbacks.get(CallbackId.of(messageId)).actions.get(actionIndex);
	}

	public void onMulliganReceived(String messageId, List<Integer> discardedCardIndices) {
		// Get the player reference
		final Optional<CallbackId> reqResult = requestCallbacks.keySet().stream().filter(ci -> ci.id.equals(messageId)).findFirst();
		if (!reqResult.isPresent()) {
			throw new RuntimeException();
		}
		CallbackId reqId = reqResult.get();
		List<Card> discardedCards = new ArrayList<>();
		GameplayRequest request = requestCallbacks.get(reqId);
		discardedCards.addAll(discardedCardIndices.stream().map(i -> request.starterCards.get(i)).collect(Collectors.toList()));
		onMulliganReceived(messageId, getPlayer(reqId.playerId), discardedCards);
	}

}