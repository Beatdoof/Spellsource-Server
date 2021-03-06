package net.demilich.metastone.game.behaviour.human;

import com.hiddenswitch.spellsource.draft.HumanDraftBehaviour;
import net.demilich.metastone.game.cards.Card;

import java.util.List;

public class DraftSelectionOptions {
	private final HumanDraftBehaviour behaviour;
	private final List<Card> offeredCards;

	public DraftSelectionOptions(HumanDraftBehaviour behaviour, List<Card> offeredCards) {
		this.behaviour = behaviour;
		this.offeredCards = offeredCards;
	}

	public HumanDraftBehaviour getBehaviour() {
		return behaviour;
	}

	public List<Card> getOfferedCards() {
		return offeredCards;
	}
}
