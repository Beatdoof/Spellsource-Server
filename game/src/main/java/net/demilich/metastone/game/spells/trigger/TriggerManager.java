package net.demilich.metastone.game.spells.trigger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import co.paralleluniverse.fibers.Suspendable;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.events.HasValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.demilich.metastone.game.events.GameEvent;
import net.demilich.metastone.game.events.GameEventType;
import net.demilich.metastone.game.spells.aura.Aura;
import net.demilich.metastone.game.targeting.EntityReference;

public class TriggerManager implements Cloneable, Serializable {
	public static Logger logger = LoggerFactory.getLogger(TriggerManager.class);

	private final List<Trigger> triggers = new ArrayList<Trigger>();

	public TriggerManager() {
	}

	private TriggerManager(TriggerManager otherTriggerManager) {
		for (Trigger gameEventListener : otherTriggerManager.triggers) {
			triggers.add(gameEventListener.clone());
		}
	}

	public void addTrigger(Trigger trigger) {
		triggers.add(trigger);
		if (triggers.size() > 100) {
			logger.warn("Warning, many triggers: " + triggers.size() + " adding one of type: " + trigger);
		}
	}

	@Override
	public TriggerManager clone() {
		return new TriggerManager(this);
	}

	public void dispose() {
		triggers.clear();
	}

	@Suspendable
	public void fireGameEvent(GameEvent event, List<Trigger> gameTriggers) {
		if (event instanceof HasValue) {
			event.getGameContext().getEventValueStack().push(((HasValue) event).getValue());
		} else {
			event.getGameContext().getEventValueStack().push(0);
		}

		if (event.getEventTarget() != null) {
			event.getGameContext().getEventTargetStack().push(event.getEventTarget().getReference());
		} else {
			event.getGameContext().getEventTargetStack().push(EntityReference.NONE);
		}

		if (event.getEventSource() != null) {
			event.getGameContext().getEventSourceStack().push(event.getEventSource().getReference());
		} else {
			event.getGameContext().getEventSourceStack().push(EntityReference.NONE);
		}

		List<Trigger> triggers = new ArrayList<>(this.triggers);
		if (gameTriggers != null
				&& gameTriggers.size() > 0) {
			// Game triggers execute first and do not serialize
			triggers.addAll(0, gameTriggers);
		}
		List<Trigger> eventTriggers = new ArrayList<Trigger>();
		List<Trigger> removeTriggers = new ArrayList<Trigger>();

		for (Trigger trigger : triggers) {
			EntityReference hostReference = trigger.getHostReference();
			if (hostReference == null) {
				hostReference = EntityReference.NONE;
			}
			event.getGameContext().getTriggerHostStack().push(hostReference);
			// In order to stop premature expiration, check
			// for a oneTurnOnly tag and that it isn't delayed.
			if (event.getEventType() == GameEventType.TURN_END) {
				if (trigger.oneTurnOnly() &&
						!trigger.interestedIn(GameEventType.TURN_START) &&
						!trigger.interestedIn(GameEventType.TURN_END)) {
					trigger.expire();
				}
			}
			if (trigger.isExpired()) {
				removeTriggers.add(trigger);
			}

			if (trigger.interestedIn(event.getEventType())
					&& triggers.contains(trigger) && trigger.canFire(event)) {
				eventTriggers.add(trigger);
			}
			event.getGameContext().getTriggerHostStack().pop();
		}

		for (Trigger trigger : eventTriggers) {
			EntityReference hostReference = trigger.getHostReference();
			if (hostReference == null) {
				hostReference = EntityReference.NONE;
			}

			event.getGameContext().getTriggerHostStack().push(hostReference);

			if (trigger.canFireCondition(event) && triggers.contains(trigger)) {
				trigger.onGameEvent(event);
			}

			// we need to double check here if the trigger still exists;
			// after all, a previous trigger may have removed it (i.e. double
			// corruption)
			if (trigger.isExpired()) {
				removeTriggers.add(trigger);
			}

			try {
				event.getGameContext().getTriggerHostStack().pop();
			} catch (NoSuchElementException | IndexOutOfBoundsException noSuchElement) {
				// If the game is over, don't worry about the host stack not having an item.
				logger.error("fireGameEvent loop", noSuchElement);
				continue;
			}
		}

		triggers.removeAll(removeTriggers);

		try {
			event.getGameContext().getEventValueStack().pop();
			event.getGameContext().getEventSourceStack().pop();
			event.getGameContext().getEventTargetStack().pop();
		} catch (IndexOutOfBoundsException | NoSuchElementException ex) {
			logger.error("fireGameEvent", ex);
		}
	}

	private List<Trigger> getListSnapshot(List<Trigger> triggerList) {
		return new ArrayList<>(triggerList);
	}

	public List<Trigger> getTriggersAssociatedWith(EntityReference entityReference) {
		List<Trigger> relevantTriggers = new ArrayList<>();
		for (Trigger trigger : triggers) {
			if (trigger.getHostReference().equals(entityReference)) {
				relevantTriggers.add(trigger);
			}
		}
		return relevantTriggers;
	}

	public void removeTrigger(Trigger trigger) {
		if (!triggers.remove(trigger)) {
			throw new RuntimeException("Trigger unexpectedly was unable to be removed.");
		}

		trigger.expire();
	}

	public void removeTriggersAssociatedWith(EntityReference entityReference, boolean removeAuras, GameContext context) {
		for (Trigger trigger : getListSnapshot(triggers)) {
			if (trigger.getHostReference().equals(entityReference)) {
				if (!removeAuras && trigger instanceof Aura) {
					continue;
				}
				trigger.onRemove(context);
				trigger.expire();
				triggers.remove(trigger);
			}
		}
	}

	public List<Trigger> getTriggers() {
		return triggers;
	}

	/**
	 * Expires all triggers in the game, to prevent end-of-game triggering from causing the game to glitch out
	 */
	public void expireAll() {
		for (Trigger trigger : triggers) {
			trigger.expire();
		}
	}
}
