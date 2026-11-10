package us.rapidskies.horizons.trade;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import us.rapidskies.horizons.data.HorizonsRepository.TradeRow;

/**
 * One player's Trade writes that haven't reached the database yet: progress rows, soft-cap
 * points and Crowns payouts. {@link TradeService} adds to it on the main thread and a
 * worker {@link #take takes} a batch out to write; whatever the write couldn't do goes
 * {@link #giveBack back} and is retried on the next payout tick, for as long as the
 * database is down. Nothing is dropped.
 *
 * <p>Every payout carries a ledger transaction id fixed when it was earned, so a retry of a
 * payout that did reach the ledger (the connection broke after the commit) comes back as a
 * duplicate and is never paid twice.
 *
 * <p>Only one batch per player is out at a time, so an older progress row can never land
 * after a newer one. Thread-safe.
 */
final class TradeOutbox {

    /** Crowns for one Trade, under a transaction id that makes paying it idempotent. */
    record Payout(Trade trade, long crowns, String txnId) {
    }

    /** One write's worth for {@code player}. */
    record Batch(UUID player, Map<Trade, TradeRow> rows, Map<LocalDate, Long> points, List<Payout> payouts) {

        Batch {
            rows = Map.copyOf(rows);
            points = Map.copyOf(points);
            payouts = List.copyOf(payouts);
        }

        static Batch empty(UUID player) {
            return new Batch(player, Map.of(), Map.of(), List.of());
        }

        boolean isEmpty() {
            return rows.isEmpty() && points.isEmpty() && payouts.isEmpty();
        }

        long crowns() {
            return payouts.stream().mapToLong(Payout::crowns).sum();
        }
    }

    private final UUID player;
    private final Map<Trade, TradeRow> rows = new EnumMap<>(Trade.class);
    private final Map<LocalDate, Long> points = new HashMap<>();
    private final List<Payout> payouts = new ArrayList<>();
    private boolean inFlight;
    private int paused;

    TradeOutbox(UUID player) {
        this.player = player;
    }

    UUID player() {
        return player;
    }

    /** Queues work: newer rows replace older ones, points and payouts add up. */
    synchronized void add(Map<Trade, TradeRow> newRows, Map<LocalDate, Long> newPoints, List<Payout> newPayouts) {
        rows.putAll(newRows);
        newPoints.forEach((day, n) -> points.merge(day, n, Long::sum));
        payouts.addAll(newPayouts);
    }

    synchronized void add(Batch batch) {
        add(batch.rows(), batch.points(), batch.payouts());
    }

    /**
     * Everything queued, moved out for one write; null when there's nothing, a batch is
     * already out, or a login is reading this player's progress.
     */
    synchronized Batch take() {
        if (inFlight || paused > 0 || (rows.isEmpty() && points.isEmpty() && payouts.isEmpty())) {
            return null;
        }
        Batch batch = new Batch(player, rows, points, payouts);
        rows.clear();
        points.clear();
        payouts.clear();
        inFlight = true;
        return batch;
    }

    /**
     * Ends the batch that was out, returning what it didn't write. Rows queued meanwhile are
     * newer and win; payouts go first again, in the order they were earned.
     */
    synchronized void giveBack(Batch unwritten) {
        unwritten.rows().forEach(rows::putIfAbsent);
        unwritten.points().forEach((day, n) -> points.merge(day, n, Long::sum));
        payouts.addAll(0, unwritten.payouts());
        inFlight = false;
        notifyAll();
    }

    /** Whether nothing is queued or out. */
    synchronized boolean idle() {
        return !inFlight && rows.isEmpty() && points.isEmpty() && payouts.isEmpty();
    }

    /**
     * Holds batches back and waits for the one out to finish, so a login can read the
     * database and {@link #pending} and see every row exactly once. Pair with {@link #resume}.
     */
    synchronized void pause() throws InterruptedException {
        paused++;
        while (inFlight) {
            wait();
        }
    }

    synchronized void resume() {
        paused = Math.max(0, paused - 1);
    }

    /** A copy of everything queued (not what's out being written). */
    synchronized Batch pending() {
        return new Batch(player, rows, points, payouts);
    }
}
