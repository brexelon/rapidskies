package us.rapidskies.market.gui;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.market.RapidMarket;
import us.rapidskies.market.model.Listing;

/** Your listings that are still up ({@code /ah listings}); click one to take it down. */
public final class MyListingsMenu extends Menu {

    private final RapidMarket plugin;
    private final BrowseMenu back;
    private List<Listing> listings = List.of();
    private int max;

    public MyListingsMenu(RapidMarket plugin, BrowseMenu back) {
        super(6, plugin.lang().get("menu.mine.title"));
        this.plugin = plugin;
        this.back = back;
    }

    public void show(Player p) {
        max = plugin.service().maxListings(p.getUniqueId());
        plugin.async(() -> plugin.market().repo().activeOf(p.getUniqueId()), result -> {
            if (!p.isOnline()) {
                return;
            }
            listings = result;
            if (p.getOpenInventory().getTopInventory() == getInventory()) {
                refresh(p);
            } else {
                open(p);
            }
        }, p);
    }

    @Override
    protected void render(Player viewer) {
        var lang = plugin.lang();
        for (int i = 0; i < listings.size() && i < 45; i++) {
            Listing l = listings.get(i);
            set(i, ListingIcons.listing(plugin, l, "listing.action-own"),
                    (p, click) -> confirmCancel(plugin, p, l, () -> show(p)));
        }
        if (listings.isEmpty()) {
            set(slot(2, 4), Icons.icon(Material.STRUCTURE_VOID, lang.get("menu.mine.empty"),
                    lang.list("menu.mine.empty-lore")));
        }
        var counts = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] {
                Placeholder.unparsed("count", Integer.toString(listings.size())),
                Placeholder.unparsed("max", Integer.toString(max))};
        set(slot(5, 0), Icons.icon(Material.ARROW, lang.get("menu.back.name"), lang.list("menu.back.lore")),
                (p, click) -> back(p));
        set(slot(5, 4), Icons.icon(Material.BOOK, lang.get("menu.mine.info.name", counts),
                lang.list("menu.mine.info.lore", counts)));
        set(slot(5, 6), Icons.icon(Material.CHEST, lang.get("menu.browse.collection.name"),
                lang.list("menu.browse.collection.lore")), (p, click) -> new CollectionMenu(plugin, back).show(p));
        fill(Icons.filler());
    }

    private void back(Player p) {
        if (back != null) {
            back.show(p);
        } else {
            p.closeInventory();
        }
    }

    /** Asks before taking {@code l} down; the item goes back to the inventory, or the bin if it's full. */
    static void confirmCancel(RapidMarket plugin, Player p, Listing l, Runnable back) {
        List<Component> details = new ArrayList<>();
        details.add(plugin.lang().get("cancel.details.title"));
        details.addAll(plugin.lang().list("cancel.details.lore", Placeholder.unparsed("item", l.itemName())));
        new ConfirmMenu(plugin, plugin.lang().get("menu.cancel.title"), ListingIcons.listing(plugin, l),
                "menu.cancel.confirm", details, () -> plugin.market().cancel(p, l), back).open(p);
    }
}
