package us.rapidskies.skyrealms.shop;

import us.rapidskies.skyrealms.SkyRealmsSettings;

/**
 * Sale arithmetic for the server shop (PLAN.md §5.3, soft caps on grinding): the first
 * {@code dailyFullRate} Coins of sales a day pay in full, the next as much at
 * {@code reducedPercent}, and everything after that at {@code floorPercent}. Tailwind multiplies
 * the result. Pure, so it's unit-tested.
 */
public final class ShopPricing {

    private ShopPricing() {
    }

    /**
     * What selling goods worth {@code value} (at full price) pays when the player already sold
     * {@code earnedToday} worth today, before Tailwind and rounding.
     */
    public static double capped(long earnedToday, double value, SkyRealmsSettings.Shop cfg) {
        double full = cfg.dailyFullRate();
        if (full <= 0 || value <= 0) {
            return Math.max(0, value);
        }
        double a = earnedToday;
        double b = earnedToday + value;
        return overlap(a, b, 0, full)
                + overlap(a, b, full, 2 * full) * cfg.reducedPercent() / 100.0
                + overlap(a, b, 2 * full, Double.MAX_VALUE) * cfg.floorPercent() / 100.0;
    }

    /** The whole Coins paid: the capped value times Tailwind, rounded down. */
    public static long payout(long earnedToday, double value, SkyRealmsSettings.Shop cfg, double tailwind) {
        return (long) Math.floor(capped(earnedToday, value, cfg) * Math.max(1.0, tailwind) + 1e-9);
    }

    /** The full-price rate (100, reduced or floor percent) a player is selling at now. */
    public static int ratePercent(long earnedToday, SkyRealmsSettings.Shop cfg) {
        long full = cfg.dailyFullRate();
        if (full <= 0 || earnedToday < full) {
            return 100;
        }
        return earnedToday < 2 * full ? cfg.reducedPercent() : cfg.floorPercent();
    }

    /** What a purchase of {@code amount} costs: rounded up, so buying is never cheaper than listed. */
    public static long cost(double unitPrice, int amount) {
        return (long) Math.ceil(unitPrice * amount - 1e-9);
    }

    private static double overlap(double a, double b, double lo, double hi) {
        return Math.max(0, Math.min(b, hi) - Math.max(a, lo));
    }
}
