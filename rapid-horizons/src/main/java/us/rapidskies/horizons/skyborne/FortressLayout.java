package us.rapidskies.horizons.skyborne;

import java.util.ArrayList;
import java.util.List;

/**
 * The launch Skyborne fortress (PLAN.md §5.2: one design), "the Gilded Bastion": a floating
 * island in the brand colours (blackstone and gold, quartz and sea lanterns) with a walled
 * courtyard where the waves and the boss fight happen.
 *
 * <pre>
 *   top view (x right, z down; origin = centre of the courtyard floor)
 *
 *          T . . . . W W W - W W W . . . . T        T  5x5 corner towers at (±10, ±10)
 *          .   courtyard, |x|,|z| ≤ 9      .        W  walls at |x| or |z| = 10, gates (-) in the middle of each side
 *          W     sea lanterns every 4      W
 *          -          dais (0,0)           -        grass out to radius 16, then a rocky underside 13 deep
 *          W                               W
 *          T . . . . W W W - W W W . . . . T
 *                        |path|
 *                     [landing pad]                 quartz with a gold rim, z 17..21
 *                          ^ updraft (0, 23)        players float up here and are nudged onto the pad
 * </pre>
 *
 * Coordinates are relative to the origin; every block exists in 1.20. Deterministic, so the
 * same layout clears what it built. Free of Bukkit.
 */
public final class FortressLayout {

    /** A block to set, relative to the origin, by Material name. */
    public record Placement(int dx, int dy, int dz, String material) {
    }

    public static final int ISLAND_RADIUS = 16;
    public static final int WALL = 10;
    public static final int UNDERSIDE_DEPTH = 13;
    public static final int TOWER_HEIGHT = 9;
    /** The updraft column, relative x and z. */
    public static final int UPDRAFT_X = 0;
    public static final int UPDRAFT_Z = 23;
    /** The landing pad's centre (relative z). */
    public static final int PAD_Z = 19;

    /** The box everything (and the fight) stays in: min and max corners, inclusive. */
    public static final int MIN_X = -ISLAND_RADIUS - 1;
    public static final int MAX_X = ISLAND_RADIUS + 1;
    public static final int MIN_Y = -UNDERSIDE_DEPTH - 1;
    public static final int MAX_Y = TOWER_HEIGHT + 2;
    public static final int MIN_Z = -ISLAND_RADIUS - 1;
    public static final int MAX_Z = UPDRAFT_Z + 1;

    private FortressLayout() {
    }

    public static boolean inside(int dx, int dy, int dz) {
        return dx >= MIN_X && dx <= MAX_X && dy >= MIN_Y && dy <= MAX_Y && dz >= MIN_Z && dz <= MAX_Z;
    }

    /** A stable 0..99 per column, for the rocky edge and material mix. */
    static int noise(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return Math.floorMod(h, 100);
    }

    public static List<Placement> blocks() {
        List<Placement> out = new ArrayList<>(9000);
        island(out);
        courtyard(out);
        walls(out);
        for (int sx : new int[] {-WALL, WALL}) {
            for (int sz : new int[] {-WALL, WALL}) {
                tower(out, sx, sz);
            }
        }
        pad(out);
        // Where parts overlap (towers over the wall ends and the courtyard corners), the
        // later one is what's built; keep one placement per position.
        java.util.Map<Long, Placement> unique = new java.util.LinkedHashMap<>();
        for (Placement p : out) {
            long key = ((long) (p.dx() & 0xFFFF) << 32) | ((long) (p.dy() & 0xFFFF) << 16) | (p.dz() & 0xFFFF);
            unique.remove(key);
            unique.put(key, p);
        }
        return List.copyOf(unique.values());
    }

    private static void island(List<Placement> out) {
        int r2 = ISLAND_RADIUS * ISLAND_RADIUS;
        for (int x = -ISLAND_RADIUS; x <= ISLAND_RADIUS; x++) {
            for (int z = -ISLAND_RADIUS; z <= ISLAND_RADIUS; z++) {
                if (x * x + z * z > r2) {
                    continue;
                }
                int cheb = Math.max(Math.abs(x), Math.abs(z));
                int n = noise(x, z);
                if (cheb > WALL) {
                    out.add(new Placement(x, 0, z, n < 15 && cheb > 13 ? "COARSE_DIRT" : "GRASS_BLOCK"));
                }
                // Underside: a rough cone, deeper in the middle.
                double dist = Math.sqrt(x * x + z * z);
                int depth = (int) Math.round((1 - dist / (ISLAND_RADIUS + 1)) * UNDERSIDE_DEPTH) + (n % 3) - 1;
                depth = Math.max(1, Math.min(UNDERSIDE_DEPTH, depth));
                for (int d = 1; d <= depth; d++) {
                    String m;
                    if (d <= 2 && cheb > WALL) {
                        m = "DIRT";
                    } else if (d == depth && d > 3) {
                        m = n % 4 == 0 ? "COAL_BLOCK" : "BLACKSTONE";
                    } else {
                        int mix = (n + d * 31) % 10;
                        m = mix < 5 ? "STONE" : mix < 8 ? "ANDESITE" : "COBBLESTONE";
                    }
                    out.add(new Placement(x, -d, z, m));
                }
            }
        }
    }

