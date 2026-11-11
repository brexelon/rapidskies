package us.rapidskies.skyrealms.island;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import org.bukkit.Material;

/**
 * The starter islands (PLAN.md phase 4b: island schematics), built in code so they're reviewed,
 * versioned and tested like everything else, with no binary schematic files to keep in step.
 * rapid-skyrealms registers each with SuperiorSkyblock2 under its id ({@link IslandSchematics}).
 *
 * <p>Every layout gives the same start: about the same dirt, one tree (its saplings are what
 * differs), a sand patch for cactus and sugar cane, coal in the rock below, and the starter chest
 * from the config. What differs is the look: the outline, the tree and what grows on the grass.
 * Each island hangs in the sky as a rounded slab of soil over a deep, ragged cone of rock (about
 * ten blocks at the middle), with roots and glow lichen on its underside.
 *
 * <p>Layouts are drawn from a fixed seed each, so an island is the same every time it's pasted
 * (and in the tests). The origin (0, 0, 0) is where a new member stands: on grass, with two
 * blocks of air above. Only blocks that exist in 1.20 are used (PLAN.md §3).
 */
public final class IslandLayouts {

    /** Every layout id, in menu order. */
    public static final List<String> IDS = List.of("meadow", "cherry", "atoll");

    /** Blocks that fall when nothing holds them up (never left over air). */
    static final Set<Material> FALLING = Set.of(Material.SAND, Material.RED_SAND, Material.GRAVEL);

    private static final String LEAVES = "[persistent=true]";

    private IslandLayouts() {
    }

    /**
     * A built layout. The chest at {@code chest} faces the spawn; {@code sapling} is the tree's
     * own (two go in the chest). {@code yaw} is the direction a new player faces.
     */
    public record Layout(String id, Blueprint blueprint, Material sapling, int chestX, int chestY, int chestZ,
                         float yaw) {
    }

    public static Layout build(String id) {
        Layout layout = switch (id.toLowerCase(Locale.ROOT)) {
            case "meadow" -> meadow();
            case "cherry" -> cherry();
            case "atoll" -> atoll();
            default -> throw new IllegalArgumentException("no island layout '" + id + "' (have " + IDS + ")");
        };
        holdUp(layout.blueprint());
        return layout;
    }

    // ------------------------------------------------------------------ the three layouts

    /**
     * The classic: a grass island with a lobe to the east, a round oak on a little rise, a mossy
     * boulder and wildflowers.
     */
    static Layout meadow() {
        Random r = new Random(0x6D656164L);
        Blueprint b = new Blueprint();
        // Round, with the lobe of the classic L pushing out east.
        body(b, r, a -> 4.9 + 0.35 * Math.sin(3 * a + 0.8) + 2.3 * Math.pow(Math.max(0, Math.cos(a)), 6));
        sand(b);
        mound(b, r, 2, -2);
        oak(b, r, 2, 1, -2);
        b.put(-2, 0, -1, Material.CHEST, "[facing=east]");
        // A mossy boulder on the north-west edge.
        b.put(-3, 0, -3, Material.MOSSY_COBBLESTONE);
        b.put(-4, 0, -2, Material.COBBLESTONE);
        b.put(-3, 1, -3, Material.MOSS_CARPET);
        b.put(-4, 1, -2, Material.MOSS_CARPET);
        plants(b, r, 0.45, Material.SHORT_GRASS, Material.SHORT_GRASS, Material.SHORT_GRASS, Material.TALL_GRASS,
                Material.DANDELION, Material.POPPY, Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.CORNFLOWER);
        return new Layout("meadow", b, Material.OAK_SAPLING, -2, 0, -1, -90f);
    }

