package us.rapidskies.horizons.trade;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import us.rapidskies.horizons.data.HorizonsRepository.TradeRow;

/**
 * An online player's Trades: progress, today's points for the soft cap, and Crowns and
 * points earned since the last batch write. Loaded before they join; changed on the main
 * thread only; written in batches by {@link TradeService}.
 */
final class TradeProfile {

    /** Most distinct Builder positions remembered per day, so memory stays bounded. */
    private static final int MAX_BUILT_POSITIONS = 50_000;

    static final class Progress {
        double xp;
        int level;
        boolean active;
        boolean dirty;

        Progress(double xp, int level, boolean active) {
            this.xp = xp;
            this.level = level;
            this.active = active;
        }

        TradeRow row() {
            return new TradeRow((long) Math.floor(xp), level, active);
        }
    }

    final UUID id;
    final Map<Trade, Progress> progress = new EnumMap<>(Trade.class);
    Instant tradesChangedAt;
    LocalDate day;
    /** Points today, before Tailwind: the soft cap's measure. */
    double pointsToday;
    /** Points not yet added to {@code horizons_trade_daily}, by the UTC day they were earned. */
    final Map<LocalDate, Double> pendingPoints = new java.util.HashMap<>();
    final Map<Trade, Double> pendingCrowns = new EnumMap<>(Trade.class);
    /** Packed positions a Builder placed at today: placing again in the same spot pays nothing. */
    final Set<Long> builtToday = new HashSet<>();

    TradeProfile(UUID id, Map<Trade, TradeRow> rows, Instant tradesChangedAt, LocalDate day, long pointsToday,
                 TradeRules rules) {
        this.id = id;
        this.tradesChangedAt = tradesChangedAt;
        this.day = day;
        this.pointsToday = pointsToday;
        rows.forEach((t, r) -> progress.put(t, new Progress(r.xp(), rules.levelFor(r.xp()), r.active())));
    }

    Progress of(Trade trade) {
        return progress.computeIfAbsent(trade, t -> new Progress(0, 0, false));
    }

    boolean active(Trade trade) {
        Progress p = progress.get(trade);
        return p != null && p.active;
    }

    int activeCount() {
        return (int) progress.values().stream().filter(p -> p.active).count();
    }

    int totalLevels() {
        return progress.values().stream().mapToInt(p -> p.level).sum();
    }

    /** Starts a new day's soft cap when the UTC date has moved on. */
    void rollDay(LocalDate today) {
        if (!today.equals(day)) {
            day = today;
            pointsToday = 0;
            builtToday.clear();
        }
    }

    /** Whether placing at {@code packed} is new today (and remembers it). */
    boolean firstBuildAt(long packed) {
        if (builtToday.size() >= MAX_BUILT_POSITIONS) {
            return false;
        }
        return builtToday.add(packed);
    }
}
