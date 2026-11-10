package us.rapidskies.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.market.model.AlertRule;
import us.rapidskies.market.model.Fees;

/**
 * rapid-market's config.yml. Parsing collects every problem and {@link #from} throws them
 * together.
 *
 * @param durations       how long a listing may run, shortest first
 * @param defaultDuration preselected when listing
 * @param minPrice        lowest asking price for a whole listing
 * @param maxPrice        highest asking price
 * @param baseListings    active listings everyone may have (PLAN.md §4.2: the same for
 *                        everyone, more from Trade or island levels, never a rank perk)
 * @param historyDays     days the average price is taken over
 * @param blockedItems    item keys that can't be listed
 * @param expirySeconds   how often expired listings are moved to their sellers' bins
 * @param custodySeconds  how often unsettled custody entries are retried
 */
public record MarketSettings(List<Duration> durations, Duration defaultDuration, long minPrice, long maxPrice,
                             int baseListings, Fees fees, int historyDays, AlertRule alerts, Set<String> blockedItems,
                             int expirySeconds, int custodySeconds) {

    public static MarketSettings from(ConfigView c) {
        List<String> problems = new ArrayList<>();
        List<Duration> durations = new ArrayList<>();
        for (String h : c.stringList("listing.durations-hours")) {
            try {
                int hours = Integer.parseInt(h.trim());
                if (hours <= 0) {
                    throw new NumberFormatException();
                }
                durations.add(Duration.ofHours(hours));
            } catch (NumberFormatException e) {
                problems.add("listing.durations-hours: '" + h + "' isn't a whole number of hours");
            }
        }
        if (durations.isEmpty()) {
            durations = new ArrayList<>(List.of(Duration.ofHours(12), Duration.ofHours(24), Duration.ofHours(48)));
        }
        durations = durations.stream().distinct().sorted().toList();
        Duration def = Duration.ofHours(c.integer("listing.default-hours", 24));
        if (!durations.contains(def)) {
            problems.add("listing.default-hours (" + def.toHours() + ") must be one of listing.durations-hours");
        }
        long minPrice = c.integer("listing.min-price", 10);
        long maxPrice = parseLong(c.string("listing.max-price", "1000000000"), "listing.max-price", problems);
        if (minPrice < 1) {
            problems.add("listing.min-price must be at least 1");
        }
        if (maxPrice < minPrice) {
            problems.add("listing.max-price must be at least listing.min-price");
        }
        int base = c.integer("listing.max-active", 10);
        if (base < 1) {
            problems.add("listing.max-active must be at least 1");
        }
        Fees fees = null;
        try {
            fees = new Fees(decimal(c, "fees.listing-percent", "2", problems),
                    decimal(c, "fees.sales-tax-percent", "5", problems));
        } catch (IllegalArgumentException e) {
            problems.add("fees: " + e.getMessage());
        }
        int historyDays = c.integer("price-history.days", 7);
        if (historyDays < 1) {
            problems.add("price-history.days must be at least 1");
        }
        AlertRule alerts = new AlertRule(c.integer("alerts.below-average-percent", 25),
                c.integer("alerts.min-average", 50), c.integer("alerts.repeat", 3), c.integer("alerts.window-days", 7));
        Set<String> blocked = new TreeSet<>();
        for (String k : c.stringList("listing.blocked-items")) {
            String key = k.trim().toLowerCase(Locale.ROOT);
            blocked.add(key.contains(":") ? key : "minecraft:" + key);
        }
        int expiry = Math.max(5, c.integer("timers.expiry-seconds", 30));
        int custody = Math.max(5, c.integer("timers.custody-retry-seconds", 30));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return new MarketSettings(List.copyOf(durations), def, minPrice, maxPrice, base, fees, historyDays, alerts,
                Set.copyOf(blocked), expiry, custody);
    }

    /** The duration a typed {@code 12}, {@code 12h} or {@code 1d} means, if it's one of {@link #durations}. */
    public Optional<Duration> duration(String input) {
        if (input == null || input.isBlank()) {
            return Optional.of(defaultDuration);
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        try {
            Duration d;
            if (s.endsWith("d")) {
                d = Duration.ofDays(Long.parseLong(s.substring(0, s.length() - 1)));
            } else {
                d = Duration.ofHours(Long.parseLong(s.endsWith("h") ? s.substring(0, s.length() - 1) : s));
            }
            return durations.contains(d) ? Optional.of(d) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static BigDecimal decimal(ConfigView c, String path, String def, List<String> problems) {
        Object raw = c.get(path);
        String s = raw == null ? def : raw.toString().trim();
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            problems.add(path + ": '" + s + "' isn't a number");
            return new BigDecimal(def);
        }
    }

    private static long parseLong(String s, String path, List<String> problems) {
        try {
            return Long.parseLong(s.trim().replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            problems.add(path + ": '" + s + "' isn't a whole number");
            return 0;
        }
    }
}
