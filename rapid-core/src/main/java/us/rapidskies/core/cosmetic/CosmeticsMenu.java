package us.rapidskies.core.cosmetic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.core.gui.Pages;
import us.rapidskies.core.rank.PlusChoice;

/**
 * {@code /cosmetics}: the wardrobe (PLAN.md §6.1, §6.2). A tab per slot; the player's
 * cosmetics first (click to wear, click the worn one to take it off), then the locked ones
 * with how to get them. Six rows:
 *
 * <pre>
 *   row 0   [info] [title][tag][name color][badge][trail][arrow] . [shop]
 *   rows 1–4  . [28 cosmetics] .
 *   row 5   [prev] . . [take off] . . . . [next]
 * </pre>
 */
public final class CosmeticsMenu extends Menu {

    private static final int INFO = 0;
    private static final int SHOP = 8;
    private static final int TAKE_OFF = 48;

    private final CosmeticService cosmetics;
    private final Lang lang;
    private final Consumer<Player> openShop;
    private CosmeticType type;
    private int page;

    public CosmeticsMenu(CosmeticService cosmetics, Lang lang, CosmeticType type, Consumer<Player> openShop) {
        super(6, lang.get("cosmetics.menu.title"));
        this.cosmetics = cosmetics;
        this.lang = lang;
        this.type = type;
        this.openShop = openShop;
    }

    @Override
    protected void render(Player viewer) {
        set(INFO, Icons.icon(Material.ARMOR_STAND, lang.get("cosmetics.menu.info-name"),
                lang.list("cosmetics.menu.info-lore",
                        Placeholder.unparsed("owned", Integer.toString(cosmetics.ownedCount(viewer))))));
        CosmeticType[] types = CosmeticType.values();
        for (int i = 0; i < types.length; i++) {
            CosmeticType t = types[i];
            boolean selected = t == type;
            Optional<Cosmetic> worn = cosmetics.worn(viewer, t);
            List<Component> lore = new ArrayList<>();
            lore.add(lang.get("cosmetics.type-lore." + t.id()));
            lore.add(worn.map(c -> lang.get("cosmetics.menu.tab-wearing", Placeholder.unparsed("name", c.name())))
                    .orElseGet(() -> lang.get("cosmetics.menu.tab-nothing")));
            lore.add(lang.get(selected ? "cosmetics.menu.tab-selected" : "cosmetics.menu.tab-click"));
            ItemStack icon = Icons.icon(tabIcon(t), lang.get("cosmetics.menu.tab-name",
                    Placeholder.component("type", lang.get("cosmetics.type." + t.id()))), lore);
            set(1 + i, selected ? Icons.glow(icon) : icon, selected ? null : (p, click) -> {
                type = t;
                page = 0;
                refresh(p);
            });
        }
        set(SHOP, Icons.icon(Material.EMERALD, lang.get("cosmetics.menu.shop-name"),
                lang.list("cosmetics.menu.shop-lore")), (p, click) -> openShop.accept(p));

        List<Cosmetic> all = cosmetics.listing(viewer, type);
        int pages = Pages.count(all.size());
        page = Math.clamp(page, 0, pages - 1);
        List<Cosmetic> shown = Pages.page(all, page);
        String wornId = cosmetics.equippedId(viewer, type).orElse(null);
        for (int i = 0; i < shown.size(); i++) {
            Cosmetic c = shown.get(i);
            boolean owned = cosmetics.has(viewer, c);
            boolean wearing = owned && c.id().equals(wornId);
            set(Pages.SLOTS[i], icon(viewer, c, owned, wearing), (p, click) -> click(p, c, owned, wearing));
        }
        if (all.isEmpty()) {
            set(22, Icons.icon(Material.GRAY_DYE, lang.get("cosmetics.menu.empty")));
        }
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
        if (wornId != null) {
            set(TAKE_OFF, Icons.icon(Material.BARRIER, lang.get("cosmetics.menu.take-off",
                    Placeholder.component("type", lang.get("cosmetics.type." + type.id())))),
                    (p, click) -> cosmetics.unequip(p, type, () -> refreshIfOpen(p)));
        }
        fill(Icons.filler());
    }

