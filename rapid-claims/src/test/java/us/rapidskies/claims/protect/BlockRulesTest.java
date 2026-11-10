package us.rapidskies.claims.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import us.rapidskies.claims.model.Role;

/**
 * The protection matrix (PLAN.md §4.1, protection coverage): which action each block, item
 * and step takes, and which trust role that needs. One row per rule, so a change in
 * classification shows up as a named failure.
 */
class BlockRulesTest {

    @ParameterizedTest(name = "right-click {0} -> {1}")
    @CsvSource({
        // Trusted: doors, buttons, levers, gates, stations
        "OAK_DOOR, USE", "IRON_DOOR, USE", "SPRUCE_TRAPDOOR, USE", "IRON_TRAPDOOR, USE", "BIRCH_FENCE_GATE, USE",
        "STONE_BUTTON, USE", "LEVER, USE", "BELL, USE", "LECTERN, USE", "CRAFTING_TABLE, USE",
        "ENCHANTING_TABLE, USE", "STONECUTTER, USE", "LOOM, USE", "GRINDSTONE, USE", "SMITHING_TABLE, USE",
        "CARTOGRAPHY_TABLE, USE", "ENDER_CHEST, USE", "CHERRY_DOOR, USE", "PALE_OAK_TRAPDOOR, USE",
        // Member: containers
        "CHEST, CONTAINER", "TRAPPED_CHEST, CONTAINER", "BARREL, CONTAINER", "FURNACE, CONTAINER",
        "BLAST_FURNACE, CONTAINER", "SMOKER, CONTAINER", "HOPPER, CONTAINER", "DISPENSER, CONTAINER",
        "DROPPER, CONTAINER", "CRAFTER, CONTAINER", "BREWING_STAND, CONTAINER", "BEACON, CONTAINER",
        "JUKEBOX, CONTAINER", "CHISELED_BOOKSHELF, CONTAINER", "DECORATED_POT, CONTAINER", "CAMPFIRE, CONTAINER",
        "SOUL_CAMPFIRE, CONTAINER", "FLOWER_POT, CONTAINER", "POTTED_POPPY, CONTAINER", "COMPOSTER, CONTAINER",
        "CAULDRON, CONTAINER", "WATER_CAULDRON, CONTAINER", "ANVIL, CONTAINER", "DAMAGED_ANVIL, CONTAINER",
        "SHULKER_BOX, CONTAINER", "LIME_SHULKER_BOX, CONTAINER", "COPPER_CHEST, CONTAINER",
        "WAXED_OXIDIZED_COPPER_CHEST, CONTAINER", "OAK_SHELF, CONTAINER",
        // Member: farms, beds, food
        "RED_BED, FARM", "RESPAWN_ANCHOR, FARM", "CAKE, FARM", "CANDLE_CAKE, FARM", "BLUE_CANDLE_CAKE, FARM",
        "SWEET_BERRY_BUSH, FARM", "CAVE_VINES, FARM", "CAVE_VINES_PLANT, FARM", "BEEHIVE, FARM", "BEE_NEST, FARM",
        "CANDLE, FARM", "PINK_CANDLE, FARM", "VAULT, FARM",
        // Builder: redstone settings and other changes
        "REPEATER, BUILD", "COMPARATOR, BUILD", "DAYLIGHT_DETECTOR, BUILD", "NOTE_BLOCK, BUILD",
        "DRAGON_EGG, BUILD", "REDSTONE_WIRE, BUILD", "SPAWNER, BUILD", "COPPER_GOLEM_STATUE, BUILD",
        "WEATHERED_COPPER_GOLEM_STATUE, BUILD"})
    void rightClickingBlocks(String material, Action expected) {
        assertEquals(Optional.of(expected), BlockRules.rightClick(material));
    }

    @ParameterizedTest(name = "right-click {0} is free")
    @CsvSource({"STONE", "DIRT", "OAK_LOG", "GRASS_BLOCK", "OAK_SIGN", "OAK_WALL_SIGN", "OAK_HANGING_SIGN",
        "BOOKSHELF", "GLASS", "WHITE_WOOL"})
    void harmlessBlocksAndShopSigns(String material) {
        // Signs stay clickable: chest-shop signs are used by right-clicking them.
        assertEquals(Optional.empty(), BlockRules.rightClick(material));
    }

    @ParameterizedTest(name = "{0} on a block -> {1}")
    @CsvSource({
        "DIAMOND_AXE, BUILD", "WOODEN_SHOVEL, BUILD", "NETHERITE_HOE, BUILD", "FLINT_AND_STEEL, BUILD",
        "FIRE_CHARGE, BUILD", "ZOMBIE_SPAWN_EGG, BUILD", "ARMOR_STAND, BUILD", "END_CRYSTAL, BUILD",
        "ITEM_FRAME, BUILD", "GLOW_ITEM_FRAME, BUILD", "PAINTING, BUILD", "LEAD, BUILD", "RED_DYE, BUILD",
        "GLOW_INK_SAC, BUILD", "HONEYCOMB, BUILD", "BRUSH, BUILD", "WRITABLE_BOOK, BUILD",
        "BONE_MEAL, FARM", "SHEARS, FARM", "GLASS_BOTTLE, FARM"})
    void itemsUsedOnBlocks(String item, Action expected) {
        assertEquals(Optional.of(expected), BlockRules.itemOnBlock(item));
    }

