package us.rapidskies.core.quest;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.quest.QuestPeriods;
import us.rapidskies.common.quest.QuestRepository;
import us.rapidskies.common.quest.QuestRepository.Assigned;
import us.rapidskies.common.quest.QuestRepository.Row;
import us.rapidskies.core.afk.AfkTracker;
import us.rapidskies.core.cosmetic.CosmeticService;
import us.rapidskies.core.economy.BoltsCache;
import us.rapidskies.core.profile.ProfileService;
import us.rapidskies.core.progress.LevelService;
import us.rapidskies.core.quest.QuestConfig.Achievement;
import us.rapidskies.core.quest.QuestConfig.Quest;
import us.rapidskies.core.reward.EarnGuard;

/**
 * Daily and weekly quests and achievements (PLAN.md §7).
 *
 * <p>Progress is counted in memory as it happens ({@link #record}) and written as increments
 * every {@link #FLUSH_SECONDS} seconds, when a quest looks done, and when the player leaves.
 * The database decides completion, so a quest finished during a server switch still pays
 * exactly once. Every objective also adds to the player's lifetime stats, which achievements
 * are measured against (retroactively too: an achievement added later is awarded on the next
 * join to players already past it).
 *
 * <p>Nothing counts while the player is AFK. Quest and achievement Bolts follow the anti-farm
 * rules (none for accounts sharing an IP); XP goes through {@link LevelService}.
 *
 * <p>All state is touched on the main thread; database work runs off it.
 */
public final class QuestService implements Listener {

    private static final long FLUSH_SECONDS = 30;
    private static final long ROLLOVER_CHECK_SECONDS = 60;

    private final Plugin plugin;
    private final Database db;
    private final QuestRepository repository;
    private final QuestConfig config;
    private final String serverMode;
    private final ProfileService profiles;
    private final LevelService levels;
    private final CosmeticService cosmetics;
    private final EarnGuard earning;
    private final AfkTracker afk;
    private final BoltsCache boltsCache;
    private final Lang lang;
    // Concurrent: placeholders (%rapid_quests%) may read it off the main thread; writes stay on it.
    private final Map<UUID, State> states = new java.util.concurrent.ConcurrentHashMap<>();
    private BukkitTask flushTask;
    private BukkitTask rolloverTask;

    private record Key(String period, int slot) {
    }

    /** One online player's quests. */
    private static final class State {
        final LocalDate firstDay;
        String day;
        String week;
        volatile List<Row> rows = List.of();
        final Map<Key, Long> pending = new HashMap<>();
        final Map<String, Long> pendingStats = new HashMap<>();
        final Map<String, Long> stats = new HashMap<>();
        final Set<String> achieved = new HashSet<>();
        boolean flushing;
        boolean flushSoon;
        /** Whether quest Bolts may be paid (no shared IP), as of the last flush while online. */
        boolean mayPay = true;
        int retries;

        State(LocalDate firstDay) {
            this.firstDay = firstDay;
        }
    }

    /** A quest as the menu shows it. */
    public record View(Row row, Optional<Quest> quest, long progress, Instant closes) {
        public boolean done() {
            return row.completed();
        }
    }

    public QuestService(Plugin plugin, Database db, QuestConfig config, String serverMode, ProfileService profiles,
                        LevelService levels, CosmeticService cosmetics, EarnGuard earning, AfkTracker afk,
                        BoltsCache boltsCache, Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.repository = new QuestRepository(db);
        this.config = config;
        this.serverMode = serverMode;
        this.profiles = profiles;
        this.levels = levels;
        this.cosmetics = cosmetics;
        this.earning = earning;
        this.afk = afk;
        this.boltsCache = boltsCache;
        this.lang = lang;
    }

    public QuestConfig config() {
        return config;
    }

