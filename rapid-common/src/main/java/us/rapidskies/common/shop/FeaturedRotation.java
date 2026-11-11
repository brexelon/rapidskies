package us.rapidskies.common.shop;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The weekly Featured Shop (PLAN.md §6.2, §12): which items are on show in a given week.
 * Worked out from the pool and the week alone, so every server shows the same items with
 * nothing stored, and the rotation changes on its own every Monday 00:00 UTC.
 *
 * <p>The pool is shuffled once per cycle and shown a window at a time, so every item comes
 * round before any repeats; each cycle's first window is kept clear of the previous cycle's
 * last one, so nothing is featured two weeks running as long as the pool holds at least
 * three times as many items as are shown.
 */
public final class FeaturedRotation {

    /** Monday 1970-01-05, the first Monday of the epoch: week 0. */
    private static final long FIRST_MONDAY = 4;
    private static final long SALT = 0x5EA50F_F00DL;

    private FeaturedRotation() {
    }

    /** The week number of {@code at}: whole Monday-to-Sunday UTC weeks since 1970-01-05. */
    public static long week(Instant at) {
        long day = LocalDate.ofInstant(at, ZoneOffset.UTC).toEpochDay();
        return Math.floorDiv(day - FIRST_MONDAY, 7);
    }

    /** When week {@code week} ends (the next Monday 00:00 UTC). */
    public static Instant endOf(long week) {
        return LocalDate.ofEpochDay(FIRST_MONDAY + (week + 1) * 7).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Monday of {@code at}'s week, for display. */
    public static LocalDate monday(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** The {@code size} items featured in {@code week}, in display order. */
    public static List<String> pick(List<String> pool, long week, int size) {
        List<String> sorted = new ArrayList<>(new java.util.TreeSet<>(pool));
        if (size <= 0) {
            return List.of();
        }
        if (sorted.size() <= size) {
            return List.copyOf(sorted);
        }
        int windows = sorted.size() / size;
        long cycle = Math.floorDiv(week, windows);
        int window = Math.floorMod(week, windows);
        List<String> order = cycle(sorted, cycle, size, windows);
        return List.copyOf(order.subList(window * size, window * size + size));
    }

    /** A cycle's order: the shuffle, with its first window moved off the last cycle's last window. */
    private static List<String> cycle(List<String> sorted, long cycle, int size, int windows) {
        List<String> order = shuffled(sorted, cycle);
        if (windows < 2) {
            return order;
        }
        List<String> previous = shuffled(sorted, cycle - 1);
        Set<String> lastShown = new HashSet<>(previous.subList((windows - 1) * size, windows * size));
        int lastStart = (windows - 1) * size;
        for (int i = 0; i < size; i++) {
            if (!lastShown.contains(order.get(i))) {
                continue;
            }
            // Swap with an item outside this cycle's first and last windows that wasn't just shown.
            for (int j = size; j < order.size(); j++) {
                if (j >= lastStart && j < lastStart + size) {
                    continue;
                }
                if (!lastShown.contains(order.get(j))) {
                    Collections.swap(order, i, j);
                    break;
                }
            }
        }
        return order;
    }

    private static List<String> shuffled(List<String> sorted, long cycle) {
        List<String> copy = new ArrayList<>(sorted);
        Collections.shuffle(copy, new Random(cycle * 0x9E3779B97F4A7C15L ^ SALT));
        return copy;
    }
}
