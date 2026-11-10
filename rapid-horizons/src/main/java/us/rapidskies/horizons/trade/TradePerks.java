package us.rapidskies.horizons.trade;

import java.util.EnumMap;
import java.util.Map;

/**
 * Side-grade perks per Trade (PLAN.md §5.2): small conveniences that grow with the Trade's
 * level under a soft cap, so a casual player's perk is never far behind a no-lifer's. None
 * of them makes anyone stronger in a fight or richer in Crowns.
 *
 * <ul>
 *   <li>Miner, <b>Prospector's Rush</b>: chance of a short Haste I after mining a natural ore.</li>
 *   <li>Farmer, <b>Green Thumb</b>: chance a fully grown crop replants itself, using a seed from its drops.</li>
 *   <li>Hunter, <b>Keen Eye</b>: more vanilla experience from hostile mobs.</li>
 *   <li>Builder, <b>Sure Footing</b>: less fall damage.</li>
 *   <li>Enchanter, <b>Lapis Saver</b>: chance to get the lapis back after enchanting.</li>
 *   <li>Fisher, <b>Quick Bite</b>: shorter waits for a bite.</li>
 * </ul>
 *
 * <p>A perk's strength is {@code max × level / (level + halfLevel)}: half of {@code max} at
 * {@code halfLevel}, two-thirds at twice that, and never all of it. Free of Bukkit.
 *
 * @param halfLevel the level at which a perk has half its maximum (0: perks are off)
 * @param max       per Trade, the strength approached at high levels, in percent
 */
public record TradePerks(int halfLevel, Map<Trade, Double> max) {

    /** Seconds of Haste from the Miner perk. */
    public static final int HASTE_SECONDS = 8;

    public TradePerks {
        max = Map.copyOf(new EnumMap<>(max));
    }

    /** Default maximums, in percent. */
    public static Map<Trade, Double> defaults() {
        Map<Trade, Double> m = new EnumMap<>(Trade.class);
        m.put(Trade.MINER, 40.0);
        m.put(Trade.FARMER, 60.0);
        m.put(Trade.HUNTER, 50.0);
        m.put(Trade.BUILDER, 30.0);
        m.put(Trade.ENCHANTER, 35.0);
        m.put(Trade.FISHER, 30.0);
        return m;
    }

    /** {@code trade}'s perk at {@code level}, in percent (0 at level 0 or when perks are off). */
    public double percent(Trade trade, int level) {
        double cap = max.getOrDefault(trade, 0.0);
        if (halfLevel <= 0 || level <= 0 || cap <= 0) {
            return 0;
        }
        return cap * level / (double) (level + halfLevel);
    }

    /** The same as a 0..1 fraction (a chance or a reduction). */
    public double fraction(Trade trade, int level) {
        return Math.min(1.0, percent(trade, level) / 100.0);
    }

    /**
     * A fishing wait (ticks) shortened by {@code fraction}, never below one second, so the
     * Fisher perk can't turn a rod into a machine gun.
     */
    public static int shortenedWait(int ticks, double fraction) {
        int shortened = (int) Math.round(ticks * (1.0 - Math.max(0, Math.min(1, fraction))));
        return Math.min(ticks, Math.max(20, shortened));
    }
}
