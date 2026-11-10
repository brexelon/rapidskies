package us.rapidskies.horizons.frontier.terrain;

import java.util.Random;
import org.bukkit.util.noise.SimplexOctaveGenerator;

/**
 * Deep Dark's caverns: where, between {@code floor} and {@code top}, the rock is hollowed out.
 * Free of the server (only Bukkit's noise classes), so it's unit-tested; read-only once built.
 *
 * <p>A wide, squashed 3D noise makes halls far broader than they are tall, a 2D noise leaves
 * stone pillars standing in them, and the density fades out near the band's edges so every
 * cavern has a floor and a roof. Like vanilla, the density is sampled every 4 blocks and
 * interpolated in between, which keeps pre-generation cheap. The threshold is measured from
 * the seed's own noise, so {@code openness} percent of the band's interior is open.
 */
public final class CavernField {

    /** Blocks over which the caverns close up towards the band's floor and roof. */
    private static final double EDGE = 10;
    private static final int CELL = 4;

    private final long seed;
    private final int floor;
    private final int top;
    private final SimplexOctaveGenerator halls;
    private final SimplexOctaveGenerator pillars;
    private final double threshold;

    public CavernField(long seed, int floor, int top, double openness) {
        this.seed = seed;
        this.floor = floor;
        this.top = top;
        this.halls = Noise.octaves(seed, 11, 3, 1);
        halls.setXScale(1 / 72.0);
        halls.setZScale(1 / 72.0);
        halls.setYScale(1 / 30.0);
        this.pillars = Noise.octaves(seed, 12, 1, 1 / 20.0);
        Random r = new Random(seed ^ 0xCA7E5L);
        int interiorLow = floor + (int) EDGE;
        int interiorHigh = Math.max(interiorLow + 1, top - (int) EDGE);
        this.threshold = Noise.threshold(() -> density(r.nextInt(400_000) - 200_000,
                interiorLow + r.nextInt(interiorHigh - interiorLow), r.nextInt(400_000) - 200_000), 4096, openness);
    }

    public long seed() {
        return seed;
    }

    public int floor() {
        return floor;
    }

    public int top() {
        return top;
    }

    /** Raw density at a block: higher is more open. Below the band's edges it falls away. */
    double density(int x, int y, int z) {
        if (y < floor || y > top) {
            return -10;
        }
        double n = halls.noise(x, y, z, 2.0, 0.5, true);
        double p = pillars.noise(x, z, 2.0, 0.5, true);
        if (p > 0.6) {
            n -= (p - 0.6) * 6;
        }
        double edge = Noise.smoothstep(0, EDGE, Math.min(y - floor, top - y));
        return n - (1 - edge) * 1.5;
    }

    public boolean open(int x, int y, int z) {
        return density(x, y, z) > threshold;
    }

    /** Rows of the band, floor to top inclusive. */
    public int height() {
        return top - floor + 1;
    }

    /**
     * Which blocks of chunk {@code chunkX, chunkZ} are open, as
     * {@code out[(y - floor) * 256 + z * 16 + x]} (local x and z), interpolated from a 4-block grid.
     */
    public void carve(int chunkX, int chunkZ, boolean[] out) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int cellsY = (height() + CELL - 1) / CELL;
        double[][][] grid = new double[5][cellsY + 1][5];
        for (int gx = 0; gx <= 4; gx++) {
            for (int gz = 0; gz <= 4; gz++) {
                for (int gy = 0; gy <= cellsY; gy++) {
                    grid[gx][gy][gz] = density(baseX + gx * CELL, Math.min(top, floor + gy * CELL), baseZ + gz * CELL);
                }
            }
        }
        for (int y = 0; y < height(); y++) {
            int gy = y / CELL;
            double fy = (y % CELL) / (double) CELL;
            for (int z = 0; z < 16; z++) {
                int gz = z / CELL;
                double fz = (z % CELL) / (double) CELL;
                for (int x = 0; x < 16; x++) {
                    int gx = x / CELL;
                    double fx = (x % CELL) / (double) CELL;
                    double d = lerp(fy,
                            lerp(fz, lerp(fx, grid[gx][gy][gz], grid[gx + 1][gy][gz]),
                                    lerp(fx, grid[gx][gy][gz + 1], grid[gx + 1][gy][gz + 1])),
                            lerp(fz, lerp(fx, grid[gx][gy + 1][gz], grid[gx + 1][gy + 1][gz]),
                                    lerp(fx, grid[gx][gy + 1][gz + 1], grid[gx + 1][gy + 1][gz + 1])));
                    out[y * 256 + z * 16 + x] = d > threshold;
                }
            }
        }
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }
}
