package us.rapidskies.claims.town;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownRole;

/**
 * The shared town vault (a Town Project unlock, PLAN.md §4.1): one 6-row chest every member
 * above Recruit can open with {@code /town vault}.
 *
 * <p>All members viewing it share one live inventory on this server, so two people can't
 * take the same item. Once loaded it stays in memory until shutdown, and it's written to
 * the database whenever someone closes it after a change and every 30 seconds while
 * changed: the same trade-off a chest in the world makes with world saves. (A second Horizons instance would need the vault locked to one
 * server; there's only one for now.)
 */
public final class TownVault implements Listener {

    private static final int SIZE = 54;

    private final RapidClaims plugin;
    /** Open vaults by town. Main thread only. */
    private final Map<UUID, Holder> open = new HashMap<>();
    /** Players waiting for a vault that's still loading. */
    private final Map<UUID, List<Player>> loading = new HashMap<>();
    private BukkitTask saver;

    public TownVault(RapidClaims plugin) {
        this.plugin = plugin;
    }

    /** Marks a vault inventory; {@link #getInventory} is the live, shared one. */
    public static final class Holder implements InventoryHolder {
        private final UUID town;
        private Inventory inventory;
        private boolean dirty;

        Holder(UUID town) {
            this.town = town;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void open(Player p) {
        Optional<Town> t = plugin.towns().mine(p);
        if (t.isEmpty()) {
            return;
        }
        Town town = t.get();
        if (!plugin.towns().unlocked(town, Project.Unlock.Kind.VAULT)) {
            plugin.lang().send(p, "town.vault-locked");
            return;
        }
        if (town.roleOf(p.getUniqueId()).orElse(TownRole.RECRUIT) == TownRole.RECRUIT) {
            plugin.lang().send(p, "town.vault-recruit");
            return;
        }
        Holder holder = open.get(town.id());
        if (holder != null) {
            p.openInventory(holder.inventory);
            return;
        }
        List<Player> waiting = loading.get(town.id());
        if (waiting != null) {
            waiting.add(p);
            return;
        }
        loading.put(town.id(), new ArrayList<>(List.of(p)));
        plugin.async(() -> plugin.repo().vault(town.id()), bytes -> {
            List<Player> viewers = loading.remove(town.id());
            Holder h = new Holder(town.id());
            h.inventory = Bukkit.createInventory(h, SIZE, plugin.lang().get("town.vault-title",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("town",
                            TownService.colored(town, town.name()))));
            bytes.ifPresent(b -> h.inventory.setContents(fit(ItemStack.deserializeItemsFromBytes(b))));
            open.put(town.id(), h);
            for (Player viewer : viewers) {
                if (viewer.isOnline()) {
                    viewer.openInventory(h.inventory);
                }
            }
        }, () -> loading.remove(town.id()), p);
    }

    private static ItemStack[] fit(ItemStack[] stored) {
        ItemStack[] out = new ItemStack[SIZE];
        System.arraycopy(stored, 0, out, 0, Math.min(SIZE, stored.length));
        return out;
    }

    public void start() {
        saver = Bukkit.getScheduler().runTaskTimer(plugin, () -> open.values().forEach(h -> {
            if (h.dirty) {
                save(h);
            }
        }), 20L * 30, 20L * 30);
    }

    private void save(Holder h) {
        h.dirty = false;
        byte[] bytes = ItemStack.serializeItemsAsBytes(h.inventory.getContents());
        plugin.async(() -> {
            plugin.repo().saveVault(h.town, bytes);
            return null;
        }, done -> { }, () -> h.dirty = true, null);
    }

    /** Blocking save of every changed vault (onDisable, while the database is still open). */
    public void saveAllNow() {
        if (saver != null) {
            saver.cancel();
        }
        for (Holder h : open.values()) {
            for (HumanEntity viewer : new ArrayList<>(h.inventory.getViewers())) {
                viewer.closeInventory();
            }
            try {
                plugin.repo().saveVault(h.town, ItemStack.serializeItemsAsBytes(h.inventory.getContents()));
            } catch (SQLException e) {
                plugin.getSLF4JLogger().error("Couldn't save the vault of town {} at shutdown", h.town, e);
            }
        }
        open.clear();
    }

    /** The town is being deleted: close its vault without saving. Safe from any thread. */
    public void discard(UUID town) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Holder h = open.remove(town);
            if (h != null) {
                for (HumanEntity viewer : new ArrayList<>(h.inventory.getViewers())) {
                    viewer.closeInventory();
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder h) {
            h.dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder h) {
            h.dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        // Loaded vaults stay in memory (one per town at most) rather than being dropped on the
        // last close: reopening right after would otherwise read the database before this
        // save lands and hand out items a second time.
        if (event.getInventory().getHolder(false) instanceof Holder h && open.get(h.town) == h && h.dirty) {
            save(h);
        }
    }
}
