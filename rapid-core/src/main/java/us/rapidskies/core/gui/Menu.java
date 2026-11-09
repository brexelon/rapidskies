package us.rapidskies.core.gui;

import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A chest menu (PLAN.md §3, cross-version rules: chest menus of at most 6 rows, no anvil
 * or text input), the building block for every rapid-* GUI.
 *
 * <p>The menu is its own {@link InventoryHolder}, which is how {@link MenuListener}
 * recognises it. Every click and drag that touches a menu is cancelled, so items can never
 * be taken out or put in; a click on a slot with a {@link Button} runs its handler.
 *
 * <p>Use it directly ({@code new Menu(3, title).set(...).open(player)}) or subclass it and
 * override {@link #render} for menus that redraw themselves. Main thread only.
 */
public class Menu implements InventoryHolder {

    public static final int MAX_ROWS = 6;

    private final Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();

    /** What a slot shows and does. A null handler makes it decoration. */
    public record Button(ItemStack icon, ClickHandler handler) {
    }

    @FunctionalInterface
    public interface ClickHandler {
        void onClick(Player player, ClickType click);
    }

    // Bukkit only stores the holder reference while creating the inventory; it never calls
    // back into this object, so handing out `this` before a subclass finishes is safe.
    @SuppressWarnings("this-escape")
    public Menu(int rows, Component title) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("a menu has 1 to " + MAX_ROWS + " rows, not " + rows);
        }
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    public final int size() {
        return inventory.getSize();
    }

    /** The slot at {@code row} (0-based) and {@code column} (0–8). */
    public static int slot(int row, int column) {
        return row * 9 + column;
    }

    public final Menu set(int slot, ItemStack icon, ClickHandler handler) {
        inventory.setItem(slot, icon);
        if (handler == null) {
            buttons.remove(slot);
        } else {
            buttons.put(slot, new Button(icon, handler));
        }
        return this;
    }

    /** A slot that only shows something. */
    public final Menu set(int slot, ItemStack icon) {
        return set(slot, icon, null);
    }

    /** Puts {@code filler} in every empty slot. */
    public final Menu fill(ItemStack filler) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
        return this;
    }

    public final void clear() {
        inventory.clear();
        buttons.clear();
    }

    /** Redraws a subclass's contents; called by {@link #open} and {@link #refresh}. */
    protected void render(Player viewer) {
    }

    /** Redraws in place for whoever has it open, without closing and reopening. */
    public final void refresh(Player viewer) {
        clear();
        render(viewer);
    }

    public void open(Player player) {
        refresh(player);
        player.openInventory(inventory);
    }

    /** Called when the player closes the menu (or it's closed for them). */
    protected void onClose(Player player) {
    }

    final void click(Player player, int slot, ClickType click) {
        Button b = buttons.get(slot);
        if (b != null) {
            b.handler().onClick(player, click);
        }
    }

    final void closed(Player player) {
        onClose(player);
    }
}
