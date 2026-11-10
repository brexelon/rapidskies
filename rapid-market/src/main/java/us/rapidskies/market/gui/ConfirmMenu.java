package us.rapidskies.market.gui;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.market.RapidMarket;

/**
 * A yes/no menu around one item: buying a listing, cancelling your own, staff taking one
 * down. Confirm on the left, the item in the middle, back on the right, details below.
 */
final class ConfirmMenu extends Menu {

    private final RapidMarket plugin;
    private final ItemStack shown;
    private final String confirmKey;
    private final List<Component> details;
    private final Runnable confirm;
    private final Runnable back;

    ConfirmMenu(RapidMarket plugin, Component title, ItemStack shown, String confirmKey, List<Component> details,
                Runnable confirm, Runnable back) {
        super(3, title);
        this.plugin = plugin;
        this.shown = shown;
        this.confirmKey = confirmKey;
        this.details = details;
        this.confirm = confirm;
        this.back = back;
    }

    @Override
    protected void render(Player viewer) {
        var lang = plugin.lang();
        set(slot(1, 2), Icons.icon(Material.LIME_STAINED_GLASS_PANE, lang.get(confirmKey + ".name"),
                lang.list(confirmKey + ".lore")), (p, click) -> {
                    p.closeInventory();
                    confirm.run();
                });
        set(slot(1, 4), shown);
        set(slot(1, 6), Icons.icon(Material.RED_STAINED_GLASS_PANE, lang.get("menu.back.name"),
                lang.list("menu.back.lore")), (p, click) -> back.run());
        if (!details.isEmpty()) {
            set(slot(2, 4), Icons.icon(Material.PAPER, details.getFirst(), details.subList(1, details.size())));
        }
        fill(Icons.filler());
    }
}
