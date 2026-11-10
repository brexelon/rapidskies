package us.rapidskies.horizons.frontier.terrain;

import java.util.function.LongFunction;

/**
 * Something built from the world seed on first use: a generator only learns the seed from the
 * {@code WorldInfo} its methods are called with, on several worldgen threads at once.
 */
final class PerSeed<T> {

    private record Built<T>(long seed, T value) {
    }

    private final LongFunction<T> build;
    private volatile Built<T> built;

    PerSeed(LongFunction<T> build) {
        this.build = build;
    }

    T get(long seed) {
        Built<T> b = built;
        if (b != null && b.seed() == seed) {
            return b.value();
        }
        synchronized (this) {
            b = built;
            if (b == null || b.seed() != seed) {
                b = new Built<>(seed, build.apply(seed));
                built = b;
            }
            return b.value();
        }
    }
}
