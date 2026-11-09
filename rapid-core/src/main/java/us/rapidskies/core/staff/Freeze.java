package us.rapidskies.core.staff;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * Freeze (PLAN.md §9): holds a suspected cheater in place while staff talk to them in
 * chat. A frozen player can look around and chat but can't move, build, fight, use items,
 * or run commands; the proxy also blocks its commands, so {@code /play} is no way out.
 * They see a chest-menu notice once, then a title and chat reminder every few
 * seconds; the menu can be closed so they can answer in chat.
 *
 * <p>Like vanish, the state lives in Redis ({@link Channels#FROZEN_KEY}): relogging
 * doesn't help, and the proxy alerts staff chat when a frozen player disconnects.
 */
public final class Freeze implements Listener {

    public static final String USE = "rapid.staff.freeze";
    /** Can't be frozen (the team). */
    public static final String EXEMPT = "rapid.staff.freeze.exempt";

    private final RapidCore plugin;
    private final RedisBus redis;
    private final StaffLog log;
    private final Lang lang;
    private final int reminderSeconds;
    /** Frozen players on this server, and who froze them. */
    private final Map<UUID, String> frozen = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private BukkitTask reminders;

    public Freeze(RapidCore plugin, RedisBus redis, StaffLog log, Lang lang, int reminderSeconds) {
        this.plugin = plugin;
        this.redis = redis;
        this.log = log;
        this.lang = lang;
        this.reminderSeconds = reminderSeconds;
    }

    public void start() {
        long period = reminderSeconds * 20L;
        reminders = Bukkit.getScheduler().runTaskTimer(plugin, this::remindAll, period, period);
    }

    public void stop() {
        if (reminders != null) {
            reminders.cancel();
        }
        frozen.clear();
    }

    /** Re-freezes online players after rapid-core is re-enabled (the Redis set is the truth). */
    public void restoreOnline() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> ids;
            try {
                ids = redis.members(Channels.FROZEN_KEY);
            } catch (RuntimeException e) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (ids.contains(p.getUniqueId().toString()) && !isFrozen(p)) {
                        freeze(p, "?");
                    }
                }
            });
        });
    }

    public boolean isFrozen(Player player) {
        return frozen.containsKey(player.getUniqueId());
    }

    /** Freezes or releases {@code target}; tells {@code staff} what happened. */
    public void toggle(Player staff, Player target) {
        if (isFrozen(target)) {
            release(staff, target);
            return;
        }
        if (target.equals(staff) || target.hasPermission(EXEMPT)) {
            lang.send(staff, "staff.freeze.exempt", Placeholder.unparsed("player", target.getName()));
            return;
        }
        freeze(target, staff.getName());
        lang.send(staff, "staff.freeze.froze", Placeholder.unparsed("player", target.getName()));
        log.log(staff, "freeze", target, null);
        publish(target.getUniqueId(), true, staff.getName());
    }

    private void release(Player staff, Player target) {
        frozen.remove(target.getUniqueId());
        if (target.getOpenInventory().getTopInventory().getHolder(false) instanceof Notice) {
            target.closeInventory();
        }
        target.clearTitle();
        lang.send(target, "staff.freeze.released");
        lang.send(staff, "staff.freeze.released-staff", Placeholder.unparsed("player", target.getName()));
        log.log(staff, "unfreeze", target, null);
        publish(target.getUniqueId(), false, staff.getName());
    }

    private void freeze(Player target, String by) {
        frozen.put(target.getUniqueId(), by);
        target.setFallDistance(0);
        new Notice(lang).open(target);
        remind(target);
    }

    private void remindAll() {
        for (UUID id : frozen.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                remind(p);
            }
        }
    }

    private void remind(Player p) {
        lang.send(p, "staff.freeze.reminder");
        p.showTitle(Title.title(lang.get("staff.freeze.title"), lang.get("staff.freeze.subtitle"),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(reminderSeconds + 1), Duration.ofMillis(250))));
    }

    private void publish(UUID player, boolean freeze, String by) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (freeze) {
                    redis.addToSet(Channels.FROZEN_KEY, player.toString());
                } else {
                    redis.removeFromSet(Channels.FROZEN_KEY, player.toString());
                }
                redis.publishJson(Channels.FREEZE, new Channels.FreezeChanged(player.toString(), freeze, by));
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't save freeze state for {}: {}", player, e.getMessage());
            }
        });
    }

    /** The notice a frozen player sees: one icon explaining what to do, and a button to close it. */
    static final class Notice extends Menu {

        private final Lang lang;

        Notice(Lang lang) {
            super(3, lang.get("staff.freeze.menu-title"));
            this.lang = lang;
        }

        @Override
        protected void render(Player viewer) {
            set(slot(1, 4), Icons.icon(Material.PACKED_ICE, lang.get("staff.freeze.menu-name"),
                    lang.list("staff.freeze.menu-lore")));
            set(slot(2, 4), Icons.icon(Material.PAPER, lang.get("staff.freeze.menu-close")),
                    (p, click) -> p.closeInventory());
            fill(Icons.filler());
        }
    }

    // ------------------------------------------------------------------ joins and quits

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            if (redis.isMember(Channels.FROZEN_KEY, event.getUniqueId().toString())) {
                pending.add(event.getUniqueId());
            }
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't check freeze state of {}: {}", event.getName(), e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (pending.remove(p.getUniqueId())) {
            freeze(p, "?");
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(USE)) {
                    lang.send(staff, "staff.freeze.rejoined", Placeholder.unparsed("player", p.getName()));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        frozen.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        pending.remove(event.getPlayerUniqueId());
    }

    // ------------------------------------------------------------------ restrictions

    /** Looking around is fine; going anywhere isn't. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isFrozen(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location stay = from.clone();
            stay.setYaw(to.getYaw());
            stay.setPitch(to.getPitch());
            event.setTo(stay);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // Pearls, chorus fruit, a portal they were standing in... Staff /tp (COMMAND, PLUGIN) still works.
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (isFrozen(event.getPlayer()) && cause != PlayerTeleportEvent.TeleportCause.COMMAND
                && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
            lang.send(event.getPlayer(), "staff.freeze.no-commands");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Their own inventory is locked too (menus are already read-only). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && isFrozen(p)) {
            event.setCancelled(true);
        }
    }

    /** Frozen players can't hurt anyone, and nobody can take advantage of them standing still. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && isFrozen(p)) {
            event.setCancelled(true);
        } else if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player p
                && isFrozen(p)) {
            event.setCancelled(true);
        }
    }
}
