package us.rapidskies.claims.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What a player may do on a piece of land (PLAN.md §4.1), lowest first. Each role includes
 * everything below it. {@link #OWNER} is the land's owner (or a town's mayor) and isn't
 * something you can trust someone with.
 */
public enum Role {
    /** Walk through only. */
    VISITOR,
    /** Also use doors, buttons, levers and pressure plates. */
    TRUSTED,
    /** Also open containers, use farms and animals, sleep in beds. */
    MEMBER,
    /** Also build and break. */
    BUILDER,
    /** Also manage trust and flags. */
    CO_OWNER,
    OWNER;

    public boolean atLeast(Role other) {
        return ordinal() >= other.ordinal();
    }

    public static Role max(Role a, Role b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Roles that can be handed out with {@code /claim trust}. */
    public boolean grantable() {
        return this != VISITOR && this != OWNER;
    }

    /** Lowercase with dashes, as typed in commands and used as lang keys ({@code co-owner}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<Role> parse(String s) {
        String n = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Role r : values()) {
            if (r.name().equals(n)) {
                return Optional.of(r);
            }
        }
        return switch (n) {
            case "TRUST" -> Optional.of(TRUSTED);
            case "BUILD" -> Optional.of(BUILDER);
            case "COOWNER", "MANAGER" -> Optional.of(CO_OWNER);
            default -> Optional.empty();
        };
    }
}
