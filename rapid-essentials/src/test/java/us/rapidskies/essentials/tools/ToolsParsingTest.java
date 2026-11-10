package us.rapidskies.essentials.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

class ToolsParsingTest {

    @Test
    void gameModeNames() {
        assertEquals(GameMode.CREATIVE, GameModes.parse("creative"));
        assertEquals(GameMode.CREATIVE, GameModes.parse("C"));
        assertEquals(GameMode.CREATIVE, GameModes.parse("1"));
        assertEquals(GameMode.SURVIVAL, GameModes.parse("s"));
        assertEquals(GameMode.SURVIVAL, GameModes.parse("0"));
        assertEquals(GameMode.ADVENTURE, GameModes.parse("adv"));
        assertEquals(GameMode.SPECTATOR, GameModes.parse("sp"));
        assertEquals(GameMode.SPECTATOR, GameModes.parse(" Spectator "));
        assertNull(GameModes.parse("hardcore"));
        assertNull(GameModes.parse("4"));
        assertNull(GameModes.parse(null));
        assertEquals("rapid.command.gamemode.creative", GameModes.permission(GameMode.CREATIVE));
        assertEquals(4, GameModes.ALL.size());
    }

    @Test
    void speedLevels() {
        assertEquals(Speeds.DEFAULT_WALK, Speeds.toGame(1, false), 1e-6);
        assertEquals(Speeds.DEFAULT_FLY, Speeds.toGame(1, true), 1e-6);
        assertEquals(1f, Speeds.toGame(10, false), 1e-6);
        assertEquals(1f, Speeds.toGame(10, true), 1e-6);
        assertEquals(0f, Speeds.toGame(0, false), 1e-6);
        assertEquals(0.05f, Speeds.toGame(0.5f, true), 1e-6);
        assertTrue(Speeds.toGame(5, false) > Speeds.toGame(2, false));
        assertTrue(Speeds.isDefault(Speeds.toGame(1, true), true));
        assertFalse(Speeds.isDefault(Speeds.toGame(2, true), true));

        assertEquals(2.5f, Speeds.parseLevel("2.5"));
        assertEquals(0f, Speeds.parseLevel("0"));
        assertEquals(10f, Speeds.parseLevel("10"));
        assertNull(Speeds.parseLevel("10.5"));
        assertNull(Speeds.parseLevel("-1"));
        assertNull(Speeds.parseLevel("NaN"));
        assertNull(Speeds.parseLevel("fast"));

        assertEquals("2", Speeds.format(2f));
        assertEquals("2.5", Speeds.format(2.5f));
        assertEquals("0", Speeds.format(0f));
    }

    @Test
    void itemInput() {
        assertNull(ItemInput.parse(List.of()));
        assertEquals(new ItemInput("diamond", null), ItemInput.parse(List.of("diamond")));
        assertEquals(new ItemInput("diamond", 32), ItemInput.parse(List.of("diamond", "32")));
        // A lone number is an item id that doesn't exist, not an amount with no item.
        assertEquals(new ItemInput("64", null), ItemInput.parse(List.of("64")));
        // Components can hold spaces; only a trailing plain number is the amount.
        ItemInput named = ItemInput.parse(List.of("diamond_sword[custom_name='\"Big", "Sword\"']", "2"));
        assertEquals(new ItemInput("diamond_sword[custom_name='\"Big Sword\"']", 2), named);
        assertEquals(Integer.MAX_VALUE, ItemInput.parse(List.of("stone", "99999999999")).amount());
        assertEquals(new ItemInput("stone", 0), ItemInput.parse(List.of("stone", "0")));
        assertEquals(new ItemInput("stone -5", null), ItemInput.parse(List.of("stone", "-5")));
    }

    @Test
    void itemIdsAreLowercasedButComponentsAreNot() {
        assertEquals("diamond_sword", new ItemInput("Diamond_Sword", null).normalized());
        assertEquals("minecraft:stone", new ItemInput("MINECRAFT:STONE", null).normalized());
        assertEquals("potion[potion_contents={potion:\"Swiftness\"}]",
                new ItemInput("POTION[potion_contents={potion:\"Swiftness\"}]", null).normalized());
    }
}
