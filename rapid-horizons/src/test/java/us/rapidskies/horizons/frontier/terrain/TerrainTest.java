package us.rapidskies.horizons.frontier.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import us.rapidskies.horizons.frontier.Modifier;

/** The Frontier generators' shapes: Shattered Skies' islands and Deep Dark's caverns, from the seed alone. */
class TerrainTest {

    private static final long SEED = 8_675_309L;

    // ------------------------------------------------------------------ Shattered Skies

    /** Share of sampled columns with at least one island. */
    private static double coverage(IslandField f) {
        int[] spans = new int[4];
        int hit = 0;
        int total = 0;
        for (int x = -3000; x < 3000; x += 37) {
            for (int z = -3000; z < 3000; z += 37) {
                total++;
                if (f.spans(x, z, spans) > 0) {
                    hit++;
                }
            }
        }
        return hit / (double) total;
    }

    @Test
    void islandsCoverAboutWhatTheConfigSays() {
        double c = coverage(new IslandField(SEED, 25));
        // Two heights at ~25% each, less the rifts at their edges: open sky between, but plenty of land.
        assertTrue(c > 0.25 && c < 0.55, "coverage " + c);
        assertTrue(coverage(new IslandField(SEED, 10)) < c, "lower density, fewer islands");
        assertTrue(coverage(new IslandField(SEED, 50)) > c, "higher density, more islands");
        double other = coverage(new IslandField(SEED + 1, 25));
        assertTrue(Math.abs(other - c) < 0.08, "the threshold is measured per seed: " + other + " vs " + c);
    }

    @Test
    void islandsHangAtTwoHeightsAboveTheSea() {
        IslandField f = new IslandField(SEED, 25);
        int[] spans = new int[4];
        int lower = 0;
        int upper = 0;
        int thick = 0;
        for (int x = -2000; x < 2000; x += 11) {
            for (int z = -2000; z < 2000; z += 53) {
                int n = f.spans(x, z, spans);
                for (int i = 0; i < n; i++) {
                    int bottom = spans[i * 2];
                    int top = spans[i * 2 + 1];
                    assertTrue(top > bottom, "a span has height at " + x + ", " + z);
                    // The generator also clips undersides to 2 above the sea; the field keeps clear by itself.
                    assertTrue(bottom >= 20 && top < 320, "between the sea and the build limit: " + bottom + ".." + top);
                    if (top < 125) {
                        lower++;
                    } else if (top > 125) {
                        upper++;
                    }
                    if (top - bottom >= 25) {
                        thick++;
                    }
                }
                assertEquals(n > 0 ? Math.max(spans[1], n == 2 ? spans[3] : Integer.MIN_VALUE)
                        : Integer.MIN_VALUE, f.top(x, z));
            }
        }
        assertTrue(lower > 100 && upper > 100, "both heights have islands: " + lower + " / " + upper);
        assertTrue(thick > 50, "island cores hang deep below their tops: " + thick);
    }

    @Test
    void islandsAreTheSameEveryTime() {
        IslandField a = new IslandField(SEED, 25);
        IslandField b = new IslandField(SEED, 25);
        int[] sa = new int[4];
        int[] sb = new int[4];
        for (int x = -500; x < 500; x += 13) {
            for (int z = -500; z < 500; z += 17) {
                assertEquals(a.spans(x, z, sa), b.spans(x, z, sb));
                assertArrayEquals(sa, sb);
            }
        }
    }

    @Test
    void theSeaFloorIsAShallowShelf() {
        IslandField f = new IslandField(SEED, 25);
        for (int x = -1000; x < 1000; x += 29) {
            int floor = f.seabed(x, x / 2, 24);
            assertTrue(floor >= 24 - 24 && floor <= 24 - 12, "12 to 24 blocks of water: " + floor);
        }
    }

    // ------------------------------------------------------------------ Deep Dark

