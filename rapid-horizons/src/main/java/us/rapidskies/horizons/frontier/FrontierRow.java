package us.rapidskies.horizons.frontier;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * One Frontier: a row of {@code frontier_history}. Its three worlds are named from the id
 * ({@link #worldKey}, {@link #netherKey}, {@link #endKey}) and generated from {@code seed}
 * with the modifier's biomes, so they can be loaded again identically after a restart.
 *
 * @param endOpensAt     when its End opens and the community dragon fight starts
 * @param dragonKilledAt when that fight was won (rewards are paid once)
 */
public record FrontierRow(int id, Modifier modifier, long seed, Optional<String> chapter, State state,
                          Instant createdAt, Optional<Instant> openedAt, Optional<Instant> closesAt,
                          Optional<Instant> closedAt, Optional<Instant> endOpensAt, Optional<Instant> dragonKilledAt,
                          Optional<String> archivePath) {

    public enum State {
        /** Created and being pre-generated; not open to players yet. */
        PREPARING,
        /** The current Frontier. */
        OPEN,
        /** Replaced; its worlds are archived, then deleted. */
        CLOSED;

        public static State parse(String s) {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    public static final String NAMESPACE = "rapidskies";

    public String worldKey() {
        return NAMESPACE + ":frontier_" + id;
    }

    public String netherKey() {
        return worldKey() + "_nether";
    }

    public String endKey() {
        return worldKey() + "_end";
    }

    /** Whether {@code worldKey} is one of this Frontier's worlds. */
    public boolean owns(String worldKey) {
        return worldKey.equals(worldKey()) || worldKey.equals(netherKey()) || worldKey.equals(endKey());
    }

    /** Whether {@code worldKey} is any Frontier's world (current, preparing or one left over). */
    public static boolean isFrontierWorld(String worldKey) {
        return worldKey.startsWith(NAMESPACE + ":frontier_");
    }

    /** The Frontier id in a Frontier world key, or -1. */
    public static int idOf(String worldKey) {
        if (!isFrontierWorld(worldKey)) {
            return -1;
        }
        String rest = worldKey.substring((NAMESPACE + ":frontier_").length());
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        try {
            return end == 0 ? -1 : Integer.parseInt(rest.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
