package us.rapidskies.common.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class PlusColorTest {

    @Test
    void paletteWidensWithTier() {
        // PLAN.md §6.1: 6 / 11 / 14 colors.
        assertEquals(0, PlusColor.availableFor(Rank.DEFAULT).size());
        assertEquals(6, PlusColor.availableFor(Rank.RAPID_PLUS).size());
        assertEquals(11, PlusColor.availableFor(Rank.RAPID_PLUS_PLUS).size());
        assertEquals(14, PlusColor.availableFor(Rank.RAPID_PLUS_PLUS_PLUS).size());
    }

    @Test
    void redShadesAreReservedForStaff() {
        for (PlusColor c : PlusColor.values()) {
            assertFalse(Set.of(NamedTextColor.RED, NamedTextColor.DARK_RED).contains(c.color()), c.name());
        }
        assertTrue(PlusColor.byId("red").isEmpty());
        assertTrue(PlusColor.byId("dark_red").isEmpty());
    }

    @Test
    void defaultIsAvailableToEveryRank() {
        assertTrue(PlusColor.DEFAULT.availableTo(Rank.RAPID_PLUS));
    }

    @Test
    void lapsedChoiceFallsBackWithoutBeingLost() {
        PlusColor stored = PlusColor.BLACK; // a Rapid+++ color
        assertEquals(PlusColor.BLACK, PlusColor.effective(stored, Rank.RAPID_PLUS_PLUS_PLUS));
        assertEquals(PlusColor.DEFAULT, PlusColor.effective(stored, Rank.RAPID_PLUS));
        assertEquals(PlusColor.DEFAULT, PlusColor.effective(null, Rank.RAPID_PLUS_PLUS));
    }

    @Test
    void parsesFriendlyIds() {
        assertEquals(PlusColor.LIGHT_PURPLE, PlusColor.byId("Light-Purple").orElseThrow());
        assertEquals(PlusColor.DARK_AQUA, PlusColor.byId(" dark aqua ").orElseThrow());
        assertTrue(PlusColor.byId(null).isEmpty());
        assertTrue(PlusColor.byId("rainbow").isEmpty());
    }
}
