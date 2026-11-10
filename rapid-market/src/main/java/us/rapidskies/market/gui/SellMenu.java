package us.rapidskies.market.gui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.market.Market;
import us.rapidskies.market.RapidMarket;
import us.rapidskies.market.model.Fees;

/**
 * Listing an item ({@code /ah sell <price>}): the item as buyers will see it, the fee, the
 * tax and what's left, the average price lately (PLAN.md §4.2: shown when listing), and a
 * button per allowed duration. Nothing leaves the inventory until Confirm.
 */
public final class SellMenu extends Menu {

    private final RapidMarket plugin;
    private Market.SellDraft draft;

    public SellMenu(RapidMarket plugin, Market.SellDraft draft) {
        super(3, plugin.lang().get("menu.sell.title"));
        this.plugin = plugin;
        this.draft = draft;
    }

    @Override
    protected void render(Player viewer) {
        var lang = plugin.lang();
        var market = plugin.market();
        Fees fees = plugin.settings().fees();
        long price = draft.price();
        TagResolver[] r = {
                Placeholder.unparsed("item", draft.itemName()),
                Placeholder.unparsed("amount", Integer.toString(draft.snapshot().getAmount())),
                market.money("price", price),
                market.unitMoney("each", (double) price / draft.snapshot().getAmount()),
                market.money("fee", fees.listingFee(price)),
                market.money("tax", fees.tax(price)),
                market.money("proceeds", fees.proceeds(price)),
                BrowseMenu.average(plugin, draft.average()),
                Placeholder.unparsed("days", Integer.toString(plugin.settings().historyDays())),
                Placeholder.unparsed("time", Durations.format(draft.duration())),
        };

        List<Component> preview = new ArrayList<>(lang.list("menu.sell.preview", r));
        set(slot(0, 4), ListingIcons.withLore(plugin, draft.snapshot().serializeAsBytes(), preview));

        List<Duration> durations = plugin.settings().durations();
        int start = 4 - (durations.size() - 1);
        for (int i = 0; i < durations.size(); i++) {
            Duration d = durations.get(i);
            boolean selected = d.equals(draft.duration());
            ItemStack icon = Icons.icon(Material.CLOCK, lang.get("menu.sell.duration.name",
                    Placeholder.unparsed("time", Durations.format(d))),
                    lang.list(selected ? "menu.sell.duration.selected" : "menu.sell.duration.pick"));
            set(slot(1, Math.max(0, Math.min(8, start + i * 2))), selected ? Icons.glow(icon) : icon, (p, click) -> {
                draft = draft.withDuration(d);
                refresh(p);
            });
        }

        set(slot(2, 2), Icons.icon(Material.LIME_STAINED_GLASS_PANE, lang.get("menu.sell.confirm.name", r),
                lang.list("menu.sell.confirm.lore", r)), (p, click) -> market.list(p, draft));
        set(slot(2, 4), Icons.icon(Material.PAPER, lang.get("menu.sell.details.name", r),
                lang.list("menu.sell.details.lore", r)));
        set(slot(2, 6), Icons.icon(Material.RED_STAINED_GLASS_PANE, lang.get("menu.sell.cancel.name"),
                lang.list("menu.sell.cancel.lore")), (p, click) -> p.closeInventory());
        fill(Icons.filler());
    }
}
