package us.rapidskies.core.world;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.staff.SavedState;
import us.rapidskies.core.staff.StaffLog;
import us.rapidskies.core.staff.StaffMode;

/**
 * Build mode: the only way to change a protected spawn world ({@code protect: true}: the
 * hubs, the Trading Post, the Horizons spawn). Having {@code rapid.world.build} isn't enough
 * on its own; builders, developers and admins switch it on with {@code /build} when they mean
 * to work on a build, so nobody changes a spawn by accident.
 *
 * <p>While it's on the player is in creative mode with an empty inventory (their real one,
 * game mode and flight are kept in their persistent data, like staff mode) and a boss bar
 * says so. Creative items never reach the rest of the server: they can't be dropped or used
 * to pick items up, a death keeps nothing, and build mode ends on its own, giving the real
 * inventory back, when the player leaves the protected worlds or the server.
 */
public final class BuildMode implements Listener {

    public static final String PERMISSION = "rapid.world.build";
    private static final long HINT_INTERVAL_MILLIS = 3_000;

    private final RapidCore plugin;
    private final WorldService worlds;
    private final StaffMode staffMode;
    private final StaffLog log;
    private final Lang lang;
    private final SavedState saved;
    /** Players in build mode and their boss bar. */
    private final Map<UUID, BossBar> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHint = new ConcurrentHashMap<>();

    public BuildMode(RapidCore plugin, WorldService worlds, StaffMode staffMode, StaffLog log, Lang lang) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.staffMode = staffMode;
        this.log = log;
        this.lang = lang;
        this.saved = new SavedState(plugin, "build", "build mode");
    }

    public boolean isActive(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    /**
     * Whether {@code player} may change protected worlds right now: build mode on, and still
     * holding the permission (it may have been taken away since).
     */
    public boolean canBuild(Player player) {
        return isActive(player) && player.hasPermission(PERMISSION);
    }

    /** Tells a player who could build that build mode is off, at most every few seconds. */
    void hint(Player player) {
        if (isActive(player) || !player.hasPermission(PERMISSION)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastHint.get(player.getUniqueId());
        if (last == null || now - last >= HINT_INTERVAL_MILLIS) {
            lastHint.put(player.getUniqueId(), now);
            player.sendActionBar(lang.get("build.hint"));
        }
    }

    // ------------------------------------------------------------------ entering and leaving

    private void enter(Player player) {
        if (!worlds.isProtected(player.getWorld())) {
            lang.send(player, "build.not-protected");
            return;
        }
        if (staffMode.isActive(player)) {
            lang.send(player, "build.staff-mode");
            return;
        }
        saved.saveAndClear(player);
        player.setGameMode(GameMode.CREATIVE);
        BossBar bar = BossBar.bossBar(lang.get("build.bossbar"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        active.put(player.getUniqueId(), bar);
        player.showBossBar(bar);
        lang.send(player, "build.enabled");
        log.log(player, "build_mode_on", null, player.getWorld().getKey().asString());
    }

    /**
     * Ends build mode and gives everything back. {@code message} is the lang key to tell the
     * player why, or null to leave quietly (quit, shutdown).
     */
    private void leave(Player player, String message) {
        BossBar bar = active.remove(player.getUniqueId());
        if (bar == null) {
            return;
        }
        player.hideBossBar(bar);
        saved.restore(player);
        player.setFallDistance(0);
        if (message != null) {
            lang.send(player, message);
        }
        log.log(player, "build_mode_off", null, null);
    }

    /** Ends build mode for everyone, quietly (plugin shutdown). */
    public void leaveAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            leave(p, null);
        }
    }

    // ------------------------------------------------------------------ keeping creative items in

    /** Out of the protected worlds (a portal, /rtp, /spawn to another world): back to normal. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isActive(player) && !worlds.isProtected(player.getWorld())) {
            leave(player, "build.left-world");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(lang.get("build.no-drop"));
        }
    }

    /** Anything picked up would vanish with the creative inventory. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && isActive(p)) {
            event.setCancelled(true);
        }
    }

    /** Nothing creative drops, and the real inventory is what the player respawns with. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (isActive(player)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            leave(player, "build.left-death");
        }
    }

    // ------------------------------------------------------------------ sessions

    /** Build mode never outlives a session; this also recovers from a crash mid-build-mode. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (saved.restore(event.getPlayer())) {
            plugin.getSLF4JLogger().warn("Restored the pre-build-mode inventory of {} (server stopped while they "
                    + "were in build mode)", event.getPlayer().getName());
        }
    }

    /** Runs before the player file is saved, so it's saved with the real inventory. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer(), null);
        lastHint.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ /build

    /** {@code /build [on|off]} (aliases {@code /buildmode}, {@code /bm}); no argument switches it. */
    public BasicCommand command() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    lang.send(stack.getSender(), "errors.players-only");
                    return;
                }
                String arg = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                switch (arg) {
                    case "" -> {
                        if (isActive(player)) {
                            leave(player, "build.disabled");
                        } else {
                            enter(player);
                        }
                    }
                    case "on" -> {
                        if (isActive(player)) {
                            lang.send(player, "build.already-on");
                        } else {
                            enter(player);
                        }
                    }
                    case "off" -> {
                        if (isActive(player)) {
                            leave(player, "build.disabled");
                        } else {
                            lang.send(player, "build.already-off");
                        }
                    }
                    default -> lang.send(player, "build.usage");
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return args.length <= 1 ? Stream.of("on", "off").filter(s -> s.startsWith(prefix)).toList() : List.of();
            }

            @Override
            public String permission() {
                return PERMISSION;
            }
        };
    }
}
