package us.rapidskies.common.rank;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Team groups (PLAN.md §9), highest display priority first. Their chat prefixes use the
 * red shades that plus colors may never use, so a rank can't impersonate staff.
 */
public enum StaffRole {
    OWNER("owner"),
    ADMIN("admin"),
    DEVELOPER("developer"),
    SR_MOD("srmod"),
    MODERATOR("moderator"),
    HELPER("helper"),
    BUILDER("builder");

    private final String group;

    StaffRole(String group) {
        this.group = group;
    }

    /** The LuckPerms group name, also the lang key under {@code rank.staff.}. */
    public String group() {
        return group;
    }

    /** The highest team role a player holds ({@code inGroup} as in {@link Rank#resolve}). */
    public static Optional<StaffRole> resolve(Predicate<String> inGroup) {
        for (StaffRole role : values()) {
            if (inGroup.test(role.group)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
