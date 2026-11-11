package us.rapidskies.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.StaffRole;

/**
 * Team prefixes ({@code rank.staff.*}) each look different, and every one stays a red shade
 * (nearest named color red or dark red), the colors plus colors and name colors can't use.
 */
class StaffColorsTest {

    private static final Set<NamedTextColor> STAFF_REDS = Set.of(NamedTextColor.RED, NamedTextColor.DARK_RED);

    @Test
    void everyRoleHasItsOwnRedShade() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Map<String, StaffRole> seen = new HashMap<>();
        for (StaffRole role : StaffRole.values()) {
            Style style = firstStyle(lang.get("rank.staff." + role.group()), Style.empty());
            assertNotNull(style, role.name() + " has no colored text");
            assertTrue(STAFF_REDS.contains(NamedTextColor.nearestTo(style.color())),
                    role.name() + " " + style.color() + " isn't a staff red");
            String look = style.color().asHexString() + (style.hasDecoration(TextDecoration.BOLD) ? " bold" : "");
            StaffRole clash = seen.put(look, role);
            assertTrue(clash == null, role.name() + " looks the same as " + clash);
        }
    }

    /** The effective style of the first colored, non-blank text. */
    static Style firstStyle(Component c, Style inherited) {
        Style style = inherited.merge(c.style());
        if (c instanceof TextComponent t && !t.content().isBlank() && style.color() != null) {
            return style;
        }
        for (Component child : c.children()) {
            Style found = firstStyle(child, style);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
