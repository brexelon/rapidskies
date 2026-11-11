package us.rapidskies.hub;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.gui.Icons;

/**
 * The hub hotbar: the game menu, the Bolt shop, cosmetics and quests (rapid-core's menus), a
 * fresh parkour course, the player visibility toggle and, during a parkour run,
 * back-to-checkpoint, restart and leave. Hub items can't be moved, dropped or swapped, and
 * the hub hands out nothing else, so every join starts from a clean inventory. Staff mode
 * keeps its own tools: nothing here touches a player in it, and leaving it gives back the
 * hotbar it saved.
 *
 * <p>Hiding players is per viewer, through Bukkit's per-plugin hiding, so it never undoes
 * (or is undone by) rapid-core's vanish. The choice is remembered in the player's data on
 * this server.
 */
public final class HubHotbar implements Listener {

    public static final int SELECTOR_SLOT = 0;
    public static final int SHOP_SLOT = 1;
    public static final int COSMETICS_SLOT = 2;
    public static final int QUESTS_SLOT = 6;
    public static final int PARKOUR_SLOT = 4;
    public static final int CHECKPOINT_SLOT = 3;
    public static final int RESTART_SLOT = 4;
    public static final int LEAVE_SLOT = 5;
    public static final int VISIBILITY_SLOT = 8;

    private static final long CLICK_COOLDOWN_MILLIS = 250;
    private static final long VISIBILITY_COOLDOWN_MILLIS = 1000;

    /** What a hub item does; stored on the item so it survives renames and relogs. */
    enum Tool {
        SELECTOR(Material.COMPASS, "selector"),
        SHOP(Material.EMERALD, "shop"),
        COSMETICS(Material.ARMOR_STAND, "cosmetics"),
        QUESTS(Material.WRITABLE_BOOK, "quests"),
        PARKOUR(Material.FEATHER, "parkour"),
        PLAYERS_SHOWN(Material.LIME_DYE, "players-shown"),
        PLAYERS_HIDDEN(Material.GRAY_DYE, "players-hidden"),
        CHECKPOINT(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, "checkpoint"),
        RESTART(Material.OAK_DOOR, "restart"),
        LEAVE(Material.BARRIER, "leave");

        final Material material;
        final String key;

        Tool(Material material, String key) {
            this.material = material;
            this.key = key;
        }
    }

    private final RapidHub hub;
    private final NamespacedKey toolKey;
    private final NamespacedKey hidingKey;
    private final Set<UUID> hiding = new HashSet<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, Long> lastToggle = new HashMap<>();

    public HubHotbar(RapidHub hub) {
        this.hub = hub;
        this.toolKey = new NamespacedKey(hub, "tool");
        this.hidingKey = new NamespacedKey(hub, "players-hidden");
    }

    private static boolean inStaffMode(Player player) {
        return RapidCore.get().staffMode().isActive(player);
    }

    // ------------------------------------------------------------------ items

