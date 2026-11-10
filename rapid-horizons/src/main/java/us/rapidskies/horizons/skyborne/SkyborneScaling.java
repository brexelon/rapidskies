package us.rapidskies.horizons.skyborne;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * How hard a Skyborne fortress is, scaled to the number of people fighting it (PLAN.md §5.2),
 * and when the next one comes. Free of Bukkit.
 *
 * @param waveBase            mobs in the first wave for one player
 * @param wavePerPlayer       more mobs per extra player
 * @param waveGrowth          more mobs per later wave
 * @param waveMax             never more than this many at once
 * @param bossHealth          the boss's health with one player
 * @param bossPerPlayerPercent more health per extra player, in percent of {@code bossHealth}
 */
public record SkyborneScaling(int waveBase, int wavePerPlayer, int waveGrowth, int waveMax, double bossHealth,
                              double bossPerPlayerPercent) {

    /** Highest maximum health Minecraft allows (the attribute's cap). */
    public static final double HEALTH_CAP = 1024;

    /** Mobs in wave {@code wave} (1-based) with {@code players} fighting. */
    public int waveSize(int wave, int players) {
        int extra = Math.max(0, Math.max(1, players) - 1);
        long size = (long) waveBase + (long) wavePerPlayer * extra + (long) waveGrowth * Math.max(0, wave - 1);
        return (int) Math.max(1, Math.min(waveMax, size));
    }

    public double bossHealth(int players) {
        int extra = Math.max(0, Math.max(1, players) - 1);
        double h = bossHealth * (1 + bossPerPlayerPercent / 100.0 * extra);
        return Math.max(1, Math.min(HEALTH_CAP, h));
    }

    /** When the next fortress appears: {@code interval} from now, give or take up to {@code jitter}. */
    public static Instant next(Instant now, Duration interval, Duration jitter, Random random) {
        long j = jitter.toSeconds();
        long offset = j <= 0 ? 0 : (long) Math.floor((random.nextDouble() * 2 - 1) * j);
        return now.plus(interval).plusSeconds(offset);
    }
}
