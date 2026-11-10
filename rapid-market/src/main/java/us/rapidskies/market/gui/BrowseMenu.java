package us.rapidskies.market.gui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.market.RapidMarket;
import us.rapidskies.market.data.MarketRepository;
import us.rapidskies.market.model.Category;
import us.rapidskies.market.model.Listing;
import us.rapidskies.market.model.Prices;
import us.rapidskies.market.model.Sort;

/**
 * The auction house (PLAN.md §4.2): 36 listings a page, categories along the fifth row, and
 * sort, search (a chat prompt, never an anvil), your listings and your collection bin along
 * the bottom. Pages are loaded from the database off the main thread each time the view
 * changes, so what's shown is never more than a click old; buying re-checks anyway.
 *
 * <p>In staff mode ({@code /ah admin}) a click opens the take-down menu instead of buying,
 * and the view can be narrowed to one seller.
 */
public final class BrowseMenu extends Menu {

    private static final int PER_PAGE = 36;
    private static final Material[] CATEGORY_ICONS = {Material.BRICKS, Material.IRON_PICKAXE, Material.BREAD,
            Material.WHEAT, Material.REDSTONE, Material.ENDER_PEARL};

    private final RapidMarket plugin;
    private final boolean admin;
    private final UUID sellerFilter;
    private final String sellerName;
    private Category category;
    private Sort sort = Sort.NEWEST;
    private String search;
    private int page;
    private MarketRepository.Page data = new MarketRepository.Page(List.of(), 0);
    private boolean loading;

    public BrowseMenu(RapidMarket plugin, boolean admin, UUID sellerFilter, String sellerName) {
        super(6, admin ? plugin.lang().get("menu.browse.admin-title",
                Placeholder.unparsed("who", sellerName == null ? "" : sellerName))
                : plugin.lang().get("menu.browse.title", Placeholder.component("where",
                        plugin.lang().get("mode." + plugin.market().mode().id()))));
        this.plugin = plugin;
        this.admin = admin;
        this.sellerFilter = sellerFilter;
        this.sellerName = sellerName;
    }

    public BrowseMenu search(String text) {
        this.search = text;
        return this;
    }

    /** Loads the current page, then opens or redraws the menu. */
    public void show(Player p) {
        if (loading) {
            return;
        }
        loading = true;
        Category cat = category;
        Sort s = sort;
        String q = search;
        int offset = page * PER_PAGE;
        plugin.async(() -> plugin.market().repo().browse(cat, s, q, sellerFilter, offset, PER_PAGE), result -> {
            loading = false;
            if (!p.isOnline()) {
                return;
            }
            data = result;
            if (result.listings().isEmpty() && page > 0 && result.total() > 0) {
                // The last page emptied meanwhile: go to the new last page.
                page = (result.total() - 1) / PER_PAGE;
                show(p);
                return;
            }
            if (p.getOpenInventory().getTopInventory() == getInventory()) {
                refresh(p);
            } else {
                open(p);
            }
        }, () -> loading = false, p);
    }

