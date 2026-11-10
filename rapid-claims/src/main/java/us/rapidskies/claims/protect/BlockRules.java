package us.rapidskies.claims.protect;

import java.util.Optional;
import java.util.Set;

/**
 * Which {@link Action} using a block or an item takes (PLAN.md §4.1, protection coverage).
 *
 * <p>Works on material names ({@code Material#name()}) rather than Bukkit tags, so it runs
 * in plain unit tests and new variants are covered by their naming pattern: every
 * {@code *_DOOR}, {@code *_SHELF} or {@code *COPPER_CHEST} added in a later version needs
 * no change here. Each rule is a row in {@code BlockRulesTest}.
 */
public final class BlockRules {

    private BlockRules() {
    }

    private static final Set<String> USE_BLOCKS = Set.of(
            "LEVER", "BELL", "LECTERN", "ENCHANTING_TABLE", "CRAFTING_TABLE", "STONECUTTER", "LOOM",
            "CARTOGRAPHY_TABLE", "GRINDSTONE", "SMITHING_TABLE", "ENDER_CHEST");

    private static final Set<String> CONTAINER_BLOCKS = Set.of(
            "CHEST", "TRAPPED_CHEST", "BARREL", "FURNACE", "BLAST_FURNACE", "SMOKER", "HOPPER", "DISPENSER",
            "DROPPER", "CRAFTER", "BREWING_STAND", "BEACON", "JUKEBOX", "CHISELED_BOOKSHELF", "DECORATED_POT",
            "CAMPFIRE", "SOUL_CAMPFIRE", "FLOWER_POT", "COMPOSTER", "CAULDRON", "WATER_CAULDRON",
            "LAVA_CAULDRON", "POWDER_SNOW_CAULDRON", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL");

    private static final Set<String> FARM_BLOCKS = Set.of(
            "SWEET_BERRY_BUSH", "CAVE_VINES", "CAVE_VINES_PLANT", "BEEHIVE", "BEE_NEST", "RESPAWN_ANCHOR", "CAKE",
            "VAULT");

    private static final Set<String> BUILD_BLOCKS = Set.of(
            "REPEATER", "COMPARATOR", "DAYLIGHT_DETECTOR", "NOTE_BLOCK", "DRAGON_EGG", "REDSTONE_WIRE",
            "SPAWNER", "TRIAL_SPAWNER");

    /** Crop blocks a Member may break and plant: harvesting a farm isn't building. */
    private static final Set<String> CROPS = Set.of(
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA", "MELON", "PUMPKIN",
            "SUGAR_CANE", "CACTUS", "BAMBOO", "KELP", "KELP_PLANT", "SWEET_BERRY_BUSH", "CAVE_VINES",
            "CAVE_VINES_PLANT", "MELON_STEM", "PUMPKIN_STEM", "TORCHFLOWER_CROP", "PITCHER_CROP");

    /** Items that change a block when used on it. */
    private static final Set<String> BUILD_ITEMS = Set.of(
            "FLINT_AND_STEEL", "FIRE_CHARGE", "ARMOR_STAND", "END_CRYSTAL", "ITEM_FRAME", "GLOW_ITEM_FRAME",
            "PAINTING", "LEAD", "INK_SAC", "GLOW_INK_SAC", "HONEYCOMB", "BRUSH", "WRITABLE_BOOK", "WRITTEN_BOOK");

    private static final Set<String> FARM_ITEMS = Set.of("BONE_MEAL", "SHEARS", "GLASS_BOTTLE");

    /**
     * The action right-clicking a block of {@code material} takes, or empty if right-clicking
     * it does nothing worth protecting (stone, dirt, logs...). The item in hand is judged
     * separately by {@link #itemOnBlock}.
     */
    public static Optional<Action> rightClick(String material) {
        if (USE_BLOCKS.contains(material) || material.endsWith("_DOOR") || material.endsWith("_TRAPDOOR")
                || material.endsWith("_FENCE_GATE") || material.endsWith("_BUTTON")) {
            return Optional.of(Action.USE);
        }
        if (CONTAINER_BLOCKS.contains(material) || material.endsWith("SHULKER_BOX")
                || material.endsWith("COPPER_CHEST") || material.endsWith("_SHELF") || material.startsWith("POTTED_")) {
            return Optional.of(Action.CONTAINER);
        }
        if (FARM_BLOCKS.contains(material) || material.endsWith("_BED") || material.endsWith("CANDLE_CAKE")
                || material.equals("CANDLE") || material.endsWith("_CANDLE")) {
            return Optional.of(Action.FARM);
        }
        // Signs aren't here: right-clicking one is how chest-shop signs are used, and editing
        // one is its own event (PlayerOpenSignEvent, SignChangeEvent).
        if (BUILD_BLOCKS.contains(material) || material.endsWith("COPPER_GOLEM_STATUE")) {
            return Optional.of(Action.BUILD);
        }
        return Optional.empty();
    }

    /**
     * The action using {@code item} on a block takes (axes strip logs, hoes till, dye colors
     * signs, bone meal grows crops), or empty for items that don't change blocks. Food is
     * never stopped: the caller skips edible items.
     */
    public static Optional<Action> itemOnBlock(String item) {
        if (FARM_ITEMS.contains(item)) {
            return Optional.of(Action.FARM);
        }
        if (BUILD_ITEMS.contains(item) || item.endsWith("_AXE") || item.endsWith("_SHOVEL") || item.endsWith("_HOE")
                || item.endsWith("_SPAWN_EGG") || item.endsWith("_DYE")) {
            return Optional.of(Action.BUILD);
        }
        return Optional.empty();
    }

    /**
     * The action stepping on {@code material} takes: pressure plates and tripwire are
     * USE, trampling farmland FARM, crushing turtle or sniffer eggs BUILD.
     */
    public static Optional<Action> step(String material) {
        if (material.endsWith("_PRESSURE_PLATE") || material.equals("TRIPWIRE")) {
            return Optional.of(Action.USE);
        }
        if (material.equals("FARMLAND")) {
            return Optional.of(Action.FARM);
        }
        if (material.equals("TURTLE_EGG") || material.equals("SNIFFER_EGG")) {
            return Optional.of(Action.BUILD);
        }
        return Optional.empty();
    }

    /** Breaking or placing {@code material}: crops are FARM, everything else BUILD. */
    public static Action breakOrPlace(String material) {
        return CROPS.contains(material) ? Action.FARM : Action.BUILD;
    }

    /**
     * Blocks a player's projectile changes on impact (smashing pots, breaking chorus flowers
     * and dripstone, lighting TNT and candles, ringing bells).
     */
    public static boolean changedByProjectile(String material) {
        return material.equals("DECORATED_POT") || material.equals("CHORUS_FLOWER")
                || material.equals("POINTED_DRIPSTONE") || material.equals("TNT") || material.equals("BELL")
                || material.equals("TARGET") || material.equals("CANDLE") || material.endsWith("_CANDLE")
                || material.endsWith("CANDLE_CAKE") || material.endsWith("CAMPFIRE");
    }
}
