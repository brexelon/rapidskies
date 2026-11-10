package us.rapidskies.market.item;

import io.papermc.paper.text.PaperComponents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import us.rapidskies.market.model.Category;
import us.rapidskies.market.model.TrueNames;

/** What the auction house needs to know about an item stack. Main thread. */
public final class Items {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.builder()
            .flattener(PaperComponents.flattener()).build();

    private Items() {
    }

    /** Paper's version-tagged form, upgraded when read back after a Minecraft update. */
    public static byte[] serialize(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack deserialize(byte[] bytes) {
        return ItemStack.deserializeBytes(bytes);
    }

    /**
     * The item's real English name, never its custom name: "Pale Oak Log", "Potion of
     * Swiftness". Shown in lore because older clients see ViaBackwards substitutes.
     */
    public static String trueName(ItemStack item) {
        String key = item.translationKey();
        String rendered;
        try {
            rendered = PLAIN.serialize(Component.translatable(key));
        } catch (RuntimeException e) {
            rendered = null;
        }
        return TrueNames.untranslated(rendered, key) ? TrueNames.fromKey(item.getType().getKey().getKey()) : rendered;
    }

    /** The custom name players gave it, or null. */
    public static String customName(ItemStack item) {
        if (!item.hasItemMeta() || !item.getItemMeta().hasCustomName()) {
            return null;
        }
        return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().customName());
    }

    /**
     * What price history is kept under: the material key for a plain item, plus a short hash
     * of its data for anything enchanted, named, damaged or otherwise special, so a Sharpness V
     * sword doesn't drag down the average of plain ones.
     */
    public static String priceKey(ItemStack item) {
        String key = item.getType().getKey().asString();
        if (!item.hasItemMeta()) {
            return key;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(item.asOne().serializeAsBytes());
            return key + "#" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return key + "#" + Integer.toHexString(new String(item.asOne().serializeAsBytes(),
                    StandardCharsets.ISO_8859_1).hashCode());
        }
    }

    public static Category category(ItemStack item) {
        var type = item.getType();
        return Category.classify(type.getKey().getKey().toLowerCase(Locale.ROOT), type.isBlock(), type.isEdible());
    }

    /** Whether all of {@code item} fits into the player's main inventory (hotbar and backpack). */
    public static boolean fits(PlayerInventory inventory, ItemStack item) {
        int left = item.getAmount();
        int max = item.getMaxStackSize();
        for (ItemStack s : inventory.getStorageContents()) {
            if (s == null || s.isEmpty()) {
                left -= max;
            } else if (s.isSimilar(item)) {
                left -= Math.max(0, s.getMaxStackSize() - s.getAmount());
            }
            if (left <= 0) {
                return true;
            }
        }
        return false;
    }
}
