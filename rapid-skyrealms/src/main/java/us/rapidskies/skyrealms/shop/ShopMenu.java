package us.rapidskies.skyrealms.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.SkyRealmsSettings.Category;
import us.rapidskies.skyrealms.SkyRealmsSettings.ShopItem;

/**
 * {@code /shop}: the categories, then a category's items. Left-click buys one, shift-left a
 * stack; right-click sells one, shift-right everything of that item. Locked categories say
 * which Realm Tier opens them.
 */
public final class ShopMenu extends Menu {

    private final RapidSkyRealms plugin;
    private final Category category;

    private ShopMenu(RapidSkyRealms plugin, Category category, int rows, Component title) {
        super(rows, title);
        this.plugin = plugin;
        this.category = category;
    }

    /** The category list. */
    public static void open(RapidSkyRealms plugin, Player player) {
        int count = plugin.settings().shop().categories().size();
        int rows = Math.min(6, 2 + (count + 6) / 7);
        plugin.shop().refreshSold(player, () -> new ShopMenu(plugin, null, rows,
                plugin.lang().get("shop.menu.title")).open(player));
    }

    private static void openCategory(RapidSkyRealms plugin, Player player, Category category) {
        new ShopMenu(plugin, category, 6, plugin.lang().get("shop.menu.category-title",
                Placeholder.component("category", plugin.lang().get("shop.category." + category.id())))).open(player);
    }

    @Override
    protected void render(Player viewer) {
        if (category == null) {
            renderCategories(viewer);
        } else {
            renderItems(viewer);
        }
        fill(Icons.filler());
    }

    private void renderCategories(Player viewer) {
        Lang lang = plugin.lang();
        List<Category> cats = plugin.settings().shop().categories();
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i);
            boolean open = plugin.shop().unlocked(viewer, c);
            int slot = slot(1 + i / 7, 1 + i % 7);
            List<Component> lore = new ArrayList<>();
            lore.add(lang.get("shop.menu.items", Placeholder.unparsed("count", Integer.toString(c.items().size()))));
            if (open) {
                lore.add(lang.get("shop.menu.open-lore"));
                set(slot, Icons.icon(c.icon(), lang.get("shop.category." + c.id()), lore),
                        (p, click) -> openCategory(plugin, p, c));
            } else {
                Component tier = plugin.settings().tier(c.tier()).map(t -> plugin.tiers().name(t))
                        .orElse(Component.text(c.tier()));
                lore.add(lang.get("shop.menu.locked-lore", Placeholder.component("tier", tier)));
                set(slot, Icons.icon(Material.GRAY_DYE, lang.get("shop.menu.locked",
                        Placeholder.component("category", lang.get("shop.category." + c.id()))), lore));
            }
        }
        set(size() - 5, rateIcon(viewer));
    }

    private void renderItems(Player viewer) {
        Lang lang = plugin.lang();
        List<ShopItem> items = category.items();
        for (int i = 0; i < items.size() && i < 45; i++) {
            ShopItem item = items.get(i);
            List<Component> lore = new ArrayList<>();
            if (item.buyable()) {
                int stack = Math.max(1, item.material().getMaxStackSize());
                lore.add(lang.get("shop.menu.buy-price", Placeholder.unparsed("each", price(item.buy())),
                        Placeholder.unparsed("stack", ShopService.coins(ShopPricing.cost(item.buy(), stack))),
                        Placeholder.unparsed("size", Integer.toString(stack))));
            }
            if (item.sellable()) {
                lore.add(lang.get("shop.menu.sell-price", Placeholder.unparsed("each", price(item.sell()))));
            }
            lore.add(Component.empty());
            if (item.buyable()) {
                lore.add(lang.get("shop.menu.buy-controls"));
            }
            if (item.sellable()) {
                lore.add(lang.get("shop.menu.sell-controls"));
            }
            set(i, Icons.icon(item.material(), Component.translatable(item.material().translationKey()), lore),
                    (p, click) -> click(p, item, click));
        }
        set(slot(5, 0), Icons.icon(Material.ARROW, lang.get("shop.menu.back")), (p, click) -> open(plugin, p));
        set(slot(5, 4), rateIcon(viewer));
        set(slot(5, 8), Icons.icon(Material.HOPPER, lang.get("shop.menu.sell-all"),
                List.of(lang.get("shop.menu.sell-all-lore"))), (p, click) -> {
                    p.closeInventory();
                    plugin.shop().sellAll(p);
                });
    }

    private void click(Player p, ShopItem item, ClickType click) {
        switch (click) {
            case LEFT -> {
                if (item.buyable()) {
                    plugin.shop().buy(p, item, 1);
                }
            }
            case SHIFT_LEFT -> {
                if (item.buyable()) {
                    plugin.shop().buy(p, item, Math.max(1, item.material().getMaxStackSize()));
                }
            }
            case RIGHT -> {
                if (item.sellable()) {
                    plugin.shop().sell(p, item.material(), 1);
                }
            }
            case SHIFT_RIGHT -> {
                if (item.sellable()) {
                    plugin.shop().sell(p, item.material(), Integer.MAX_VALUE);
                }
            }
            default -> {
            }
        }
    }

    private org.bukkit.inventory.ItemStack rateIcon(Player viewer) {
        Lang lang = plugin.lang();
        long sold = plugin.shop().soldToday(viewer);
        int rate = ShopPricing.ratePercent(sold, plugin.settings().shop());
        List<Component> lore = new ArrayList<>();
        lore.add(lang.get("shop.menu.rate", Placeholder.unparsed("rate", Integer.toString(rate)),
                Placeholder.unparsed("sold", ShopService.coins(sold)),
                Placeholder.unparsed("full", ShopService.coins(plugin.settings().shop().dailyFullRate()))));
        if (plugin.core().tailwind().active(viewer)) {
            lore.add(lang.get("shop.menu.tailwind",
                    Placeholder.unparsed("bonus", Integer.toString(plugin.core().tailwind().bonusPercent()))));
        }
        lore.add(lang.get("shop.menu.rate-hint"));
        return Icons.icon(Material.CLOCK, lang.get("shop.menu.rate-title"), lore);
    }

    /** 2 → "2", 0.5 → "0.50". */
    static String price(double p) {
        if (p == Math.rint(p)) {
            return String.format("%,d", (long) p);
        }
        return String.format(Locale.ROOT, "%.2f", p);
    }
}
