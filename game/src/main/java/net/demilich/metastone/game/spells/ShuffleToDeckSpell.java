package net.demilich.metastone.game.spells;

import co.paralleluniverse.fibers.Suspendable;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.Player;
import net.demilich.metastone.game.cards.Card;
import net.demilich.metastone.game.cards.CardCatalogue;
import net.demilich.metastone.game.cards.CardList;
import net.demilich.metastone.game.cards.CardArrayList;
import net.demilich.metastone.game.entities.Entity;
import net.demilich.metastone.game.events.CardShuffledEvent;
import net.demilich.metastone.game.spells.desc.SpellArg;
import net.demilich.metastone.game.spells.desc.SpellDesc;
import net.demilich.metastone.game.spells.desc.filter.EntityFilter;
import net.demilich.metastone.game.utils.Attribute;
import net.demilich.metastone.game.utils.AttributeMap;

public class ShuffleToDeckSpell extends Spell {

	@Override
	@Suspendable
	protected void onCast(GameContext context, Player player, SpellDesc desc, Entity source, Entity target) {
		int copies = desc.getValue(SpellArg.HOW_MANY, context, player, target, source, 1);
		SpellDesc subSpell = (SpellDesc) (desc.getOrDefault(SpellArg.SPELL, NullSpell.create()));
		boolean quiet = desc.getBool(SpellArg.EXCLUSIVE);


		if (target != null) {
			// Implements Kingsbane in a very basic way, since weapons pretty much only get enchanted for attack,
			// durability, windfury, lifesteal and poisonous bonuses.
			AttributeMap map = SpellUtils.processKeptEnchantments(target, new AttributeMap());
			for (int i = 0; i < copies; i++) {
				final Card copy = target.getSourceCard().getCopy();
				copy.getAttributes().putAll(map);
				context.getLogic().shuffleToDeck(player, copy, quiet);
				copy.setAttribute(Attribute.SHUFFLED);
				SpellUtils.castChildSpell(context, player, subSpell, source, target, copy);
				context.getLogic().removeAttribute(copy, Attribute.SHUFFLED);
			}
			return;
		}

		CardList cards = SpellUtils.getCards(context, player, target, source, desc,
				desc.getValue(SpellArg.VALUE, context, player, target, source, 1));

		for (int i = 0; i < copies; i++) {
			for (Card original : cards) {
				final Card copy = original.getCopy();
				copy.setAttribute(Attribute.SHUFFLED);
				context.getLogic().shuffleToDeck(player, copy, quiet);
			}
		}

		for (Card card : player.getDeck()) {
			if (card.hasAttribute(Attribute.SHUFFLED)) {
				SpellUtils.castChildSpell(context, player, subSpell, source, target, card);
				context.getLogic().removeAttribute(card, Attribute.SHUFFLED);
			}
		}
	}
}

