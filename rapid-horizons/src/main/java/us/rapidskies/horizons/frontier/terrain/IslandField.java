package us.rapidskies.horizons.frontier.terrain;

import java.util.Random;
import org.bukkit.util.noise.SimplexOctaveGenerator;

/**
 * Shattered Skies' terrain, column by column: islands at two heights over a sea. Free of the
 * server (only Bukkit's noise classes), so it's unit-tested; read-only once built, so one
 * instance serves every worldgen thread.
 *
 * <p>Each height has a 2D mask: where it rises above a threshold there's an island, and the
 * further above, the thicker. An island's top is a gentle dome; its underside hangs much
 * further down, an inverted cone with a jagged edge, so they read as torn-off chunks of land.
 * Long rifts split their outer parts into shards. The threshold is measured from the seed's own
 * noise, so {@code density} percent of each height is island whatever the seed.
 */
public final class IslandField {

    /** One height of islands. */
    private record Layer(int base, int spread, int topAmp, int depth, SimplexOctaveGenerator mask,
                         SimplexOctaveGenerator height, double threshold) {
    }

    /** How far above the threshold the mask must be for an island at full thickness. */
    private static final double FULL = 0.35;

    private final long seed;
    private final Layer lower;
    private final Layer upper;
    private final SimplexOctaveGenerator detail;
    private final SimplexOctaveGenerator rifts;
    private final SimplexOctaveGenerator seabed;

    public IslandField(long seed, double density) {
        this.seed = seed;
        this.lower = layer(seed, 1, 84, 22, 7, 34, 1 / 110.0, density);
        this.upper = layer(seed, 2, 156, 26, 8, 40, 1 / 85.0, density);
        this.detail = Noise.octaves(seed, 3, 2, 1 / 14.0);
        this.rifts = Noise.octaves(seed, 4, 1, 1 / 45.0);
        this.seabed = Noise.octaves(seed, 5, 2, 1 / 60.0);
    }

    private static Layer layer(long seed, long salt, int base, int spread, int topAmp, int depth, double scale,
                               double density) {
        SimplexOctaveGenerator mask = Noise.octaves(seed, salt * 31, 3, scale);
        SimplexOctaveGenerator height = Noise.octaves(seed, salt * 31 + 7, 2, scale / 2);
        Random r = new Random(seed ^ (salt * 0x5DEECE66DL));
        double threshold = Noise.threshold(() -> mask.noise(r.nextInt(400_000) - 200_000,
                r.nextInt(400_000) - 200_000, 2.0, 0.5, true), 4096, density);
        return new Layer(base, spread, topAmp, depth, mask, height, threshold);
    }

    public long seed() {
        return seed;
    }

    /**
     * The island spans of column {@code x, z}, lowest first, as {@code bottom, top} pairs in
     * {@code out} (length 4, both blocks inclusive); returns how many (0 to 2). Spans of the
     * two heights can overlap; filling both is fine.
     */
    public int spans(int x, int z, int[] out) {
        int n = 0;
        if (span(lower, x, z, out, 0)) {
            n = 1;
        }
        if (span(upper, x, z, out, n * 2)) {
            n++;
        }
        return n;
    }

    private boolean span(Layer l, int x, int z, int[] out, int at) {
        double m = l.mask().noise(x, z, 2.0, 0.5, true);
        if (m <= l.threshold()) {
            return false;
        }
        double k = Math.min(1, (m - l.threshold()) / FULL);
        // Rifts: long cracks (where a ridged noise crosses zero) split the thinner outer parts
        // into separate shards; the cores stay whole.
        if (k < 0.7 && Math.abs(rifts.noise(x, z, 2.0, 0.5, true)) < 0.045) {
            return false;
        }
        double d = detail.noise(x, z, 2.0, 0.5, true);
        double center = l.base() + l.height().noise(x, z, 2.0, 0.5, true) * l.spread();
        int top = (int) Math.round(center + Math.sqrt(k) * l.topAmp() + d * 1.5);
        int bottom = (int) Math.round(center - Math.pow(k, 0.8) * l.depth() - Math.abs(d) * 3 - 1);
        if (top - bottom < 1) {
            return false;
        }
        out[at] = bottom;
        out[at + 1] = top;
        return true;
    }

    /** The highest island block in the column, or {@link Integer#MIN_VALUE} for open sky. */
    public int top(int x, int z) {
        int[] s = new int[4];
        int n = spans(x, z, s);
        int top = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            top = Math.max(top, s[i * 2 + 1]);
        }
        return top;
    }

    /** The sea floor's top block: 12 to 24 blocks under the surface. */
    public int seabed(int x, int z, int seaLevel) {
        return seaLevel - 18 + (int) Math.round(seabed.noise(x, z, 2.0, 0.5, true) * 6);
    }
}
