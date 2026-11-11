package us.rapidskies.core.shop;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.shop.FeaturedRotation;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.cosmetic.Catalog;
import us.rapidskies.core.cosmetic.Cosmetic;
import us.rapidskies.core.cosmetic.CosmeticType;
import us.rapidskies.core.cosmetic.CosmeticsMenu;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.core.gui.Pages;
import us.rapidskies.core.progress.ProgressConfig;
import us.rapidskies.core.rank.PlusChoice;

/**
 * {@code /boltshop}: everything Bolts buy (PLAN.md §6.2). Tabs for the Featured Shop, each
 * cosmetic type, rank passes and the Season Pass; every purchase goes through a confirm
 * screen. Six rows:
 *
 * <pre>
 *   row 0   [featured][title][tag][name color][badge][trail][arrow][ranks][pass]
 *   rows 1–4  . [28 items] .
 *   row 5   [prev] . [wardrobe] . [balance] . . . [next]
 * </pre>
 */
public final class ShopMenu extends Menu {

    private enum Tab {
        FEATURED(Material.BEACON), TITLE(Material.NAME_TAG), TAG(Material.NETHER_STAR), NAME_COLOR(Material.LIME_DYE),
        BADGE(Material.GOLD_NUGGET), TRAIL(Material.BLAZE_POWDER), ARROW(Material.ARROW), RANKS(Material.GOLD_INGOT),
        PASS(Material.EXPERIENCE_BOTTLE);

        final Material icon;

        Tab(Material icon) {
            this.icon = icon;
        }

        Optional<CosmeticType> cosmeticType() {
            return switch (this) {
                case TITLE -> Optional.of(CosmeticType.TITLE);
                case TAG -> Optional.of(CosmeticType.TAG);
                case NAME_COLOR -> Optional.of(CosmeticType.NAME_COLOR);
                case BADGE -> Optional.of(CosmeticType.BADGE);
                case TRAIL -> Optional.of(CosmeticType.TRAIL);
                case ARROW -> Optional.of(CosmeticType.ARROW);
                default -> Optional.empty();
            };
        }

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    private static final int WARDROBE = 47;
    private static final int BALANCE = 49;

    private final BoltShop shop;
    private final Lang lang;
    private final Consumer<Player> openWardrobe;
    private final Consumer<Player> openPass;
    private Tab tab = Tab.FEATURED;
    private int page;

    public ShopMenu(BoltShop shop, Consumer<Player> openWardrobe, Consumer<Player> openPass) {
        super(6, shop.lang().get("shop.menu.title"));
        this.shop = shop;
        this.lang = shop.lang();
        this.openWardrobe = openWardrobe;
        this.openPass = openPass;
    }

    /** Opens on the Season Pass. */
    public ShopMenu pass() {
        tab = Tab.PASS;
        return this;
    }

    /** Opens on the rank passes. */
    public ShopMenu ranks() {
        tab = Tab.RANKS;
        return this;
    }

    @Override
    protected void render(Player viewer) {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            boolean selected = t == tab;
            Component name = t.cosmeticType()
                    .map(ct -> lang.get("shop.menu.tab", Placeholder.component("name", lang.get("cosmetics.type." + ct.id()))))
                    .orElseGet(() -> lang.get("shop.menu.tab-" + t.key()));
            ItemStack icon = Icons.icon(t.icon, name,
                    List.of(lang.get(selected ? "shop.menu.tab-selected" : "shop.menu.tab-click")));
            set(i, selected ? Icons.glow(icon) : icon, selected ? null : (p, click) -> {
                tab = t;
                page = 0;
                refresh(p);
            });
        }
        String balance = shop.boltsCache().cached(viewer.getUniqueId()).stream()
                .mapToObj(b -> String.format("%,d", b)).findFirst().orElse("…");
        set(BALANCE, Icons.icon(Material.SUNFLOWER, lang.get("shop.menu.balance",
                Placeholder.unparsed("balance", balance)), lang.list("shop.menu.balance-lore")));
        set(WARDROBE, Icons.icon(Material.ARMOR_STAND, lang.get("shop.menu.wardrobe")), (p, click) -> openWardrobe.accept(p));

