package us.rapidskies.horizons.frontier.terrain;

import java.util.Arrays;
import java.util.Random;
import java.util.function.DoubleSupplier;
import org.bukkit.util.noise.SimplexOctaveGenerator;

/**
 * Small noise helpers shared by the Frontier generators. Everything here is deterministic
 * from the world seed and read-only once built, so one instance serves every worldgen thread.
 */
final class Noise {

    private Noise() {
    }

    /** A seeded octave generator, its seed mixed with {@code salt} so each use gets its own field. */
    static SimplexOctaveGenerator octaves(long seed, long salt, int octaves, double scale) {
        SimplexOctaveGenerator g = new SimplexOctaveGenerator(new Random(seed * 0x9E3779B97F4A7C15L + salt), octaves);
        g.setScale(scale);
        return g;
    }

    /**
     * The value that {@code percent}% of {@code samples} draws lie above: the threshold that
     * makes a noise field cover that share of the world, whatever its distribution. 0 percent
     * gives +infinity (nothing passes), 100 gives -infinity (everything does).
     */
    static double threshold(DoubleSupplier sampler, int samples, double percent) {
        if (percent <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (percent >= 100) {
            return Double.NEGATIVE_INFINITY;
        }
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            values[i] = sampler.getAsDouble();
        }
        Arrays.sort(values);
        int index = (int) Math.floor(samples * (1 - percent / 100.0));
        return values[Math.max(0, Math.min(samples - 1, index))];
    }

    /** A stable pseudo-random number in [0, 1) for a block position (no shared state). */
    static double hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x632BE59BD9B4E019L) ^ (z * 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }

    static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    /**
     * Patches over the ground: blobs covering about {@code percent}% of it, a few dozen blocks
     * across ({@code scale} is the noise frequency per block).
     */
    static final class Patches {

        private final SimplexOctaveGenerator noise;
        private final double threshold;

        Patches(long seed, long salt, double scale, double percent) {
            this.noise = octaves(seed, salt, 2, scale);
            Random r = new Random(seed ^ salt);
            this.threshold = threshold(() -> sample(r.nextInt(200_000) - 100_000, r.nextInt(200_000) - 100_000),
                    2048, percent);
        }

        private double sample(int x, int z) {
            return noise.noise(x, z, 2.0, 0.5, true);
        }

        boolean at(int x, int z) {
            return sample(x, z) > threshold;
        }
    }
}
