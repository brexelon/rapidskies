package us.rapidskies.common.rank;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Team groups (PLAN.md §9), highest display priority first. Each has its own prefix color
 * ({@code rank.staff.*}): reds for Owner and Admin, a named chat color for the rest.
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
