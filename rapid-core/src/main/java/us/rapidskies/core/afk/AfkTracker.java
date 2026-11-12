package us.rapidskies.core.afk;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.CoreSettings;

/**
 * AFK detection (PLAN.md §6.2 anti-farm): a player is AFK after
 * {@link CoreSettings.Afk#idleSeconds} without turning their view, chatting or running a
 * command. Playtime Bolts and Tailwind spending pause while a player is AFK.
 *
 * <p>Only deliberate input counts. Walking, clicking and attacking don't: water streams,
 * pistons and minecarts move AFK players around, and auto-clickers at mob grinders click
 * forever, but neither turns the player's view.
 */
public final class AfkTracker implements Listener {

    /** A turn smaller than this (degrees) is noise, not a player at the mouse. */
    static final float MIN_TURN = 1.0f;
    private static final long CHECK_TICKS = 20;

    private static final class State {
        volatile long lastActive = System.currentTimeMillis();
        /** Main thread only. */
        boolean afk;
        /** Set by /afk: AFK right now, regardless of the timer. */
        volatile boolean manual;
    }

    private final Plugin plugin;
    private final CoreSettings.Afk settings;
    private final Lang lang;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AfkTracker(Plugin plugin, CoreSettings.Afk settings, Lang lang) {
        this.plugin = plugin;
        this.settings = settings;
        this.lang = lang;
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(p -> states.put(p.getUniqueId(), new State()));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, CHECK_TICKS, CHECK_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    /** Whether the player is AFK. Unknown (offline) players count as AFK. */
    public boolean isAfk(UUID player) {
        State s = states.get(player);
        return s == null || s.afk || s.manual;
    }

    public boolean isAfk(Player player) {
        return isAfk(player.getUniqueId());
    }

    /** When the player last turned their view, chatted or ran a command (epoch millis), or now if unknown. */
    public long lastActive(UUID player) {
        State s = states.get(player);
        return s == null ? System.currentTimeMillis() : s.lastActive;
    }

    /** Marks a player AFK now ({@code /afk}); any deliberate input brings them back. */
    public void setAfk(Player player) {
        State s = states.get(player.getUniqueId());
        if (s != null && !s.afk) {
            s.manual = true;
            change(player, s, true);
        }
    }

    // ------------------------------------------------------------------ activity

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        states.put(event.getPlayer().getUniqueId(), new State());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (turned(event.getFrom(), event.getTo())) {
            active(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // /afk itself must not count as coming back.
        String cmd = event.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (!cmd.equals("/afk") && !cmd.startsWith("/afk ")) {
            active(event.getPlayer());
        }
    }

    static boolean turned(Location from, Location to) {
        return Math.abs(angle(from.getYaw() - to.getYaw())) >= MIN_TURN
                || Math.abs(from.getPitch() - to.getPitch()) >= MIN_TURN;
    }

    /** Wraps a yaw difference into -180..180, so 359° → 1° is a 2° turn. */
    private static float angle(float degrees) {
        float a = degrees % 360f;
        if (a > 180f) {
            a -= 360f;
        } else if (a < -180f) {
            a += 360f;
        }
        return a;
    }

    /** Any thread. Coming back is announced on the main thread. */
    private void active(Player player) {
        State s = states.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        s.lastActive = System.currentTimeMillis();
        s.manual = false;
        if (Bukkit.isPrimaryThread()) {
            if (s.afk) {
                change(player, s, false);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (s.afk && !s.manual && player.isOnline()) {
                    change(player, s, false);
                }
            });
        }
    }

    private void check() {
        long cutoff = System.currentTimeMillis() - settings.idleSeconds() * 1000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s != null && !s.afk && s.lastActive < cutoff) {
                change(p, s, true);
            }
        }
    }

    private void change(Player player, State s, boolean afk) {
        s.afk = afk;
        lang.send(player, afk ? "afk.now-afk" : "afk.back");
        Bukkit.getPluginManager().callEvent(new PlayerAfkChangeEvent(player, afk));
    }
}
