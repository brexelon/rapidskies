package us.rapidskies.claims.model;

import java.util.Locale;
import java.util.Optional;

/** A town member's rank (PLAN.md §4.1), highest first. */
public enum TownRole {
    MAYOR,
    ASSISTANT,
    RESIDENT,
    RECRUIT;

    /** True if this role ranks above {@code other} (promotions and kicks only go downwards). */
    public boolean above(TownRole other) {
        return ordinal() < other.ordinal();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<TownRole> parse(String s) {
        try {
            return Optional.of(valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
