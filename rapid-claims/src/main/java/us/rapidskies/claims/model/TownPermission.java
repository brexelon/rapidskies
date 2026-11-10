package us.rapidskies.claims.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Town actions the mayor can hand to each role (PLAN.md §4.1: configurable permissions per role). */
public enum TownPermission {
    /** Invite players. */
    INVITE,
    /** Remove members of a lower role. */
    KICK,
    /** Claim and unclaim town chunks. */
    CLAIM,
    /** Assign and clear plots. */
    PLOTS,
    /** Change flags on town land. */
    FLAGS,
    /** Trust outsiders on town land and keep players out. */
    TRUST,
    /** Promote and demote members of a lower role. */
    PROMOTE,
    /** Take money out of the town bank. */
    WITHDRAW,
    /** Start Town Projects. */
    PROJECTS,
    /** Move the town warp. */
    WARP;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<TownPermission> parse(String s) {
        try {
            return Optional.of(valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code invite,kick} → the set; unknown names are skipped. */
    public static Set<TownPermission> parseAll(String csv) {
        EnumSet<TownPermission> out = EnumSet.noneOf(TownPermission.class);
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                parse(part).ifPresent(out::add);
            }
        }
        return out;
    }

    public static String format(Set<TownPermission> perms) {
        return String.join(",", perms.stream().map(p -> p.name()).toList());
    }
}
