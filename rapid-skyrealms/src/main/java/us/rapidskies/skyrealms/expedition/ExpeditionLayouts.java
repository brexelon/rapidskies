package us.rapidskies.skyrealms.expedition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.bukkit.Material;
import us.rapidskies.skyrealms.island.Blueprint;

/**
 * The three Expedition islands (PLAN.md §5.1): crystal caverns, a sunken ship, overgrown ruins.
 * Built in code from a seed, so every Expedition of a theme is a little different, and the same
 * seed always gives the same island (a crash leftover is removed by building its blueprint again).
 *
 * <p>The origin is the island's centre at surface level (y 0 is the top layer). The heart sits
 * on the ground at the centre (0, 1, 0), and a clear walkway runs to it from the landing pad in
 * the south, so everyone can reach it on foot. Resource blocks (the theme's
 * {@link Theme#nodeMaterials()}) are always reachable: they face air, or sit under another
 * resource block. Blocks are placed without physics, so everything that needs support has it.
 * Everything exists in 1.20.
 */
public final class ExpeditionLayouts {

    /**
     * A built island. The heart is at {@code heart*}; players land at {@code pad*} facing
     * {@code padYaw}.
     */
    public record Layout(Theme theme, Blueprint blueprint, int heartX, int heartY, int heartZ,
                         double padX, double padY, double padZ, float padYaw) {

        /** Whether a block in the blueprint is one of the resource blocks. */
        public boolean node(Blueprint.Block b) {
            return theme.nodeMaterials().contains(b.material());
        }

        public boolean heart(int x, int y, int z) {
            return x == heartX && y == heartY && z == heartZ;
        }
    }

    /** The four horizontal directions, as {dx, dz}, and their block-state names. */
    private static final int[][] SIDES = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final String[] SIDE_NAMES = {"north", "east", "south", "west"};
    private static final String LEAVES = "[persistent=true]";
    private static final String UP = "[facing=up]";

    private ExpeditionLayouts() {
    }

    public static Layout build(Theme theme, long seed) {
        Random r = new Random(seed ^ theme.ordinal() * 0x9E3779B97F4A7C15L);
        return switch (theme) {
            case CRYSTAL_CAVERNS -> caverns(r);
            case SUNKEN_SHIP -> ship(r);
            case OVERGROWN_RUINS -> ruins(r);
        };
    }

    // ------------------------------------------------------------------ crystal caverns

