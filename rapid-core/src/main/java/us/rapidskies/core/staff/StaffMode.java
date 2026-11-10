package us.rapidskies.core.staff;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.CoreSettings;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.gui.Icons;

/**
 * Staff mode (PLAN.md §9): {@code /staff} swaps the inventory for a hotbar of tools, makes
 * the staff member fly, invulnerable and (by default) vanished, and stops them changing the
 * world, so moderating can never put items into the economy.
 *
 * <ul>
 *   <li>Compass: teleport to a random player on this server</li>
 *   <li>Book: right-click a player to inspect their inventory, sneak for their ender chest</li>
 *   <li>Packed ice: right-click a player to freeze or release them</li>
 *   <li>Head: list of players on this server (click to teleport)</li>
 *   <li>Dye: vanish on/off</li>
 *   <li>Barrier: leave staff mode</li>
 * </ul>
 *
 * <p>The real inventory, game mode and flight state are kept in the player's persistent
 * data, which the server saves with the player. Staff mode ends on quit; if the server
 * crashed instead, the inventory is restored on the next join.
 */
public final class StaffMode implements Listener {

    public static final String USE = "rapid.staff.mode";
    public static final String INSPECT = "rapid.staff.inspect";

    enum Tool { RANDOM, INSPECT, FREEZE, PLAYERS, VANISH, EXIT }

    private static final int VANISH_SLOT = 7;

    private final RapidCore plugin;
    private final Vanish vanish;
    private final Freeze freeze;
    private final StaffLog log;
    private final Lang lang;
    private final CoreSettings.Staff settings;
    private final NamespacedKey toolKey;
    private final SavedState saved;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** Vanished by entering staff mode, so leaving it makes them visible again. */
    private final Set<UUID> vanishedByMode = ConcurrentHashMap.newKeySet();

    public StaffMode(RapidCore plugin, Vanish vanish, Freeze freeze, StaffLog log, Lang lang,
                     CoreSettings.Staff settings) {
        this.plugin = plugin;
        this.vanish = vanish;
        this.freeze = freeze;
        this.log = log;
        this.lang = lang;
        this.settings = settings;
        this.toolKey = new NamespacedKey(plugin, "staff_tool");
        this.saved = new SavedState(plugin, "staff", "staff mode");
    }

    public Vanish vanish() {
        return vanish;
    }

    public Freeze freeze() {
        return freeze;
    }

    public boolean isActive(Player player) {
        return active.contains(player.getUniqueId());
    }

    public void toggle(Player player) {
        if (isActive(player)) {
            leave(player, true);
        } else {
            enter(player);
        }
    }

    // ------------------------------------------------------------------ entering and leaving

