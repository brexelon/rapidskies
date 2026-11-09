package us.rapidskies.common.rank;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import us.rapidskies.common.config.ConfigView;

/**
 * A number per paid rank, such as party size or friend-list size (PLAN.md §6.1). Read from
 * a config section keyed by LuckPerms group name; a rank missing from the section keeps
 * the built-in default, so a config written by an older build still works.
 */
public final class RankValues {

    private final Map<Rank, Integer> values;

    private RankValues(Map<Rank, Integer> values) {
        this.values = values;
    }

    /** {@code defaults} must give a value for every rank, in tier order. */
    public static RankValues of(int... defaults) {
        Rank[] ranks = Rank.values();
        if (defaults.length != ranks.length) {
            throw new IllegalArgumentException("need " + ranks.length + " values, got " + defaults.length);
        }
        Map<Rank, Integer> m = new EnumMap<>(Rank.class);
        for (int i = 0; i < ranks.length; i++) {
            m.put(ranks[i], defaults[i]);
        }
        return new RankValues(m);
    }

    /** {@code section} keys are group names ({@code default}, {@code rapidplus}, ...). */
    public RankValues overriddenBy(ConfigView section) {
        Objects.requireNonNull(section);
        Map<Rank, Integer> m = new EnumMap<>(values);
        for (Rank r : Rank.values()) {
            m.put(r, section.integer(r.group(), values.get(r)));
        }
        return new RankValues(m);
    }

    public int get(Rank rank) {
        return values.get(rank);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