    public void start() {
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushAll, FLUSH_SECONDS * 20, FLUSH_SECONDS * 20);
        rolloverTask = Bukkit.getScheduler().runTaskTimer(plugin, this::rollover, ROLLOVER_CHECK_SECONDS * 20,
                ROLLOVER_CHECK_SECONDS * 20);
        for (Player p : Bukkit.getOnlinePlayers()) {
            load(p);
        }
    }

    /** Writes everything still counted in memory, blocking (plugin disable). */
    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        if (rolloverTask != null) {
            rolloverTask.cancel();
        }
        for (Map.Entry<UUID, State> e : states.entrySet()) {
            State s = e.getValue();
            if (s.pending.isEmpty() && s.pendingStats.isEmpty()) {
                continue;
            }
            try {
                repository.flush(e.getKey(), steps(s.pending), Map.copyOf(s.pendingStats));
            } catch (SQLException ex) {
                plugin.getSLF4JLogger().warn("Couldn't save quest progress of {} at shutdown: {}", e.getKey(),
                        ex.getMessage());
            }
        }
        states.clear();
    }

    // ------------------------------------------------------------------ counting

    /**
     * Counts {@code amount} of an objective for {@code player}: the gamemode plugins' entry
     * point ({@code parkour}, {@code expedition}, {@code shop-sell}, {@code skyborne},
     * {@code ah-buy}). Main thread.
     */
    public void record(Player player, String objective, long amount) {
        record(player, objective, amount, null, null);
    }

    void record(Player player, String objective, long amount, org.bukkit.Material block,
                org.bukkit.entity.EntityType mob) {
        if (amount <= 0 || afk.isAfk(player)) {
            return;
        }
        State s = states.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        s.pendingStats.merge(objective, amount, Long::sum);
        for (Row row : s.rows) {
            if (row.completed()) {
                continue;
            }
            Optional<Quest> quest = quest(row);
            if (quest.isEmpty() || !quest.get().countsOn(serverMode) || !quest.get().matches(objective, block, mob)) {
                continue;
            }
            Key key = new Key(row.period(), row.slot());
            long pending = s.pending.merge(key, amount, Long::sum);
            if (row.progress() + pending >= row.target() && !s.flushSoon) {
                s.flushSoon = true;
                Bukkit.getScheduler().runTaskLater(plugin, () -> flush(player.getUniqueId()), 5);
            }
        }
    }

    private Optional<Quest> quest(Row row) {
        return Optional.ofNullable(config.pool(row.kind()).get(row.quest()));
    }

    // ------------------------------------------------------------------ reading (main thread)

    /** The player's open quests: weeklies first, then dailies newest first. */
    public List<View> quests(Player player) {
        State s = states.get(player.getUniqueId());
        if (s == null) {
            return List.of();
        }
        List<View> out = new ArrayList<>();
        Instant now = Instant.now();
        for (Row row : s.rows) {
            long progress = Math.min(row.target(), row.progress() + s.pending.getOrDefault(
                    new Key(row.period(), row.slot()), 0L));
            Instant closes = QuestPeriods.isWeek(row.period()) ? QuestPeriods.weekEnds(now)
                    : QuestPeriods.dayCloses(row.period(), config.bankDays());
            out.add(new View(row, quest(row), progress, closes));
        }
        out.sort(java.util.Comparator.comparing((View v) -> !QuestPeriods.isWeek(v.row().period()))
                .thenComparing(v -> v.row().period(), java.util.Comparator.reverseOrder())
                .thenComparing(v -> v.row().slot()));
        return out;
    }

    public boolean loaded(Player player) {
        return states.containsKey(player.getUniqueId());
    }

    /** A lifetime stat, with what's counted but not yet written. */
    public long stat(Player player, String stat) {
        State s = states.get(player.getUniqueId());
        return s == null ? 0 : s.stats.getOrDefault(stat, 0L) + s.pendingStats.getOrDefault(stat, 0L);
    }

    public boolean achieved(Player player, String achievement) {
        State s = states.get(player.getUniqueId());
        return s != null && s.achieved.contains(achievement);
    }

    /** Open quests not yet done, for the sidebar and placeholders. */
    public int openCount(Player player) {
        State s = states.get(player.getUniqueId());
        return s == null ? 0 : (int) s.rows.stream().filter(r -> !r.completed()).count();
    }

    // ------------------------------------------------------------------ loading and rollover

    private void load(Player player) {
        UUID id = player.getUniqueId();
        LocalDate firstDay = profiles.get(id).map(PlayerProfile::firstJoin)
                .map(t -> LocalDate.ofInstant(t, ZoneOffset.UTC)).orElse(LocalDate.now(ZoneOffset.UTC));
        State existing = states.get(id);
        State s = existing != null ? existing : new State(firstDay);
        states.put(id, s);
        Instant now = Instant.now();
        String day = QuestPeriods.day(now);
        String week = QuestPeriods.week(now);
        List<String> days = QuestPeriods.openDays(now, config.bankDays(), s.firstDay);
        db.async(() -> {
            for (String d : days) {
                assign(id, d, "daily");
            }
            assign(id, week, "weekly");
            List<String> periods = new ArrayList<>(days);
            periods.add(week);
            List<Row> rows = repository.quests(id, periods);
            Map<String, Long> stats = repository.stats(id);
            Set<String> achieved = repository.achievements(id).keySet();
            return new Loaded(rows, stats, achieved);
        }).whenComplete((loaded, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("Couldn't load quests of {}: {}", player.getName(), e.getMessage());
                return;
            }
            if (states.get(id) != s) {
                return; // left meanwhile
            }
            s.day = day;
            s.week = week;
            s.rows = loaded.rows();
            s.stats.clear();
            s.stats.putAll(loaded.stats());
            s.achieved.clear();
            s.achieved.addAll(loaded.achieved());
            checkAchievements(player, s, s.stats);
        }));
    }

    private record Loaded(List<Row> rows, Map<String, Long> stats, Set<String> achieved) {
    }

    /** Fills a period's empty slots (blocking). Slots already filled keep their quest. */
    private void assign(UUID player, String period, String kind) throws SQLException {
        List<String> slots = config.slots(kind);
        Map<String, Quest> pool = config.pool(kind);
        Set<String> taken = new HashSet<>();
        List<Assigned> picks = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            String slotMode = slots.get(slot);
            List<String> fitting = pool.values().stream().filter(q -> q.fits(slotMode)).map(Quest::id).toList();
            Optional<String> pick = QuestPeriods.pick(fitting, taken, player, period, slot);
            if (pick.isPresent()) {
                taken.add(pick.get());
                picks.add(new Assigned(slot, pick.get(), pool.get(pick.get()).target()));
            }
        }
        repository.assign(player, period, kind, picks);
    }

    private void rollover() {
        Instant now = Instant.now();
        String day = QuestPeriods.day(now);
        String week = QuestPeriods.week(now);
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s != null && s.day != null && (!day.equals(s.day) || !week.equals(s.week))) {
                flush(p.getUniqueId());
                load(p);
                lang.send(p, "quests.new-day");
            }
        }
    }

    // ------------------------------------------------------------------ writing

    private void flushAll() {
        for (UUID id : List.copyOf(states.keySet())) {
            flush(id);
        }
    }

    private static List<QuestRepository.Step> steps(Map<Key, Long> pending) {
        List<QuestRepository.Step> out = new ArrayList<>();
        pending.forEach((k, v) -> out.add(new QuestRepository.Step(k.period(), k.slot(), v)));
        return out;
    }

    private void flush(UUID id) {
        State s = states.get(id);
        if (s != null) {
            flush(id, s);
        }
    }

    private void flush(UUID id, State s) {
        if (s.flushing || (s.pending.isEmpty() && s.pendingStats.isEmpty())) {
            s.flushSoon = false;
            return;
        }
        s.flushing = true;
        s.flushSoon = false;
        Map<Key, Long> pending = new LinkedHashMap<>(s.pending);
        Map<String, Long> stats = new HashMap<>(s.pendingStats);
        s.pending.clear();
        s.pendingStats.clear();
        Player player = Bukkit.getPlayer(id);
        boolean mayPay = player != null ? !earning.sharesIp(player) : s.mayPay;
        s.mayPay = mayPay;
        db.async(() -> repository.flush(id, steps(pending), stats)).whenComplete((done, e) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    s.flushing = false;
                    boolean gone = states.get(id) != s;
                    if (e != null) {
                        plugin.getSLF4JLogger().warn("Couldn't save quest progress of {}: {}", id, e.getMessage());
                        // Put it back for the next try; a player who left gets a few more tries.
                        pending.forEach((k, v) -> s.pending.merge(k, v, Long::sum));
                        stats.forEach((k, v) -> s.pendingStats.merge(k, v, Long::sum));
                        if (gone && ++s.retries <= 3) {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> flush(id, s), FLUSH_SECONDS * 20);
                        } else if (gone) {
                            plugin.getSLF4JLogger().warn("Gave up saving quest progress of {} who left", id);
                        }
                        return;
                    }
                    applyFlush(id, s, done, mayPay);
                    if (gone) {
                        // Counted while this flush was running, or a finished-quest stat: write it too.
                        flush(id, s);
                    }
                }));
    }

    private void applyFlush(UUID id, State s, QuestRepository.Flushed done, boolean mayPay) {
        Map<Key, QuestRepository.Advanced> byKey = new HashMap<>();
        for (QuestRepository.Advanced a : done.quests()) {
            byKey.put(new Key(a.period(), a.slot()), a);
        }
        List<Row> rows = new ArrayList<>();
        Instant now = Instant.now();
        for (Row r : s.rows) {
            QuestRepository.Advanced a = byKey.get(new Key(r.period(), r.slot()));
            if (a == null) {
                rows.add(r);
                continue;
            }
            Row updated = new Row(r.period(), r.slot(), r.kind(), r.quest(), a.progress(), r.target(),
                    a.justCompleted() ? now : r.completedAt());
            rows.add(updated);
            if (a.justCompleted()) {
                completed(id, updated, mayPay);
                s.pendingStats.merge(QuestConfig.QUESTS_STAT, 1L, Long::sum);
            }
        }
        s.rows = List.copyOf(rows);
        s.stats.putAll(done.stats());
        Player player = Bukkit.getPlayer(id);
        if (player != null && states.get(id) == s) {
            checkAchievements(player, s, done.stats());
        }
    }

    private void completed(UUID id, Row row, boolean mayPay) {
        QuestConfig.Reward reward = config.reward(row.kind());
        String name = quest(row).map(Quest::name).orElse(row.quest());
        if (reward.bolts() > 0 && mayPay) {
            earning.pay(id, reward.bolts(), "quest", 0, "quest:" + id + ":" + row.period() + ":" + row.slot())
                    .thenAccept(paid -> {
                        if (paid > 0) {
                            boltsCache.refresh(id);
                        }
                    });
        }
        if (reward.xp() > 0) {
            levels.add(id, reward.xp(), "quest");
        }
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            lang.send(player, row.kind().equals("weekly") ? "quests.completed-weekly" : "quests.completed",
                    Placeholder.unparsed("name", name),
                    Placeholder.unparsed("bolts", Long.toString(mayPay ? reward.bolts() : 0)),
                    Placeholder.unparsed("xp", Long.toString(reward.xp())));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        }
    }

    /** Completes every achievement the totals have reached that the player doesn't have yet. */
    private void checkAchievements(Player player, State s, Map<String, Long> totals) {
        List<Achievement> reached = new ArrayList<>();
        for (Achievement a : config.achievements().values()) {
            Long total = totals.get(a.stat());
            if (total != null && total >= a.target() && !s.achieved.contains(a.id())) {
                reached.add(a);
                s.achieved.add(a.id()); // optimistic; the database has the last word
            }
        }
        if (reached.isEmpty()) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean mayPay = !earning.sharesIp(player);
        db.async(() -> {
            List<Achievement> fresh = new ArrayList<>();
            for (Achievement a : reached) {
                if (repository.completeAchievement(id, a.id())) {
                    fresh.add(a);
                }
            }
            return fresh;
        }).whenComplete((fresh, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("Couldn't record achievements of {}: {}", id, e.getMessage());
                reached.forEach(a -> s.achieved.remove(a.id()));
                return;
            }
            for (Achievement a : fresh) {
                achieved(id, a, mayPay);
            }
        }));
    }

    private void achieved(UUID id, Achievement a, boolean mayPay) {
        if (a.bolts() > 0 && mayPay) {
            earning.pay(id, a.bolts(), "achievement", 0, "achievement:" + id + ":" + a.id())
                    .thenAccept(paid -> boltsCache.refresh(id));
        }
        if (a.xp() > 0) {
            levels.add(id, a.xp(), "achievement");
        }
        if (a.cosmetic() != null) {
            cosmetics.grant(id, a.cosmetic(), "achievement:" + a.id());
        }
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            lang.send(player, "achievements.unlocked", Placeholder.unparsed("name", a.name()),
                    Placeholder.unparsed("description", a.description()),
                    Placeholder.unparsed("bolts", Long.toString(mayPay ? a.bolts() : 0)),
                    Placeholder.unparsed("xp", Long.toString(a.xp())));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        State s = states.remove(id);
        if (s != null) {
            // A flush already running writes whatever was counted after it once it's back.
            flush(id, s);
        }
    }
}
