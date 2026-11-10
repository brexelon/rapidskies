package us.rapidskies.core.staff;

import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * A player's real inventory, game mode and flight, kept in their persistent data while a
 * mode (staff mode, build mode) gives them something else. The server saves persistent data
 * with the player, so a crash mid-mode loses nothing: {@link #restore} on the next join puts
 * it all back.
 */
public final class SavedState {

    private final NamespacedKey itemsKey;
    private final NamespacedKey modeKey;
    private final NamespacedKey flightKey;
    private final Logger log;
    private final String what;

    /**
     * @param prefix key prefix in the player's data ({@code staff} gives {@code staff_saved_items}, ...)
     * @param what   what the state is saved for, for log lines ("staff mode")
     */
    public SavedState(Plugin plugin, String prefix, String what) {
        this.itemsKey = new NamespacedKey(plugin, prefix + "_saved_items");
        this.modeKey = new NamespacedKey(plugin, prefix + "_saved_mode");
        this.flightKey = new NamespacedKey(plugin, prefix + "_saved_flight");
        this.log = plugin.getSLF4JLogger();
        this.what = what;
    }

    /** Saves the inventory, game mode and flight, then empties the inventory and cursor. */
    public void saveAndClear(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        ItemStack[] contents = Arrays.stream(player.getInventory().getContents())
                .map(i -> i == null ? ItemStack.empty() : i)
                .toArray(ItemStack[]::new);
        pdc.set(itemsKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(contents));
        pdc.set(modeKey, PersistentDataType.STRING, player.getGameMode().name());
        pdc.set(flightKey, PersistentDataType.BYTE, (byte) (player.getAllowFlight() ? 1 : 0));
        player.getInventory().clear();
        player.setItemOnCursor(null);
    }

    /** Puts back what {@link #saveAndClear} saved, if anything. Returns whether there was something. */
    public boolean restore(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        byte[] saved = pdc.get(itemsKey, PersistentDataType.BYTE_ARRAY);
        if (saved == null) {
            return false;
        }
        ItemStack[] items;
        try {
            items = ItemStack.deserializeItemsFromBytes(saved);
        } catch (RuntimeException e) {
            // Touch nothing and keep the saved copy, so an admin can recover it from the player
            // file. Leftover mode items do nothing outside the mode.
            log.error("Couldn't read the inventory {} had before {}", player.getName(), what, e);
            return false;
        }
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.getInventory().setContents(items);
        String mode = pdc.get(modeKey, PersistentDataType.STRING);
        Byte flight = pdc.get(flightKey, PersistentDataType.BYTE);
        player.setGameMode(mode == null ? Bukkit.getDefaultGameMode() : GameMode.valueOf(mode));
        boolean allow = flight != null && flight == 1;
        player.setAllowFlight(allow || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR);
        pdc.remove(itemsKey);
        pdc.remove(modeKey);
        pdc.remove(flightKey);
        return true;
    }
}