    /**
     * A cherry grove: a rounder island carpeted in petals under a wide cherry tree whose crown
     * droops at the edges, with a lower second bough over the west side.
     */
    static Layout cherry() {
        Random r = new Random(0x63686572L);
        Blueprint b = new Blueprint();
        body(b, r, a -> 5.1 + 0.45 * Math.sin(2 * a + 2.1) + 0.3 * Math.sin(5 * a));
        sand(b);
        mound(b, r, 2, -2);
        cherryTree(b, r, 2, 1, -2);
        b.put(-2, 0, -1, Material.CHEST, "[facing=east]");
        plants(b, r, 0.55, Material.PINK_PETALS, Material.PINK_PETALS, Material.PINK_PETALS, Material.SHORT_GRASS,
                Material.SHORT_GRASS, Material.ALLIUM, Material.LILY_OF_THE_VALLEY);
        return new Layout("cherry", b, Material.CHERRY_SAPLING, -2, 0, -1, -90f);
    }

    /**
     * An atoll: a grass middle ringed by a sand beach that steps down to the water line that
     * isn't there, under a tall jungle tree hung with vines. Ferns in the grass, dead bushes on
     * the sand.
     */
    static Layout atoll() {
        Random r = new Random(0x61746F6CL);
        Blueprint b = new Blueprint();
        DoubleUnaryOperator outline = a -> 5.4 + 0.3 * Math.sin(5 * a + 0.4) + 0.25 * Math.sin(2 * a + 1.3);
        body(b, r, outline);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                if (b.material(x, -1, z) != Material.GRASS_BLOCK) {
                    continue;
                }
                double d = Math.hypot(x, z) / outline.applyAsDouble(Math.atan2(z, x));
                if (d > 0.88) {
                    // The beach steps down a block at the very edge.
                    b.put(x, -1, z, Material.AIR);
                    b.put(x, -2, z, Material.SAND);
                } else if (d > 0.62) {
                    b.put(x, -1, z, Material.SAND);
                }
            }
        }
        mound(b, r, 1, -2);
        jungleTree(b, r, 1, 1, -2);
        b.put(-2, 0, 1, Material.CHEST, "[facing=east]");
        plants(b, r, 0.4, Material.SHORT_GRASS, Material.FERN, Material.FERN, Material.LARGE_FERN,
                Material.DEAD_BUSH);
        return new Layout("atoll", b, Material.JUNGLE_SAPLING, -2, 0, 1, -90f);
    }

    // ------------------------------------------------------------------ the shared body

    /**
     * The island itself: grass on top (y -1) inside {@code outline} (radius by angle, east is 0),
     * two or three blocks of dirt under it, then rock that reaches deeper toward the middle
     * (to about y -11) with a ragged underside. No column hangs more than two blocks below all of
     * its neighbours, so the underside is a stepped cone, not a spike. The rock is stone streaked
     * with andesite and tuff, with coal in it; dirt that shows underneath grows hanging roots, and
     * some deep rock glow lichen.
     */
    private static void body(Blueprint b, Random r, DoubleUnaryOperator outline) {
        final int span = 9;
        final int size = 2 * span + 1;
        double[] share = new double[size * size];
        int[] bottoms = new int[size * size];
        for (int x = -span; x <= span; x++) {
            for (int z = -span; z <= span; z++) {
                int i = (x + span) * size + z + span;
                double dist = Math.hypot(x, z);
                double radius = outline.applyAsDouble(Math.atan2(z, x));
                share[i] = dist > radius ? -1 : dist / radius;
                if (share[i] >= 0) {
                    bottoms[i] = -2 - (int) Math.round(9 * Math.pow(1 - share[i], 1.5) * (0.8 + 0.4 * r.nextDouble()));
                }
            }
        }
        // Clamp spikes: each column at most two below its deepest neighbour.
        for (int pass = 0; pass < 2; pass++) {
            int[] next = bottoms.clone();
            for (int x = -span + 1; x < span; x++) {
                for (int z = -span + 1; z < span; z++) {
                    int i = (x + span) * size + z + span;
                    if (share[i] < 0) {
                        continue;
                    }
                    int deepest = -1;
                    for (int n : new int[]{i - 1, i + 1, i - size, i + size}) {
                        if (share[n] >= 0) {
                            deepest = Math.min(deepest, bottoms[n]);
                        }
                    }
                    next[i] = Math.max(bottoms[i], deepest - 2);
                }
            }
            bottoms = next;
        }
        for (int x = -span; x <= span; x++) {
            for (int z = -span; z <= span; z++) {
                int i = (x + span) * size + z + span;
                double d = share[i];
                if (d < 0) {
                    continue;
                }
                int soil = d < 0.55 ? -4 : d < 0.9 ? -3 : -2;
                int bottom = Math.min(bottoms[i], soil);
                b.put(x, -1, z, Material.GRASS_BLOCK);
                for (int y = -2; y >= bottom; y--) {
                    Material m;
                    if (y >= soil) {
                        m = Material.DIRT;
                    } else if (y == soil - 1 && r.nextInt(3) == 0) {
                        m = Material.DIRT;
                    } else {
                        int roll = r.nextInt(20);
                        m = roll < 13 ? Material.STONE : roll < 17 ? Material.ANDESITE : Material.TUFF;
                    }
                    b.put(x, y, z, m);
                }
                Material under = b.material(x, bottom, z);
                if (under == Material.DIRT && r.nextInt(5) < 2) {
                    b.put(x, bottom, z, Material.ROOTED_DIRT);
                    b.put(x, bottom - 1, z, Material.HANGING_ROOTS);
                } else if (under != Material.DIRT && bottom <= -6 && r.nextInt(6) == 0) {
                    b.put(x, bottom - 1, z, Material.GLOW_LICHEN, "[up=true]");
                }
            }
        }
        // Coal for the first torches, in the rock under the middle.
        for (int[] p : new int[][]{{1, -5, 1}, {-1, -6, -1}, {0, -7, 1}, {-1, -5, 2}}) {
            if (!b.isAir(p[0], p[1], p[2])) {
                b.put(p[0], p[1], p[2], Material.COAL_ORE);
            }
        }
    }

    /** A sand patch on the south-west edge, for cactus and sugar cane. */
    private static void sand(Blueprint b) {
        for (int[] p : new int[][]{{-3, 2}, {-2, 3}, {-3, 3}, {-4, 2}}) {
            b.put(p[0], -1, p[1], Material.SAND);
            if (b.isAir(p[0], -2, p[1])) {
                b.put(p[0], -2, p[1], Material.DIRT);
            }
        }
    }

    /**
     * A one-block rise of grass round {@code (x, z)}, where the tree stands: the middle 3 x 3
     * and some of the ring round it, never next to the spawn or the chest.
     */
    private static void mound(Blueprint b, Random r, int x, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int px = x + dx;
                int pz = z + dz;
                int d2 = dx * dx + dz * dz;
                if (d2 > 5 || (d2 > 2 && r.nextBoolean())) {
                    continue;
                }
                if (px * px + pz * pz <= 2 || b.material(px, -1, pz) != Material.GRASS_BLOCK) {
                    continue;
                }
                b.put(px, -1, pz, Material.DIRT);
                b.put(px, 0, pz, Material.GRASS_BLOCK);
            }
        }
        // Logs stand on dirt.
        b.put(x, 0, z, Material.DIRT);
    }

    /**
     * Sand and gravel with nothing solid under them (air, roots, lichen) would drop into the
     * void at the first block update: sandstone goes under them.
     */
    private static void holdUp(Blueprint b) {
        for (Blueprint.Block blk : List.copyOf(b.blocks())) {
            if (!FALLING.contains(blk.material())) {
                continue;
            }
            Material below = b.material(blk.x(), blk.y() - 1, blk.z());
            if (below == Material.AIR || below == Material.HANGING_ROOTS || below == Material.GLOW_LICHEN) {
                b.put(blk.x(), blk.y() - 1, blk.z(), Material.SANDSTONE);
            }
        }
    }

    // ------------------------------------------------------------------ trees

    /** A round oak: a six-log trunk in a lumpy ball of leaves, with a short bough to the east. */
    private static void oak(Blueprint b, Random r, int x, int y, int z) {
        crown(b, r, x, y + 5, z, 2.7, 2.1, Material.OAK_LEAVES);
        crown(b, r, x + 2, y + 3, z + 1, 1.6, 1.2, Material.OAK_LEAVES);
        trunk(b, x, y, z, 6, Material.OAK_LOG);
        b.put(x + 1, y + 3, z, Material.OAK_LOG, "[axis=x]");
    }

    /**
     * A cherry tree: the trunk leans up to a wide, flat crown that droops at its rim, and a
     * bough reaches west to a smaller crown of its own.
     */
    private static void cherryTree(Blueprint b, Random r, int x, int y, int z) {
        crown(b, r, x + 1, y + 6, z, 3.2, 1.5, Material.CHERRY_LEAVES);
        crown(b, r, x - 4, y + 4, z, 2.0, 1.1, Material.CHERRY_LEAVES);
        droop(b, r, Material.CHERRY_LEAVES);
        trunk(b, x, y, z, 6, Material.CHERRY_LOG);
        // The bough: out west from the trunk, then up into its crown.
        b.put(x - 1, y + 3, z, Material.CHERRY_LOG, "[axis=x]");
        b.put(x - 2, y + 3, z, Material.CHERRY_LOG, "[axis=x]");
        b.put(x - 3, y + 3, z, Material.CHERRY_LOG, "[axis=x]");
        b.put(x - 4, y + 3, z, Material.CHERRY_LOG, "[axis=y]");
    }

    /** A jungle tree: a tall trunk under a broad, flat crown, with vines hanging off it and the trunk. */
    private static void jungleTree(Blueprint b, Random r, int x, int y, int z) {
        crown(b, r, x, y + 7, z, 3.0, 1.3, Material.JUNGLE_LEAVES);
        crown(b, r, x, y + 9, z, 1.6, 0.9, Material.JUNGLE_LEAVES);
        trunk(b, x, y, z, 8, Material.JUNGLE_LOG);
        vines(b, r, Material.JUNGLE_LEAVES, 0.3);
        // Vines climbing the trunk.
        for (int dy = 2; dy < 6; dy++) {
            if (r.nextBoolean()) {
                b.put(x - 1, y + dy, z, Material.VINE, "[east=true]");
            }
            if (r.nextInt(3) == 0) {
                b.put(x, y + dy, z + 1, Material.VINE, "[north=true]");
            }
        }
    }

    private static void trunk(Blueprint b, int x, int y, int z, int height, Material log) {
        for (int dy = 0; dy < height; dy++) {
            b.put(x, y + dy, z, log, "[axis=y]");
        }
    }

    /**
     * An uneven ellipsoid of persistent leaves round {@code (x, y, z)}: {@code rx} across,
     * {@code ry} up and down, with its surface roughened so no two crowns are the same box.
     */
    private static void crown(Blueprint b, Random r, int x, int y, int z, double rx, double ry, Material leaves) {
        int ix = (int) Math.ceil(rx);
        int iy = (int) Math.ceil(ry);
        for (int dy = -iy; dy <= iy; dy++) {
            for (int dx = -ix; dx <= ix; dx++) {
                for (int dz = -ix; dz <= ix; dz++) {
                    double e = (dx * dx + dz * dz) / (rx * rx) + (double) (dy * dy) / (ry * ry);
                    if (e <= 1 + (r.nextDouble() - 0.5) * 0.5 && b.isAir(x + dx, y + dy, z + dz)) {
                        b.put(x + dx, y + dy, z + dz, leaves, LEAVES);
                    }
                }
            }
        }
    }

    /** Leaves hanging one or two blocks under the rim of a crown (a cherry's droop). */
    private static void droop(Blueprint b, Random r, Material leaves) {
        for (Blueprint.Block leaf : List.copyOf(b.blocks())) {
            if (leaf.material() != leaves || !b.isAir(leaf.x(), leaf.y() - 1, leaf.z()) || !rim(b, leaf, leaves)) {
                continue;
            }
            if (r.nextInt(5) < 2) {
                b.put(leaf.x(), leaf.y() - 1, leaf.z(), leaves, LEAVES);
                if (r.nextInt(3) == 0 && b.isAir(leaf.x(), leaf.y() - 2, leaf.z())) {
                    b.put(leaf.x(), leaf.y() - 2, leaf.z(), leaves, LEAVES);
                }
            }
        }
    }

    /** Vines hanging one to three blocks off the outer sides of a crown. */
    private static void vines(Blueprint b, Random r, Material leaves, double chance) {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // The vine's face that touches the leaf it hangs from: east of the leaf means its west face.
        String[] faces = {"west", "east", "north", "south"};
        for (Blueprint.Block leaf : List.copyOf(b.blocks())) {
            if (leaf.material() != leaves) {
                continue;
            }
            for (int i = 0; i < sides.length; i++) {
                int vx = leaf.x() + sides[i][0];
                int vz = leaf.z() + sides[i][1];
                if (!b.isAir(vx, leaf.y(), vz) || !b.isAir(vx, leaf.y() + 1, vz) || r.nextDouble() >= chance) {
                    continue;
                }
                int length = 1 + r.nextInt(3);
                for (int dy = 0; dy < length && b.isAir(vx, leaf.y() - dy, vz); dy++) {
                    b.put(vx, leaf.y() - dy, vz, Material.VINE, "[" + faces[i] + "=true]");
                }
            }
        }
    }

    /** Whether a leaf is on the outside of its crown (air beside it). */
    private static boolean rim(Blueprint b, Blueprint.Block leaf, Material leaves) {
        int x = leaf.x();
        int y = leaf.y();
        int z = leaf.z();
        return b.material(x + 1, y, z) != leaves || b.material(x - 1, y, z) != leaves
                || b.material(x, y, z + 1) != leaves || b.material(x, y, z - 1) != leaves;
    }

    // ------------------------------------------------------------------ what grows on top

    /**
     * Scatters {@code kinds} (repeats weigh more) over the open ground, a {@code density} share
     * of it: plants on grass, dead bushes on sand. The spawn and the chest stay clear, so a new
     * member sees the chest from where they stand.
     */
    private static void plants(Blueprint b, Random r, double density, Material... kinds) {
        List<int[]> ground = new ArrayList<>();
        for (Blueprint.Block blk : b.blocks()) {
            if ((blk.material() == Material.GRASS_BLOCK || blk.material() == Material.SAND)
                    && b.isAir(blk.x(), blk.y() + 1, blk.z()) && b.isAir(blk.x(), blk.y() + 2, blk.z())) {
                ground.add(new int[]{blk.x(), blk.y(), blk.z()});
            }
        }
        for (int[] g : ground) {
            int x = g[0];
            int y = g[1] + 1;
            int z = g[2];
            // Clear round the spawn (0, 0) and the chest beside it (both chests are at x -2).
            if (Math.abs(x) <= 1 && Math.abs(z) <= 1 || x >= -3 && x <= -1 && Math.abs(z) <= 2) {
                continue;
            }
            if (r.nextDouble() >= density) {
                continue;
            }
            Material kind = kinds[r.nextInt(kinds.length)];
            boolean onSand = b.material(x, g[1], z) == Material.SAND;
            if (onSand != (kind == Material.DEAD_BUSH)) {
                continue;
            }
            switch (kind) {
                case PINK_PETALS -> b.put(x, y, z, kind, "[facing=" + List.of("north", "east", "south", "west")
                        .get(r.nextInt(4)) + ",flower_amount=" + (1 + r.nextInt(4)) + "]");
                case TALL_GRASS, LARGE_FERN -> {
                    b.put(x, y, z, kind, "[half=lower]");
                    b.put(x, y + 1, z, kind, "[half=upper]");
                }
                default -> b.put(x, y, z, kind);
            }
        }
    }
}
