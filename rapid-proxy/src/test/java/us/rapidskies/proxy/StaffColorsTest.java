package us.rapidskies.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.StaffRole;

/**
 * Team role names ({@code grant.name.*}, also the staff chat role tag) each look different:
 * Owner and Admin in the reds, every other role in its own named chat color.
 */
class StaffColorsTest {

    private static final Map<StaffRole, NamedTextColor> COLORS = Map.of(
            StaffRole.OWNER, NamedTextColor.DARK_RED,
            StaffRole.ADMIN, NamedTextColor.RED,
            StaffRole.DEVELOPER, NamedTextColor.DARK_PURPLE,
            StaffRole.SR_MOD, NamedTextColor.DARK_GREEN,
            StaffRole.MODERATOR, NamedTextColor.GREEN,
            StaffRole.HELPER, NamedTextColor.BLUE,
            StaffRole.BUILDER, NamedTextColor.DARK_AQUA);

    @Test
    void everyRoleHasItsOwnColor() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Map<String, StaffRole> seen = new HashMap<>();
        for (StaffRole role : StaffRole.values()) {
            Style style = firstStyle(lang.get("grant.name." + role.group()), Style.empty());
            assertNotNull(style, role.name() + " has no colored text");
            assertEquals(COLORS.get(role), NamedTextColor.nearestTo(style.color()), role.name() + " " + style.color());
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
