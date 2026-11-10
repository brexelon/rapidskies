package us.rapidskies.market.model;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The auction house's browse categories (PLAN.md §4.2). An item's category is decided once,
 * when it's listed, from its material key alone, so the rules here are plain string tests
 * that run without a server.
 */
public enum Category {
    BLOCKS("blocks"),
    TOOLS("tools"),
    FOOD("food"),
    FARMING("farming"),
    REDSTONE("redstone"),
    MISC("misc");

    private static final Set<String> TOOL_SUFFIXES = Set.of("_sword", "_pickaxe", "_axe", "_shovel", "_hoe",
            "_helmet", "_chestplate", "_leggings", "_boots", "_horse_armor", "_spear");
    private static final Set<String> TOOLS_EXACT = Set.of("bow", "crossbow", "trident", "shield", "mace",
            "fishing_rod", "shears", "flint_and_steel", "elytra", "brush", "spyglass", "arrow", "spectral_arrow",
            "tipped_arrow", "wolf_armor", "carrot_on_a_stick", "warped_fungus_on_a_stick", "totem_of_undying",
            "compass", "recovery_compass", "clock", "lead", "name_tag", "saddle", "wind_charge");
    private static final Set<String> FARMING_SUFFIXES = Set.of("_seeds", "_sapling", "_propagule");
    private static final Set<String> FARMING_EXACT = Set.of("bone_meal", "wheat", "sugar_cane", "cactus",
            "bamboo", "pumpkin", "melon", "nether_wart", "cocoa_beans", "hay_block", "kelp", "egg", "feather",
            "leather", "white_wool", "string", "honeycomb", "ink_sac", "glow_ink_sac", "turtle_scute",
            "armadillo_scute", "rabbit_hide", "torchflower", "pitcher_pod", "sweet_berries", "glow_berries",
            "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "lily_pad", "vine",
            "moss_block", "farmland", "composter", "beehive", "bee_nest");
    private static final Set<String> REDSTONE_EXACT = Set.of("redstone", "redstone_block", "redstone_torch",
            "repeater", "comparator", "piston", "sticky_piston", "observer", "hopper", "dropper", "dispenser",
            "lever", "tripwire_hook", "daylight_detector", "target", "crafter", "sculk_sensor",
            "calibrated_sculk_sensor", "tnt", "note_block", "lightning_rod", "redstone_lamp", "slime_block",
            "honey_block", "trapped_chest", "jukebox", "minecart", "hopper_minecart", "chest_minecart",
            "tnt_minecart", "furnace_minecart");
    private static final Set<String> REDSTONE_SUFFIXES = Set.of("_button", "_pressure_plate", "rail");

    private final String id;

    Category(String id) {
        this.id = id;
    }

    /** Stored in {@code market_listings.category}, and the lang key suffix. */
    public String id() {
        return id;
    }

    public static Optional<Category> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.toLowerCase(Locale.ROOT);
        for (Category c : values()) {
            if (c.id.equals(wanted) || c.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * The category of an item. {@code key} is the material key without a namespace
     * ({@code diamond_pickaxe}); {@code block} and {@code edible} come from the material.
     * More specific groups win: a hay bale is farming, not blocks; a carrot is food.
     */
    public static Category classify(String key, boolean block, boolean edible) {
        String k = key.toLowerCase(Locale.ROOT);
        int colon = k.indexOf(':');
        if (colon >= 0) {
            k = k.substring(colon + 1);
        }
        if (TOOLS_EXACT.contains(k) || endsWithAny(k, TOOL_SUFFIXES)) {
            return TOOLS;
        }
        if (REDSTONE_EXACT.contains(k) || endsWithAny(k, REDSTONE_SUFFIXES)) {
            return REDSTONE;
        }
        if (FARMING_EXACT.contains(k) || endsWithAny(k, FARMING_SUFFIXES)) {
            return FARMING;
        }
        if (edible) {
            return FOOD;
        }
        if (block) {
            return BLOCKS;
        }
        return MISC;
    }

    private static boolean endsWithAny(String k, Set<String> suffixes) {
        for (String s : suffixes) {
            if (k.endsWith(s)) {
                return true;
            }
        }
        return false;
    }
}
