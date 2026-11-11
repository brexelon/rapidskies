package us.rapidskies.core.reward;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.core.CoreSettings;

/**
 * Bolts for active play (PLAN.md §6.2: 2 every 15 minutes, at most 20 a day). Active
 * minutes are counted in Redis per player per UTC day, so progress carries across server
 * switches. Each finished interval pays once: its transaction id is the interval's number.
 */
public final class PlaytimeRewards {

    static final String REASON = "playtime";
    private static final long TICK_SECONDS = 60;
    private static final long COUNTER_TTL_SECONDS = 2 * 24 * 3600;

    private final Plugin plugin;
    private final EarnGuard guard;
    private final RedisBus redis;
    private final CoreSettings.Playtime settings;
    private final Lang lang;
    private BukkitTask task;
    private java.util.function.Consumer<Player> onActiveMinute = p -> { };
    private java.util.function.BiConsumer<UUID, Long> onInterval = (id, n) -> { };

    public PlaytimeRewards(Plugin plugin, EarnGuard guard, RedisBus redis, CoreSettings.Playtime settings, Lang lang) {
        this.plugin = plugin;
        this.guard = guard;
        this.redis = redis;
        this.settings = settings;
        this.lang = lang;
    }

    /** Called on the main thread for every player who earned an active minute (quests' play-minutes). */
    public void onActiveMinute(java.util.function.Consumer<Player> hook) {
        this.onActiveMinute = hook;
    }

    /**
     * Called off the main thread with the day's interval number each time a player completes
     * an interval of active play, whether or not the Bolts cap was reached (playtime XP).
     */
    public void onInterval(java.util.function.BiConsumer<UUID, Long> hook) {
        this.onInterval = hook;
    }

    public void start() {
        if (settings.enabled()) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_SECONDS * 20, TICK_SECONDS * 20);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private void tick() {
        List<Player> earning = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (guard.mayEarn(p)) {
                earning.add(p);
            }
        }
        if (earning.isEmpty()) {
            return;
        }
        for (Player p : earning) {
            try {
                onActiveMinute.accept(p);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Active-minute hook failed for {}: {}", p.getName(), e.toString());
            }
        }
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        long period = settings.everyMinutes() * 60L;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Player p : earning) {
                UUID id = p.getUniqueId();
                long active;
                try {
                    active = redis.incrementBy("playtime:" + day + ":" + id, TICK_SECONDS, COUNTER_TTL_SECONDS);
                } catch (RuntimeException e) {
                    plugin.getSLF4JLogger().warn("Couldn't count playtime: {}", e.getMessage());
                    return;
                }
                long interval = completedInterval(active, TICK_SECONDS, period);
                if (interval > 0) {
                    try {
                        onInterval.accept(id, interval);
                    } catch (RuntimeException e) {
                        plugin.getSLF4JLogger().warn("Playtime interval hook failed: {}", e.toString());
                    }
                    guard.pay(id, settings.amount(), REASON, settings.dailyCap(),
                                    REASON + ":" + id + ":" + day + ":" + interval)
                            .thenAccept(paid -> {
                                if (paid > 0 && p.isOnline()) {
                                    lang.send(p, "rewards.playtime", Placeholder.unparsed("amount", Long.toString(paid)),
                                            Placeholder.unparsed("minutes", Long.toString(settings.everyMinutes())));
                                }
                            })
                            .exceptionally(e -> {
                                plugin.getSLF4JLogger().warn("Couldn't pay playtime Bolts to {}: {}", p.getName(),
                                        e.getMessage());
                                return null;
                            });
                }
            }
        });
    }

    /**
     * The number of the interval that {@code step} more seconds just completed, or 0 if this
     * step didn't finish one. With 900 s intervals, going from 840 to 900 completes interval 1.
     */
    static long completedInterval(long total, long step, long period) {
        long before = (total - step) / period;
        long after = total / period;
        return after > before ? after : 0;
    }
}
