package us.rapidskies.core.staff;

import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * A read-only snapshot of a player's inventory or ender chest (PLAN.md §9). Items are
 * copies and the menu framework cancels every click, so inspecting can never move, take or
 * duplicate anything. Refresh takes a new snapshot.
 *
 * <pre>
 *   inventory (6 rows)                       ender chest (3 rows)
 *   rows 0-2  main inventory (slots 9-35)    rows 0-2  ender chest
 *   row 3     hotbar (slots 0-8)
 *   row 4     helmet chest legs boots . offhand
 *   row 5     . . . [info] [refresh] . . . .
 * </pre>
 */
final class InspectMenu extends Menu {

    private final Lang lang;
    private final Player target;
    private final boolean ender;

    private InspectMenu(Lang lang, Player target, boolean ender) {
        super(ender ? 4 : 6, lang.get(ender ? "staff.inspect.ender-title" : "staff.inspect.title",
                Placeholder.unparsed("player", target.getName())));
        this.lang = lang;
        this.target = target;
        this.ender = ender;
    }

    static void inventory(Lang lang, Player staff, Player target) {
        new InspectMenu(lang, target, false).open(staff);
    }

    static void enderChest(Lang lang, Player staff, Player target) {
        new InspectMenu(lang, target, true).open(staff);
    }

    @Override
    protected void render(Player viewer) {
        if (ender) {
            ItemStack[] items = target.getEnderChest().getContents();
            for (int i = 0; i < items.length && i < 27; i++) {
                set(i, copy(items[i]));
            }
            set(slot(3, 4), refreshIcon(), (p, click) -> refreshOrClose(p));
            return;
        }
        PlayerInventory inv = target.getInventory();
        for (int i = 9; i < 36; i++) {
            set(i - 9, copy(inv.getItem(i)));
        }
        for (int i = 0; i < 9; i++) {
            set(slot(3, i), copy(inv.getItem(i)));
        }
        set(slot(4, 0), copy(inv.getHelmet()));
        set(slot(4, 1), copy(inv.getChestplate()));
        set(slot(4, 2), copy(inv.getLeggings()));
        set(slot(4, 3), copy(inv.getBoots()));
        set(slot(4, 5), copy(inv.getItemInOffHand()));
        Location at = target.getLocation();
        set(slot(5, 3), Icons.icon(Material.NAME_TAG, lang.get("staff.inspect.info-name",
                        Placeholder.unparsed("player", target.getName())),
                List.of(lang.get("staff.inspect.info-health",
                                Placeholder.unparsed("health", String.format("%.1f", target.getHealth())),
                                Placeholder.unparsed("food", Integer.toString(target.getFoodLevel()))),
                        lang.get("staff.inspect.info-mode",
                                Placeholder.unparsed("mode", target.getGameMode().name().toLowerCase(java.util.Locale.ROOT))),
                        lang.get("staff.inspect.info-location",
                                Placeholder.unparsed("world", at.getWorld().getName()),
                                Placeholder.unparsed("x", Integer.toString(at.getBlockX())),
                                Placeholder.unparsed("y", Integer.toString(at.getBlockY())),
                                Placeholder.unparsed("z", Integer.toString(at.getBlockZ()))))));
        set(slot(5, 5), refreshIcon(), (p, click) -> refreshOrClose(p));
    }

    private ItemStack refreshIcon() {
        return Icons.icon(Material.CLOCK, lang.get("staff.inspect.refresh"));
    }

    private void refreshOrClose(Player viewer) {
        if (Bukkit.getPlayer(target.getUniqueId()) == null) {
            viewer.closeInventory();
            lang.send(viewer, "staff.inspect.gone", Placeholder.unparsed("player", target.getName()));
            return;
        }
        refresh(viewer);
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }
}
