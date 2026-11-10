package us.rapidskies.claims.town;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A Town Project from the catalog (PLAN.md §5.2): resources and Crowns a town gathers
 * together, unlocking something when it's done.
 *
 * @param name     MiniMessage display name
 * @param icon     item id for menus
 * @param requires resource to amount, in display order: an item key ({@code minecraft:glass})
 *                 or {@link #CROWNS}
 */
public record Project(String id, String name, String icon, Map<String, Long> requires, List<Unlock> unlocks) {

    public static final String CROWNS = "crowns";

    public record Unlock(Kind kind, int amount) {

        public enum Kind {
            /** {@code /town warp} and {@code /town setwarp}. */
            WARP,
            /** The shared town vault, {@code /town vault}. */
            VAULT,
            /** A stall at the spawn market (rapid-market, phase 4a). Recorded now, used then. */
            MARKET_STALL,
            /** Town banner cosmetics (phase 5). Recorded now, used then. */
            BANNER,
            /** A town title cosmetic (phase 5). Recorded now, used then. */
            TITLE,
            /** Raises the town's chunk cap by {@code amount}. */
            CHUNKS
        }

        /** {@code warp}, {@code vault}, {@code market-stall}, {@code banner}, {@code title}, {@code chunks:16}. */
        public static Optional<Unlock> parse(String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("chunks:")) {
                try {
                    int n = Integer.parseInt(t.substring("chunks:".length()).trim());
                    return n > 0 ? Optional.of(new Unlock(Kind.CHUNKS, n)) : Optional.empty();
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
            try {
                return Optional.of(new Unlock(Kind.valueOf(t.replace('-', '_').toUpperCase(Locale.ROOT)), 0));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        /** Lang key part. */
        public String id() {
            return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    public Project {
        requires = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(requires));
        unlocks = List.copyOf(unlocks);
    }

    public boolean unlocks(Unlock.Kind kind) {
        return unlocks.stream().anyMatch(u -> u.kind() == kind);
    }

    public int extraChunks() {
        return unlocks.stream().filter(u -> u.kind() == Unlock.Kind.CHUNKS).mapToInt(Unlock::amount).sum();
    }
}
