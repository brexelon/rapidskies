package us.rapidskies.horizons.skyborne;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launch Skyborne fortress (PLAN.md §5.2: one design), "the Gilded Bastion": a floating
 * island in the brand colours (blackstone and gold, quartz and sea lanterns) with a walled
 * courtyard where the waves and the boss fight happen.
 *
 * <pre>
 *   top view (x right, z down; origin = centre of the courtyard floor)
 *
 *          T . . . . W W W G W W W . . . . T        T  5x5 corner towers at (±10, ±10): corbelled
 *          .   courtyard, |x|,|z| ≤ 9      .           crenellated parapet, pyramid roof, gold spire
 *          W     sea lanterns every 4      W        W  walls at |x| or |z| = 10 with a parapet on corbels,
 *          G   braziers (±6,±6), dais (0,0) G          buttresses outside and pilasters inside at ±5
 *          W                               W        G  gatehouses (5 wide, 3 deep, arched passage); the
 *          T . . . . W W W G W W W . . . . T           north one is taller and faces the pad with an emblem
 *                        |path|                     meadow out to radius 18 (flowers, azalea trees),
 *                     [landing pad]                 then a layered underside 14 deep with a keel, veins
 *                          ^ updraft (0, 27)        of gold and stalactites; four islets float around it
 * </pre>
 *
 * The landing pad (quartz, gold ring, lamp posts) is 7x7 around (0, {@link #PAD_Z}); players
 * float up the updraft column just past its south rim and are nudged onto it.
 *
 * <p>Coordinates are relative to the origin; every block exists in 1.20 (PLAN.md §2), and
 * nothing in it changes by itself (no grass under solid blocks, persistent leaves, no copper),
 * so clearing finds exactly what was built. Deterministic, so the same layout clears what it
 * built. Free of Bukkit.
 */
public final class FortressLayout {

    /**
     * A block to set, relative to the origin: a Material name and its block states without the
     * brackets ({@code facing=north,half=top}), empty for the default state.
     */
    public record Placement(int dx, int dy, int dz, String material, String state) {

        public Placement(int dx, int dy, int dz, String material) {
            this(dx, dy, dz, material, "");
        }
    }

    public static final int ISLAND_RADIUS = 18;
    public static final int WALL = 10;
    /** The wall's walkway height; the parapet's merlons stand one above it. */
    public static final int WALL_HEIGHT = 5;
    public static final int UNDERSIDE_DEPTH = 14;
    /** The extra depth of the keel under the middle of the island, and its radius. */
    public static final int KEEL_DEPTH = 6;
    private static final double KEEL_RADIUS = 5.0;
    /** Longest stalactite under the island. */
    private static final int DRIP = 3;
    /** The towers' solid body ends here (parapet level); roof and spire rise above. */
    public static final int TOWER_HEIGHT = 11;
    /** The end rod on top of each tower's spire. */
    public static final int SPIRE_TOP = TOWER_HEIGHT + 5;
    /** The landing pad's centre (relative z) and its half-width (7x7). */
    public static final int PAD_Z = 22;
    public static final int PAD_HALF = 3;
    /** The updraft column, relative x and z: just past the pad's south rim. */
    public static final int UPDRAFT_X = 0;
    public static final int UPDRAFT_Z = PAD_Z + PAD_HALF + 2;
    /** The small islands around the fortress: centre x, top y, centre z, radius. */
    private static final int[][] ISLETS = {{-23, -3, -6, 3}, {22, -6, 11, 2}, {11, 1, -22, 2}, {-16, -8, 21, 2}};
    /** Azalea trees on the meadow, one per side, between a buttress and a tower. */
    private static final int[][] TREES = {{15, 7}, {-7, 15}, {-15, -7}, {7, -15}};

    /** The box everything (and the fight) stays in: min and max corners, inclusive. */
    public static final int MIN_X = -ISLAND_RADIUS - 8;
    public static final int MAX_X = ISLAND_RADIUS + 8;
    public static final int MIN_Y = -(UNDERSIDE_DEPTH + KEEL_DEPTH + DRIP) - 1;
    public static final int MAX_Y = SPIRE_TOP + 2;
    public static final int MIN_Z = -ISLAND_RADIUS - 8;
    public static final int MAX_Z = UPDRAFT_Z + 1;

    /** Blocks that sit on grass without turning it to dirt. */
    private static final Set<String> PLANTS = Set.of("DANDELION", "OXEYE_DAISY", "AZURE_BLUET", "FERN", "AZALEA",
            "FLOWERING_AZALEA", "MOSS_CARPET");

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

    /** A stable 0..99 per block. */
    static int noise(int x, int y, int z) {
        return noise(x * 7 + y * 92821, z * 11 - y * 68917);
    }

    static boolean onIsland(int x, int z) {
        return x * x + z * z <= ISLAND_RADIUS * ISLAND_RADIUS;
    }

    /** How deep the underside goes under a column of the island: a bowl with a keel in the middle. */
    static int depth(int x, int z) {
        double dist = Math.sqrt(x * x + z * z);
        double t = Math.max(0, 1 - dist / (ISLAND_RADIUS + 1));
        int d = (int) Math.round(Math.pow(t, 0.8) * UNDERSIDE_DEPTH) + noise(x, z) % 3 - 1;
        if (dist < KEEL_RADIUS) {
            d += (int) Math.round((1 - dist / KEEL_RADIUS) * KEEL_DEPTH);
        }
        return Math.max(1, Math.min(UNDERSIDE_DEPTH + KEEL_DEPTH, d));
    }

    public static List<Placement> blocks() {
        Build b = new Build();
        island(b);
        courtyard(b);
        for (Side s : Side.ALL) {
            walls(b, s);
            gatehouse(b, s, s.nz() < 0 ? WALL_HEIGHT + 4 : WALL_HEIGHT + 1);
        }
        for (int sx : new int[] {-WALL, WALL}) {
            for (int sz : new int[] {-WALL, WALL}) {
                tower(b, sx, sz);
            }
        }
        pad(b);
        for (int[] t : TREES) {
            tree(b, t[0], t[1]);
        }
        meadow(b);
        for (int[] i : ISLETS) {
            islet(b, i[0], i[1], i[2], i[3]);
        }
        b.settle();
        return b.list();
    }

    // ------------------------------------------------------------------ the island

    private static void island(Build b) {
        int r = ISLAND_RADIUS;
        int[][] pendants = {{0, 9}, {0, -9}, {9, 0}, {-9, 0}, {7, 7}, {7, -7}, {-7, 7}, {-7, -7}};
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (!onIsland(x, z)) {
                    continue;
                }
                int cheb = Math.max(Math.abs(x), Math.abs(z));
                int n = noise(x, z);
                boolean outside = cheb > WALL;
                double dist = Math.sqrt(x * x + z * z);
                if (outside) {
                    boolean rim = dist > r - 1.5;
                    boolean mossy = noise(Math.floorDiv(x, 3) + 500, Math.floorDiv(z, 3) - 500) < 22;
                    String top = rim && n < 18 ? "COARSE_DIRT"
                            : rim && n < 28 ? "MOSSY_COBBLESTONE"
                            : mossy && n < 75 ? "MOSS_BLOCK"
                            : "GRASS_BLOCK";
                    b.set(x, 0, z, top);
                }
                // Underside: soil, then stone with a little gold ore, then blackstone and basalt
                // with gilded veins towards the bottom.
                int depth = depth(x, z);
                for (int d = 1; d <= depth; d++) {
                    int v = noise(x, -d, z);
                    String m;
                    if (d <= 2 && outside) {
                        m = d == 2 && v < 30 ? "ROOTED_DIRT" : "DIRT";
                    } else if (d == depth && d > 3) {
                        m = v < 25 ? "GILDED_BLACKSTONE" : "BLACKSTONE";
                    } else if (d > depth * 0.6) {
                        m = v < 5 ? "GILDED_BLACKSTONE" : v < 50 ? "BLACKSTONE" : v < 75 ? "BASALT" : v < 90 ? "TUFF"
                                : "BLACKSTONE";
                    } else {
                        m = v < 3 ? "GOLD_ORE" : v < 45 ? "STONE" : v < 65 ? "ANDESITE" : v < 80 ? "TUFF"
                                : "COBBLESTONE";
                    }
                    b.set(x, -d, z, m);
                }
                int below = -depth - 1;
                int h = noise(z * 7 + 3, x * 11 - 5);
                if (isPendant(pendants, x, z)) {
                    // Lanterns on short posts, glowing under the island.
                    int posts = 1 + h % 3;
                    for (int i = 0; i < posts; i++) {
                        b.set(x, below - i, z, "POLISHED_BLACKSTONE_BRICK_WALL");
                    }
                    b.set(x, below - posts, z, "LANTERN", "hanging=true");
                } else if (depth >= 5 && h < 16) {
                    stalactite(b, x, below, z, 1 + h % DRIP);
                } else if (depth <= 2 && outside && h > 72) {
                    b.set(x, below, z, "HANGING_ROOTS");
                }
            }
        }
    }

    private static boolean isPendant(int[][] pendants, int x, int z) {
        for (int[] p : pendants) {
            if (p[0] == x && p[1] == z) {
                return true;
            }
        }
        return false;
    }

    /** Pointed dripstone hanging down from {@code top}, {@code length} long. */
    private static void stalactite(Build b, int x, int top, int z, int length) {
        for (int i = 0; i < length; i++) {
            int fromTip = length - 1 - i;
            String thickness = fromTip == 0 ? "tip" : fromTip == 1 ? "frustum" : "base";
            b.set(x, top - i, z, "POINTED_DRIPSTONE", "vertical_direction=down,thickness=" + thickness);
        }
    }

    // ------------------------------------------------------------------ the courtyard

    private static void courtyard(Build b) {
        int in = WALL - 1;
        for (int x = -in; x <= in; x++) {
            for (int z = -in; z <= in; z++) {
                int c = Math.max(Math.abs(x), Math.abs(z));
                String m;
                if (c == 0) {
                    m = "CHISELED_POLISHED_BLACKSTONE";
                } else if (c == 1) {
                    m = "POLISHED_BLACKSTONE";
                } else if (c == 2) {
                    m = "GOLD_BLOCK";
                } else if (Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0) {
                    // Light 15 every 4 blocks: no mob spawns on the floor except the event's own.
                    m = "SEA_LANTERN";
                } else if (c == 3 || c == in) {
                    m = "POLISHED_BLACKSTONE";
                } else if (c == 6) {
                    m = Math.abs(x) == Math.abs(z) ? "GOLD_BLOCK" : "CHISELED_POLISHED_BLACKSTONE";
                } else if (Math.abs(x) <= 1 || Math.abs(z) <= 1 || Math.abs(x) == Math.abs(z)) {
                    // The ways in from the gates, and the diagonals.
                    m = "POLISHED_BLACKSTONE";
                } else {
                    m = bricks(x, 0, z);
                }
                b.set(x, 0, z, m);
            }
        }
        // Braziers on the ring's gold corners (even coordinates: clear of the spawn spots).
        for (int x : new int[] {-6, 6}) {
            for (int z : new int[] {-6, 6}) {
                b.set(x, 1, z, "CHISELED_POLISHED_BLACKSTONE");
                b.set(x, 2, z, "CAMPFIRE");
            }
        }
    }

    // ------------------------------------------------------------------ walls and gates

    private static void walls(Build b, Side s) {
        for (int along = -7; along <= 7; along++) {
            int a = Math.abs(along);
            if (a <= 2) {
                continue; // the gatehouse
            }
            int x = s.x(along, WALL);
            int z = s.z(along, WALL);
            b.set(x, 0, z, "POLISHED_BLACKSTONE");
            b.set(x, 1, z, "POLISHED_BLACKSTONE");
            for (int y = 2; y < WALL_HEIGHT; y++) {
                boolean slit = (a == 3 || a == 7) && y <= 3;
                b.set(x, y, z, slit ? "TINTED_GLASS" : bricks(x, y, z));
            }
            b.set(x, WALL_HEIGHT, z, "POLISHED_BLACKSTONE");
            // The parapet projects outward on corbels; merlons on the odd positions.
            int px = s.x(along, WALL + 1);
            int pz = s.z(along, WALL + 1);
            b.stairs(px, WALL_HEIGHT - 1, pz, "POLISHED_BLACKSTONE_BRICK_STAIRS", s.inward(), true);
            b.set(px, WALL_HEIGHT, pz, a == 5 ? "GILDED_BLACKSTONE" : "POLISHED_BLACKSTONE_BRICKS");
            if (a % 2 == 1) {
                b.set(px, WALL_HEIGHT + 1, pz, "POLISHED_BLACKSTONE_BRICKS");
            } else {
                b.set(px, WALL_HEIGHT - 2, pz, "LANTERN", "hanging=true");
            }
            if (a == 5) {
                buttress(b, s, along);
                // A pilaster inside, carrying a lantern above the walkway.
                int ix = s.x(along, WALL - 1);
                int iz = s.z(along, WALL - 1);
                for (int y = 1; y < WALL_HEIGHT; y++) {
                    b.set(ix, y, iz, "POLISHED_BLACKSTONE");
                }
                b.set(ix, WALL_HEIGHT, iz, "CHISELED_POLISHED_BLACKSTONE");
                b.set(ix, WALL_HEIGHT + 1, iz, "LANTERN");
            }
        }
    }

    /** A stepped buttress against the outside of the wall. */
    private static void buttress(Build b, Side s, int along) {
        String stairs = "POLISHED_BLACKSTONE_BRICK_STAIRS";
        for (int out = WALL + 1; out <= WALL + 3; out++) {
            b.set(s.x(along, out), 0, s.z(along, out), "POLISHED_BLACKSTONE");
        }
        int x1 = s.x(along, WALL + 1);
        int z1 = s.z(along, WALL + 1);
        for (int y = 1; y < WALL_HEIGHT; y++) {
            b.set(x1, y, z1, "POLISHED_BLACKSTONE_BRICKS");
        }
        int x2 = s.x(along, WALL + 2);
        int z2 = s.z(along, WALL + 2);
        b.set(x2, 1, z2, "POLISHED_BLACKSTONE_BRICKS");
        b.set(x2, 2, z2, "POLISHED_BLACKSTONE_BRICKS");
        b.stairs(x2, 3, z2, stairs, s.inward(), false);
        b.stairs(s.x(along, WALL + 3), 1, s.z(along, WALL + 3), stairs, s.inward(), false);
    }

    /**
     * A gatehouse over the middle of a wall: 5 wide, 3 deep (out of the wall), with an arched
     * passage, a gold keystone, a lit window over the arch and banners on its pillars.
     */
    private static void gatehouse(Build b, Side s, int top) {
        for (int along = -2; along <= 2; along++) {
            int a = Math.abs(along);
            boolean passage = a <= 1;
            for (int out = WALL; out <= WALL + 2; out++) {
                int x = s.x(along, out);
                int z = s.z(along, out);
                b.set(x, 0, z, !passage ? "POLISHED_BLACKSTONE" : along == 0 ? "POLISHED_BLACKSTONE"
                        : "POLISHED_BLACKSTONE_BRICKS");
                for (int y = 1; y <= top; y++) {
                    if (passage && y <= 2) {
                        continue;
                    }
                    if (passage && y == 3) {
                        if (a == 1) {
                            // Arch corners: the low half against the pillar.
                            b.stairs(x, y, z, "POLISHED_BLACKSTONE_BRICK_STAIRS", along > 0 ? s.plus() : s.minus(),
                                    true);
                        }
                        continue;
                    }
                    boolean outer = out == WALL + 2;
                    String m = a == 2 && outer ? "POLISHED_BLACKSTONE" : bricks(x, y, z);
                    if (y == 4 && passage && outer) {
                        m = along == 0 ? "GOLD_BLOCK" : "CHISELED_POLISHED_BLACKSTONE";
                    }
                    if (y == 4 && along == 0 && out == WALL) {
                        m = "GOLD_BLOCK";
                    }
                    b.set(x, y, z, m);
                }
            }
            // Crenellations around the top, gold pinnacles on the outer corners.
            for (int out = WALL; out <= WALL + 2; out++) {
                boolean edge = a == 2 || out == WALL || out == WALL + 2;
                if (edge && Math.floorMod(along + out, 2) == 0) {
                    b.set(s.x(along, out), top + 1, s.z(along, out), "POLISHED_BLACKSTONE_BRICKS");
                }
            }
            if (a == 2) {
                b.set(s.x(along, WALL + 2), top + 2, s.z(along, WALL + 2), "GOLD_BLOCK");
                // The pillar's foot and its banner.
                int fx = s.x(along, WALL + 3);
                int fz = s.z(along, WALL + 3);
                b.set(fx, 0, fz, "POLISHED_BLACKSTONE");
                b.stairs(fx, 1, fz, "POLISHED_BLACKSTONE_BRICK_STAIRS", s.inward(), false);
                b.set(fx, top - 1, fz, "YELLOW_WALL_BANNER", "facing=" + s.outward());
            }
        }
        b.set(s.x(0, WALL + 1), 3, s.z(0, WALL + 1), "LANTERN", "hanging=true");
        // A window over the arch, lit from behind.
        for (int y = 5; y < top; y++) {
            b.set(s.x(0, WALL + 2), y, s.z(0, WALL + 2), "YELLOW_STAINED_GLASS");
            b.set(s.x(0, WALL + 1), y, s.z(0, WALL + 1), "SHROOMLIGHT");
        }
        if (top > WALL_HEIGHT + 2) {
            // The tall gate faces the pad across the courtyard: a gold emblem around a light.
            b.set(s.x(0, WALL), 6, s.z(0, WALL), "GOLD_BLOCK");
            b.set(s.x(0, WALL), 7, s.z(0, WALL), "SEA_LANTERN");
            b.set(s.x(0, WALL), 8, s.z(0, WALL), "GOLD_BLOCK");
            b.set(s.x(-1, WALL), 7, s.z(-1, WALL), "GOLD_BLOCK");
            b.set(s.x(1, WALL), 7, s.z(1, WALL), "GOLD_BLOCK");
        }
    }

    // ------------------------------------------------------------------ towers

    private static void tower(Build b, int cx, int cz) {
        int sx = Integer.signum(cx);
        int sz = Integer.signum(cz);
        String stairs = "POLISHED_BLACKSTONE_BRICK_STAIRS";
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                int c = Math.max(Math.abs(dx), Math.abs(dz));
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                b.set(x, 0, z, "POLISHED_BLACKSTONE");
                for (int y = 1; y <= TOWER_HEIGHT; y++) {
                    String m = corner ? "POLISHED_BLACKSTONE" : bricks(x, y, z);
                    if (c == 2 && !corner && y == 6) {
                        m = "CHISELED_POLISHED_BLACKSTONE";
                    }
                    b.set(x, y, z, m);
                }
            }
        }
        // Lit windows high on every face; arrow slits low on the two outer faces.
        for (int[] f : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int y = 8; y <= 9; y++) {
                b.set(cx + 2 * f[0], y, cz + 2 * f[1], "YELLOW_STAINED_GLASS");
                b.set(cx + f[0], y, cz + f[1], "SHROOMLIGHT");
            }
            if (f[0] == sx || f[1] == sz) {
                for (int y = 3; y <= 4; y++) {
                    b.set(cx + 2 * f[0], y, cz + 2 * f[1], "TINTED_GLASS");
                }
            }
        }
        // A sloped plinth on the outer faces (where there's island under it).
        for (int t = -3; t <= 3; t++) {
            plinth(b, cx + 3 * sx, cz + t, Math.abs(t) == 3, facing(-sx, 0));
            plinth(b, cx + t, cz + 3 * sz, Math.abs(t) == 3, facing(0, -sz));
        }
        // Corbels and a crenellated parapet one block out, gold on the corner merlons.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 3) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                boolean corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
                if (corner) {
                    b.set(x, TOWER_HEIGHT - 1, z, "POLISHED_BLACKSTONE_BRICKS");
                    b.set(x, TOWER_HEIGHT - 2, z, "LANTERN", "hanging=true");
                } else {
                    b.stairs(x, TOWER_HEIGHT - 1, z, stairs, inwardOf(dx, dz), true);
                }
                b.set(x, TOWER_HEIGHT, z, "POLISHED_BLACKSTONE_BRICKS");
                if (Math.floorMod(dx + dz, 2) == 0) {
                    b.set(x, TOWER_HEIGHT + 1, z, "POLISHED_BLACKSTONE_BRICKS");
                }
                if (corner) {
                    b.set(x, TOWER_HEIGHT + 2, z, "GOLD_BLOCK");
                }
            }
        }
        // A pyramid roof inside the parapet, then a gold spire.
        for (int ring = 2; ring >= 1; ring--) {
            int y = TOWER_HEIGHT + 3 - ring;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    int c = Math.max(Math.abs(dx), Math.abs(dz));
                    int x = cx + dx;
                    int z = cz + dz;
                    if (c < ring) {
                        b.set(x, y, z, "POLISHED_BLACKSTONE_BRICKS");
                    } else if (Math.abs(dx) == ring && Math.abs(dz) == ring) {
                        b.set(x, y, z, stairs, "facing=" + (dz > 0 ? "north" : "south") + ",half=bottom,shape="
                                + (dx * dz > 0 ? "outer_left" : "outer_right"));
                    } else {
                        b.stairs(x, y, z, stairs, inwardOf(dx, dz), false);
                    }
                }
            }
        }
        b.set(cx, TOWER_HEIGHT + 3, cz, "GOLD_BLOCK");
        b.set(cx, TOWER_HEIGHT + 4, cz, "POLISHED_BLACKSTONE_BRICK_WALL");
        b.set(cx, SPIRE_TOP, cz, "END_ROD");
    }

    private static void plinth(Build b, int x, int z, boolean corner, String inward) {
        if (!onIsland(x, z)) {
            return;
        }
        b.set(x, 0, z, "POLISHED_BLACKSTONE");
        if (corner) {
            b.set(x, 1, z, "POLISHED_BLACKSTONE");
        } else {
            b.stairs(x, 1, z, "POLISHED_BLACKSTONE_BRICK_STAIRS", inward, false);
        }
    }

    // ------------------------------------------------------------------ path and landing pad

    private static void pad(Build b) {
        int padNorth = PAD_Z - PAD_HALF;
        // The path from the south gate, with lamp posts.
        for (int z = WALL + 3; z < padNorth; z++) {
            for (int x = -1; x <= 1; x++) {
                b.set(x, 0, z, x == 0 ? "POLISHED_BLACKSTONE_BRICKS" : "POLISHED_BLACKSTONE");
                if (!onIsland(x, z)) {
                    b.set(x, -1, z, "POLISHED_BLACKSTONE");
                }
            }
        }
        for (int z : new int[] {WALL + 4, padNorth - 2}) {
            for (int x : new int[] {-2, 2}) {
                lampPost(b, x, 0, z);
            }
        }
        String stairs = "POLISHED_BLACKSTONE_BRICK_STAIRS";
        for (int x = -PAD_HALF; x <= PAD_HALF; x++) {
            for (int z = PAD_Z - PAD_HALF; z <= PAD_Z + PAD_HALF; z++) {
                int dz = z - PAD_Z;
                int c = Math.max(Math.abs(x), Math.abs(dz));
                boolean corner = Math.abs(x) == Math.abs(dz);
                String top = c == 3 ? corner ? "CHISELED_POLISHED_BLACKSTONE" : "POLISHED_BLACKSTONE_BRICKS"
                        : c == 2 ? "GOLD_BLOCK"
                        : c == 1 ? "SMOOTH_QUARTZ"
                        : "CHISELED_QUARTZ_BLOCK";
                b.set(x, 0, z, top);
                // Underneath, an inverted stepped pyramid.
                for (int ring = PAD_HALF; ring >= 1; ring--) {
                    int y = ring - PAD_HALF - 1;
                    if (c > ring) {
                        continue;
                    }
                    if (c < ring) {
                        b.set(x, y, z, ring == 1 ? "GOLD_BLOCK" : "POLISHED_BLACKSTONE");
                    } else if (corner) {
                        b.set(x, y, z, "POLISHED_BLACKSTONE_BRICKS");
                    } else {
                        b.stairs(x, y, z, stairs, inwardOf(x, dz), true);
                    }
                }
            }
        }
        b.set(0, -PAD_HALF - 1, PAD_Z, "LANTERN", "hanging=true");
        // Quartz posts with lanterns on the corners.
        for (int x : new int[] {-PAD_HALF, PAD_HALF}) {
            for (int z : new int[] {PAD_Z - PAD_HALF, PAD_Z + PAD_HALF}) {
                b.set(x, 1, z, "QUARTZ_PILLAR");
                b.set(x, 2, z, "QUARTZ_PILLAR");
                b.set(x, 3, z, "LANTERN");
            }
        }
    }

    private static void lampPost(Build b, int x, int ground, int z) {
        b.set(x, ground, z, "POLISHED_BLACKSTONE");
        b.set(x, ground + 1, z, "POLISHED_BLACKSTONE_BRICK_WALL");
        b.set(x, ground + 2, z, "POLISHED_BLACKSTONE_BRICK_WALL");
        b.set(x, ground + 3, z, "LANTERN");
    }

    // ------------------------------------------------------------------ the meadow and islets

    private static void tree(Build b, int x, int z) {
        b.set(x, 0, z, "DIRT");
        for (int y = 1; y <= 4; y++) {
            b.set(x, y, z, "DARK_OAK_LOG");
        }
        for (int y = 3; y <= 6; y++) {
            int r = y <= 4 ? 2 : y == 5 ? 1 : 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) == r && Math.abs(dz) == r) {
                        continue;
                    }
                    String leaves = noise(x + dx, y, z + dz) < 35 ? "FLOWERING_AZALEA_LEAVES" : "AZALEA_LEAVES";
                    b.add(x + dx, y, z + dz, leaves, "persistent=true");
                }
            }
        }
    }

    /** Flowers, ferns and azalea bushes on the meadow, keeping the ways out of the gates clear. */
    private static void meadow(Build b) {
        int r = ISLAND_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (!onIsland(x, z) || Math.max(Math.abs(x), Math.abs(z)) <= WALL + 1 || Math.abs(x) <= 2
                        || Math.abs(z) <= 2 || b.has(x, 1, z) || b.has(x, 2, z)) {
                    continue;
                }
                plant(b, x, 0, z);
            }
        }
    }

    private static void plant(Build b, int x, int ground, int z) {
        String below = b.material(x, ground, z);
        int v = noise(x * 5 + 17, z * 3 - 29);
        String m = null;
        if ("MOSS_BLOCK".equals(below)) {
            m = v < 45 ? "MOSS_CARPET" : null;
        } else if ("GRASS_BLOCK".equals(below)) {
            m = v < 7 ? "DANDELION" : v < 10 ? "OXEYE_DAISY" : v < 12 ? "AZURE_BLUET" : v < 22 ? "FERN"
                    : v < 25 ? "AZALEA" : v < 27 ? "FLOWERING_AZALEA" : null;
        }
        if (m != null) {
            b.add(x, ground + 1, z, m, "");
        }
    }

    private static void islet(Build b, int cx, int cy, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r + 1) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                int v = noise(x, z);
                b.set(x, cy, z, v < 30 ? "MOSS_BLOCK" : "GRASS_BLOCK");
                int depth = Math.max(1, (int) Math.round((r + 1 - Math.sqrt(d2)) * 1.7) + v % 2);
                for (int d = 1; d <= depth; d++) {
                    int w = noise(x, cy - d, z);
                    b.set(x, cy - d, z, d == 1 ? "DIRT"
                            : d == depth ? w < 25 ? "GILDED_BLACKSTONE" : "BLACKSTONE"
                            : w < 50 ? "STONE" : w < 75 ? "ANDESITE" : "TUFF");
                }
                if (depth >= 3 && v > 80) {
                    stalactite(b, x, cy - depth - 1, z, 1);
                } else if (depth <= 2 && v < 20) {
                    b.set(x, cy - depth - 1, z, "HANGING_ROOTS");
                }
            }
        }
        // A lamp in the middle keeps mobs from spawning on it at night.
        lampPost(b, cx, cy, cz);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (b.has(cx + dx, cy, cz + dz) && !b.has(cx + dx, cy + 1, cz + dz)) {
                    plant(b, cx + dx, cy, cz + dz);
                }
            }
        }
    }

    /** Where wave mobs appear: courtyard floor spots (relative, standing height 1), away from the dais. */
    public static List<int[]> spawnSpots() {
        List<int[]> out = new ArrayList<>();
        // Odd coordinates up to 7: clear of the walls, the corner towers (|x|, |z| >= 8) and the
        // braziers (on even coordinates).
        for (int x = -(WALL - 3); x <= WALL - 3; x += 2) {
            for (int z = -(WALL - 3); z <= WALL - 3; z += 2) {
                if (Math.max(Math.abs(x), Math.abs(z)) >= 4) {
                    out.add(new int[] {x, 1, z});
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Polished blackstone bricks, a few of them cracked. */
    private static String bricks(int x, int y, int z) {
        return noise(x * 7 + y * 131, z * 11 - y * 71) < 12 ? "CRACKED_POLISHED_BLACKSTONE_BRICKS"
                : "POLISHED_BLACKSTONE_BRICKS";
    }

    static String facing(int dx, int dz) {
        return dz < 0 ? "north" : dz > 0 ? "south" : dx > 0 ? "east" : "west";
    }

    /** From an edge of a square ring (relative to its centre), the direction towards the centre. */
    private static String inwardOf(int dx, int dz) {
        return Math.abs(dx) >= Math.abs(dz) ? facing(-Integer.signum(dx), 0) : facing(0, -Integer.signum(dz));
    }

    /** A side of the courtyard: its outward normal; "along" runs on the other axis. */
    private record Side(int nx, int nz) {

        static final List<Side> ALL = List.of(new Side(0, -1), new Side(0, 1), new Side(1, 0), new Side(-1, 0));

        int x(int along, int out) {
            return nx != 0 ? nx * out : along;
        }

        int z(int along, int out) {
            return nz != 0 ? nz * out : along;
        }

        String outward() {
            return facing(nx, nz);
        }

        String inward() {
            return facing(-nx, -nz);
        }

        /** The direction of increasing "along". */
        String plus() {
            return nx != 0 ? "south" : "east";
        }

        String minus() {
            return nx != 0 ? "north" : "west";
        }
    }

    /** One placement per position; a later {@link #set} replaces (and moves to the end) an earlier one. */
    private static final class Build {

        private final Map<Long, Placement> blocks = new LinkedHashMap<>(16384);

        private static long key(int x, int y, int z) {
            return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (z & 0xFFFF);
        }

        void set(int x, int y, int z, String material) {
            set(x, y, z, material, "");
        }

        void set(int x, int y, int z, String material, String state) {
            long k = key(x, y, z);
            blocks.remove(k);
            blocks.put(k, new Placement(x, y, z, material, state));
        }

        /** Only where nothing is yet. */
        void add(int x, int y, int z, String material, String state) {
            if (!has(x, y, z)) {
                set(x, y, z, material, state);
            }
        }

        void stairs(int x, int y, int z, String material, String facing, boolean top) {
            set(x, y, z, material, "facing=" + facing + ",half=" + (top ? "top" : "bottom"));
        }

        boolean has(int x, int y, int z) {
            return blocks.containsKey(key(x, y, z));
        }

        String material(int x, int y, int z) {
            Placement p = blocks.get(key(x, y, z));
            return p == null ? null : p.material();
        }

        /** Grass under anything but a plant would turn to dirt by itself; make it dirt now. */
        void settle() {
            for (Placement p : List.copyOf(blocks.values())) {
                if (p.material().equals("GRASS_BLOCK")) {
                    String above = material(p.dx(), p.dy() + 1, p.dz());
                    if (above != null && !PLANTS.contains(above)) {
                        blocks.put(key(p.dx(), p.dy(), p.dz()), new Placement(p.dx(), p.dy(), p.dz(), "DIRT"));
                    }
                }
            }
        }

        List<Placement> list() {
            return List.copyOf(blocks.values());
        }
    }
}