    private static void courtyard(List<Placement> out) {
        for (int x = -(WALL - 1); x <= WALL - 1; x++) {
            for (int z = -(WALL - 1); z <= WALL - 1; z++) {
                int cheb = Math.max(Math.abs(x), Math.abs(z));
                String m;
                if (cheb <= 1) {
                    m = "CHISELED_POLISHED_BLACKSTONE";
                } else if (cheb == 2) {
                    m = "GOLD_BLOCK";
                } else if (Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0) {
                    // Light 15 every 4 blocks: no mob spawns on the floor except the event's own.
                    m = "SEA_LANTERN";
                } else if (cheb == 6) {
                    m = "GILDED_BLACKSTONE";
                } else {
                    m = "POLISHED_BLACKSTONE_BRICKS";
                }
                out.add(new Placement(x, 0, z, m));
            }
        }
    }

    private static boolean gate(int along) {
        return Math.abs(along) <= 1;
    }

    private static void walls(List<Placement> out) {
        for (int i = -WALL; i <= WALL; i++) {
            for (int[] p : new int[][] {{i, -WALL}, {i, WALL}, {-WALL, i}, {WALL, i}}) {
                int x = p[0];
                int z = p[1];
                if (Math.abs(x) == WALL && Math.abs(z) == WALL) {
                    continue; // a tower stands there
                }
                int along = Math.abs(x) == WALL ? z : x;
                out.add(new Placement(x, 0, z, "POLISHED_BLACKSTONE"));
                for (int y = 1; y <= 4; y++) {
                    if (gate(along) && y <= 3) {
                        continue;
                    }
                    out.add(new Placement(x, y, z, gate(along) && y == 4 ? "GOLD_BLOCK" : "POLISHED_BLACKSTONE_BRICKS"));
                }
                if (Math.floorMod(along, 2) == 0) {
                    out.add(new Placement(x, 5, z, "POLISHED_BLACKSTONE_BRICKS"));
                }
            }
        }
    }

    private static void tower(List<Placement> out, int cx, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                out.add(new Placement(x, 0, z, "POLISHED_BLACKSTONE"));
                for (int y = 1; y <= TOWER_HEIGHT - 1; y++) {
                    String m = cheb == 2 && (dx == 0 || dz == 0) && y == TOWER_HEIGHT - 2 ? "YELLOW_STAINED_GLASS"
                            : "POLISHED_BLACKSTONE_BRICKS";
                    if (dx == 0 && dz == 0 && y == TOWER_HEIGHT - 1) {
                        m = "SEA_LANTERN";
                    }
                    out.add(new Placement(x, y, z, m));
                }
                if (cheb == 2 && Math.floorMod(dx + dz, 2) == 0) {
                    out.add(new Placement(x, TOWER_HEIGHT, z, "GOLD_BLOCK"));
                }
            }
        }
    }

    private static void pad(List<Placement> out) {
        // Path from the south gate to the pad.
        for (int z = WALL + 1; z <= PAD_Z - 3; z++) {
            for (int x = -1; x <= 1; x++) {
                out.add(new Placement(x, 0, z, "POLISHED_BLACKSTONE_BRICKS"));
            }
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = PAD_Z - 2; z <= PAD_Z + 2; z++) {
                boolean rim = Math.abs(x) == 2 || Math.abs(z - PAD_Z) == 2;
                out.add(new Placement(x, 0, z, rim ? "GOLD_BLOCK" : "QUARTZ_BLOCK"));
                if (z > ISLAND_RADIUS - 2) {
                    out.add(new Placement(x, -1, z, "POLISHED_BLACKSTONE"));
                }
            }
        }
    }

    /** Where wave mobs appear: courtyard floor spots (relative, standing height 1), away from the dais. */
    public static List<int[]> spawnSpots() {
        List<int[]> out = new ArrayList<>();
        // Odd coordinates up to 7: clear of the walls and of the corner towers (|x|, |z| >= 8).
        for (int x = -(WALL - 3); x <= WALL - 3; x += 2) {
            for (int z = -(WALL - 3); z <= WALL - 3; z += 2) {
                if (Math.max(Math.abs(x), Math.abs(z)) >= 4) {
                    out.add(new int[] {x, 1, z});
                }
            }
        }
        return out;
    }
}
