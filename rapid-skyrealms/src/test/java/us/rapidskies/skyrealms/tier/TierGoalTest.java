package us.rapidskies.skyrealms.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class TierGoalTest {

    @Test
    void parsesEveryType() {
        TierGoal mine = TierGoal.parse("mine cobblestone 1,000");
        assertEquals(TierGoal.Type.MINE, mine.type());
        assertEquals(Material.COBBLESTONE, mine.material());
        assertEquals(1000, mine.target());
        assertEquals("mine:cobblestone", mine.id());
        assertEquals("level", TierGoal.parse("level 10").id());
        assertEquals("harvest:wheat", TierGoal.parse("HARVEST minecraft:wheat 256").id());
        for (String s : new String[] {"sell 2500", "fish 64", "expedition 3", "route 2"}) {
            assertEquals(s.split(" ")[0], TierGoal.parse(s).id());
        }
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> TierGoal.parse("dance 5"));
        assertThrows(IllegalArgumentException.class, () -> TierGoal.parse("mine 5"), "mine needs a block");
        assertThrows(IllegalArgumentException.class, () -> TierGoal.parse("level stone 5"), "level takes none");
        assertThrows(IllegalArgumentException.class, () -> TierGoal.parse("sell 0"));
        assertThrows(IllegalArgumentException.class, () -> TierGoal.parse("mine nothing 5"));
    }

    @Test
    void countsOnlyItsOwnKind() {
        TierGoal mine = TierGoal.parse("mine iron_ore 10");
        assertTrue(mine.counts(TierGoal.Type.MINE, Material.IRON_ORE));
        assertFalse(mine.counts(TierGoal.Type.MINE, Material.COBBLESTONE));
        assertFalse(mine.counts(TierGoal.Type.HARVEST, Material.IRON_ORE));
        TierGoal sell = TierGoal.parse("sell 100");
        assertTrue(sell.counts(TierGoal.Type.SELL, null));
        assertEquals(100, sell.clamp(150));
        assertEquals(0, sell.clamp(-3));
        assertTrue(sell.done(100));
        assertFalse(sell.done(99));
    }

    @Test
    void cropsCountAsTheirItems() {
        assertEquals(Material.CARROT, TierService.cropItem(Material.CARROTS));
        assertEquals(Material.COCOA_BEANS, TierService.cropItem(Material.COCOA));
        assertEquals(Material.WHEAT, TierService.cropItem(Material.WHEAT));
        assertEquals(Material.CACTUS, TierService.cropItem(Material.CACTUS));
    }
}
