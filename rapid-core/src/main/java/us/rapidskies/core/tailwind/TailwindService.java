package us.rapidskies.core.tailwind;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
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
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.tailwind.TailwindBank;
import us.rapidskies.core.CoreSettings;
import us.rapidskies.core.afk.AfkTracker;
import us.rapidskies.core.profile.ProfileService;

/**
 * Tailwind on this server (PLAN.md §5.3). The bank is loaded with the profile (the proxy
 * has already added the time away). On a gamemode server it drains while the player is
 * playing and not AFK, and while it lasts {@link #multiplier} boosts mode XP and Trade
 * income; the mode plugins apply it. Other servers only show the bank.
 *
 * <p>Spending is counted in memory each second and written in batches, so a crash loses at
 * most a minute of spending, in the player's favour.
 */
public final class TailwindService implements Listener {

    private static final long TICK_SECONDS = 1;
    private static final long FLUSH_SECONDS = 60;

    private final Plugin plugin;
    private final TailwindBank bank;
    private final ProfileService profiles;
    private final AfkTracker afk;
    private final Lang lang;
    private final boolean spends;
    private final int bonusPercent;
    /** Seconds left, for online players. */
    private final Map<UUID, Long> remaining = new ConcurrentHashMap<>();
    /** Spent but not yet written. */
    private final Map<UUID, Long> unflushed = new ConcurrentHashMap<>();
    private BukkitTask tick;
    private BukkitTask flush;

    public TailwindService(Plugin plugin, Database db, ProfileService profiles, AfkTracker afk,
                           CoreSettings settings, Lang lang) {
        this.plugin = plugin;
        this.bank = new TailwindBank(db);
        this.profiles = profiles;
        this.afk = afk;
        this.lang = lang;
        this.spends = settings.gamemode().isPresent();
        this.bonusPercent = settings.tailwind().bonusPercent();
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::load);
        if (spends) {
            tick = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_SECONDS * 20, TICK_SECONDS * 20);
            flush = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll,
                    FLUSH_SECONDS * 20, FLUSH_SECONDS * 20);
        }
    }

    /** Stops spending and writes what's left. Blocks: for plugin shutdown only. */
    public void stop() {
        if (tick != null) {
            tick.cancel();
        }
        if (flush != null) {
            flush.cancel();
        }
        flushAll();
    }

    // ------------------------------------------------------------------ API for the mode plugins

    /** Seconds of Tailwind the player has left. */
    public long remainingSeconds(Player player) {
        return remaining.getOrDefault(player.getUniqueId(), 0L);
    }

    /** Whether the bonus applies right now: a gamemode server, Tailwind left, not AFK. */
    public boolean active(Player player) {
        return spends && remainingSeconds(player) > 0 && !afk.isAfk(player);
    }

    /** What to multiply mode XP and Trade income by: 1.5 while Tailwind is active, else 1. */
    public double multiplier(Player player) {
        return active(player) ? 1.0 + bonusPercent / 100.0 : 1.0;
    }

    public int bonusPercent() {
        return bonusPercent;
    }

    /** Whether this server spends Tailwind (it's a gamemode server). */
    public boolean spendsHere() {
        return spends;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
        long left = remainingSeconds(event.getPlayer());
        if (spends && left > 0) {
            lang.send(event.getPlayer(), "tailwind.join", Placeholder.unparsed("time", format(left)),
                    Placeholder.unparsed("bonus", Integer.toString(bonusPercent)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        remaining.remove(id);
        Long spent = unflushed.remove(id);
        if (spent != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(id, spent));
        }
    }

    private void load(Player player) {
        long banked = profiles.get(player.getUniqueId()).map(PlayerProfile::tailwindBankedSeconds).orElse(0L);
        remaining.put(player.getUniqueId(), banked);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            long left = remaining.getOrDefault(id, 0L);
            if (left <= 0 || afk.isAfk(p)) {
                continue;
            }
            long spend = Math.min(left, TICK_SECONDS);
            remaining.put(id, left - spend);
            unflushed.merge(id, spend, Long::sum);
            if (left - spend == 0) {
                lang.send(p, "tailwind.depleted");
            }
        }
    }

    private void flushAll() {
        Map<UUID, Long> batch = new HashMap<>();
        for (UUID id : unflushed.keySet()) {
            Long spent = unflushed.remove(id);
            if (spent != null) {
                batch.put(id, spent);
            }
        }
        batch.forEach(this::write);
    }

    private void write(UUID player, long seconds) {
        try {
            bank.spend(player, seconds);
        } catch (SQLException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't record {}s of Tailwind spent by {}: {}", seconds, player,
                    e.getMessage());
        }
    }

    /** "2h 15m", "45m", "30s". */
    public static String format(long seconds) {
        Duration d = Duration.ofSeconds(Math.max(0, seconds));
        long h = d.toHours();
        int m = d.toMinutesPart();
        if (h > 0) {
            return m > 0 ? h + "h " + m + "m" : h + "h";
        }
        if (m > 0) {
            return m + "m";
        }
        return d.toSecondsPart() + "s";
    }
}
