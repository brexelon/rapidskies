package us.rapidskies.market.gui;

import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.market.RapidMarket;
import us.rapidskies.market.model.Stored;

/**
 * Your collection bin ({@code /ah collect}, PLAN.md §4.2): items bought while your inventory
 * was full, and expired, cancelled or removed listings. Click one to take it, or take
 * everything that fits. Items wait here as long as it takes; they're never dropped.
 */
public final class CollectionMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final RapidMarket plugin;
    private final BrowseMenu back;
    private List<Stored> items = List.of();
    private int total;
    private int page;
    private boolean busy;

    public CollectionMenu(RapidMarket plugin, BrowseMenu back) {
        super(6, plugin.lang().get("menu.collection.title"));
        this.plugin = plugin;
        this.back = back;
    }

    public void show(Player p) {
        record Loaded(List<Stored> items, int total) {
        }
        var repo = plugin.market().repo();
        int offset = page * PER_PAGE;
        plugin.async(() -> new Loaded(repo.collection(p.getUniqueId(), offset, PER_PAGE),
                repo.collectionCount(p.getUniqueId())), loaded -> {
            if (!p.isOnline()) {
                return;
            }
            items = loaded.items();
            total = loaded.total();
            if (items.isEmpty() && page > 0) {
                page = 0;
                show(p);
                return;
            }
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
        for (int i = 0; i < items.size() && i < PER_PAGE; i++) {
            Stored s = items.get(i);
            set(i, ListingIcons.stored(plugin, s, "collect.action"), (p, click) -> take(p, s));
        }
        if (items.isEmpty()) {
            set(slot(2, 4), Icons.icon(Material.STRUCTURE_VOID, lang.get("menu.collection.empty")));
        }
        int pages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
        TagResolver[] info = {Placeholder.unparsed("page", Integer.toString(page + 1)),
                Placeholder.unparsed("pages", Integer.toString(pages)),
                Placeholder.unparsed("count", Integer.toString(total))};
        set(slot(5, 0), Icons.icon(Material.ARROW, lang.get("menu.back.name"), lang.list("menu.back.lore")),
                (p, click) -> {
                    if (back != null) {
                        back.show(p);
                    } else {
                        p.closeInventory();
                    }
                });
        if (page > 0) {
            set(slot(5, 3), Icons.icon(Material.ARROW, lang.get("menu.previous", info)), (p, click) -> {
                page--;
                show(p);
            });
        }
        if (!items.isEmpty()) {
            set(slot(5, 4), Icons.icon(Material.HOPPER, lang.get("menu.collection.all.name", info),
                    lang.list("menu.collection.all.lore", info)), (p, click) -> takeAll(p));
        }
        if (page + 1 < pages) {
            set(slot(5, 5), Icons.icon(Material.ARROW, lang.get("menu.next", info)), (p, click) -> {
                page++;
                show(p);
            });
        }
        fill(Icons.filler());
    }

    private void take(Player p, Stored s) {
        if (busy) {
            return;
        }
        busy = true;
        plugin.market().collect(p, s, delivered -> {
            busy = false;
            if (!delivered) {
                plugin.lang().send(p, "collect.no-room");
            }
            show(p);
        });
    }

    private void takeAll(Player p) {
        if (busy) {
            return;
        }
        busy = true;
        plugin.market().collectAll(p, () -> {
            busy = false;
            show(p);
        });
    }
}
