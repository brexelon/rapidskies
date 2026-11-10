package us.rapidskies.horizons.chapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import us.rapidskies.horizons.chapter.ChapterGoal.Kind;

class ChapterGoalTest {

    @Test
    void goalsParse() {
        assertEquals(new ChapterGoal(Kind.VISIT, "wildlands"), ChapterGoal.parse(" Visit:Wildlands "));
        assertEquals(new ChapterGoal(Kind.MINE, "diamond_ore"), ChapterGoal.parse("mine:minecraft:diamond_ore"));
        assertEquals(2500, ChapterGoal.parse("distance:2500").blocks());
        assertEquals(new ChapterGoal(Kind.BIOMES, ""), ChapterGoal.parse("biomes"));
        assertEquals(new ChapterGoal(Kind.SKYBORNE, ""), ChapterGoal.parse("skyborne"));
        assertEquals("kill:witch", ChapterGoal.parse("kill:witch").text());
    }

    @Test
    void badGoalsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ChapterGoal.parse("visit:moon"));
        assertThrows(IllegalArgumentException.class, () -> ChapterGoal.parse("distance:far"));
        assertThrows(IllegalArgumentException.class, () -> ChapterGoal.parse("distance:-5"));
        assertThrows(IllegalArgumentException.class, () -> ChapterGoal.parse("mine:"));
        assertThrows(IllegalArgumentException.class, () -> ChapterGoal.parse("dragon:red"));
        assertThrows(IllegalArgumentException.class, () -> ChapterGoal.parse("craft:table"));
    }

    @Test
    void deepslateOresCount() {
        ChapterGoal diamonds = ChapterGoal.parse("mine:diamond_ore");
        assertTrue(diamonds.minesBlock("diamond_ore"));
        assertTrue(diamonds.minesBlock("deepslate_diamond_ore"));
        assertFalse(diamonds.minesBlock("emerald_ore"));
        assertFalse(ChapterGoal.parse("kill:diamond_ore").minesBlock("diamond_ore"), "only mine goals");
    }

    @Test
    void menuIconsExistIn120() {
        assertEquals("DIAMOND_ORE", ChapterMenu.iconName(ChapterGoal.parse("mine:diamond_ore")));
        assertEquals("WITCH_SPAWN_EGG", ChapterMenu.iconName(ChapterGoal.parse("kill:witch")));
        assertEquals("COMPASS", ChapterMenu.iconName(ChapterGoal.parse("distance:100")));
        assertEquals("IRON_SWORD", ChapterMenu.iconName(ChapterGoal.parse("visit:wildlands")));
        for (ChapterGoal.Kind kind : ChapterGoal.Kind.values()) {
            String target = switch (kind) {
                case VISIT -> "end";
                case DISTANCE -> "10";
                case MINE, FISH -> "cod";
                case KILL -> "zombie";
                default -> "";
            };
            String name = ChapterMenu.iconName(ChapterGoal.parse(target.isEmpty() ? kind.id() : kind.id() + ":" + target));
            assertTrue(Material.getMaterial(name) != null, kind + " -> " + name);
        }
    }
}
