package us.rapidskies.horizons.trade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link PlacedBlocks}' array helpers and packing, and {@link FishCatch}. */
class TradeHelpersTest {

    @Test
    void packedPositionsAreUniqueWithinAChunk() {
        Set<Integer> seen = new HashSet<>();
        for (int y = -64; y < 320; y += 7) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    seen.add(PlacedBlocks.pack(x, y, z));
                }
            }
        }
        assertEquals(55 * 256, seen.size());
        assertEquals(PlacedBlocks.pack(3, 70, 5), PlacedBlocks.pack(19, 70, -11), "same in-chunk position");
    }

    @Test
    void sortedArrayAddAndRemove() {
        int[] a = PlacedBlocks.withAdded(new int[0], 5);
        a = PlacedBlocks.withAdded(a, 1);
        a = PlacedBlocks.withAdded(a, 9);
        assertArrayEquals(new int[] {1, 5, 9}, a);
        assertSame(a, PlacedBlocks.withAdded(a, 5), "already marked: unchanged");
        assertArrayEquals(new int[] {1, 9}, PlacedBlocks.withRemoved(a, 5));
        assertSame(a, PlacedBlocks.withRemoved(a, 4), "not marked: unchanged");
        assertArrayEquals(new int[0], PlacedBlocks.withRemoved(new int[] {7}, 7));
    }

    @Test
    void fishCatchGroups() {
        assertEquals("fish", FishCatch.classify("COD", false));
        assertEquals("fish", FishCatch.classify("PUFFERFISH", false));
        assertEquals("treasure", FishCatch.classify("NAME_TAG", false));
        assertEquals("treasure", FishCatch.classify("ENCHANTED_BOOK", true));
        assertEquals("treasure", FishCatch.classify("BOW", true));
        assertEquals("junk", FishCatch.classify("BOW", false), "a plain bow is junk");
        assertEquals("junk", FishCatch.classify("LEATHER_BOOTS", false));
    }
}
