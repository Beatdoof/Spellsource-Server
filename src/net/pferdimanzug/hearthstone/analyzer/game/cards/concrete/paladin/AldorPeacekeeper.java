package net.pferdimanzug.hearthstone.analyzer.game.cards.concrete.paladin;

import net.pferdimanzug.hearthstone.analyzer.game.actions.Battlecry;
import net.pferdimanzug.hearthstone.analyzer.game.cards.MinionCard;
import net.pferdimanzug.hearthstone.analyzer.game.cards.Rarity;
import net.pferdimanzug.hearthstone.analyzer.game.entities.heroes.HeroClass;
import net.pferdimanzug.hearthstone.analyzer.game.entities.minions.Minion;
import net.pferdimanzug.hearthstone.analyzer.game.spells.SetAttackSpell;
import net.pferdimanzug.hearthstone.analyzer.game.targeting.TargetSelection;

public class AldorPeacekeeper extends MinionCard {

	public AldorPeacekeeper() {
		super("Aldor Peacekeeper", 3, 3, Rarity.RARE, HeroClass.PALADIN, 3);
		setDescription("Battlecry: Change an enemy minion's Attack to 1.");
	}

	@Override
	public Minion summon() {
		Minion aldorPeacekepper = createMinion();
		Battlecry battlecry = Battlecry.createBattlecry(new SetAttackSpell(1), TargetSelection.MINIONS);
		aldorPeacekepper.setBattlecry(battlecry);
		return aldorPeacekepper;
	}

}
