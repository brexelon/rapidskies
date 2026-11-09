package us.rapidskies.common.rank;

import java.util.function.Predicate;

/**
 * Paid player ranks (PLAN.md §6.1). Each is a LuckPerms group of the same name, granted
 * as a temporary parent; higher tiers inherit lower ones.
 */
public enum Rank {
    DEFAULT(0, "default", ""),
    RAPID_PLUS(1, "rapidplus", "+"),
    RAPID_PLUS_PLUS(2, "rapidplusplus", "++"),
    RAPID_PLUS_PLUS_PLUS(3, "rapidplusplusplus", "+++");

    private final int tier;
    private final String group;
    private final String pluses;

    Rank(int tier, String group, String pluses) {
        this.tier = tier;
        this.group = group;
        this.pluses = pluses;
    }

    public int tier() {
        return tier;
    }

    /** The LuckPerms group name. */
    public String group() {
        return group;
    }

    public String pluses() {
        return pluses;
    }

    public boolean atLeast(Rank other) {
        return tier >= other.tier;
    }

    /**
     * The highest rank a player holds. {@code inGroup} tests membership of a LuckPerms group,
     * inherited groups included. Group membership is used rather than {@code group.<name>}
     * permission checks, because a {@code *} wildcard (owners, developers on staging) would
     * match every group.
     */
    public static Rank resolve(Predicate<String> inGroup) {
        Rank[] all = values();
        for (int i = all.length - 1; i > 0; i--) {
            if (inGroup.test(all[i].group)) {
                return all[i];
            }
        }
        return DEFAULT;
    }

    public static Rank byGroup(String group) {
        for (Rank r : values()) {
            if (r.group.equalsIgnoreCase(group)) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown rank '" + group + "'");
    }
}