    private void click(Player player, Cosmetic c, boolean owned, boolean wearing) {
        if (wearing) {
            cosmetics.unequip(player, c.type(), () -> refreshIfOpen(player));
        } else if (owned) {
            cosmetics.equip(player, c, () -> refreshIfOpen(player));
        } else if (c.obtain().bought()) {
            openShop.accept(player);
        }
    }

    private void refreshIfOpen(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) == this) {
            refresh(player);
        }
    }

    private ItemStack icon(Player viewer, Cosmetic c, boolean owned, boolean wearing) {
        List<Component> lore = new ArrayList<>(describe(lang, viewer, c));
        lore.add(Component.empty());
        if (wearing) {
            lore.add(lang.get("cosmetics.menu.wearing"));
        } else if (owned) {
            lore.add(lang.get("cosmetics.menu.click-wear"));
        } else {
            lore.add(how(lang, cosmetics.catalog(), c));
        }
        ItemStack icon = Icons.icon(owned ? Material.valueOf(c.icon()) : Material.GRAY_DYE, title(lang, c), lore);
        return wearing ? Icons.glow(icon) : icon;
    }

    /** The menu name of a cosmetic: titles, tags and badges as they render, the rest by name. */
    public static Component title(Lang lang, Cosmetic c) {
        return switch (c.type()) {
            case TITLE, TAG, BADGE -> lang.get("cosmetics.item-name.rendered",
                    Placeholder.component("display", Lang.parse(c.display())), Placeholder.unparsed("name", c.name()));
            case NAME_COLOR -> lang.get("cosmetics.item-name.plain", Placeholder.unparsed("name", c.name()))
                    .color(c.nameColor());
            default -> lang.get("cosmetics.item-name.plain", Placeholder.unparsed("name", c.name()));
        };
    }

    /** Type, rarity and a preview of how it looks on the viewer. */
    public static List<Component> describe(Lang lang, Player viewer, Cosmetic c) {
        TagResolver[] tags = {
            Placeholder.component("type", lang.get("cosmetics.type." + c.type().id())),
            Placeholder.component("rarity", lang.get("cosmetics.rarity." + c.rarity().id())),
            Placeholder.unparsed("player", viewer.getName()),
            Placeholder.component("display", Lang.parse(c.display())),
            Placeholder.unparsed("name", c.name())};
        List<Component> lore = new ArrayList<>();
        lore.add(lang.get("cosmetics.lore.kind", tags));
        Component preview = switch (c.type()) {
            case NAME_COLOR -> lang.get("cosmetics.lore.preview-namecolor",
                    Placeholder.component("name", Component.text(viewer.getName(), c.nameColor())));
            default -> lang.get("cosmetics.lore.preview-" + c.type().id(), tags);
        };
        lore.add(preview);
        return lore;
    }

    /** How to get a locked cosmetic. */
    public static Component how(Lang lang, Catalog catalog, Cosmetic c) {
        String price = String.format("%,d", c.price());
        return switch (c.obtain()) {
            case SHOP -> lang.get("cosmetics.how.shop", Placeholder.unparsed("price", price));
            case FEATURED -> catalog.onSale(c, Instant.now())
                    ? lang.get("cosmetics.how.featured-now", Placeholder.unparsed("price", price))
                    : lang.get("cosmetics.how.featured");
            case RANK -> lang.get("cosmetics.how.rank", Placeholder.unparsed("rank", PlusChoice.rankName(c.rank())));
            default -> c.hintText().map(Lang::parse).orElseGet(() -> lang.get("cosmetics.how." + c.obtain().id()));
        };
    }

    private static Material tabIcon(CosmeticType t) {
        return switch (t) {
            case TITLE -> Material.NAME_TAG;
            case TAG -> Material.NETHER_STAR;
            case NAME_COLOR -> Material.LIME_DYE;
            case BADGE -> Material.GOLD_NUGGET;
            case TRAIL -> Material.BLAZE_POWDER;
            case ARROW -> Material.ARROW;
        };
    }
}
