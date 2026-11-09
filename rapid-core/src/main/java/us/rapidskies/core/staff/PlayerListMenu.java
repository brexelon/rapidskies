package us.rapidskies.core.staff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * Staff mode's player list: everyone on this server, a page of 45 at a time. Click a head
 * to teleport to that player; shift-click to inspect their inventory.
 */
final class PlayerListMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final Lang lang;
    private final StaffMode staffMode;
    private int page;

    PlayerListMenu(Lang lang, StaffMode staffMode) {
        super(6, lang.get("staff.players.title"));
        this.lang = lang;
        this.staffMode = staffMode;
    }

    @Override
    protected void render(Player viewer) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.remove(viewer);
        players.sort(Comparator.comparing(p -> p.getName().toLowerCase(java.util.Locale.ROOT)));
        int pages = Math.max(1, (players.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        List<Player> shown = players.subList(page * PER_PAGE, Math.min(players.size(), (page + 1) * PER_PAGE));
        for (int i = 0; i < shown.size(); i++) {
            Player target = shown.get(i);
            set(i, head(target), (staff, click) -> {
                if (!target.isOnline()) {
                    refresh(staff);
                } else if (click.isShiftClick()) {
                    InspectMenu.inventory(lang, staff, target);
                } else {
                    staff.closeInventory();
                    staffMode.teleport(staff, target);
                }
            });
        }
        if (page > 0) {
            set(slot(5, 3), Icons.icon(Material.ARROW, lang.get("staff.players.previous")), (p, c) -> {
                page--;
                refresh(p);
            });
        }
        set(slot(5, 4), Icons.icon(Material.PAPER, lang.get("staff.players.page",
                Placeholder.unparsed("page", Integer.toString(page + 1)),
                Placeholder.unparsed("pages", Integer.toString(pages)))));
        if (page < pages - 1) {
            set(slot(5, 5), Icons.icon(Material.ARROW, lang.get("staff.players.next")), (p, c) -> {
                page++;
                refresh(p);
            });
        }
    }

    private ItemStack head(Player target) {
        List<Component> lore = new ArrayList<>();
        if (staffMode.freeze().isFrozen(target)) {
            lore.add(lang.get("staff.players.frozen"));
        }
        if (staffMode.vanish().isVanished(target)) {
            lore.add(lang.get("staff.players.vanished"));
        }
        lore.add(lang.get("staff.players.click"));
        ItemStack item = Icons.icon(Material.PLAYER_HEAD,
                lang.get("staff.players.name", Placeholder.unparsed("player", target.getName())), lore);
        item.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(target));
        return item;
    }
}
