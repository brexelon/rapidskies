package us.rapidskies.common.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

class PlusColorTest {

    private static final Set<NamedTextColor> STAFF_REDS = Set.of(NamedTextColor.RED, NamedTextColor.DARK_RED);

    @Test
    void paletteWidensWithTier() {
        // PLAN.md §6.1: 6 / 11 / 14 colors, plus gradients for Rapid+++ only.
        assertEquals(0, PlusColor.availableFor(Rank.DEFAULT).size());
        assertEquals(6, PlusColor.availableFor(Rank.RAPID_PLUS).size());
        assertEquals(11, PlusColor.availableFor(Rank.RAPID_PLUS_PLUS).size());
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
    void redShadesAreReservedForStaff() {
        for (PlusColor c : PlusColor.values()) {
            assertFalse(STAFF_REDS.contains(NamedTextColor.nearestTo(c.color())), c.name());
        }
        assertTrue(PlusColor.byId("red").isEmpty());
        assertTrue(PlusColor.byId("dark_red").isEmpty());
    }

    @Test
    void gradientsStayClearOfStaffRedsInAPrefix() {
        // "+++" shows three colors: check each, not just the stops.
        for (PlusColor c : PlusColor.values()) {
            if (c.gradient()) {
                for (TextColor shown : colors(render(c, "+++"))) {
                    assertFalse(STAFF_REDS.contains(NamedTextColor.nearestTo(shown)), c.name() + " shows " + shown);
                }
            }
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
