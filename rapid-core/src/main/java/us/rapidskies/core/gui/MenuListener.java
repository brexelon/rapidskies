package us.rapidskies.core.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Makes {@link Menu}s read-only and dispatches their clicks. Registered once by rapid-core
 * for every plugin's menus.
 */
public final class MenuListener implements Listener {

    // LOWEST so the cancel happens before anything else looks at the click; other plugins'
    // listeners still see a cancelled event and leave it alone.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        // Covers everything: picking up, shift-click from the player's own inventory into
        // the menu, number keys, double-click collecting, dropping.
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == menu.getInventory()) {
            menu.click(player, event.getSlot(), event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu menu) {
            int top = menu.size();
            if (event.getRawSlots().stream().anyMatch(s -> s < top)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Menu menu && event.getPlayer() instanceof Player p) {
            menu.closed(p);
        }
    }

    /** Closes every open menu, e.g. when rapid-core is disabled, so nobody keeps a dead one. */
    public static void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) {
                p.closeInventory();
            }
        }
    }
}
