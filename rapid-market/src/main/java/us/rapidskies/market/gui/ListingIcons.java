package us.rapidskies.market.gui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.market.RapidMarket;
import us.rapidskies.market.item.Items;
import us.rapidskies.market.model.Listing;
import us.rapidskies.market.model.Stored;

/**
 * The item a menu shows for a listing or a bin item: the real item (so enchantments and its
 * own tooltip show as usual) with the auction details added to its lore, including its real
 * name, since older clients see ViaBackwards substitutes for newer items (PLAN.md §4.2).
 */
final class ListingIcons {

    private ListingIcons() {
    }

    static ItemStack listing(RapidMarket plugin, Listing l, String... actionKeys) {
        var market = plugin.market();
        long secondsLeft = Duration.between(Instant.now(), l.expiresAt()).toSeconds();
        TagResolver[] r = {
                Placeholder.unparsed("item", l.itemName()),
                Placeholder.unparsed("amount", Integer.toString(l.amount())),
                market.money("price", l.price()),
                market.unitMoney("each", l.unitPrice()),
                Placeholder.unparsed("seller", l.sellerName()),
                Placeholder.unparsed("time", secondsLeft > 0 ? Durations.format(Duration.ofSeconds(secondsLeft)) : "0s"),
                Placeholder.unparsed("id", Long.toString(l.id())),
        };
        List<Component> extra = new ArrayList<>(plugin.lang().list(l.amount() > 1 ? "listing.lore-stack"
                : "listing.lore", r));
        for (String key : actionKeys) {
            extra.addAll(plugin.lang().list(key, r));
        }
        return withLore(plugin, l.item(), extra);
    }

    static ItemStack stored(RapidMarket plugin, Stored s, String... actionKeys) {
        TagResolver[] r = {
                Placeholder.unparsed("item", s.itemName()),
                Placeholder.unparsed("amount", Integer.toString(s.amount())),
                Placeholder.component("reason", plugin.lang().get("collect.reason." + s.reason().id())),
        };
        List<Component> extra = new ArrayList<>(plugin.lang().list("collect.lore", r));
        for (String key : actionKeys) {
            extra.addAll(plugin.lang().list(key, r));
        }
        return withLore(plugin, s.item(), extra);
    }

    /** The stored item with {@code extra} lore under its own. A broken item shows as a barrier. */
    static ItemStack withLore(RapidMarket plugin, byte[] bytes, List<Component> extra) {
        ItemStack item;
        try {
            item = Items.deserialize(bytes);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().warn("Can't read a stored item: {}", e.toString());
            return Icons.icon(Material.BARRIER, plugin.lang().get("menu.unreadable"), extra);
        }
        List<Component> lore = new ArrayList<>();
        List<Component> own = item.lore();
        if (own != null) {
            lore.addAll(own);
        }
        for (Component c : extra) {
            lore.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        item.lore(lore);
        return item;
    }
}
