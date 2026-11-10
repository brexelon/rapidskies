package us.rapidskies.skyrealms.season;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.SkyRealmsSettings;
import us.rapidskies.skyrealms.data.SkyRealmsRepository;
import us.rapidskies.skyrealms.data.SkyRealmsRepository.AwardRow;
import us.rapidskies.skyrealms.data.SkyRealmsRepository.Season;
import us.rapidskies.skyrealms.island.Islands.IslandRef;

/**
 * Seasons (PLAN.md §5.1, about three months): the island-value leaderboard ({@code /season})
 * ranks worth gained since the season began; islands keep everything. When a season ends,
 * every member of the top islands is recorded for the cosmetic of the best award their island
 * reached (for the cosmetics engine, like Horizons' Chapter rewards) and paid its Bolts once,
 * and the next season starts from every island's current worth.
 *
 * <p>Ending is one transaction (season closed and awards written, or neither); Bolts are paid
 * from the awards afterwards with a transaction id per player, and again on every start for
 * the last ended season, so a crash in between pays late but never twice.
 */
public final class SeasonService {

    private static final Duration STANDINGS_TTL = Duration.ofSeconds(30);

    private final RapidSkyRealms plugin;
    private volatile Season season;
    private final Map<UUID, BigDecimal> baselines = new ConcurrentHashMap<>();
    private List<SeasonRanking.Entry> standings = List.of();
    private Instant standingsAt = Instant.EPOCH;
    private boolean closing;
    private BukkitTask task;

    public SeasonService(RapidSkyRealms plugin) {
        this.plugin = plugin;
    }

    private SkyRealmsSettings.Seasons cfg() {
        return plugin.settings().seasons();
    }