    @ParameterizedTest(name = "{0} on a block is free")
    @CsvSource({"DIAMOND_SWORD", "BOW", "COOKED_BEEF", "STONE", "TORCH", "WATER_BUCKET", "COMPASS"})
    void itemsThatDontChangeBlocks(String item) {
        // Buckets and placing blocks have their own events.
        assertEquals(Optional.empty(), BlockRules.itemOnBlock(item));
    }

    @ParameterizedTest(name = "stepping on {0} -> {1}")
    @CsvSource({"STONE_PRESSURE_PLATE, USE", "LIGHT_WEIGHTED_PRESSURE_PLATE, USE", "TRIPWIRE, USE",
        "FARMLAND, FARM", "TURTLE_EGG, BUILD", "SNIFFER_EGG, BUILD"})
    void stepping(String material, Action expected) {
        assertEquals(Optional.of(expected), BlockRules.step(material));
    }

    @Test
    void steppingOnOrdinaryBlocksIsFree() {
        assertEquals(Optional.empty(), BlockRules.step("STONE"));
        assertEquals(Optional.empty(), BlockRules.step("BIG_DRIPLEAF"));
    }

    @ParameterizedTest(name = "break/place {0} -> {1}")
    @CsvSource({"WHEAT, FARM", "CARROTS, FARM", "POTATOES, FARM", "BEETROOTS, FARM", "NETHER_WART, FARM",
        "MELON, FARM", "PUMPKIN, FARM", "SUGAR_CANE, FARM", "COCOA, FARM", "PUMPKIN_STEM, FARM",
        "STONE, BUILD", "CHEST, BUILD", "OAK_LOG, BUILD", "FARMLAND, BUILD", "TNT, BUILD", "HAY_BLOCK, BUILD"})
    void breakingAndPlacing(String material, Action expected) {
        assertEquals(expected, BlockRules.breakOrPlace(material));
    }

    @Test
    void projectilesChangingBlocks() {
        for (String m : new String[] {"DECORATED_POT", "CHORUS_FLOWER", "POINTED_DRIPSTONE", "TNT", "BELL", "TARGET",
            "CANDLE", "RED_CANDLE", "CANDLE_CAKE", "CAMPFIRE", "SOUL_CAMPFIRE"}) {
            assertTrue(BlockRules.changedByProjectile(m), m);
        }
        assertFalse(BlockRules.changedByProjectile("STONE"));
        assertFalse(BlockRules.changedByProjectile("OAK_BUTTON"), "buttons are EntityInteractEvent (USE)");
    }

    /** PLAN.md §4.1's trust table: what each role may do. */
    @ParameterizedTest(name = "{0} may {1}: {2}")
    @CsvSource({
        "VISITOR, USE, false", "VISITOR, CONTAINER, false", "VISITOR, BUILD, false",
        "TRUSTED, USE, true", "TRUSTED, CONTAINER, false", "TRUSTED, ANIMALS, false",
        "MEMBER, CONTAINER, true", "MEMBER, FARM, true", "MEMBER, ANIMALS, true", "MEMBER, BUILD, false",
        "BUILDER, BUILD, true", "BUILDER, MANAGE, false",
        "CO_OWNER, MANAGE, true", "OWNER, MANAGE, true"})
    void trustTable(Role role, Action action, boolean allowed) {
        assertEquals(allowed, action.allowedFor(role));
    }

    /** Every name the rules match on is a real material in Paper 26.3, so a rename can't silently open a hole. */
    @Test
    void namedMaterialsExist() {
        for (String m : new String[] {"LEVER", "BELL", "LECTERN", "CHEST", "BARREL", "CRAFTER", "CHISELED_BOOKSHELF",
            "DECORATED_POT", "COMPOSTER", "RESPAWN_ANCHOR", "VAULT", "REPEATER", "COMPARATOR", "DAYLIGHT_DETECTOR",
            "NOTE_BLOCK", "TRIAL_SPAWNER", "TRIPWIRE", "FARMLAND", "SNIFFER_EGG", "TORCHFLOWER_CROP", "PITCHER_CROP",
            "BRUSH", "HONEYCOMB", "GLOW_INK_SAC", "CAVE_VINES_PLANT", "COPPER_CHEST", "OAK_SHELF",
            "COPPER_GOLEM_STATUE"}) {
            assertTrue(Material.getMaterial(m) != null, m + " isn't a Material");
        }
    }
}