    private static Layout caverns(Random r) {
        Site s = new Site(r);
        Blueprint b = s.b;
        int radius = 14;
        body(b, r, radius, 15, () -> s.pick(Material.STONE, Material.STONE, Material.ANDESITE, Material.TUFF,
                        Material.CALCITE),
                y -> y > -4 ? s.pick(Material.STONE, Material.TUFF, Material.ANDESITE)
                        : s.pick(Material.DEEPSLATE, Material.DEEPSLATE, Material.TUFF));

        // The geode in the middle: a dome of smooth basalt, calcite and an amethyst lining, with
        // the heart inside. Shafts of light come through the roof, each a whole column over the
        // open middle, so whoever falls in from the dome lands on the floor and walks out.
        double inner = 4.4;
        double outer = 6.5;
        boolean[][] shaft = new boolean[7][7];
        for (int i = 0; i < 3; i++) {
            int cx = r.nextInt(5) - 2;
            int cz = r.nextInt(5) - 2;
            shaft[cx + 3][cz + 3] = true;
            for (int[] d : SIDES) {
                int x = cx + d[0];
                int z = cz + d[1];
                if (x * x + z * z <= 9 && s.chance(50)) {
                    shaft[x + 3][z + 3] = true;
                }
            }
        }
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                boolean open = Math.abs(x) <= 3 && Math.abs(z) <= 3 && shaft[x + 3][z + 3];
                for (int y = 0; y <= 8; y++) {
                    double d = geode(x, y, z);
                    if (d > outer) {
                        continue;
                    }
                    if (y == 0) {
                        if (d < inner) {
                            b.put(x, 0, z, s.chance(25) ? Material.SMOOTH_BASALT : Material.CALCITE);
                        }
                    } else if (d < inner || open) {
                        b.put(x, y, z, Material.AIR);
                    } else if (d < inner + 0.6) {
                        b.put(x, y, z, Material.AMETHYST_BLOCK);
                    } else if (d < inner + 1.2) {
                        b.put(x, y, z, Material.CALCITE);
                    } else {
                        // Here and there the crystal shows through the crust.
                        b.put(x, y, z, s.chance(7) ? Material.AMETHYST_BLOCK : Material.SMOOTH_BASALT);
                    }
                }
            }
        }
        pad(s, Material.POLISHED_DEEPSLATE, 11);
        // The way in: a tiled walk from the pad through the geode's mouth, jagged at the edges.
        s.walk(-1, 2, 1, 9);
        for (int z = 3; z <= 5; z++) {
            for (int y = 1; y <= 2; y++) {
                b.put(-2, y, z, Material.AIR);
                b.put(2, y, z, Material.AIR);
            }
        }
        for (int z = 3; z <= 9; z++) {
            for (int x = -1; x <= 1; x++) {
                b.put(x, 0, z, s.pick(Material.DEEPSLATE_TILES, Material.POLISHED_DEEPSLATE, Material.COBBLED_DEEPSLATE));
            }
        }
        s.walk(-1, -1, 1, 1);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                b.put(x, 0, z, Material.SMOOTH_BASALT);
            }
        }
        b.put(0, 0, 0, Material.CALCITE);

        // Crags round the rim, rising toward the edge, lowest where the way in is. Terraced: a
        // crag is never two blocks lower than the one beside it, so there's no pit among them
        // that can't be climbed out of.
        double p1 = r.nextDouble() * Math.PI * 2;
        double p2 = r.nextDouble() * Math.PI * 2;
        int span = radius * 2 + 1;
        int[][] crag = new int[span][span];
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double d = Math.sqrt(x * x + z * z) / radius;
                if (d < 0.6 || b.isAir(x, 0, z) || s.kept(x, z) || z >= 6 && Math.abs(x) <= 3) {
                    crag[x + radius][z + radius] = -1;
                    continue;
                }
                double a = Math.atan2(z, x);
                double rise = (d - 0.6) / 0.4 * (2.4 + 1.6 * Math.sin(3 * a + p1) + Math.sin(7 * a + p2));
                crag[x + radius][z + radius] = Math.max(0, Math.min(4, (int) Math.round(rise + r.nextDouble() * 0.8 - 0.4)));
            }
        }
        for (boolean raised = true; raised; ) {
            raised = false;
            for (int i = 0; i < span; i++) {
                for (int j = 0; j < span; j++) {
                    for (int[] d : SIDES) {
                        int ni = i + d[0];
                        int nj = j + d[1];
                        if (crag[i][j] >= 0 && ni >= 0 && nj >= 0 && ni < span && nj < span
                                && crag[ni][nj] - 1 > crag[i][j]) {
                            crag[i][j] = crag[ni][nj] - 1;
                            raised = true;
                        }
                    }
                }
            }
        }
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int h = crag[x + radius][z + radius];
                for (int y = 1; y <= h; y++) {
                    b.put(x, y, z, y == h && s.chance(30) ? Material.COBBLED_DEEPSLATE
                            : s.pick(Material.STONE, Material.STONE, Material.TUFF, Material.ANDESITE));
                }
            }
        }

        // Ore: outcrops on the open ground, reaching a block or two down, and veins in the crags.
        List<Material> ores = List.of(Material.IRON_ORE, Material.IRON_ORE, Material.IRON_ORE, Material.GOLD_ORE,
                Material.GOLD_ORE, Material.LAPIS_ORE, Material.LAPIS_ORE, Material.REDSTONE_ORE, Material.REDSTONE_ORE,
                Material.EMERALD_ORE, Material.DIAMOND_ORE);
        for (int i = 0; i < 18; i++) {
            int[] p = s.spot(7, radius - 1);
            if (p == null) {
                continue;
            }
            Material ore = ores.get(r.nextInt(ores.size()));
            int depth = 1 + r.nextInt(2);
            b.put(p[0], 1, p[1], ore);
            for (int y = 0; y > -depth; y--) {
                b.put(p[0], y, p[1], r.nextInt(3) == 0 ? ores.get(r.nextInt(ores.size())) : ore);
            }
        }
        Set<Material> rock = Set.of(Material.STONE, Material.TUFF, Material.ANDESITE, Material.COBBLED_DEEPSLATE);
        for (int i = 0; i < 40; i++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = radius * (0.62 + r.nextDouble() * 0.36);
            int x = (int) Math.round(Math.cos(angle) * dist);
            int z = (int) Math.round(Math.sin(angle) * dist);
            int top = surface(b, x, z);
            if (top < 1) {
                continue;
            }
            int y = 1 + r.nextInt(top);
            if (rock.contains(b.material(x, y, z)) && b.exposed(x, y, z)) {
                b.put(x, y, z, ores.get(r.nextInt(ores.size())));
            }
        }

        // Crystal spires: a tall one with shorter ones leaning on it, each tipped with a crystal.
        // Clusters stand apart, so two can't ring in a hole nobody could climb out of.
        for (int i = 0; i < 9; i++) {
            int[] p = s.spot(7, radius - 2);
            if (p == null || near(b, p[0], 1, p[1], 3, Material.AMETHYST_BLOCK)) {
                continue;
            }
            int h = 3 + r.nextInt(3);
            crystal(b, p[0], p[1], h, Material.AMETHYST_CLUSTER);
            for (int[] d : SIDES) {
                int x = p[0] + d[0];
                int z = p[1] + d[1];
                if (s.chance(55) && !s.kept(x, z) && !b.isAir(x, 0, z) && b.isAir(x, 1, z)) {
                    crystal(b, x, z, 1 + r.nextInt(h - 1), Material.LARGE_AMETHYST_BUD);
                }
            }
        }

        // Stalagmites on the crags and the open ground.
        for (int i = 0; i < 16; i++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = radius * (0.5 + r.nextDouble() * 0.45);
            int x = (int) Math.round(Math.cos(angle) * dist);
            int z = (int) Math.round(Math.sin(angle) * dist);
            int top = surface(b, x, z);
            if (top < 0 || s.kept(x, z) || !rock.contains(b.material(x, top, z)) && top > 0) {
                continue;
            }
            b.put(x, top, z, Material.DRIPSTONE_BLOCK);
            dripstone(b, x, top + 1, z, 1 + r.nextInt(3), true);
        }

        // Stalactites hanging under the island.
        for (int i = 0; i < 34; i++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = radius * 0.8 * Math.sqrt(r.nextDouble());
            int x = (int) Math.round(Math.cos(angle) * dist);
            int z = (int) Math.round(Math.sin(angle) * dist);
            int bottom = bottom(b, x, z);
            if (bottom == Integer.MIN_VALUE || b.material(x, bottom, z) == Material.POINTED_DRIPSTONE) {
                continue;
            }
            b.put(x, bottom, z, Material.DRIPSTONE_BLOCK);
            dripstone(b, x, bottom - 1, z, 1 + r.nextInt(4), false);
        }

        // Inside the geode: buds growing in from the lining, clusters on the floor.
        for (Blueprint.Block blk : List.copyOf(b.blocks())) {
            if (blk.material() != Material.AMETHYST_BLOCK || blk.y() < 1 || geode(blk.x(), blk.y(), blk.z()) > outer) {
                continue;
            }
            for (int[] d : new int[][]{{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}}) {
                int x = blk.x() + d[0];
                int y = blk.y() + d[1];
                int z = blk.z() + d[2];
                if (y < 1 || geode(x, y, z) >= inner || !b.isAir(x, y, z) || s.clear(x, y, z) || !s.chance(22)) {
                    continue;
                }
                b.put(x, y, z, s.pick(Material.SMALL_AMETHYST_BUD, Material.MEDIUM_AMETHYST_BUD,
                        Material.LARGE_AMETHYST_BUD), "[facing=" + facing(d) + "]");
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                // Only where there's headroom to step up onto it.
                if (geode(x, 3, z) < inner && b.isAir(x, 1, z) && b.isAir(x, 2, z) && b.isAir(x, 3, z)
                        && !s.kept(x, z) && s.chance(14)) {
                    b.put(x, 1, z, Material.AMETHYST_CLUSTER, UP);
                }
            }
        }

        bury(b, Theme.CRYSTAL_CAVERNS, m -> m == Material.AMETHYST_BLOCK ? Material.CALCITE : Material.STONE);
        b.put(0, 1, 0, Material.BUDDING_AMETHYST);
        return new Layout(Theme.CRYSTAL_CAVERNS, b, 0, 1, 0, 0.5, 1, 11.5, 180f);
    }

    /** Distance from the geode's centre, stretched a little in height so it stands tall. */
    private static double geode(int x, int y, int z) {
        return Math.sqrt(x * x + z * z + 0.85 * y * y);
    }

    /** A column of amethyst {@code h} high on the ground, tipped with {@code tip}. */
    private static void crystal(Blueprint b, int x, int z, int h, Material tip) {
        for (int y = 1; y <= h; y++) {
            b.put(x, y, z, Material.AMETHYST_BLOCK);
        }
        b.put(x, h + 1, z, tip, UP);
    }

    /** Pointed dripstone {@code length} long from (x, y, z), growing up or hanging down. */
    private static void dripstone(Blueprint b, int x, int y, int z, int length, boolean up) {
        for (int i = 0; i < length; i++) {
            String thickness = i == length - 1 ? "tip" : i == length - 2 ? "frustum" : i == 0 ? "base" : "middle";
            b.put(x, up ? y + i : y - i, z, Material.POINTED_DRIPSTONE,
                    "[thickness=" + thickness + ",vertical_direction=" + (up ? "up" : "down") + "]");
        }
    }

    // ------------------------------------------------------------------ sunken ship

    /** The ship's centre line (z); it lies east-west, bow to the east. */
    private static final int KEEL = -5;

    private static Layout ship(Random r) {
        Site s = new Site(r);
        Blueprint b = s.b;
        int radius = 14;
        body(b, r, radius, 9, () -> Material.SAND,
                y -> y > -3 ? Material.SAND : s.pick(Material.SANDSTONE, Material.SANDSTONE, Material.SMOOTH_SANDSTONE));
        pad(s, Material.SMOOTH_SANDSTONE, 11);
        // The heart, the captain's chest, has spilled out of the wreck onto the sand; a
        // driftwood boardwalk leads to it from the pad.
        s.walk(-1, -1, 1, 1);
        s.walk(-1, 2, 1, 9);
        for (int z = 2; z <= 9; z++) {
            for (int x = -1; x <= 1; x++) {
                if (s.chance(80)) {
                    b.put(x, 0, z, Material.STRIPPED_BIRCH_LOG, "[axis=x]");
                }
            }
        }
        hull(s);
        rigging(s);
        cargo(s);
        s.keep(-11, KEEL - 4, 13, KEEL + 4);

        // Tide pools: a block of sea water in the sand, with seagrass and sea pickles. Kept two
        // blocks from anything that can be mined, so the water never finds a way out.
        int pools = 0;
        for (int attempt = 0; attempt < 40 && pools < 3; attempt++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = 5 + r.nextDouble() * 5;
            int px = (int) Math.round(Math.cos(angle) * dist);
            int pz = (int) Math.round(Math.sin(angle) * dist);
            double pr = 1.2 + r.nextDouble() * 0.8;
            int ir = (int) Math.ceil(pr);
            boolean fits = true;
            for (int x = px - ir - 2; x <= px + ir + 2 && fits; x++) {
                for (int z = pz - ir - 2; z <= pz + ir + 2 && fits; z++) {
                    int d2 = (x - px) * (x - px) + (z - pz) * (z - pz);
                    if (d2 > (pr + 2) * (pr + 2)) {
                        continue;
                    }
                    fits = !s.kept(x, z) && b.material(x, 0, z) == Material.SAND && b.isAir(x, 1, z)
                            && (d2 > pr * pr || !b.isAir(x, -2, z));
                }
            }
            if (!fits) {
                continue;
            }
            for (int x = -ir; x <= ir; x++) {
                for (int z = -ir; z <= ir; z++) {
                    if (x * x + z * z > pr * pr) {
                        continue;
                    }
                    int roll = r.nextInt(100);
                    if (roll < 25) {
                        b.put(px + x, 0, pz + z, Material.SEAGRASS);
                    } else if (roll < 37) {
                        b.put(px + x, 0, pz + z, Material.SEA_PICKLE, "[pickles=" + (1 + r.nextInt(4))
                                + ",waterlogged=true]");
                    } else {
                        b.put(px + x, 0, pz + z, Material.WATER);
                    }
                }
            }
            s.keep(px - ir - 2, pz - ir - 2, px + ir + 2, pz + ir + 2);
            pools++;
        }

        // A broken topmast washed up on the sand, a scrap of sail still on it.
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = (r.nextBoolean() ? 1 : -1) * (6 + r.nextInt(3));
            int z0 = 1 + r.nextInt(4);
            boolean fits = true;
            for (int z = z0 - 1; z <= z0 + 4 && fits; z++) {
                for (int dx = -1; dx <= 1 && fits; dx++) {
                    fits = !s.kept(x + dx, z) && b.material(x + dx, 0, z) == Material.SAND && b.isAir(x + dx, 1, z);
                }
            }
            if (!fits) {
                continue;
            }
            for (int z = z0; z < z0 + 4; z++) {
                b.put(x, 1, z, Material.SPRUCE_LOG, "[axis=z]");
            }
            b.put(x + (x > 0 ? 1 : -1), 1, z0 + 1 + r.nextInt(2), Material.WHITE_WOOL);
            s.keep(x - 1, z0 - 1, x + 1, z0 + 4);
            break;
        }

        // Low dunes toward the edge.
        for (int i = 0; i < 5; i++) {
            int[] p = s.spot(8, radius - 2);
            if (p == null) {
                continue;
            }
            double dr = 1.4 + r.nextDouble();
            int ir = (int) Math.ceil(dr);
            for (int x = -ir; x <= ir; x++) {
                for (int z = -ir; z <= ir; z++) {
                    int dx = p[0] + x;
                    int dz = p[1] + z;
                    if (x * x + z * z <= dr * dr && !s.kept(dx, dz) && b.material(dx, 0, dz) == Material.SAND
                            && b.isAir(dx, 1, dz)) {
                        b.put(dx, 1, dz, Material.SAND);
                    }
                }
            }
        }

        // The reef: resource blocks on the sand (one or two more beneath or on top), dead coral
        // round their feet.
        List<Material> reef = List.of(Material.PRISMARINE, Material.PRISMARINE, Material.PRISMARINE,
                Material.SEA_LANTERN, Material.DRIED_KELP_BLOCK, Material.DRIED_KELP_BLOCK, Material.WET_SPONGE);
        for (int i = 0; i < 30; i++) {
            int[] p = s.spot(3, radius - 1);
            if (p == null) {
                continue;
            }
            b.put(p[0], 1, p[1], reef.get(r.nextInt(reef.size())));
            b.put(p[0], 0, p[1], reef.get(r.nextInt(reef.size())));
            if (s.chance(25)) {
                b.put(p[0], 2, p[1], reef.get(r.nextInt(reef.size())));
            }
            for (int[] d : SIDES) {
                int x = p[0] + d[0];
                int z = p[1] + d[1];
                if (s.chance(22) && !s.kept(x, z) && b.material(x, 0, z) == Material.SAND && b.isAir(x, 1, z)) {
                    b.put(x, 1, z, deadCoral(s, false), "[waterlogged=false]");
                }
            }
        }
        // Dead coral heads, some with a fan on top.
        for (int i = 0; i < 10; i++) {
            int[] p = s.spot(4, radius - 1);
            if (p == null) {
                continue;
            }
            if (s.chance(55)) {
                b.put(p[0], 1, p[1], s.pick(Material.DEAD_BRAIN_CORAL_BLOCK, Material.DEAD_TUBE_CORAL_BLOCK,
                        Material.DEAD_FIRE_CORAL_BLOCK, Material.DEAD_HORN_CORAL_BLOCK, Material.DEAD_BUBBLE_CORAL_BLOCK));
                b.put(p[0], 2, p[1], deadCoral(s, s.chance(50)), "[waterlogged=false]");
            } else {
                b.put(p[0], 1, p[1], deadCoral(s, s.chance(40)), "[waterlogged=false]");
            }
        }
        // Old bones in the sand.
        for (int i = 0; i < 2; i++) {
            int[] p = s.spot(5, radius - 2);
            if (p != null && b.material(p[0], 0, p[1]) == Material.SAND) {
                b.put(p[0], 0, p[1], Material.BONE_BLOCK, r.nextBoolean() ? "[axis=x]" : "[axis=z]");
            }
        }

        connectFences(b);
        bury(b, Theme.SUNKEN_SHIP, m -> m == Material.BARREL ? Material.SPRUCE_PLANKS : Material.SANDSTONE);
        b.put(0, 1, 0, Material.CHEST, "[facing=south]");
        return new Layout(Theme.SUNKEN_SHIP, b, 0, 1, 0, 0.5, 1, 11.5, 180f);
    }

    /** Half the hull's width at {@code x}: full amidships, narrowing to a point at the bow (x 10). */
    private static int beam(int x) {
        return x <= 6 ? 3 : x <= 8 ? 2 : x == 9 ? 1 : 0;
    }

    /**
     * The hull, beached in the sand from the stern (x -10) to the bow (x 10): a hold (floor
     * y 0, walls to y 2), the main deck at y 3 with a rail, and a sterncastle with the captain's
     * cabin under a poop deck at y 6. A gangplank climbs to the deck from the south, stairs climb
     * to the poop deck, and a breach in the south side opens the hold to the beach.
     */
    private static void hull(Site s) {
        Blueprint b = s.b;
        int cz = KEEL;
        for (int x = -10; x <= 10; x++) {
            int hw = beam(x);
            for (int dz = -hw; dz <= hw; dz++) {
                int z = cz + dz;
                boolean wall = Math.abs(dz) == hw || x == -10;
                b.put(x, 0, z, Material.DARK_OAK_PLANKS);
                b.put(x, 1, z, wall ? Material.DARK_OAK_PLANKS : Material.AIR);
                b.put(x, 2, z, wall ? Material.SPRUCE_PLANKS : Material.AIR);
                if (wall) {
                    b.put(x, 3, z, Material.STRIPPED_SPRUCE_LOG, x == -10 ? "[axis=z]" : "[axis=x]");
                } else if (s.chance(15)) {
                    b.put(x, 3, z, Material.STRIPPED_OAK_LOG, "[axis=x]");
                } else {
                    b.put(x, 3, z, Material.OAK_PLANKS);
                }
                if (Math.abs(dz) == hw && x >= -6 && x <= 9) {
                    b.put(x, 4, z, Material.SPRUCE_FENCE);
                }
            }
        }
        // The stem and bowsprit.
        b.put(10, 4, cz, Material.STRIPPED_SPRUCE_LOG, "[axis=y]");
        b.put(10, 5, cz, Material.STRIPPED_SPRUCE_LOG, "[axis=y]");
        for (int x = 11; x <= 13; x++) {
            b.put(x, 5, cz, Material.SPRUCE_LOG, "[axis=x]");
        }
        // Gunports, their lids hanging open.
        for (int x : new int[]{-8, -5, -1, 4}) {
            b.put(x, 2, cz - 3, Material.SPRUCE_TRAPDOOR, "[facing=south,half=top,open=true]");
        }
        for (int x : new int[]{-8, -5, 5}) {
            b.put(x, 2, cz + 3, Material.SPRUCE_TRAPDOOR, "[facing=north,half=top,open=true]");
        }
        // The breach: the south side stove in, the hold open to the beach.
        for (int x = -2; x <= 1; x++) {
            b.put(x, 1, cz + 3, Material.AIR);
            b.put(x, 2, cz + 3, Material.AIR);
        }
        b.put(2, 2, cz + 3, Material.AIR);
        b.put(-3, 2, cz + 3, Material.SPRUCE_STAIRS, "[facing=west,half=top]");
        // Hatches: a cargo hatch, and a ladder down the north side.
        for (int x = -3; x <= -2; x++) {
            b.put(x, 3, cz, Material.AIR);
            b.put(x, 3, cz - 1, Material.AIR);
        }
        for (int y = 1; y <= 3; y++) {
            b.put(2, y, cz - 2, Material.LADDER, "[facing=south]");
        }

        // The sterncastle: the captain's cabin under the poop deck.
        for (int x = -10; x <= -7; x++) {
            for (int dz = -3; dz <= 3; dz++) {
                int z = cz + dz;
                boolean edge = x == -10 || x == -7 || Math.abs(dz) == 3;
                for (int y = 4; y <= 5; y++) {
                    b.put(x, y, z, edge ? Material.SPRUCE_PLANKS : Material.AIR);
                }
                b.put(x, 6, z, Material.SPRUCE_PLANKS);
                if (edge) {
                    b.put(x, 7, z, Material.SPRUCE_FENCE);
                }
            }
        }
        b.put(-7, 4, cz, Material.AIR);
        b.put(-7, 5, cz, Material.AIR);
        for (int dz = -1; dz <= 1; dz++) {
            b.put(-10, 5, cz + dz, Material.GLASS_PANE, "[north=true,south=true]");
        }
        for (int x = -9; x <= -8; x++) {
            b.put(x, 5, cz - 3, Material.GLASS_PANE, "[east=true,west=true]");
            b.put(x, 5, cz + 3, Material.GLASS_PANE, "[east=true,west=true]");
        }
        b.put(-9, 4, cz - 2, Material.CARTOGRAPHY_TABLE);
        b.put(-9, 5, cz - 2, Material.LANTERN, "[hanging=true]");
        b.put(-9, 4, cz - 1, Material.SKELETON_SKULL, "[rotation=" + s.r.nextInt(16) + "]");
        b.put(-10, 8, cz - 3, Material.LANTERN);
        b.put(-10, 8, cz + 3, Material.LANTERN);
        // Stairs up to the poop deck along the south side, through a gap in its rail.
        b.put(-4, 4, cz + 2, Material.SPRUCE_STAIRS, "[facing=west]");
        b.put(-5, 4, cz + 2, Material.SPRUCE_PLANKS);
        b.put(-5, 5, cz + 2, Material.SPRUCE_STAIRS, "[facing=west]");
        b.put(-6, 4, cz + 2, Material.SPRUCE_PLANKS);
        b.put(-6, 5, cz + 2, Material.SPRUCE_PLANKS);
        b.put(-6, 6, cz + 2, Material.SPRUCE_STAIRS, "[facing=west]");
        b.put(-7, 7, cz + 2, Material.AIR);

        // The gangplank: from the sand up to a gap in the south rail, lanterns either side.
        for (int x = 3; x <= 4; x++) {
            b.put(x, 1, cz + 6, Material.SPRUCE_STAIRS, "[facing=north]");
            b.put(x, 2, cz + 5, Material.SPRUCE_STAIRS, "[facing=north]");
            b.put(x, 3, cz + 4, Material.SPRUCE_STAIRS, "[facing=north]");
            b.put(x, 1, cz + 5, Material.SPRUCE_FENCE);
            b.put(x, 1, cz + 4, Material.SPRUCE_FENCE);
            b.put(x, 2, cz + 4, Material.SPRUCE_FENCE);
            b.put(x, 4, cz + 3, Material.AIR);
        }
        b.put(2, 5, cz + 3, Material.LANTERN);
        b.put(5, 5, cz + 3, Material.LANTERN);
        b.put(9, 5, cz - 1, Material.LANTERN);
        b.put(9, 5, cz + 1, Material.LANTERN);
        s.keep(2, cz + 3, 5, cz + 8);
    }

    /** Two masts with yards and torn sails, a crow's nest, rigging and a black flag. */
    private static void rigging(Site s) {
        Blueprint b = s.b;
        int cz = KEEL;
        for (int y = 1; y <= 17; y++) {
            b.put(0, y, cz, Material.SPRUCE_LOG, "[axis=y]");
        }
        for (int dz = -4; dz <= 4; dz++) {
            if (dz != 0) {
                b.put(0, 7, cz + dz, Material.STRIPPED_SPRUCE_LOG, "[axis=z]");
                b.put(0, 13, cz + dz, Material.STRIPPED_SPRUCE_LOG, "[axis=z]");
            }
        }
        sail(s, 1, 8, 12, 3);
        for (int y = 8; y <= 12; y++) {
            b.put(0, y, cz - 4, Material.SPRUCE_FENCE);
            b.put(0, y, cz + 4, Material.SPRUCE_FENCE);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    b.put(dx, 14, cz + dz, Material.SPRUCE_PLANKS);
                    b.put(dx, 15, cz + dz, Material.SPRUCE_FENCE);
                }
            }
        }
        b.put(-1, 17, cz, Material.BLACK_WOOL);
        b.put(-2, 17, cz, Material.BLACK_WOOL);
        b.put(-1, 16, cz, Material.BLACK_WOOL);
        if (s.chance(50)) {
            b.put(-2, 16, cz, Material.BLACK_WOOL);
        }
        // The foremast.
        for (int y = 1; y <= 12; y++) {
            b.put(6, y, cz, Material.SPRUCE_LOG, "[axis=y]");
        }
        for (int dz = -3; dz <= 3; dz++) {
            if (dz != 0) {
                b.put(6, 6, cz + dz, Material.STRIPPED_SPRUCE_LOG, "[axis=z]");
                b.put(6, 11, cz + dz, Material.STRIPPED_SPRUCE_LOG, "[axis=z]");
            }
        }
        sail(s, 7, 7, 10, 2);
    }

    /** A sail at {@code x} from {@code y1} to {@code y2}, weathered, frayed at the foot and torn. */
    private static void sail(Site s, int x, int y1, int y2, int half) {
        int tear = s.r.nextInt(half * 2 + 1) - half;
        for (int dz = -half; dz <= half; dz++) {
            for (int y = y1; y <= y2; y++) {
                boolean torn = dz == tear && y > y1 && y <= y1 + 2 || y == y1 && s.chance(35) || s.chance(8);
                if (!torn) {
                    s.b.put(x, y, KEEL + dz, s.chance(15) ? Material.LIGHT_GRAY_WOOL : Material.WHITE_WOOL);
                }
            }
        }
    }

    /** Barrels (resource blocks) in the hold, on deck and in the cabin, and the wreck's clutter. */
    private static void cargo(Site s) {
        Blueprint b = s.b;
        int cz = KEEL;
        // The hold: barrels along both sides, leaving the middle and the breach clear.
        for (int x = -9; x <= 6; x++) {
            for (int dz : new int[]{-2, 2}) {
                int z = cz + dz;
                boolean breach = dz == 2 && x >= -3 && x <= 2;
                if (breach || !b.isAir(x, 1, z)) {
                    continue;
                }
                if (s.chance(45)) {
                    b.put(x, 1, z, Material.BARREL, UP);
                    if (s.chance(35)) {
                        b.put(x, 2, z, Material.BARREL, UP);
                    }
                } else if (s.chance(18)) {
                    b.put(x, 2, z, Material.COBWEB);
                } else if (s.chance(18) && b.material(x, 2, z + Integer.signum(dz)) == Material.SPRUCE_PLANKS) {
                    b.put(x, 2, z, Material.GLOW_LICHEN, dz < 0 ? "[north=true]" : "[south=true]");
                }
            }
        }
        // On deck, amidships and one high: a barrel can always be hopped onto and off, so they
        // never pen anyone in against the rail.
        List<int[]> deck = new ArrayList<>();
        for (int x = -5; x <= 5; x++) {
            for (int dz = -(beam(x) - 1); dz <= beam(x) - 1; dz++) {
                int z = cz + dz;
                Material floor = b.material(x, 3, z);
                boolean stairs = dz == 2 && x >= -6 && x <= -3;
                boolean landing = dz == 2 && x >= 2 && x <= 5;
                if ((floor == Material.OAK_PLANKS || floor == Material.STRIPPED_OAK_LOG) && b.isAir(x, 4, z)
                        && !stairs && !landing) {
                    deck.add(new int[]{x, z});
                }
            }
        }
        Collections.shuffle(deck, s.r);
        for (int[] p : deck.subList(0, Math.min(7, deck.size()))) {
            b.put(p[0], 4, p[1], Material.BARREL, UP);
        }
        // The captain's stores.
        b.put(-9, 4, cz + 2, Material.BARREL, UP);
        b.put(-9, 5, cz + 2, Material.BARREL, UP);
        b.put(-8, 4, cz + 2, Material.BARREL, UP);
        // What spilled out with the chest.
        b.put(-3, 1, 1, Material.BARREL, "[facing=south]");
        b.put(-3, 1, 0, Material.SPRUCE_SLAB, "[type=bottom]");
        b.put(-2, 1, -1, Material.SKELETON_SKULL, "[rotation=" + s.r.nextInt(16) + "]");
        b.put(2, 1, 0, Material.SPRUCE_SLAB, "[type=bottom]");
    }

    private static Material deadCoral(Site s, boolean fan) {
        return fan ? s.pick(Material.DEAD_BRAIN_CORAL_FAN, Material.DEAD_TUBE_CORAL_FAN, Material.DEAD_FIRE_CORAL_FAN,
                Material.DEAD_HORN_CORAL_FAN, Material.DEAD_BUBBLE_CORAL_FAN)
                : s.pick(Material.DEAD_BRAIN_CORAL, Material.DEAD_TUBE_CORAL, Material.DEAD_FIRE_CORAL,
                Material.DEAD_HORN_CORAL, Material.DEAD_BUBBLE_CORAL);
    }

    // ------------------------------------------------------------------ overgrown ruins

    private static Layout ruins(Random r) {
        Site s = new Site(r);
        Blueprint b = s.b;
        int radius = 15;
        int w = 7; // the courtyard walls, 15 x 15
        body(b, r, radius, 12, () -> Material.GRASS_BLOCK,
                y -> y > -3 ? Material.DIRT : s.pick(Material.STONE, Material.STONE, Material.ANDESITE));
        pad(s, Material.MOSSY_STONE_BRICKS, 12);
        s.walk(-2, -2, 2, 2); // the altar
        s.walk(-1, 3, 1, 10); // pad to altar, through the gate
        s.keep(-2, -6, 2, -3); // the pool

        // Broken walls round the courtyard, their height wandering, a tower at each corner and
        // an arched gate in the south.
        List<int[]> ring = new ArrayList<>();
        for (int x = -w; x <= w; x++) {
            ring.add(new int[]{x, -w});
        }
        for (int z = -w + 1; z <= w; z++) {
            ring.add(new int[]{w, z});
        }
        for (int x = w - 1; x >= -w; x--) {
            ring.add(new int[]{x, w});
        }
        for (int z = w - 1; z > -w; z--) {
            ring.add(new int[]{-w, z});
        }
        int h = 2 + r.nextInt(3);
        for (int[] c : ring) {
            int x = c[0];
            int z = c[1];
            h = Math.max(0, Math.min(5, h + r.nextInt(3) - 1));
            boolean corner = Math.abs(x) == w && Math.abs(z) == w;
            boolean pillar = z == w && Math.abs(x) == 2;
            if (z == w && Math.abs(x) <= 1) {
                continue;
            }
            int height = corner ? 5 + r.nextInt(2) : pillar ? 5 : h;
            for (int y = 1; y <= height; y++) {
                Material m;
                if (corner) {
                    m = s.chance(70) ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
                } else if (pillar) {
                    m = y == 3 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS;
                } else {
                    m = switch (r.nextInt(4)) {
                        case 0 -> Material.CRACKED_STONE_BRICKS;
                        case 1 -> Material.MOSSY_STONE_BRICKS;
                        default -> r.nextBoolean() ? Material.STONE_BRICKS : Material.CRACKED_STONE_BRICKS;
                    };
                }
                b.put(x, y, z, m);
            }
        }
        for (int x = -1; x <= 1; x++) {
            b.put(x, 5, w, Material.STONE_BRICKS);
            b.put(x, 0, w, Material.MOSSY_STONE_BRICKS);
        }
        b.put(0, 5, w, Material.CHISELED_STONE_BRICKS);
        b.put(-1, 4, w, Material.STONE_BRICK_STAIRS, "[facing=west,half=top]");
        b.put(1, 4, w, Material.STONE_BRICK_STAIRS, "[facing=east,half=top]");
        b.put(-2, 6, w, Material.CAMPFIRE, "[lit=true,signal_fire=false]");
        b.put(2, 6, w, Material.CAMPFIRE, "[lit=true,signal_fire=false]");

        // The courtyard floor: old paving, and rubble and moss to clear.
        for (int x = -w + 1; x <= w - 1; x++) {
            for (int z = -w + 1; z <= w - 1; z++) {
                if (s.kept(x, z)) {
                    continue;
                }
                int roll = r.nextInt(100);
                if (roll < 20) {
                    b.put(x, 0, z, Material.STONE_BRICKS);
                } else if (roll < 30) {
                    b.put(x, 0, z, Material.MOSSY_STONE_BRICKS);
                } else if (roll < 45) {
                    b.put(x, 0, z, Material.MOSSY_COBBLESTONE);
                } else if (roll < 58) {
                    b.put(x, 0, z, Material.MOSS_BLOCK);
                } else if (roll < 62) {
                    b.put(x, 1, z, Material.MOSSY_COBBLESTONE);
                }
            }
        }
        for (int z = 3; z <= 6; z++) {
            for (int x = -1; x <= 1; x++) {
                b.put(x, 0, z, s.chance(65) ? Material.STONE_BRICKS : Material.MOSSY_STONE_BRICKS);
            }
        }
        for (int z = 8; z <= 10; z++) {
            for (int x = -1; x <= 1; x++) {
                b.put(x, 0, z, x == 0 || s.chance(50) ? Material.DIRT_PATH : Material.COARSE_DIRT);
            }
        }

        // The altar: a dais of mossy stone ringed with andesite, a lantern on each corner post.
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
                b.put(x, 0, z, rim ? Material.POLISHED_ANDESITE : Material.MOSSY_STONE_BRICKS);
            }
        }
        b.put(0, 0, 0, Material.STONE_BRICKS);
        for (int x : new int[]{-2, 2}) {
            for (int z : new int[]{-2, 2}) {
                b.put(x, 1, z, Material.MOSSY_STONE_BRICK_WALL);
                b.put(x, 2, z, Material.LANTERN);
            }
        }
        // A reflecting pool behind it, lily pads on the water.
        for (int x = -2; x <= 2; x++) {
            for (int z = -6; z <= -3; z++) {
                boolean rim = Math.abs(x) == 2 || z == -6 || z == -3;
                if (rim) {
                    b.put(x, 0, z, s.chance(50) ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                } else {
                    b.put(x, 0, z, Material.WATER);
                    if (s.chance(30)) {
                        b.put(x, 1, z, Material.LILY_PAD);
                    }
                }
            }
        }
        // A colonnade, mostly fallen: whole columns with a capital, or stumps with a drum beside.
        for (int[] c : new int[][]{{-4, -1}, {4, -1}, {-4, 3}, {4, 3}, {4, -4}}) {
            int x = c[0];
            int z = c[1];
            int ch = s.chance(40) ? 5 : 1 + r.nextInt(3);
            b.put(x, 0, z, Material.STONE_BRICKS);
            for (int y = 1; y <= ch; y++) {
                b.put(x, y, z, y == ch && ch < 5 ? Material.CRACKED_STONE_BRICKS
                        : s.pick(Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS));
            }
            if (ch == 5) {
                b.put(x, 5, z, Material.CHISELED_STONE_BRICKS);
                b.put(x, 6, z, Material.STONE_BRICK_SLAB, "[type=bottom]");
            } else {
                int toward = x > 0 ? -1 : 1;
                int len = 1 + r.nextInt(2);
                for (int i = 1; i <= len && !s.kept(x + toward * i, z); i++) {
                    b.put(x + toward * i, 1, z, Material.MOSSY_STONE_BRICKS);
                }
            }
        }
        // An old oak has split the north-west corner, its roots in the paving.
        int tx = -5;
        int tz = -5;
        b.tree(tx, 1, tz, 6, Material.OAK_LOG, Material.OAK_LEAVES, 3);
        b.put(tx, 0, tz, Material.ROOTED_DIRT);
        for (int[] d : SIDES) {
            b.put(tx + d[0], 0, tz + d[1], Material.ROOTED_DIRT);
            if (s.chance(60)) {
                b.put(tx + d[0], 1, tz + d[1], Material.MANGROVE_ROOTS);
            }
        }
        b.put(tx + 1, 3, tz, Material.BEE_NEST, "[facing=east]");

        // Leaves sprawled over the wall tops, vines down their faces and the columns'.
        for (int[] c : ring) {
            int top = surface(b, c[0], c[1]);
            if (top >= 2 && s.chance(30) && b.isAir(c[0], top + 1, c[1])) {
                b.put(c[0], top + 1, c[1], s.pick(Material.OAK_LEAVES, Material.AZALEA_LEAVES,
                        Material.FLOWERING_AZALEA_LEAVES), LEAVES);
            }
            vines(s, c[0], c[1], top, 28);
        }
        for (int[] c : new int[][]{{-4, -1}, {4, -1}, {-4, 3}, {4, 3}, {4, -4}}) {
            vines(s, c[0], c[1], surface(b, c[0], c[1]), 30);
        }

        // Trees outside the walls, each with a bee nest on its trunk.
        for (int[] t : new int[][]{{-11, -4}, {10, -8}, {11, 5}, {-10, 8}}) {
            if (b.isAir(t[0], 0, t[1])) {
                continue;
            }
            boolean birch = t[0] > 0 && t[1] > 0;
            b.tree(t[0], 1, t[1], 5, birch ? Material.BIRCH_LOG : Material.OAK_LOG,
                    birch ? Material.BIRCH_LEAVES : Material.OAK_LEAVES, 2);
            b.put(t[0], 3, t[1] + (t[1] > 0 ? -1 : 1), Material.BEE_NEST, t[1] > 0 ? "[facing=north]" : "[facing=south]");
            s.keep(t[0] - 1, t[1] - 1, t[0] + 1, t[1] + 1);
        }
        // Melon and pumpkin patches gone wild, rubble, azalea bushes.
        for (int i = 0; i < 16; i++) {
            int[] p = s.spot(9, radius - 1);
            if (p != null) {
                b.put(p[0], 1, p[1], r.nextBoolean() ? Material.MELON : Material.PUMPKIN);
            }
        }
        for (int i = 0; i < 8; i++) {
            int[] p = s.spot(9, radius - 3);
            if (p != null) {
                b.put(p[0], 1, p[1], Material.MOSSY_COBBLESTONE);
            }
        }
        for (int i = 0; i < 6; i++) {
            int[] p = s.spot(9, radius - 2);
            if (p != null) {
                b.put(p[0], 1, p[1], s.pick(Material.AZALEA, Material.FLOWERING_AZALEA));
            }
        }

        // Grass and flowers on the open ground, moss carpets on the paving.
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (s.kept(x, z) || !b.isAir(x, 1, z)) {
                    continue;
                }
                Material ground = b.material(x, 0, z);
                if (ground == Material.GRASS_BLOCK && s.chance(36)) {
                    int roll = r.nextInt(100);
                    if (roll < 6 && b.isAir(x, 2, z)) {
                        b.put(x, 1, z, Material.TALL_GRASS, "[half=lower]");
                        b.put(x, 2, z, Material.TALL_GRASS, "[half=upper]");
                    } else if (roll < 58) {
                        b.put(x, 1, z, Material.SHORT_GRASS);
                    } else if (roll < 72) {
                        b.put(x, 1, z, Material.FERN);
                    } else {
                        b.put(x, 1, z, s.pick(Material.POPPY, Material.DANDELION, Material.OXEYE_DAISY,
                                Material.AZURE_BLUET, Material.CORNFLOWER));
                    }
                } else if ((ground == Material.STONE_BRICKS || ground == Material.MOSSY_STONE_BRICKS)
                        && Math.abs(x) < w && Math.abs(z) < w && s.chance(20)) {
                    b.put(x, 1, z, Material.MOSS_CARPET);
                }
            }
        }

        // Vines trailing off the island's edge, roots hanging under it.
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (b.isAir(x, 0, z) || b.material(x, 0, z) == Material.VINE) {
                    continue;
                }
                for (int i = 0; i < SIDES.length; i++) {
                    int vx = x + SIDES[i][0];
                    int vz = z + SIDES[i][1];
                    if (!b.isAir(vx, 0, vz) || !s.chance(30)) {
                        continue;
                    }
                    String face = "[" + SIDE_NAMES[(i + 2) % 4] + "=true]";
                    int len = 2 + r.nextInt(5);
                    for (int y = 0; y > -len && !b.isAir(x, y, z) && b.isAir(vx, y, vz); y--) {
                        b.put(vx, y, vz, Material.VINE, face);
                    }
                }
            }
        }
        for (int i = 0; i < 20; i++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = radius * 0.85 * Math.sqrt(r.nextDouble());
            int x = (int) Math.round(Math.cos(angle) * dist);
            int z = (int) Math.round(Math.sin(angle) * dist);
            int bottom = bottom(b, x, z);
            if (bottom != Integer.MIN_VALUE && b.material(x, bottom, z) != Material.HANGING_ROOTS) {
                b.put(x, bottom - 1, z, Material.HANGING_ROOTS);
            }
        }

        bury(b, Theme.OVERGROWN_RUINS, m -> m == Material.CRACKED_STONE_BRICKS ? Material.STONE_BRICKS : Material.DIRT);
        b.put(0, 1, 0, Material.CHISELED_STONE_BRICKS);
        return new Layout(Theme.OVERGROWN_RUINS, b, 0, 1, 0, 0.5, 1, 12.5, 180f);
    }

    /**
     * Vines on the open faces of the column at (x, z), hanging from {@code top} part of the way
     * down. Each is held by the block beside it, or the vine above.
     */
    private static void vines(Site s, int x, int z, int top, int percent) {
        if (top < 2) {
            return;
        }
        for (int i = 0; i < SIDES.length; i++) {
            int vx = x + SIDES[i][0];
            int vz = z + SIDES[i][1];
            if (s.kept(vx, vz) || !s.chance(percent)) {
                continue;
            }
            String face = "[" + SIDE_NAMES[(i + 2) % 4] + "=true]";
            int len = 1 + s.r.nextInt(top);
            for (int y = top; y > top - len && y >= 1 && !s.b.isAir(x, y, z) && s.b.isAir(vx, y, vz); y--) {
                s.b.put(vx, y, vz, Material.VINE, face);
            }
        }
    }

    // ------------------------------------------------------------------ shared

    /** A layout in progress: the blueprint, its dice, and the columns kept clear. */
    private static final class Site {
        final Blueprint b = new Blueprint();
        final Random r;
        private final Set<Long> kept = new HashSet<>();

        Site(Random r) {
            this.r = r;
        }

        private static long column(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        /** Keeps the columns x1..x2, z1..z2 free of anything placed by {@link #spot} later. */
        void keep(int x1, int z1, int x2, int z2) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    kept.add(column(x, z));
                }
            }
        }

        boolean kept(int x, int z) {
            return kept.contains(column(x, z));
        }

        /** Whether (x, y, z) is in a kept column at walking height. */
        boolean clear(int x, int y, int z) {
            return y >= 1 && y <= 3 && kept(x, z);
        }

        /** A way through: clears y 1 to 3 over x1..x2, z1..z2 and keeps it that way. */
        void walk(int x1, int z1, int x2, int z2) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    for (int y = 1; y <= 3; y++) {
                        b.put(x, y, z, Material.AIR);
                    }
                }
            }
            keep(x1, z1, x2, z2);
        }

        /**
         * A random surface spot between {@code min} and {@code max} blocks from the centre,
         * not kept and with nothing on it yet; null after a few misses.
         */
        int[] spot(int min, int max) {
            for (int attempt = 0; attempt < 16; attempt++) {
                double angle = r.nextDouble() * Math.PI * 2;
                double dist = min + r.nextDouble() * (max - min);
                int x = (int) Math.round(Math.cos(angle) * dist);
                int z = (int) Math.round(Math.sin(angle) * dist);
                if (!kept(x, z) && !b.isAir(x, 0, z) && b.isAir(x, 1, z)) {
                    return new int[]{x, z};
                }
            }
            return null;
        }

        Material pick(Material... options) {
            return options[r.nextInt(options.length)];
        }

        boolean chance(int percent) {
            return r.nextInt(100) < percent;
        }
    }

    /**
     * A floating island: {@code top} on the surface (y 0), {@code fill(y)} below, each column
     * reaching deeper toward the middle, with a ragged underside.
     */
    private static void body(Blueprint b, Random r, int radius, int depth, Supplier<Material> top,
                             IntFunction<Material> fill) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double d = Math.sqrt(x * x + z * z) / radius;
                if (d > 1) {
                    continue;
                }
                int col = (int) Math.round(depth * (1 - d * d) * (0.75 + r.nextDouble() * 0.4)) + 1;
                b.put(x, 0, z, top.get());
                for (int y = -1; y >= -col; y--) {
                    b.put(x, y, z, fill.apply(y));
                }
                // Nothing that falls at the bottom of a column (it would drop into the void).
                Material bottom = b.material(x, -col, z);
                if (bottom == Material.SAND || bottom == Material.GRAVEL || bottom == Material.RED_SAND) {
                    b.put(x, -col, z, Material.SANDSTONE);
                }
            }
        }
    }

    /** A 3 x 3 landing pad centred at (0, 0, z), the space above it cleared and kept. */
    private static void pad(Site s, Material m, int z) {
        for (int x = -1; x <= 1; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                s.b.put(x, 0, z + dz, m);
                for (int y = 1; y <= 3; y++) {
                    s.b.put(x, y, z + dz, Material.AIR);
                }
            }
        }
        s.keep(-2, z - 2, 2, z + 2);
    }

    /** Whether {@code m} is within {@code d} blocks of (x, y, z) across, at that height. */
    private static boolean near(Blueprint b, int x, int y, int z, int d, Material m) {
        for (int dx = -d; dx <= d; dx++) {
            for (int dz = -d; dz <= d; dz++) {
                if (b.material(x + dx, y, z + dz) == m) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The highest block from y 8 down to y 0 in a column, or -1 if there's none. */
    private static int surface(Blueprint b, int x, int z) {
        for (int y = 8; y >= 0; y--) {
            if (!b.isAir(x, y, z)) {
                return y;
            }
        }
        return -1;
    }

    /** The lowest block of the solid run under (x, 0, z), or Integer.MIN_VALUE if there's no ground. */
    private static int bottom(Blueprint b, int x, int z) {
        if (b.isAir(x, 0, z)) {
            return Integer.MIN_VALUE;
        }
        int y = 0;
        while (!b.isAir(x, y - 1, z)) {
            y--;
        }
        return y;
    }

    /**
     * Resource blocks nobody can reach (walled in, not under another resource block) become
     * {@code filler}. From the top down, so each block sees what's finally above it.
     */
    private static void bury(Blueprint b, Theme theme, UnaryOperator<Material> filler) {
        Set<Material> nodes = theme.nodeMaterials();
        List<Blueprint.Block> blocks = new ArrayList<>(b.blocks());
        blocks.sort((p, q) -> Integer.compare(q.y(), p.y()));
        for (Blueprint.Block blk : blocks) {
            if (nodes.contains(blk.material()) && !b.exposed(blk.x(), blk.y(), blk.z())
                    && !nodes.contains(b.material(blk.x(), blk.y() + 1, blk.z()))) {
                b.put(blk.x(), blk.y(), blk.z(), filler.apply(blk.material()));
            }
        }
    }

    /** Joins each fence to the fences beside it (placed without physics, they'd stand alone). */
    private static void connectFences(Blueprint b) {
        for (Blueprint.Block blk : List.copyOf(b.blocks())) {
            if (!blk.material().name().endsWith("_FENCE")) {
                continue;
            }
            StringBuilder state = new StringBuilder("[");
            for (int i = 0; i < SIDES.length; i++) {
                Material next = b.material(blk.x() + SIDES[i][0], blk.y(), blk.z() + SIDES[i][1]);
                state.append(i == 0 ? "" : ",").append(SIDE_NAMES[i]).append('=').append(next.name().endsWith("_FENCE"));
            }
            b.put(blk.x(), blk.y(), blk.z(), blk.material(), state.append(']').toString());
        }
    }

    /** The block-state name of a direction {dx, dy, dz}. */
    private static String facing(int[] d) {
        if (d[1] != 0) {
            return d[1] > 0 ? "up" : "down";
        }
        for (int i = 0; i < SIDES.length; i++) {
            if (SIDES[i][0] == d[0] && SIDES[i][1] == d[2]) {
                return SIDE_NAMES[i];
            }
        }
        throw new IllegalArgumentException("not a direction");
    }
}