    /**
     * Loads the open season, or opens the first one (blocking; while enabling, after
     * SuperiorSkyblock2 has loaded its islands). Pays any Bolts left owed from the last one.
     */
    public void start() throws SQLException {
        if (!cfg().enabled()) {
            return;
        }
        SkyRealmsRepository repo = plugin.repo();
        Optional<Season> open = repo.openSeason();
        int last = repo.lastSeasonId();
        if (open.isPresent()) {
            season = open.get();
            baselines.putAll(repo.baselines(season.id()));
        } else {
            Instant now = Instant.now();
            Season fresh = new Season(last + 1, now, now.plus(Duration.ofDays(cfg().lengthDays())));
            Map<UUID, BigDecimal> worths = plugin.islands().worths();
            repo.startSeason(fresh, worths);
            season = fresh;
            baselines.putAll(worths);
            plugin.getSLF4JLogger().info("Season {} started; it ends {}", fresh.id(), fresh.endsAt());
        }
        int ended = open.isPresent() ? season.id() - 1 : last;
        if (ended > 0) {
            payAwards(ended);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L * 60, 20L * 60);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    public Optional<Season> current() {
        return Optional.ofNullable(season);
    }

    /** The current standings, recomputed at most every 30 s. Any thread (PlaceholderAPI asks off it). */
    public synchronized List<SeasonRanking.Entry> standings() {
        if (Instant.now().isAfter(standingsAt.plus(STANDINGS_TTL))) {
            standings = SeasonRanking.rank(plugin.islands().worths(), baselines);
            standingsAt = Instant.now();
        }
        return standings;
    }

    /** A new island starts the season from nothing. */
    public void islandCreated(UUID island) {
        Season s = season;
        if (s == null) {
            return;
        }
        baselines.putIfAbsent(island, BigDecimal.ZERO);
        plugin.async(() -> {
            plugin.repo().addBaseline(s.id(), island, BigDecimal.ZERO);
            return null;
        }, v -> { });
    }

    private void check() {
        Season s = season;
        if (s != null && !closing && !Instant.now().isBefore(s.endsAt())) {
            close();
        }
    }

    /** Ends the season now (staff, or its end date). Main thread. */
    public boolean close() {
        Season s = season;
        if (s == null || closing) {
            return false;
        }
        closing = true;
        List<SeasonRanking.Entry> ranking = SeasonRanking.rank(plugin.islands().worths(), baselines);
        List<AwardRow> rows = new ArrayList<>();
        for (int i = 0; i < ranking.size(); i++) {
            int rank = i + 1;
            Optional<SkyRealmsSettings.Award> award = cfg().award(rank);
            if (award.isEmpty()) {
                break; // awards are sorted by reach; nothing further down gets one
            }
            SeasonRanking.Entry e = ranking.get(i);
            String name = plugin.islands().byId(e.island()).map(IslandRef::name).orElse("?");
            for (UUID member : plugin.islands().members(e.island())) {
                rows.add(new AwardRow(s.id(), member, e.island(), name, rank, e.gained(),
                        award.get().cosmeticFor(s.id()), award.get().bolts()));
            }
        }
        Instant now = Instant.now();
        Season next = new Season(s.id() + 1, now, now.plus(Duration.ofDays(cfg().lengthDays())));
        Map<UUID, BigDecimal> worths = plugin.islands().worths();
        // Closing and opening are separate steps: if opening failed last time, the next try
        // finds the season already closed and only opens the next one.
        plugin.async(() -> {
            boolean closed = plugin.repo().closeSeason(s.id(), now, rows);
            if (plugin.repo().openSeason().isEmpty()) {
                plugin.repo().startSeason(next, worths);
            }
            return closed;
        }, closed -> {
            closing = false;
            season = next;
            baselines.clear();
            baselines.putAll(worths);
            synchronized (this) {
                standingsAt = Instant.EPOCH;
            }
            if (closed) {
                announce(s, ranking);
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> payAwards(s.id()));
        }, null, () -> closing = false);
        return true;
    }

    private void announce(Season s, List<SeasonRanking.Entry> ranking) {
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            SeasonRanking.Entry e = ranking.get(i);
            lines.add(plugin.lang().get("season.winner-line", Placeholder.unparsed("rank", Integer.toString(i + 1)),
                    Placeholder.unparsed("island", plugin.islands().byId(e.island()).map(IslandRef::name).orElse("?")),
                    Placeholder.unparsed("gained", worth(e.gained()))));
        }
        plugin.announcer().network(plugin.lang().get("season.ended", Placeholder.unparsed("season",
                Integer.toString(s.id())), Placeholder.component("winners",
                Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines))));
    }

    /** Pays a season's award Bolts (blocking). The ledger's transaction ids make it once per player. */
    private void payAwards(int seasonId) {
        try {
            Ledger bolts = plugin.core().bolts();
            for (AwardRow a : plugin.repo().awards(seasonId)) {
                if (a.bolts() > 0) {
                    bolts.credit(a.player(), a.bolts(), "season", plugin.core().settings().serverId(),
                            "season:" + seasonId + ":" + a.player());
                }
            }
        } catch (SQLException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't pay season {} Bolts yet (retried on the next start): {}", seasonId,
                    e.getMessage());
        }
    }

    /** The season line for {@code /season}: number and time left. */
    public void sendStandings(Player player) {
        Season s = season;
        if (s == null) {
            plugin.lang().send(player, "season.disabled");
            return;
        }
        Duration left = Duration.between(Instant.now(), s.endsAt());
        plugin.lang().send(player, "season.header", Placeholder.unparsed("season", Integer.toString(s.id())),
                Placeholder.unparsed("days", Long.toString(Math.max(0, left.toDays()))));
        List<SeasonRanking.Entry> ranking = standings();
        for (int i = 0; i < Math.min(10, ranking.size()); i++) {
            SeasonRanking.Entry e = ranking.get(i);
            plugin.lang().send(player, "season.line", Placeholder.unparsed("rank", Integer.toString(i + 1)),
                    Placeholder.unparsed("island", plugin.islands().byId(e.island()).map(IslandRef::name).orElse("?")),
                    Placeholder.unparsed("gained", worth(e.gained())));
        }
        if (ranking.isEmpty()) {
            plugin.lang().send(player, "season.empty");
        }
        plugin.islands().of(player).ifPresent(i -> {
            int rank = SeasonRanking.rankOf(ranking, i.id());
            BigDecimal gained = ranking.stream().filter(e -> e.island().equals(i.id())).findFirst()
                    .map(SeasonRanking.Entry::gained).orElse(BigDecimal.ZERO);
            plugin.lang().send(player, rank > 0 ? "season.yours" : "season.yours-unranked",
                    Placeholder.unparsed("rank", Integer.toString(rank)), Placeholder.unparsed("gained", worth(gained)));
        });
    }

    static String worth(BigDecimal value) {
        return String.format("%,d", value.setScale(0, RoundingMode.DOWN).longValue());
    }
}
