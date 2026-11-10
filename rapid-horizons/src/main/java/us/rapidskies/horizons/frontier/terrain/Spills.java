package us.rapidskies.horizons.frontier.terrain;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.generator.LimitedRegion;

/**
 * Water and lava a generator leaves where they'd run: with air beside or below them. Vanilla
 * never generates that (its aquifers keep a wall between liquid and air), but a Frontier
 * reshapes vanilla's world after the fact, and wherever that opens liquid to the air the first
 * update pours it out: sheets of water hanging over dry valleys, streams leaking out of chunk
 * edges into caverns, falls dropping off islands. The generators find such liquid and seal it:
 * it becomes the ground it sat in.
 *
 * <p>Plain block comparisons only (no {@code Material.isAir()}, no tags): those go through the
 * server's registries, which a worldgen loop doesn't need and unit tests don't have.
 */
final class Spills {

    /** Ground a sealed block copies from the block under it. */
    private static final Set<Material> GROUND = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.CALCITE, Material.DIRT,
            Material.COARSE_DIRT, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.SANDSTONE,
            Material.RED_SANDSTONE, Material.TERRACOTTA, Material.CLAY);
    /** Topsoil, which a sealed block copies as plain dirt (no grass in a wall). */
    private static final Set<Material> TOPSOIL = EnumSet.of(Material.GRASS_BLOCK, Material.PODZOL,
            Material.MYCELIUM, Material.MOSS_BLOCK, Material.ROOTED_DIRT);

    private Spills() {
    }

    static boolean liquid(Material m) {
        return m == Material.WATER || m == Material.LAVA;
    }

    static boolean air(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    /** Liquid with air beside or below it (as far as the region reaches): it would run. */
    static boolean wouldRun(LimitedRegion region, int x, int y, int z) {
        return liquid(region.getType(x, y, z))
                && (air(region, x - 1, y, z) || air(region, x + 1, y, z) || air(region, x, y, z - 1)
                        || air(region, x, y, z + 1) || air(region, x, y - 1, z));
    }

    /** Turns the liquid at a block to ground if it would run; whether it did. */
    static boolean seal(LimitedRegion region, int x, int y, int z) {
        if (!wouldRun(region, x, y, z)) {
            return false;
        }
        region.setType(x, y, z, ground(region, x, y, z));
        return true;
    }

    /** The natural ground under a block, or stone (deepslate below 0) where there's none. */
    static Material ground(LimitedRegion region, int x, int y, int z) {
        if (region.isInRegion(x, y - 1, z)) {
            Material below = region.getType(x, y - 1, z);
            if (GROUND.contains(below)) {
                return below;
            }
            if (TOPSOIL.contains(below)) {
                return Material.DIRT;
            }
        }
        return y < 0 ? Material.DEEPSLATE : Material.STONE;
    }

    private static boolean air(LimitedRegion region, int x, int y, int z) {
        return region.isInRegion(x, y, z) && air(region.getType(x, y, z));
    }
}
