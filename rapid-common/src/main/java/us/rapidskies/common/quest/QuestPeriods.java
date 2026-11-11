package us.rapidskies.common.quest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Quest periods and assignment (PLAN.md §7). Dailies run per UTC day ({@code 2026-11-10})
 * and bank: the last {@code bankDays} days' dailies stay open, so a player who misses a day
 * or two can still do them. Weeklies run per ISO week ({@code 2026-W46}, Monday to Sunday
 * UTC). Which quest fills a slot is decided by the player, the period and the slot alone,
 * so it's the same on every server and a race between two servers assigns the same thing.
 */
public final class QuestPeriods {

    private QuestPeriods() {
    }

    public static String day(LocalDate date) {
        return date.toString();
    }

    public static String day(Instant at) {
        return day(LocalDate.ofInstant(at, ZoneOffset.UTC));
    }

    public static String week(Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZoneOffset.UTC);
        return String.format("%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public static boolean isWeek(String period) {
        return period.contains("-W");
    }

    /**
     * The daily periods still open at {@code now}, newest first: today and the
     * {@code bankDays - 1} days before it, but never a day before {@code firstDay} (a new
     * player starts with today's).
     */
    public static List<String> openDays(Instant now, int bankDays, LocalDate firstDay) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, bankDays); i++) {
            LocalDate d = today.minusDays(i);
            if (i > 0 && firstDay != null && d.isBefore(firstDay)) {
                break;
            }
            out.add(day(d));
        }
        return out;
    }

    /** When a daily period closes for good: {@code bankDays} days after it starts. */
    public static Instant dayCloses(String period, int bankDays) {
        return LocalDate.parse(period).plusDays(Math.max(1, bankDays)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** When the ISO week of {@code at} ends. */
    public static Instant weekEnds(Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZoneOffset.UTC);
        return d.plusDays(8 - d.getDayOfWeek().getValue()).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * The quest for one slot: one of {@code candidates}, not already in {@code taken},
     * chosen by the player, period and slot. Empty if every candidate is taken.
     */
    public static java.util.Optional<String> pick(Collection<String> candidates, Set<String> taken, UUID player,
                                                  String period, int slot) {
        List<String> open = candidates.stream().filter(q -> !taken.contains(q)).sorted().toList();
        if (open.isEmpty()) {
            return java.util.Optional.empty();
        }
        long seed = player.getMostSignificantBits() * 31 + player.getLeastSignificantBits();
        seed = seed * 1_000_003 + period.hashCode();
        seed = seed * 1_000_003 + slot;
        return java.util.Optional.of(open.get(new SplittableRandom(seed).nextInt(open.size())));
    }
}
