package us.rapidskies.core.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import us.rapidskies.common.rank.PlusColor;

class RapidPlaceholdersTest {

    @Test
    void plusColorIsTheLegacyCode() {
        assertEquals("§e", RapidPlaceholders.legacyCode(PlusColor.YELLOW));
        assertEquals("§3", RapidPlaceholders.legacyCode(PlusColor.DARK_AQUA));
        assertEquals("§7", RapidPlaceholders.legacyCode(PlusColor.GRAY));
        // A gradient falls back to its first color (Twilight starts blue, Ocean aqua).
        assertEquals("§9", RapidPlaceholders.legacyCode(PlusColor.TWILIGHT));
        assertEquals("§b", RapidPlaceholders.legacyCode(PlusColor.OCEAN));
    }

    @Test
    void prettyNames() {
        assertEquals("Light Purple", PlusChoice.pretty(PlusColor.LIGHT_PURPLE));
        assertEquals("Rapid+++", PlusChoice.rankName(us.rapidskies.common.rank.Rank.RAPID_PLUS_PLUS_PLUS));
    }
}
