package us.rapidskies.essentials.teleport;

import java.util.Set;

/**
 * Which blocks make a spot unsafe to land on or in, by Material name, free of Bukkit so
 * they're unit-tested. Shared by {@link SafeSpot} and rapid-horizons' {@code /rtp}.
 */
public final class SafeBlocks {

    /** Ground that would drown, burn, hurt or trap a player, or isn't land at all. */
    private static final Set<String> BAD_GROUND = Set.of(
            "WATER", "LAVA", "POWDER_SNOW", "MAGMA_BLOCK", "CACTUS", "FIRE", "SOUL_FIRE", "CAMPFIRE",
            "SOUL_CAMPFIRE", "SWEET_BERRY_BUSH", "POINTED_DRIPSTONE", "BUBBLE_COLUMN", "SEAGRASS", "TALL_SEAGRASS",
            "KELP", "KELP_PLANT", "COBWEB", "BEDROCK", "WITHER_ROSE");

    /** Blocks a player shouldn't appear inside, even though they can walk through them. */
    private static final Set<String> BAD_SPACE = Set.of(
            "WATER", "LAVA", "POWDER_SNOW", "FIRE", "SOUL_FIRE", "SWEET_BERRY_BUSH", "COBWEB", "WITHER_ROSE",
            "BUBBLE_COLUMN", "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT", "NETHER_PORTAL", "END_PORTAL");

    private SafeBlocks() {
    }

    /** Whether a player may stand on {@code material}. Leaves are out: canopy tops strand players. */
    public static boolean safeGround(String material) {
        return !BAD_GROUND.contains(material) && !material.endsWith("LEAVES");
    }

    /** Whether a passable block of {@code material} is fine to stand in (feet or head). */
    public static boolean safeSpace(String material) {
        return !BAD_SPACE.contains(material);
    }
}
