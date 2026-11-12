package us.rapidskies.common.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

class PlusColorTest {

    @Test
    void paletteWidensWithTier() {
        // PLAN.md §6.1: 7 / 13 / 14 colors, plus gradients for Rapid+++ only.
        assertEquals(0, PlusColor.availableFor(Rank.DEFAULT).size());
        assertEquals(7, PlusColor.availableFor(Rank.RAPID_PLUS).size());
        assertEquals(13, PlusColor.availableFor(Rank.RAPID_PLUS_PLUS).size());
        List<PlusColor> top = PlusColor.availableFor(Rank.RAPID_PLUS_PLUS_PLUS);
        assertEquals(14, top.stream().filter(c -> !c.gradient()).count());
        assertFalse(top.stream().filter(PlusColor::gradient).toList().isEmpty());
        for (PlusColor c : PlusColor.values()) {
            if (c.gradient()) {
                assertEquals(Rank.RAPID_PLUS_PLUS_PLUS, c.minimum(), c.name());
                assertFalse(c.availableTo(Rank.RAPID_PLUS_PLUS), c.name());
                assertTrue(c.stops().size() >= 2, c.name());
            }
        }
    }

    @Test
    void redsAreOpenToRanks() {
        assertEquals(PlusColor.RED, PlusColor.byId("red").orElseThrow());
        assertTrue(PlusColor.RED.availableTo(Rank.RAPID_PLUS));
        assertEquals(PlusColor.DARK_RED, PlusColor.byId("Dark Red").orElseThrow());
        assertFalse(PlusColor.DARK_RED.availableTo(Rank.RAPID_PLUS));
        assertTrue(PlusColor.DARK_RED.availableTo(Rank.RAPID_PLUS_PLUS));
    }

    @Test
    void everyChoiceLooksDifferentInAPrefix() {
        // "+++" shows three colors, so two gradients that differ only between them would look the same.
        Map<List<TextColor>, PlusColor> seen = new HashMap<>();
        for (PlusColor c : PlusColor.values()) {
            PlusColor clash = seen.put(colors(render(c, "+++")), c);
            assertTrue(clash == null, c.name() + " looks the same as " + clash);
        }
    }

    @Test
    void gradientRunsFromFirstToLastColor() {
        List<TextColor> shown = colors(render(PlusColor.OCEAN, "+++"));
        assertEquals(List.of(TextColor.color(0x55FFFF),
                TextColor.lerp(0.5f, TextColor.color(0x55FFFF), TextColor.color(0x0000AA)),
                TextColor.color(0x0000AA)), shown);
        // Three stops over three characters: one stop each.
        assertEquals(PlusColor.SUNSET.stops(), colors(render(PlusColor.SUNSET, "+++")));
        assertEquals("+++", plain(render(PlusColor.SUNSET, "+++")));
    }

    @Test
    void gradientSpansNestedTextAndKeepsDecorations() {
        Component c = MiniMessage.miniMessage().deserialize("<plus>Su<bold>nset</bold></plus>", PlusColor.SUNSET.tag());
        List<TextColor> shown = colors(c);
        assertEquals(6, shown.size());
        assertEquals(TextColor.color(0xFFFF55), shown.get(0));
        assertEquals(TextColor.color(0xFF55FF), shown.get(5));
        assertEquals("Sunset", plain(c));
        assertTrue(hasBold(c));
    }

    @Test
    void eachPlusTagStartsItsOwnGradient() {
        // The tag holds per-use state; two uses in one message must not share it.
        Component c = MiniMessage.miniMessage().deserialize("<plus>++</plus> <plus>++</plus>", PlusColor.FROST.tag());
        List<TextColor> shown = colors(c);
        assertEquals(List.of(TextColor.color(0xFFFFFF), TextColor.color(0x55FFFF),
                TextColor.color(0xFFFFFF), TextColor.color(0x55FFFF)), shown);
    }

    @Test
    void plainColorTagIsUnchanged() {
        Component c = render(PlusColor.GOLD, "++");
        assertEquals(List.of(NamedTextColor.GOLD), colors(c).stream().distinct().toList());
    }

    @Test
    void defaultIsAvailableToEveryRank() {
        assertTrue(PlusColor.DEFAULT.availableTo(Rank.RAPID_PLUS));
    }

    @Test
    void lapsedChoiceFallsBackWithoutBeingLost() {
        PlusColor stored = PlusColor.GRAY; // a Rapid+++ color
        assertEquals(PlusColor.GRAY, PlusColor.effective(stored, Rank.RAPID_PLUS_PLUS_PLUS));
        assertEquals(PlusColor.DEFAULT, PlusColor.effective(stored, Rank.RAPID_PLUS));
        assertEquals(PlusColor.DEFAULT, PlusColor.effective(null, Rank.RAPID_PLUS_PLUS));
    }

    @Test
    void parsesFriendlyIds() {
        assertEquals(PlusColor.LIGHT_PURPLE, PlusColor.byId("Light-Purple").orElseThrow());
        assertEquals(PlusColor.DARK_AQUA, PlusColor.byId(" dark aqua ").orElseThrow());
        assertTrue(PlusColor.byId(null).isEmpty());
        assertTrue(PlusColor.byId("rainbow").isEmpty());
        // Removed colors: a stored choice of one of these loads as no choice (the default).
        assertTrue(PlusColor.byId("dark_gray").isEmpty());
        assertTrue(PlusColor.byId("black").isEmpty());
        assertEquals(PlusColor.TWILIGHT, PlusColor.byId("Twilight").orElseThrow());
    }

    @Test
    void idsFitTheColumn() {
        for (PlusColor c : PlusColor.values()) {
            assertTrue(c.id().length() <= 32, c.id()); // players.plus_color VARCHAR(32)
        }
    }

    private static Component render(PlusColor color, String text) {
        return MiniMessage.miniMessage().deserialize("<plus>" + text + "</plus>", color.tag());
    }

    /** The color of every non-blank character, in order (inherited from parents). */
    private static List<TextColor> colors(Component c) {
        List<TextColor> out = new ArrayList<>();
        collect(c, null, out);
        return out;
    }

    private static void collect(Component c, TextColor inherited, List<TextColor> out) {
        TextColor color = c.color() != null ? c.color() : inherited;
        if (c instanceof TextComponent t) {
            t.content().codePoints().filter(cp -> !Character.isWhitespace(cp)).forEach(cp -> out.add(color));
        }
        for (Component child : c.children()) {
            collect(child, color, out);
        }
    }

    private static String plain(Component c) {
        StringBuilder out = new StringBuilder();
        if (c instanceof TextComponent t) {
            out.append(t.content());
        }
        c.children().forEach(child -> out.append(plain(child)));
        return out.toString();
    }

    private static boolean hasBold(Component c) {
        return c.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
                || c.children().stream().anyMatch(PlusColorTest::hasBold);
    }
}
