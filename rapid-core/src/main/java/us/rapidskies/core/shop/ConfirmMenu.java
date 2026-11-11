package us.rapidskies.core.shop;

import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * "Buy this for N Bolts?" before anything is charged, so a stray click in the shop never
 * spends Bolts. The buttons say what they do in their names (green and red alone aren't
 * enough for colorblind players). Three rows: confirm, the item, cancel.
 */
final class ConfirmMenu extends Menu {

    private final ItemStack item;
    private final Component confirm;
    private final List<Component> confirmLore;
    private final Consumer<Player> onConfirm;
    private final Consumer<Player> onCancel;
    private final Lang lang;
    private boolean done;

    ConfirmMenu(Lang lang, ItemStack item, Component confirm, List<Component> confirmLore, Consumer<Player> onConfirm,
                Consumer<Player> onCancel) {
        super(3, lang.get("shop.confirm.title"));
        this.lang = lang;
        this.item = item;
        this.confirm = confirm;
        this.confirmLore = confirmLore;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected void render(Player viewer) {
        set(11, Icons.icon(Material.LIME_CONCRETE, confirm, confirmLore), (p, click) -> {
            if (done) {
                return;
            }
            done = true;
            p.closeInventory();
            onConfirm.accept(p);
        });
        set(13, item);
        set(15, Icons.icon(Material.RED_CONCRETE, lang.get("shop.confirm.cancel")), (p, click) -> {
            done = true;
            onCancel.accept(p);
        });
        fill(Icons.filler());
    }
}