    @Override
    protected void render(Player viewer) {
        var lang = plugin.lang();
        List<Listing> listings = data.listings();
        for (int i = 0; i < listings.size() && i < PER_PAGE; i++) {
            Listing l = listings.get(i);
            boolean own = l.seller().equals(viewer.getUniqueId());
            String action = admin ? "listing.action-admin" : own ? "listing.action-own" : "listing.action-buy";
            set(i, ListingIcons.listing(plugin, l, action), (p, click) -> clicked(p, l));
        }
        if (listings.isEmpty()) {
            set(slot(1, 4), Icons.icon(Material.STRUCTURE_VOID, lang.get(search == null ? "menu.browse.empty"
                    : "menu.browse.no-results", Placeholder.unparsed("search", search == null ? "" : search))));
        }

        // Row 5: categories.
        set(slot(4, 1), category(lang.get("category.all"), Material.NETHER_STAR, category == null),
                (p, click) -> setCategory(p, null));
        Category[] all = Category.values();
        for (int i = 0; i < all.length; i++) {
            Category c = all[i];
            set(slot(4, 2 + i), category(lang.get("category." + c.id()), CATEGORY_ICONS[i], category == c),
                    (p, click) -> setCategory(p, category == c ? null : c));
        }

        // Row 6: navigation.
        int pages = Math.max(1, (data.total() + PER_PAGE - 1) / PER_PAGE);
        var pageInfo = new TagResolver[] {
                Placeholder.unparsed("page", Integer.toString(page + 1)),
                Placeholder.unparsed("pages", Integer.toString(pages)),
                Placeholder.unparsed("total", Integer.toString(data.total()))};
        if (page > 0) {
            set(slot(5, 0), Icons.icon(Material.ARROW, lang.get("menu.previous", pageInfo)), (p, click) -> {
                page--;
                show(p);
            });
        }
        if (page + 1 < pages) {
            set(slot(5, 8), Icons.icon(Material.ARROW, lang.get("menu.next", pageInfo)), (p, click) -> {
                page++;
                show(p);
            });
        }
        set(slot(5, 2), Icons.icon(Material.HOPPER, lang.get("menu.browse.sort.name",
                Placeholder.component("sort", lang.get("sort." + sort.id()))), lang.list("menu.browse.sort.lore")),
                (p, click) -> {
                    sort = click.isRightClick() ? previous(sort) : sort.next();
                    page = 0;
                    show(p);
                });
        set(slot(5, 3), Icons.icon(Material.OAK_SIGN, search == null ? lang.get("menu.browse.search.name")
                        : lang.get("menu.browse.search.active", Placeholder.unparsed("search", search)),
                lang.list(search == null ? "menu.browse.search.lore" : "menu.browse.search.lore-active")),
                (p, click) -> {
                    if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                        search = null;
                        page = 0;
                        show(p);
                    } else {
                        askSearch(p);
                    }
                });
        set(slot(5, 4), Icons.icon(Material.BOOK, lang.get("menu.browse.info.name", pageInfo),
                lang.list("menu.browse.info.lore", pageInfo)));
        if (!admin) {
            set(slot(5, 5), Icons.icon(Material.WRITABLE_BOOK, lang.get("menu.browse.mine.name"),
                    lang.list("menu.browse.mine.lore")), (p, click) -> new MyListingsMenu(plugin, this).show(p));
            set(slot(5, 6), Icons.icon(Material.CHEST, lang.get("menu.browse.collection.name"),
                    lang.list("menu.browse.collection.lore")), (p, click) -> new CollectionMenu(plugin, this).show(p));
        }
        set(slot(5, 7), Icons.icon(Material.SUNFLOWER, lang.get("menu.browse.refresh.name"),
                lang.list("menu.browse.refresh.lore")), (p, click) -> show(p));
        fill(Icons.filler());
    }

    private ItemStack category(Component name, Material icon, boolean selected) {
        ItemStack item = Icons.icon(icon, name, plugin.lang().list(selected ? "category.selected" : "category.pick"));
        return selected ? Icons.glow(item) : item;
    }

    private void setCategory(Player p, Category c) {
        category = c;
        page = 0;
        show(p);
    }

    private static Sort previous(Sort s) {
        Sort[] all = Sort.values();
        return all[(s.ordinal() + all.length - 1) % all.length];
    }

    private void askSearch(Player p) {
        p.closeInventory();
        plugin.lang().send(p, "search.prompt");
        RapidCore.get().prompts().ask(plugin, p, Duration.ofSeconds(60), text -> {
            search = text.length() > 48 ? text.substring(0, 48) : text;
            page = 0;
            show(p);
        }, () -> {
            plugin.lang().send(p, "search.cancelled");
            show(p);
        });
    }

    private void clicked(Player p, Listing l) {
        if (admin) {
            confirmRemove(p, l);
        } else if (l.seller().equals(p.getUniqueId())) {
            MyListingsMenu.confirmCancel(plugin, p, l, () -> show(p));
        } else {
            confirmBuy(p, l);
        }
    }

    private void confirmBuy(Player p, Listing l) {
        var market = plugin.market();
        plugin.async(() -> market.repo().averageUnitPrice(l.itemKey()), avg -> {
            if (!p.isOnline()) {
                return;
            }
            List<Component> details = new ArrayList<>();
            details.add(plugin.lang().get("buy.details.title"));
            details.addAll(plugin.lang().list("buy.details.lore", market.money("price", l.price()),
                    market.unitMoney("each", l.unitPrice()), average(plugin, avg),
                    Placeholder.unparsed("days", Integer.toString(plugin.settings().historyDays())),
                    Placeholder.unparsed("balance", balance(p))));
            new ConfirmMenu(plugin, plugin.lang().get("menu.buy.title"), ListingIcons.listing(plugin, l), "menu.buy.confirm",
                    details, () -> market.buy(p, l), () -> show(p)).open(p);
        }, p);
    }

    private void confirmRemove(Player p, Listing l) {
        List<Component> details = new ArrayList<>();
        details.add(plugin.lang().get("admin.details.title", Placeholder.unparsed("id", Long.toString(l.id()))));
        details.addAll(plugin.lang().list("admin.details.lore", Placeholder.unparsed("seller", l.sellerName()),
                Placeholder.unparsed("id", Long.toString(l.id()))));
        new ConfirmMenu(plugin, plugin.lang().get("menu.admin.title"), ListingIcons.listing(plugin, l),
                "menu.admin.confirm", details, () -> plugin.market().remove(p, l.id(), () -> show(p)),
                () -> show(p)).open(p);
    }

    private String balance(Player p) {
        var cached = RapidCore.get().economy().cachedBalance(p.getUniqueId());
        return cached.isPresent() ? Prices.format(cached.getAsLong()) : "?";
    }

    static TagResolver average(RapidMarket plugin,
                                                                                OptionalDouble avg) {
        return avg.isPresent() ? plugin.market().unitMoney("average", avg.getAsDouble())
                : Placeholder.component("average", plugin.lang().get("price.no-history"));
    }
}
