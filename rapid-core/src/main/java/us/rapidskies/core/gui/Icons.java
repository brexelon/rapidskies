package us.rapidskies.core.gui;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

/**
 * Menu icons. Names and lore lose the italics Minecraft adds to custom names. Meaning must
 * never depend on the material alone (older clients see ViaBackwards substitutes for newer
 * items, and colorblind players can't tell wool apart): put it in the name or lore.
 */
public final class Icons {

    private Icons() {
    }

    public static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.displayName(plain(name));
            meta.lore(lore.stream().map(Icons::plain).toList());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        });
        return item;
    }

    public static ItemStack icon(Material material, Component name) {
        return icon(material, name, List.of());
    }

    /** Adds the enchantment shimmer, e.g. to mark the selected option (lore should say so too). */
    public static ItemStack glow(ItemStack item) {
        item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    /** An unnamed pane for empty slots. */
    public static ItemStack filler() {
        return icon(Material.BLACK_STAINED_GLASS_PANE, Component.space());
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