    private ItemStack item(Tool tool) {
        Lang lang = hub.lang();
        ItemStack item = Icons.icon(tool.material, lang.get("item." + tool.key + "-name"),
                lang.list("item." + tool.key + "-lore"));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, tool.name()));
        return item;
    }

    private Tool tool(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        String name = item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (name == null) {
            return null;
        }
        try {
            return Tool.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isTool(ItemStack item) {
        return tool(item) != null;
    }

    /** The normal hub hotbar. */
    public void give(Player player) {
        if (inStaffMode(player)) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        inv.clear();
        player.setItemOnCursor(null);
        inv.setItem(SELECTOR_SLOT, item(Tool.SELECTOR));
        // rapid-core's phase 5 menus (PLAN.md §6.2, §7), here so the hub is where they're found.
        inv.setItem(SHOP_SLOT, item(Tool.SHOP));
        inv.setItem(COSMETICS_SLOT, item(Tool.COSMETICS));
        inv.setItem(QUESTS_SLOT, item(Tool.QUESTS));
        if (hub.parkour().firstOpen().isPresent()) {
            inv.setItem(PARKOUR_SLOT, item(Tool.PARKOUR));
        }
        inv.setItem(VISIBILITY_SLOT, visibilityItem(player));
    }

    /** The hotbar during a parkour run. */
    public void giveParkour(Player player) {
        if (inStaffMode(player)) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        inv.clear();
        player.setItemOnCursor(null);
        inv.setItem(CHECKPOINT_SLOT, item(Tool.CHECKPOINT));
        inv.setItem(RESTART_SLOT, item(Tool.RESTART));
        inv.setItem(LEAVE_SLOT, item(Tool.LEAVE));
        inv.setItem(VISIBILITY_SLOT, visibilityItem(player));
        inv.setHeldItemSlot(CHECKPOINT_SLOT);
    }

    private ItemStack visibilityItem(Player player) {
        return item(hiding.contains(player.getUniqueId()) ? Tool.PLAYERS_HIDDEN : Tool.PLAYERS_SHOWN);
    }

    // ------------------------------------------------------------------ visibility

    public void toggleVisibility(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastToggle.get(player.getUniqueId());
        if (last != null && now - last < VISIBILITY_COOLDOWN_MILLIS) {
            hub.lang().send(player, "visibility.cooldown");
            return;
        }
        lastToggle.put(player.getUniqueId(), now);
        boolean hide = !hiding.contains(player.getUniqueId());
        setHiding(player, hide);
        player.getPersistentDataContainer().set(hidingKey, PersistentDataType.BOOLEAN, hide);
        if (!inStaffMode(player)) {
            player.getInventory().setItem(VISIBILITY_SLOT, visibilityItem(player));
        }
        hub.lang().send(player, hide ? "visibility.hidden" : "visibility.shown");
    }

    private void setHiding(Player viewer, boolean hide) {
        if (hide) {
            hiding.add(viewer.getUniqueId());
        } else {
            hiding.remove(viewer.getUniqueId());
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != viewer) {
                if (hide) {
                    viewer.hidePlayer(hub, other);
                } else {
                    viewer.showPlayer(hub, other);
                }
            }
        }
    }

    /** Sets up a player already online (plugin start, /rapidhub reload). */
    public void setUp(Player player) {
        give(player);
        if (Boolean.TRUE.equals(player.getPersistentDataContainer().get(hidingKey, PersistentDataType.BOOLEAN))) {
            setHiding(player, true);
            if (!inStaffMode(player)) {
                player.getInventory().setItem(VISIBILITY_SLOT, visibilityItem(player));
            }
        }
        for (UUID viewerId : hiding) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer != player) {
                viewer.hidePlayer(hub, player);
            }
        }
    }

    /** Shows everyone to everyone again (plugin stop). */
    public void showAll() {
        for (UUID viewerId : hiding) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                Bukkit.getOnlinePlayers().forEach(other -> viewer.showPlayer(hub, other));
            }
        }
        hiding.clear();
    }

    // ------------------------------------------------------------------ events

    // After rapid-core's own join handling (staff mode restores itself there).
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        setUp(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        hiding.remove(id);
        lastClick.remove(id);
        lastToggle.remove(id);
        hub.selector().forget(event.getPlayer());
    }

    /** keep_inventory is on in the hub, but a respawn is a good moment to fix a messed-up hotbar. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(hub, () -> {
            if (player.isOnline() && !hub.parkour().inRun(player)) {
                give(player);
            }
        });
    }

    // HIGH and not ignoreCancelled: right-clicking air arrives cancelled, and rapid-core's
    // spawn protection denies item use on blocks; the hub items must work either way.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Tool tool = tool(event.getItem());
        if (tool == null) {
            return;
        }
        event.setCancelled(true);
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < CLICK_COOLDOWN_MILLIS) {
            return;
        }
        lastClick.put(player.getUniqueId(), now);
        switch (tool) {
            case SELECTOR -> hub.selector().open(player);
            case SHOP -> RapidCore.get().progress().openShop(player);
            case COSMETICS -> RapidCore.get().progress().openWardrobe(player);
            case QUESTS -> RapidCore.get().progress().openQuests(player);
            case PARKOUR -> hub.parkour().firstOpen().ifPresentOrElse(c -> hub.parkour().join(player, c),
                    () -> hub.lang().send(player, "parkour.closed"));
            case PLAYERS_SHOWN, PLAYERS_HIDDEN -> toggleVisibility(player);
            case CHECKPOINT -> hub.parkour().backToCheckpoint(player);
            case RESTART -> hub.parkour().restart(player);
            case LEAVE -> hub.parkour().leave(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isTool(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isTool(event.getMainHandItem()) || isTool(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean touchesTool = isTool(event.getCurrentItem()) || isTool(event.getCursor());
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            touchesTool |= isTool(player.getInventory().getItem(event.getHotbarButton()));
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            touchesTool |= isTool(player.getInventory().getItemInOffHand());
        }
        if (touchesTool) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (isTool(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    /** Hub items never end up on the ground, but if one ever does, nobody picks it up twice. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (isTool(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }
}
