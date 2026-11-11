package us.rapidskies.core.reward;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.reward.LoginStreaks;
import us.rapidskies.core.economy.BoltsCache;
import us.rapidskies.core.progress.ProgressConfig;
import us.rapidskies.core.rank.RankDisplay;

/**
 * Bolts that come from showing up (PLAN.md §6.1, §6.2):
 *
 * <ul>
 *   <li><b>Login streak:</b> the first login of each UTC day pays by how many days in a row
 *       (5 → 25 by day 7). Counted a few seconds after joining, once the anti-farm flags are
 *       loaded, on whichever server the player is on; the day's transaction id makes it pay
 *       once however many servers they visit.</li>
 *   <li><b>Rank stipend:</b> a player with a rank who hasn't had a stipend in the last
 *       {@code every-days} days gets it (100 / 250 / 600); an upgrade inside that time tops
 *       it up to the new amount. Checked on join and every half hour.</li>
 * </ul>
 */
public final class DailyRewards implements Listener {

    private static final long STIPEND_CHECK_TICKS = 30 * 60 * 20;

    private final Plugin plugin;
    private final Database db;
    private final Ledger bolts;
    private final LoginStreaks streaks;
    private final EarnGuard earning;
    private final RankDisplay ranks;
    private final BoltsCache boltsCache;
    private final ProgressConfig config;
    private final String serverId;
    private final Lang lang;
    private final Map<UUID, Integer> streakShown = new ConcurrentHashMap<>();
    private BukkitTask stipendTask;

    public DailyRewards(Plugin plugin, Database db, Ledger bolts, EarnGuard earning, RankDisplay ranks,
                        BoltsCache boltsCache, ProgressConfig config, String serverId, Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.bolts = bolts;
        this.streaks = new LoginStreaks(db);
        this.earning = earning;
        this.ranks = ranks;
        this.boltsCache = boltsCache;
        this.config = config;
        this.serverId = serverId;
        this.lang = lang;
    }

    public void start() {
        stipendTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::stipend),
                STIPEND_CHECK_TICKS, STIPEND_CHECK_TICKS);
    }

    public void stop() {
        if (stipendTask != null) {
            stipendTask.cancel();
        }
    }

    /** The login streak as last counted for an online player (0 until then). */
    public int streak(Player player) {
        return streakShown.getOrDefault(player.getUniqueId(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                countStreak(player);
                stipend(player);
            }
        }, Math.max(1, config.streakDelaySeconds()) * 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        streakShown.remove(event.getPlayer().getUniqueId());
    }

    private void countStreak(Player player) {
        UUID id = player.getUniqueId();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        db.async(() -> streaks.record(id, today)).whenComplete((day, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("Couldn't count the login streak of {}: {}", player.getName(),
                        e.getMessage());
                return;
            }
            if (day.isEmpty() || !player.isOnline()) {
                return;
            }
            int streak = day.get().streak();
            streakShown.put(id, streak);
            long amount = config.streakReward(streak);
            if (amount <= 0) {
                return;
            }
            // Tried on every join; the day's transaction id pays it once.
            earning.award(player, amount, "streak", 0, "streak:" + id + ":" + today).thenAccept(paid -> {
                if (paid > 0 && player.isOnline()) {
                    lang.send(player, "streak.paid", Placeholder.unparsed("amount", Long.toString(paid)),
                            Placeholder.unparsed("days", Integer.toString(streak)),
                            Placeholder.unparsed("tomorrow", Long.toString(config.streakReward(streak + 1))));
                    boltsCache.refresh(id);
                }
            });
        }));
    }

    private void stipend(Player player) {
        Rank rank = ranks.rank(player);
        long amount = config.stipend(rank);
        if (amount <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        Instant since = Instant.now().minus(Duration.ofDays(config.stipendEveryDays()));
        String txn = "stipend:" + id + ":" + rank.group() + ":" + LocalDate.now(ZoneOffset.UTC);
        db.async(() -> {
            long owed = amount - bolts.earnedSince(id, "stipend", since);
            if (owed <= 0) {
                return 0L;
            }
            return bolts.credit(id, owed, "stipend", serverId, txn) instanceof Ledger.Result.Applied ? owed : 0L;
        }).whenComplete((paid, e) -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("Couldn't pay the stipend of {}: {}", player.getName(), e.getMessage());
                return;
            }
            if (paid > 0 && player.isOnline()) {
                lang.send(player, "stipend.paid", Placeholder.unparsed("amount", Long.toString(paid)),
                        Placeholder.component("rank", ranks.rankPrefix(rank, ranks.of(player).plusColor())),
                        Placeholder.unparsed("days", Integer.toString(config.stipendEveryDays())));
                boltsCache.refresh(id);
            }
        });
    }
}
