package us.rapidskies.skyrealms.expedition;

import java.util.List;
import java.util.Random;
import org.bukkit.Material;
import us.rapidskies.skyrealms.island.Blueprint;

/**
 * The three Expedition islands (PLAN.md §5.1): crystal caverns, a sunken ship, overgrown ruins.
 * Built in code from a seed, so every Expedition of a theme is a little different, and the same
 * seed always gives the same island (a crash leftover is removed by building its blueprint again).
 *
 * <p>The origin is the island's centre at surface level (y 0 is the top layer). Resource blocks
 * (the theme's {@link Theme#nodeMaterials()}) are always reachable: on the surface, or under
 * another resource block. The heart sits at the centre, the landing pad to the south.
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
        Blueprint b = new Blueprint();
        int radius = 13;
        body(b, r, radius, 14, () -> pick(r, Material.STONE, Material.CALCITE, Material.TUFF),
                y -> y > -5 ? pick(r, Material.STONE, Material.TUFF) : pick(r, Material.DEEPSLATE, Material.STONE));
        // Crystal spires, away from the heart and the pad.
        for (int i = 0; i < 10; i++) {
            int[] p = spot(r, b, 4, radius - 2);
            if (p == null) {
                continue;
            }
            int h = 2 + r.nextInt(4);
            for (int y = 1; y <= h; y++) {
                b.put(p[0], y, p[1], Material.AMETHYST_BLOCK);
            }
            b.put(p[0], h + 1, p[1], Material.AMETHYST_CLUSTER, "[facing=up]");
        }
        // Ore outcrops: one on the surface, one or two below it.
        List<Material> ores = List.of(Material.IRON_ORE, Material.IRON_ORE, Material.IRON_ORE, Material.GOLD_ORE,
                Material.GOLD_ORE, Material.LAPIS_ORE, Material.LAPIS_ORE, Material.REDSTONE_ORE, Material.REDSTONE_ORE,
                Material.EMERALD_ORE, Material.DIAMOND_ORE);
        for (int i = 0; i < 26; i++) {
            int[] p = spot(r, b, 3, radius - 1);
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
        // The heart: budding amethyst on a calcite plinth, ringed with basalt.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                b.put(x, 0, z, Material.SMOOTH_BASALT);
            }
        }
        b.put(0, 0, 0, Material.CALCITE);
        b.put(0, 1, 0, Material.BUDDING_AMETHYST);
        pad(b, Material.SMOOTH_STONE, 10);
        return new Layout(Theme.CRYSTAL_CAVERNS, b, 0, 1, 0, 0.5, 1, 10.5, 180f);
    }

    // ------------------------------------------------------------------ sunken ship

    private static Layout ship(Random r) {
        Blueprint b = new Blueprint();
        int radius = 13;
        body(b, r, radius, 8, () -> Material.SAND,
                y -> y > -2 ? Material.SAND : pick(r, Material.SANDSTONE, Material.SANDSTONE, Material.SMOOTH_SANDSTONE));
        // The hull lies east-west, beached in the sand: floor at y 0, walls up to 2, deck at 3.
        for (int x = -8; x <= 8; x++) {
            int half = x >= 6 ? 8 - x : 2; // the bow narrows to a point at x 8
            for (int z = -half; z <= half; z++) {
                b.put(x, 0, z, Material.SPRUCE_PLANKS);
                boolean side = Math.abs(z) == half || x == -8;
                for (int y = 1; y <= 2; y++) {
                    b.put(x, y, z, side ? Material.SPRUCE_PLANKS : Material.AIR);
                }
                // Deck, with two open hatches down into the hold.
                boolean hatch = (x == -3 || x == -2 || x == 3 || x == 4) && z == 0;
                if (!hatch) {
                    b.put(x, 3, z, Material.OAK_PLANKS);
                }
                if (Math.abs(z) == half && half > 0 && x > -8 && x % 2 == 0) {
                    b.put(x, 4, z, Material.OAK_FENCE);
                }
            }
        }
        // Mast and a torn sail.
        for (int y = 4; y <= 12; y++) {
            b.put(0, y, 0, Material.SPRUCE_LOG, "[axis=y]");
        }
        for (int z = -3; z <= 3; z++) {
            for (int y = 6; y <= 11; y++) {
                if (r.nextInt(7) != 0) {
                    b.put(1, y, z, Material.WHITE_WOOL);
                }
            }
        }
        // Cargo: barrels along the hold walls and a few on deck.
        for (int x = -6; x <= 5; x += 2) {
            b.put(x, 1, -1, Material.BARREL, "[facing=up]");
            b.put(x + 1, 1, 1, Material.BARREL, "[facing=up]");
        }
        for (int[] p : new int[][]{{-4, 4, 1}, {5, 4, -1}, {2, 4, 1}}) {
            b.put(p[0], p[1], p[2], Material.BARREL, "[facing=up]");
        }
        // The reef around it.
        List<Material> reef = List.of(Material.PRISMARINE, Material.PRISMARINE, Material.PRISMARINE,
                Material.SEA_LANTERN, Material.DRIED_KELP_BLOCK, Material.DRIED_KELP_BLOCK, Material.WET_SPONGE);
        for (int i = 0; i < 26; i++) {
            int[] p = spot(r, b, 4, radius - 1);
            if (p == null || Math.abs(p[1]) <= 3 && Math.abs(p[0]) <= 9) {
                continue; // not inside or against the hull
            }
            Material m = reef.get(r.nextInt(reef.size()));
            b.put(p[0], 1, p[1], m);
            b.put(p[0], 0, p[1], reef.get(r.nextInt(reef.size())));
        }
        // The heart: the captain's chest on the stern deck.
        b.put(-6, 4, 0, Material.CHEST, "[facing=east]");
        pad(b, Material.SMOOTH_SANDSTONE, 10);
        return new Layout(Theme.SUNKEN_SHIP, b, -6, 4, 0, 0.5, 1, 10.5, 180f);
    }

    // ------------------------------------------------------------------ overgrown ruins

    private static Layout ruins(Random r) {
        Blueprint b = new Blueprint();
        int radius = 14;
        body(b, r, radius, 12, () -> Material.GRASS_BLOCK,
                y -> y > -3 ? Material.DIRT : pick(r, Material.STONE, Material.STONE, Material.ANDESITE));
        // Broken courtyard walls, 13 x 13, open to the south.
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                boolean edge = Math.abs(x) == 6 || Math.abs(z) == 6;
                if (!edge || z == 6 && Math.abs(x) <= 1) {
                    continue;
                }
                boolean corner = Math.abs(x) == 6 && Math.abs(z) == 6;
                int h = corner ? 5 : r.nextInt(5);
                for (int y = 1; y <= h; y++) {
                    Material m = corner ? Material.MOSSY_STONE_BRICKS : switch (r.nextInt(4)) {
                        case 0 -> Material.CRACKED_STONE_BRICKS;
                        case 1 -> Material.MOSSY_STONE_BRICKS;
                        default -> r.nextBoolean() ? Material.STONE_BRICKS : Material.CRACKED_STONE_BRICKS;
                    };
                    b.put(x, y, z, m);
                }
            }
        }
        // The courtyard floor: rubble and moss to clear.
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                    continue;
                }
                int roll = r.nextInt(100);
                if (roll < 14) {
                    b.put(x, 0, z, Material.MOSS_BLOCK);
                } else if (roll < 26) {
                    b.put(x, 0, z, Material.MOSSY_COBBLESTONE);
                } else if (roll < 30) {
                    b.put(x, 1, z, Material.MOSSY_COBBLESTONE);
                }
            }
        }
        // Trees outside the walls, each with a bee nest on its trunk.
        for (int[] t : new int[][]{{-10, -4}, {9, -7}, {10, 5}, {-9, 7}}) {
            if (b.isAir(t[0], 0, t[1])) {
                continue;
            }
            b.tree(t[0], 1, t[1], 5, Material.OAK_LOG, Material.OAK_LEAVES, 2);
            b.put(t[0], 3, t[1] + (t[1] > 0 ? -1 : 1), Material.BEE_NEST, t[1] > 0 ? "[facing=north]" : "[facing=south]");
        }
        // Melon and pumpkin patches gone wild.
        for (int i = 0; i < 16; i++) {
            int[] p = spot(r, b, 8, radius - 1);
            if (p != null) {
                b.put(p[0], 1, p[1], r.nextBoolean() ? Material.MELON : Material.PUMPKIN);
            }
        }
        // The altar: chiseled stone bricks on a stone-brick dais.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                b.put(x, 0, z, Material.STONE_BRICKS);
            }
        }
        b.put(0, 1, 0, Material.CHISELED_STONE_BRICKS);
        // A worn path from the pad to the gap in the south wall.
        for (int z = 7; z <= 12; z++) {
            b.put(0, 0, z, Material.DIRT_PATH);
        }
        pad(b, Material.MOSSY_STONE_BRICKS, 12);
        return new Layout(Theme.OVERGROWN_RUINS, b, 0, 1, 0, 0.5, 1, 12.5, 180f);
    }

    // ------------------------------------------------------------------ shared

    /**
     * A floating island: {@code top} on the surface (y 0), {@code fill(y)} below, each column
     * reaching deeper toward the middle, with a ragged underside.
     */
    private static void body(Blueprint b, Random r, int radius, int depth, java.util.function.Supplier<Material> top,
                             java.util.function.IntFunction<Material> fill) {
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

    /** A 3 x 3 landing pad centred at (0, 0, z), with the space above it cleared. */
    private static void pad(Blueprint b, Material m, int z) {
        for (int x = -1; x <= 1; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                b.put(x, 0, z + dz, m);
                for (int y = 1; y <= 3; y++) {
                    b.put(x, y, z + dz, Material.AIR);
                }
            }
        }
    }

    /**
     * A random surface spot between {@code min} and {@code max} blocks from the centre, clear of
     * the pad (south) and with nothing on it yet; null after a few misses.
     */
    private static int[] spot(Random r, Blueprint b, int min, int max) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = min + r.nextDouble() * (max - min);
            int x = (int) Math.round(Math.cos(angle) * dist);
            int z = (int) Math.round(Math.sin(angle) * dist);
            boolean nearPad = Math.abs(x) <= 2 && z >= 8;
            if (!nearPad && !b.isAir(x, 0, z) && b.isAir(x, 1, z)) {
                return new int[]{x, z};
            }
        }
        return null;
    }

    private static Material pick(Random r, Material... options) {
        return options[r.nextInt(options.length)];
    }
}