        switch (tab) {
            case RANKS -> renderRanks(viewer);
            case PASS -> renderPass(viewer);
            default -> renderCosmetics(viewer);
        }
        fill(Icons.filler());
    }

    private void renderCosmetics(Player viewer) {
        List<Cosmetic> items;
        Instant now = Instant.now();
        if (tab == Tab.FEATURED) {
            items = shop.featured();
            long week = FeaturedRotation.week(now);
            Duration left = Duration.between(now, FeaturedRotation.endOf(week));
            set(4 + 36, Icons.icon(Material.CLOCK, lang.get("shop.menu.featured-ends",
                    Placeholder.unparsed("time", Durations.format(left.withNanos(0))))));
        } else {
            CosmeticType type = tab.cosmeticType().orElseThrow();
            items = shop.catalog().ofType(type).stream().filter(c -> shop.catalog().onSale(c, now)).toList();
        }
        int pages = Pages.count(items.size());
        page = Math.clamp(page, 0, pages - 1);
        List<Cosmetic> shown = Pages.page(items, page);
        for (int i = 0; i < shown.size(); i++) {
            Cosmetic c = shown.get(i);
            boolean owned = shop.cosmetics().has(viewer, c);
            set(tab == Tab.FEATURED ? featuredSlot(i, shown.size()) : Pages.SLOTS[i], item(viewer, c, owned),
                    owned ? null : (p, click) -> confirm(p, c));
        }
        if (items.isEmpty()) {
            set(22, Icons.icon(Material.GRAY_DYE, lang.get("shop.menu.empty")));
        }
        paging(pages);
    }

    /** Featured items sit centred on the middle row. */
    private static int featuredSlot(int i, int count) {
        return count <= 5 ? 22 - (count - 1) + i * 2 : Pages.SLOTS[i];
    }

    private ItemStack item(Player viewer, Cosmetic c, boolean owned) {
        List<Component> lore = new ArrayList<>(CosmeticsMenu.describe(lang, viewer, c));
        if (c.obtain() == Cosmetic.Obtain.FEATURED) {
            lore.add(lang.get("shop.menu.featured-only"));
        }
        lore.add(Component.empty());
        if (owned) {
            lore.add(lang.get("shop.menu.owned"));
        } else {
            lore.add(lang.get("shop.menu.price", Placeholder.unparsed("price", String.format("%,d", c.price()))));
            lore.add(lang.get("shop.menu.click-buy"));
        }
        ItemStack icon = Icons.icon(Material.valueOf(c.icon()), CosmeticsMenu.title(lang, c), lore);
        return owned ? Icons.glow(icon) : icon;
    }

    private void confirm(Player player, Cosmetic c) {
        new ConfirmMenu(lang, item(player, c, false),
                lang.get("shop.confirm.buy", Placeholder.unparsed("price", String.format("%,d", c.price()))),
                lang.list("shop.confirm.buy-lore", Placeholder.unparsed("name", c.name())),
                p -> shop.buy(p, c, () -> { }), this::open).open(player);
    }

