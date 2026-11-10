package us.rapidskies.horizons.trade;

import java.util.Locale;
import java.util.Optional;

/**
 * A profession (PLAN.md §5.2): a player picks up to two and earns Trade XP and Crowns from
 * matching work. Each icon exists in 1.20, and meaning never rests on the icon alone (the
 * menu names every Trade).
 */
public enum Trade {
    /** Breaking natural ores. */
    MINER("IRON_PICKAXE"),
    /** Harvesting fully grown crops. */
    FARMER("WHEAT"),
    /** Killing hostile mobs. */
    HUNTER("BOW"),
    /** Placing blocks. */
    BUILDER("BRICKS"),
    /** Enchanting at a table. */
    ENCHANTER("ENCHANTING_TABLE"),
    /** Fishing. */
    FISHER("FISHING_ROD");

    /** Material name of the menu icon. */
    public final String icon;

    Trade(String icon) {
        this.icon = icon;
    }

    /** Lowercase: config sections, lang keys and the database value. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Trade> parse(String s) {
        try {
            return Optional.of(valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
