package us.rapidskies.essentials.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafeBlocksTest {

    @Test
    void unsafeGround() {
        for (String bad : new String[] {"LAVA", "WATER", "MAGMA_BLOCK", "CACTUS", "OAK_LEAVES", "AZALEA_LEAVES",
                "BEDROCK", "POWDER_SNOW", "SEAGRASS", "CAMPFIRE"}) {
            assertFalse(SafeBlocks.safeGround(bad), bad);
        }
        for (String good : new String[] {"GRASS_BLOCK", "STONE", "SAND", "OAK_PLANKS", "SNOW_BLOCK", "PODZOL", "ICE"}) {
            assertTrue(SafeBlocks.safeGround(good), good);
        }
    }

    @Test
    void unsafeSpace() {
        for (String bad : new String[] {"LAVA", "WATER", "FIRE", "COBWEB", "NETHER_PORTAL", "POWDER_SNOW",
                "SWEET_BERRY_BUSH"}) {
            assertFalse(SafeBlocks.safeSpace(bad), bad);
        }
        for (String good : new String[] {"AIR", "SHORT_GRASS", "TALL_GRASS", "SNOW", "TORCH", "DANDELION"}) {
            assertTrue(SafeBlocks.safeSpace(good), good);
        }
    }
}
