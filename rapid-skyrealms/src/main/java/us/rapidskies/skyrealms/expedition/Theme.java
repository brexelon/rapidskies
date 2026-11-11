package us.rapidskies.skyrealms.expedition;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;

/**
 * An Expedition island's theme (PLAN.md §5.1: crystal caverns, sunken ship, overgrown ruins).
 * Each has its own layout ({@link ExpeditionLayouts}); its resource blocks are
 * {@link #nodeMaterials()}, whose loot and points come from the config.
 */
public enum Theme {
    CRYSTAL_CAVERNS(Material.BUDDING_AMETHYST, 0xC58CF2, Set.of(Material.IRON_ORE, Material.GOLD_ORE, Material.LAPIS_ORE,
            Material.REDSTONE_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE, Material.AMETHYST_BLOCK)),
    SUNKEN_SHIP(Material.CHEST, 0xFFC94D, Set.of(Material.BARREL, Material.PRISMARINE, Material.SEA_LANTERN,
            Material.WET_SPONGE, Material.DRIED_KELP_BLOCK)),
    OVERGROWN_RUINS(Material.CHISELED_STONE_BRICKS, 0x8BD45A, Set.of(Material.MOSSY_COBBLESTONE,
            Material.CRACKED_STONE_BRICKS, Material.MELON, Material.PUMPKIN, Material.BEE_NEST, Material.MOSS_BLOCK));

    private final Material heart;
    private final int glow;
    private final Set<Material> nodes;

    Theme(Material heart, int glow, Set<Material> nodes) {
        this.heart = heart;
        this.glow = glow;
        this.nodes = nodes;
    }

    /** {@code crystal-caverns}: the config and lang id. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The block of the heart, which takes hits instead of breaking. */
    public Material heart() {
        return heart;
    }

    /** The colour (0xRRGGBB) the heart's outline glows in. */
    public int glow() {
        return glow;
    }

    /** The resource blocks the layout places. */
    public Set<Material> nodeMaterials() {
        return nodes;
    }

    public static Optional<Theme> byId(String id) {
        String k = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values()).filter(t -> t.id().equals(k)).findFirst();
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(Theme::id).toList();
    }
}
