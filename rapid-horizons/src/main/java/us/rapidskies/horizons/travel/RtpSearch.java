package us.rapidskies.horizons.travel;

import java.util.Random;

/**
 * Where to try for a random-teleport spot, free of Bukkit so it's unit-tested. Which blocks
 * make a spot unsafe is rapid-essentials' {@code SafeBlocks}, shared with {@code /back} and
 * homes; the live check also needs the ground to be solid and the two blocks above it
 * passable, which only the server can answer.
 */
public final class RtpSearch {

    private RtpSearch() {
    }

    public record Point(int x, int z) {
    }

    /**
     * A point between {@code minRadius} and {@code maxRadius} blocks from the centre, spread
     * evenly over the ring's area (not bunched near the middle, as a uniform radius would be).
     */
    public static Point candidate(Random random, int centerX, int centerZ, int minRadius, int maxRadius) {
        int max = Math.max(0, maxRadius);
        int min = Math.max(0, Math.min(minRadius, max));
        double r = Math.sqrt(random.nextDouble() * ((double) max * max - (double) min * min) + (double) min * min);
        double angle = random.nextDouble() * Math.PI * 2;
        return new Point(centerX + (int) Math.floor(Math.cos(angle) * r),
                centerZ + (int) Math.floor(Math.sin(angle) * r));
    }
}