    private void enter(Player player) {
        saved.saveAndClear(player);
        active.add(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setFallDistance(0);
        if (settings.vanishOnStaffMode() && player.hasPermission(Vanish.USE) && !vanish.isVanished(player)) {
            vanish.set(player, true);
            vanishedByMode.add(player.getUniqueId());
        }
        giveTools(player);
        lang.send(player, "staff.mode.enabled");
        log.log(player, "staff_mode_on", null, null);
    }

    /** Ends staff mode and gives everything back. {@code announce} is false on quit and shutdown. */
    private void leave(Player player, boolean announce) {
        active.remove(player.getUniqueId());
        player.setInvulnerable(false);
        player.setCollidable(true);
        saved.restore(player);
        if (vanishedByMode.remove(player.getUniqueId()) && announce) {
            vanish.set(player, false);
        }
        if (announce) {
            lang.send(player, "staff.mode.disabled");
            log.log(player, "staff_mode_off", null, null);
        }
    }

    /** Ends staff mode for everyone, quietly (plugin shutdown). */
    public void leaveAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isActive(p)) {
                leave(p, false);
            }
        }
    }

    // ------------------------------------------------------------------ tools

    private void giveTools(Player player) {
        var inv = player.getInventory();
        inv.setItem(0, tool(Tool.RANDOM, Material.COMPASS, "random"));
        inv.setItem(1, tool(Tool.INSPECT, Material.BOOK, "inspect"));
        inv.setItem(2, tool(Tool.FREEZE, Material.PACKED_ICE, "freeze"));
        inv.setItem(4, tool(Tool.PLAYERS, Material.PLAYER_HEAD, "players"));
        inv.setItem(VANISH_SLOT, vanishTool(player));
        inv.setItem(8, tool(Tool.EXIT, Material.BARRIER, "exit"));
    }

    private ItemStack vanishTool(Player player) {
        boolean on = vanish.isVanished(player);
        return tool(Tool.VANISH, on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "vanish-on" : "vanish-off");
    }

    private ItemStack tool(Tool tool, Material material, String key) {
        ItemStack item = Icons.icon(material, lang.get("staff.tool." + key + "-name"),
                lang.list("staff.tool." + key + "-lore"));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, tool.name()));
        return item;
    }

    private Optional<Tool> toolOf(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        String id = item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        try {
            return id == null ? Optional.empty() : Optional.of(Tool.valueOf(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Vanish on/off, keeping the hotbar dye in step when in staff mode. */
    public void toggleVanish(Player player) {
        vanish.toggle(player);
        vanishedByMode.remove(player.getUniqueId()); // a deliberate choice now; leaving keeps it
        if (isActive(player)) {
            player.getInventory().setItem(VANISH_SLOT, vanishTool(player));
        }
    }

    public void teleport(Player staff, Player target) {
        staff.teleport(target.getLocation());
        lang.send(staff, "staff.mode.teleported", Placeholder.unparsed("player", target.getName()));
    }

    private void randomTeleport(Player staff) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(staff) && !isActive(p) && !vanish.isVanished(p)) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            lang.send(staff, "staff.mode.nobody");
            return;
        }
        teleport(staff, candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }

    public void inspect(Player staff, Player target, boolean enderChest) {
        if (enderChest) {
            InspectMenu.enderChest(lang, staff, target);
        } else {
            InspectMenu.inventory(lang, staff, target);
        }
        log.log(staff, enderChest ? "inspect_ender" : "inspect", target, null);
    }

    // ------------------------------------------------------------------ tool use

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!isActive(p)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        toolOf(event.getItem()).ifPresent(tool -> {
            switch (tool) {
                case RANDOM -> randomTeleport(p);
                case PLAYERS -> new PlayerListMenu(lang, this).open(p);
                case VANISH -> {
                    if (p.hasPermission(Vanish.USE)) {
                        toggleVanish(p);
                    }
                }
                case EXIT -> leave(p, true);
                case INSPECT, FREEZE -> lang.send(p, "staff.mode.click-a-player");
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        if (!isActive(p)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        toolOf(p.getInventory().getItemInMainHand()).ifPresent(tool -> {
            switch (tool) {
                case INSPECT -> {
                    if (p.hasPermission(INSPECT)) {
                        inspect(p, target, p.isSneaking());
                    }
                }
                case FREEZE -> {
                    if (p.hasPermission(Freeze.USE)) {
                        freeze.toggle(p, target);
                    }
                }
                default -> {
                }
            }
        });
    }

    // ------------------------------------------------------------------ no world changes

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && isActive(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && isActive(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && isActive(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p && isActive(p)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ sessions

    /** Staff mode never outlives a session; this also recovers from a crash mid-staff-mode. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (saved.restore(event.getPlayer())) {
            plugin.getSLF4JLogger().warn("Restored the pre-staff-mode inventory of {} (server stopped while they "
                    + "were in staff mode)", event.getPlayer().getName());
        }
    }

    /** Runs before the player file is saved, so it's saved with the real inventory. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        if (isActive(event.getPlayer())) {
            leave(event.getPlayer(), false);
        }
        vanishedByMode.remove(event.getPlayer().getUniqueId());
    }
}
