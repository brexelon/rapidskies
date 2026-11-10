package us.rapidskies.horizons.chapter;

import java.util.Locale;

/**
 * What one Chapter entry asks for (PLAN.md §5.2: rare finds, event participation,
 * exploration milestones). Written in the config as {@code kind} or {@code kind:target}:
 *
 * <pre>
 *   visit:frontier | visit:wildlands | visit:nether | visit:end
 *   distance:2500        reach this far from the Frontier's centre
 *   biomes               visit {@code amount} different biomes in the Frontier
 *   mine:diamond_ore     break natural blocks (deepslate variants count too)
 *   kill:witch           kill mobs
 *   fish:pufferfish      catch items
 *   skyborne             be rewarded in a won Skyborne event
 *   dragon               be rewarded in the community dragon fight
 * </pre>
 *
 * Progress only counts in the current Frontier, its Nether and its End. Free of Bukkit.
 */
public record ChapterGoal(Kind kind, String target) {

    public enum Kind {
        VISIT, DISTANCE, BIOMES, MINE, KILL, FISH, SKYBORNE, DRAGON;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code visit:} targets. */
    public static final java.util.Set<String> PLACES = java.util.Set.of("frontier", "wildlands", "nether", "end");

    public static ChapterGoal parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        int colon = t.indexOf(':');
        String kind = colon < 0 ? t : t.substring(0, colon).trim();
        String target = colon < 0 ? "" : t.substring(colon + 1).trim().replace("minecraft:", "");
        Kind k;
        try {
            k = Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown goal '" + kind
                    + "' (visit, distance, biomes, mine, kill, fish, skyborne, dragon)");
        }
        switch (k) {
            case VISIT -> {
                if (!PLACES.contains(target)) {
                    throw new IllegalArgumentException("visit: expected frontier, wildlands, nether or end, got '"
                            + target + "'");
                }
            }
            case DISTANCE -> {
                try {
                    if (Integer.parseInt(target) <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("distance: expected a positive number of blocks, got '"
                            + target + "'");
                }
            }
            case MINE, KILL, FISH -> {
                if (!target.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException(k.id() + ": expected a block, mob or item id, got '"
                            + target + "'");
                }
            }
            case BIOMES, SKYBORNE, DRAGON -> {
                if (!target.isEmpty()) {
                    throw new IllegalArgumentException(k.id() + " takes no target");
                }
            }
        }
        return new ChapterGoal(k, target);
    }

    /** Blocks away from the centre for {@code distance:}. */
    public int blocks() {
        return kind == Kind.DISTANCE ? Integer.parseInt(target) : 0;
    }

    /** Whether a natural break of block {@code id} counts ({@code deepslate_diamond_ore} for {@code diamond_ore}). */
    public boolean minesBlock(String id) {
        return kind == Kind.MINE && (id.equals(target) || id.equals("deepslate_" + target));
    }

    public String text() {
        return target.isEmpty() ? kind.id() : kind.id() + ":" + target;
    }
}
