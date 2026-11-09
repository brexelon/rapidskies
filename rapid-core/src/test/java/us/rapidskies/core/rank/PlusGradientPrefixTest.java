package us.rapidskies.core.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.PlusColor;

/** Gradients render through rapid-core's real prefix templates (PLAN.md §6.1). */
class PlusGradientPrefixTest {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    @Test
    void rapidPlusPlusPlusPrefixShowsTheGradient() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Component prefix = lang.get("rank.prefix.rapidplusplusplus", PlusColor.OCEAN.tag());
        String legacy = LEGACY.serialize(prefix);
        // Ocean: aqua, then the midpoint, then dark blue, one per "+".
        TextColor mid = TextColor.lerp(0.5f, TextColor.color(0x55FFFF), TextColor.color(0x0000AA));
        assertTrue(legacy.contains("§b+" + LEGACY.serialize(Component.text("+", mid)) + "§1+"), legacy);
        assertTrue(LegacyComponentSerializer.legacySection().serialize(prefix).contains("RAPID"), legacy);
    }

    @Test
    void menuTitleIsDrawnInItsGradient() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Component title = lang.get("plus.menu.color-name",
                Placeholder.unparsed("name", PlusChoice.pretty(PlusColor.SUNSET)), PlusColor.SUNSET.tag());
        String legacy = LEGACY.serialize(title);
        assertTrue(legacy.startsWith("§eS"), legacy);
        assertTrue(legacy.endsWith("§dt"), legacy);
        assertEquals("Sunset", LEGACY.serialize(title).replaceAll("§x(§.){6}|§.", ""));
    }

    @Test
    void plainColorTitleStillWorks() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Component title = lang.get("plus.menu.color-name",
                Placeholder.unparsed("name", PlusChoice.pretty(PlusColor.DARK_AQUA)), PlusColor.DARK_AQUA.tag());
        assertEquals("§3Dark Aqua", LEGACY.serialize(title));
    }
}