    private void renderRanks(Player viewer) {
        Rank current = shop.ranks().rank(viewer);
        int[] slots = {20, 22, 24};
        int i = 0;
        for (Catalog.RankPass pass : shop.catalog().rankPasses().values()) {
            if (i >= slots.length) {
                break;
            }
            String rank = PlusChoice.rankName(pass.rank());
            List<Component> lore = new ArrayList<>(lang.list("shop.rank.perks." + pass.rank().group()));
            lore.add(Component.empty());
            lore.add(lang.get("shop.rank.length", Placeholder.unparsed("days", Integer.toString(pass.days()))));
            lore.add(lang.get("shop.menu.price", Placeholder.unparsed("price", String.format("%,d", pass.price()))));
            boolean higher = current.tier() > pass.rank().tier();
            lore.add(lang.get(higher ? "shop.rank.have-higher" : current == pass.rank() ? "shop.rank.click-extend"
                    : "shop.rank.click-buy"));
            ItemStack icon = Icons.icon(Material.valueOf(pass.icon()), lang.get("shop.rank.name",
                    Placeholder.component("rankprefix", shop.ranks().rankPrefix(pass.rank(), shop.ranks().of(viewer).plusColor())),
                    Placeholder.unparsed("rank", rank)), lore);
            set(slots[i++], icon, higher ? null : (p, click) -> new ConfirmMenu(lang, icon,
                    lang.get("shop.confirm.buy", Placeholder.unparsed("price", String.format("%,d", pass.price()))),
                    lang.list("shop.confirm.rank-lore", Placeholder.unparsed("rank", rank),
                            Placeholder.unparsed("days", Integer.toString(pass.days()))),
                    q -> shop.buy(q, pass, () -> { }), this::open).open(p));
        }
        set(4 + 36, Icons.icon(Material.BOOK, lang.get("shop.rank.info-name"), lang.list("shop.rank.info-lore")));
    }

    private void renderPass(Player viewer) {
        Optional<ProgressConfig.Season> season = shop.levels().season();
        if (season.isEmpty()) {
            Optional<ProgressConfig.Season> next = shop.levels().config().nextSeason(Instant.now());
            set(22, Icons.icon(Material.CLOCK, lang.get("pass.none-name"), next.map(s -> lang.list("pass.next-lore",
                    Placeholder.unparsed("season", s.name()), Placeholder.unparsed("date", s.starts().toString())))
                    .orElseGet(() -> lang.list("pass.none-lore"))));
            return;
        }
        ProgressConfig.Season s = season.get();
        boolean premium = shop.levels().standing(viewer).premium();
        List<Component> lore = new ArrayList<>(lang.list("shop.pass.lore", Placeholder.unparsed("season", s.name()),
                Placeholder.unparsed("tiers", Integer.toString(s.tiers()))));
        lore.add(Component.empty());
        if (premium) {
            lore.add(lang.get("shop.pass.owned"));
        } else {
            lore.add(lang.get("shop.menu.price", Placeholder.unparsed("price", String.format("%,d", s.premiumPrice()))));
            lore.add(lang.get("shop.menu.click-buy"));
        }
        ItemStack icon = Icons.icon(Material.NETHER_STAR, lang.get("shop.pass.name",
                Placeholder.unparsed("season", s.name())), lore);
        set(21, premium ? Icons.glow(icon) : icon, premium ? null : (p, click) -> new ConfirmMenu(lang, icon,
                lang.get("shop.confirm.buy", Placeholder.unparsed("price", String.format("%,d", s.premiumPrice()))),
                lang.list("shop.confirm.pass-lore", Placeholder.unparsed("season", s.name())),
                q -> shop.buyPremium(q, s, () -> { }), this::open).open(p));
        set(23, Icons.icon(Material.EXPERIENCE_BOTTLE, lang.get("shop.pass.view")), (p, click) -> openPass.accept(p));
    }

    private void paging(int pages) {
        if (page > 0) {
            set(Pages.PREVIOUS, Icons.icon(Material.ARROW, lang.get("cosmetics.menu.previous")), (p, click) -> {
                page--;
                refresh(p);
            });
        }
        if (page < pages - 1) {
            set(Pages.NEXT, Icons.icon(Material.ARROW, lang.get("cosmetics.menu.next",
                    Placeholder.unparsed("page", Integer.toString(page + 2)),
                    Placeholder.unparsed("pages", Integer.toString(pages)))), (p, click) -> {
                page++;
                refresh(p);
            });
        }
    }
}
