package us.rapidskies.skyrealms;

import java.util.Collection;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;

/** Item helpers shared by the shop, Trade Routes, Realm Tiers and Expeditions. Main thread. */
public final class Give {

    private Give() {
    }

    /**
     * Puts items in the player's inventory; what doesn't fit drops at their feet, owned by them
     * so nobody else can pick it up first.
     */
    public static void items(Player player, Collection<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
                continue;
            }
            for (ItemStack rest : player.getInventory().addItem(split(stack)).values()) {
                Item dropped = player.getWorld().dropItem(player.getLocation(), rest);
                dropped.setOwner(player.getUniqueId());
                dropped.setPickupDelay(0);
            }
        }
    }

    /** {@code amount} of a material as stacks no bigger than its stack size. */
    public static ItemStack[] stacks(Material material, long amount) {
        int max = Math.max(1, material.getMaxStackSize());
        int count = (int) Math.min(Integer.MAX_VALUE, (amount + max - 1) / max);
        ItemStack[] out = new ItemStack[count];
        long left = amount;
        for (int i = 0; i < count; i++) {
            int n = (int) Math.min(max, left);
            out[i] = ItemStack.of(material, n);
            left -= n;
        }
        return out;
    }

    private static ItemStack[] split(ItemStack stack) {
        int max = Math.max(1, stack.getMaxStackSize());
        if (stack.getAmount() <= max) {
            return new ItemStack[]{stack};
        }
        int count = (stack.getAmount() + max - 1) / max;
        ItemStack[] out = new ItemStack[count];
        int left = stack.getAmount();
        for (int i = 0; i < count; i++) {
            ItemStack s = stack.clone();
            s.setAmount(Math.min(max, left));
            left -= s.getAmount();
            out[i] = s;
        }
        return out;
    }

    /** A plain item: no name, lore, enchantments, damage or other data (what shops and routes take). */
    public static boolean plain(ItemStack stack) {
        return stack != null && stack.getType() != Material.AIR && stack.isSimilar(ItemStack.of(stack.getType()));
    }

    /** How many more of a plain {@code material} fit (a player's main inventory, or a chest). */
    public static int room(Inventory inv, Material material) {
        int max = Math.max(1, material.getMaxStackSize());
        int room = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType() == Material.AIR) {
                room += max;
            } else if (s.getType() == material && plain(s)) {
                room += Math.max(0, max - s.getAmount());
            }
        }
        return room;
    }

    /** How many plain {@code material} items an inventory holds (a player's: the main part only). */
    public static int count(Inventory inv, Material material) {
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && s.getType() == material && plain(s)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    /** Removes up to {@code amount} plain {@code material} items; returns how many were taken. */
    public static int take(Inventory inv, Material material, int amount) {
        int left = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (s != null && s.getType() == material && plain(s)) {
                int n = Math.min(left, s.getAmount());
                s.setAmount(s.getAmount() - n);
                contents[i] = s.getAmount() <= 0 ? null : s;
                left -= n;
            }
        }
        inv.setStorageContents(contents);
        return amount - left;
    }
}