    @Test
    void cavernsOpenAboutWhatTheConfigSays() {
        CavernField f = new CavernField(SEED, -48, 8, 35);
        Random r = new Random(1);
        int open = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            if (f.open(r.nextInt(20_000) - 10_000, -38 + r.nextInt(36), r.nextInt(20_000) - 10_000)) {
                open++;
            }
        }
        double share = open / (double) n;
        assertTrue(share > 0.27 && share < 0.43, "open share of the band's interior: " + share);
    }

    @Test
    void cavernsStayInsideTheirBand() {
        CavernField f = new CavernField(SEED, -48, 8, 70);
        for (int x = -300; x < 300; x += 7) {
            for (int z = -300; z < 300; z += 11) {
                assertFalse(f.open(x, -49, z));
                assertFalse(f.open(x, 9, z));
                assertFalse(f.open(x, -48, z), "a floor at the band's bottom");
                assertFalse(f.open(x, 8, z), "a roof at its top");
            }
        }
    }

    @Test
    void carvingFollowsTheField() {
        CavernField f = new CavernField(SEED, -48, 8, 35);
        boolean[] out = new boolean[f.height() * 256];
        int agree = 0;
        int total = 0;
        int opened = 0;
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                f.carve(cx, cz, out);
                for (int y = f.floor(); y <= f.top(); y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            boolean carved = out[(y - f.floor()) * 256 + z * 16 + x];
                            if (carved == f.open((cx << 4) + x, y, (cz << 4) + z)) {
                                agree++;
                            }
                            if (carved) {
                                opened++;
                            }
                            total++;
                        }
                    }
                }
            }
        }
        assertTrue(agree / (double) total > 0.85, "interpolated carving matches the field: " + agree / (double) total);
        assertTrue(opened > total / 10, "and does open caverns: " + opened + " of " + total);
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void thresholdsCoverTheirShare() {
        Random r = new Random(3);
        double t = Noise.threshold(r::nextDouble, 10_000, 30);
        assertTrue(t > 0.67 && t < 0.73, "30% of uniform draws lie above ~0.7: " + t);
        assertEquals(Double.POSITIVE_INFINITY, Noise.threshold(r::nextDouble, 10, 0), "0%: nothing passes");
        assertEquals(Double.NEGATIVE_INFINITY, Noise.threshold(r::nextDouble, 10, 100), "100%: everything does");
        double h = Noise.hash(SEED, 10, -4);
        assertEquals(h, Noise.hash(SEED, 10, -4));
        assertTrue(h >= 0 && h < 1);
        assertTrue(Noise.hash(SEED, 10, -4) != Noise.hash(SEED, -4, 10));
    }

    @Test
    void patchesCoverTheirShare() {
        Noise.Patches p = new Noise.Patches(SEED, 21, 1 / 18.0, 20);
        int hit = 0;
        int total = 0;
        for (int x = -2000; x < 2000; x += 9) {
            for (int z = -2000; z < 2000; z += 9) {
                total++;
                if (p.at(x, z)) {
                    hit++;
                }
            }
        }
        double share = hit / (double) total;
        assertTrue(share > 0.15 && share < 0.25, "about 20%: " + share);
    }

    @Test
    void builtOncePerSeed() {
        AtomicInteger builds = new AtomicInteger();
        PerSeed<Object> lazy = new PerSeed<>(seed -> {
            builds.incrementAndGet();
            return new Object();
        });
        Object a = lazy.get(1);
        assertSame(a, lazy.get(1));
        assertNotSame(a, lazy.get(2), "another seed, another world");
        assertEquals(2, builds.get());
    }

    @Test
    void generatorIdsNameModifiers() {
        assertEquals("rapid-horizons:shattered_skies", FrontierGenerator.generatorString(Modifier.SHATTERED_SKIES));
        assertEquals(Optional.of(Modifier.DEEP_DARK), FrontierGenerator.modifierOf("deep_dark"));
        assertEquals(Optional.of(Modifier.OVERGROWTH), FrontierGenerator.modifierOf("OVERGROWTH"));
        assertEquals(Optional.empty(), FrontierGenerator.modifierOf(""), "Multiverse's probe");
        assertEquals(Optional.empty(), FrontierGenerator.modifierOf(null));
        assertEquals(Optional.empty(), FrontierGenerator.modifierOf("amplified"));
    }
}
