package us.rapidskies.core.progress;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.progress.NetworkLevels;
import us.rapidskies.common.progress.ProgressRepository;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.cosmetic.Catalog;

/**
 * {@code progression.yml} (PLAN.md §6.1, §6.2, §7): the level curve and level rewards, XP
 * sources, the login streak, the rank stipend and the Season Pass seasons. Read from the
 * rapid-core jar; every cosmetic it names must be in the catalog.
 */
public record ProgressConfig(NetworkLevels levels, long boltsPerLevel, Map<Integer, List<String>> levelRewards,
                             long playtimeXp, int playtimeDailyIntervals, Map<String, Long> eventXp,
                             List<Long> streakRewards, int streakDelaySeconds, int stipendEveryDays,
                             Map<Rank, Long> stipend, List<Season> seasons) {

    public static final String RESOURCE = "progression.yml";

    /** A Season Pass season, running from {@code starts} (inclusive) to {@code ends}, 00:00 UTC. */
    public record Season(String id, String name, LocalDate starts, LocalDate ends, int tiers, long xpPerTier,
                         long premiumPrice, Map<Integer, List<String>> free, Map<Integer, List<String>> premium) {

        public Instant startsAt() {
            return starts.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        public Instant endsAt() {
            return ends.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        public boolean running(Instant at) {
            return !at.isBefore(startsAt()) && at.isBefore(endsAt());
        }

        public int tierOf(long xp) {
            return (int) Math.min(tiers, xp / xpPerTier);
        }

        public List<String> free(int tier) {
            return free.getOrDefault(tier, List.of());
        }

        public List<String> premium(int tier) {
            return premium.getOrDefault(tier, List.of());
        }

        /** The form the repository grants from. */
        public ProgressRepository.Season forRepository() {
            return new ProgressRepository.Season(id, xpPerTier, tiers, this::free, this::premium);
        }
    }

    public static ProgressConfig load(ClassLoader loader, Catalog catalog) throws IOException {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("missing bundled resource " + RESOURCE);
            }
            return parse(ConfigView.load(in), catalog);
        }
    }

    public static ProgressConfig parse(ConfigView c, Catalog catalog) {
        List<String> problems = new ArrayList<>();
        NetworkLevels levels = null;
        try {
            levels = new NetworkLevels(c.integer("levels.base", 250), c.integer("levels.step", 50),
                    c.integer("levels.cap", 1500), c.integer("levels.max-level", 100));
        } catch (IllegalArgumentException e) {
            problems.add("levels: " + e.getMessage());
        }
        Map<Integer, List<String>> levelRewards = tiers(c.section("levels.rewards"), "levels.rewards", catalog,
                problems);

        Map<String, Long> events = new LinkedHashMap<>();
        ConfigView ev = c.section("xp.events");
        for (String k : ev.keys()) {
            int xp = ev.integer(k, 0);
            if (xp <= 0) {
                problems.add("xp.events." + k + ": must be positive");
            } else {
                events.put(k, (long) xp);
            }
        }

        List<Long> streak = new ArrayList<>();
        for (String s : c.stringList("streak.rewards")) {
            try {
                long v = Long.parseLong(s.trim());
                if (v < 0) {
                    throw new NumberFormatException();
                }
                streak.add(v);
            } catch (NumberFormatException e) {
                problems.add("streak.rewards: '" + s + "' isn't a whole number of Bolts");
            }
        }
        if (streak.isEmpty()) {
            problems.add("streak.rewards: needs at least one day");
        }

        Map<Rank, Long> stipend = new EnumMap<>(Rank.class);
        ConfigView st = c.section("stipend.amounts");
        for (String group : st.keys()) {
            try {
                Rank r = Rank.byGroup(group);
                stipend.put(r, (long) Math.max(0, st.integer(group, 0)));
            } catch (IllegalArgumentException e) {
                problems.add("stipend.amounts." + group + ": not a rank");
            }
        }

        List<Season> seasons = new ArrayList<>();
        if (c.get("pass.seasons") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    problems.add("pass.seasons: each season is a section");
                    continue;
                }
                Map<String, Object> map = new LinkedHashMap<>();
                m.forEach((k, v) -> map.put(String.valueOf(k), v));
                season(ConfigView.of(map), catalog, problems).ifPresent(seasons::add);
            }
        }
        seasons.sort(java.util.Comparator.comparing(Season::starts));
        for (int i = 1; i < seasons.size(); i++) {
            if (seasons.get(i).starts().isBefore(seasons.get(i - 1).ends())) {
                problems.add("pass.seasons: " + seasons.get(i - 1).id() + " and " + seasons.get(i).id() + " overlap");
            }
        }
        if (seasons.stream().map(Season::id).distinct().count() != seasons.size()) {
            problems.add("pass.seasons: ids must be unique");
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(RESOURCE + " is invalid:\n  " + String.join("\n  ", problems));
        }
        return new ProgressConfig(levels, Math.max(0, c.integer("levels.bolts-per-level", 25)), levelRewards,
                Math.max(0, c.integer("xp.playtime.amount", 10)),
                Math.max(0, c.integer("xp.playtime.daily-intervals", 12)), Collections.unmodifiableMap(events),
                List.copyOf(streak), Math.max(0, c.integer("streak.delay-seconds", 20)),
                Math.max(1, c.integer("stipend.every-days", 30)), Collections.unmodifiableMap(stipend),
                List.copyOf(seasons));
    }

    private static Optional<Season> season(ConfigView s, Catalog catalog, List<String> problems) {
        String id = s.string("id", "").trim();
        String where = "pass.seasons." + (id.isEmpty() ? "?" : id);
        if (!id.matches("[a-z0-9-]{1,32}")) {
            problems.add(where + ": id must be 1–32 of a-z, 0-9, -");
            return Optional.empty();
        }
        LocalDate starts;
        LocalDate ends;
        try {
            starts = LocalDate.parse(s.string("starts", ""));
            ends = LocalDate.parse(s.string("ends", ""));
        } catch (DateTimeParseException e) {
            problems.add(where + ": starts and ends are dates (\"2026-11-16\")");
            return Optional.empty();
        }
        if (!ends.isAfter(starts)) {
            problems.add(where + ": ends must be after starts");
        }
        int tiers = s.integer("tiers", 30);
        long perTier = s.integer("xp-per-tier", 500);
        long price = s.integer("premium-price", 0);
        if (tiers < 1 || tiers > 100 || perTier <= 0 || price <= 0) {
            problems.add(where + ": tiers 1–100, xp-per-tier and premium-price positive");
        }
        Map<Integer, List<String>> free = tiers(s.section("free"), where + ".free", catalog, problems);
        Map<Integer, List<String>> premium = tiers(s.section("premium"), where + ".premium", catalog, problems);
        for (int t : free.keySet()) {
            if (t > tiers) {
                problems.add(where + ".free." + t + ": past the last tier");
            }
        }
        for (int t : premium.keySet()) {
            if (t > tiers) {
                problems.add(where + ".premium." + t + ": past the last tier");
            }
        }
        return Optional.of(new Season(id, s.string("name", id), starts, ends, tiers, perTier, price, free, premium));
    }

    private static Map<Integer, List<String>> tiers(ConfigView section, String where, Catalog catalog,
                                                    List<String> problems) {
        Map<Integer, List<String>> out = new TreeMap<>();
        for (String key : section.keys()) {
            int n;
            try {
                n = Integer.parseInt(key.trim());
            } catch (NumberFormatException e) {
                problems.add(where + "." + key + ": keys are numbers");
                continue;
            }
            List<String> ids = section.stringList(key);
            for (String id : ids) {
                if (!catalog.exists(id)) {
                    problems.add(where + "." + key + ": no cosmetic '" + id + "' in " + Catalog.RESOURCE);
                }
            }
            out.put(n, List.copyOf(ids));
        }
        return Collections.unmodifiableMap(out);
    }

    /** The season running at {@code at}. */
    public Optional<Season> season(Instant at) {
        return seasons.stream().filter(s -> s.running(at)).findFirst();
    }

    /** The next season to start after {@code at}. */
    public Optional<Season> nextSeason(Instant at) {
        return seasons.stream().filter(s -> s.startsAt().isAfter(at)).findFirst();
    }

    public long stipend(Rank rank) {
        return stipend.getOrDefault(rank, 0L);
    }

    /** Bolts for day {@code streak} of a login streak (1-based). */
    public long streakReward(int streak) {
        return streakRewards.get(Math.clamp(streak, 1, streakRewards.size()) - 1);
    }

    /** The cosmetics a level unlocks. */
    public List<String> levelCosmetics(int level) {
        return levelRewards.getOrDefault(level, List.of());
    }

    /** The next level after {@code level} that unlocks a cosmetic. */
    public Optional<Integer> nextRewardLevel(int level) {
        return levelRewards.keySet().stream().filter(l -> l > level).findFirst();
    }
}
