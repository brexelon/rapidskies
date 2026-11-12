package us.rapidskies.claims.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The colors a town tag may use (PLAN.md §4.1: a limited palette of named chat colors).
 * Black is unreadable on the chat background, so it isn't here.
 */
public enum TagColor {
    WHITE,
    YELLOW,
    GOLD,
    AQUA,
    GREEN,
    LIGHT_PURPLE,
    RED,
    BLUE,
    DARK_BLUE,
    DARK_AQUA,
    DARK_GREEN,
    DARK_PURPLE,
    DARK_RED,
    GRAY,
    DARK_GRAY;

    /** The MiniMessage tag name ({@code light_purple}). */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code Light Purple}. */
    public String pretty() {
        StringBuilder sb = new StringBuilder();
        for (String word : name().split("_")) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    public static Optional<TagColor> parse(String s) {
        String n = s.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (TagColor c : values()) {
            if (c.name().equals(n)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
