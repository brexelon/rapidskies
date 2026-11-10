package us.rapidskies.skyrealms.route;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.island.Islands.IslandRef;

/**
 * {@code /routes}: the island's Trade Routes (offers to answer, active routes with their next
 * delivery and contract chest, offers waiting on the other island) and its depot. Offers are
 * made with {@code /route offer} (typed terms; PLAN.md §3 keeps text input out of menus).
 */
public final class RoutesMenu extends Menu {

    private final RapidSkyRealms plugin;
    private final IslandRef island;
    private final Map<String, Long> depot;

    private RoutesMenu(RapidSkyRealms plugin, IslandRef island, Map<String, Long> depot) {
        super(depot == null ? 4 : 6, plugin.lang().get(depot == null ? "route.menu.title" : "route.menu.depot-title",
                Placeholder.unparsed("island", island.name())));
        this.plugin = plugin;
        this.island = island;
        this.depot = depot;
    }

    public static void open(RapidSkyRealms plugin, Player player) {
        Optional<IslandRef> island = plugin.islands().of(player);
        if (island.isEmpty()) {
            plugin.lang().send(player, "errors.no-island");
            return;
        }
        new RoutesMenu(plugin, island.get(), null).open(player);
    }

    public static void openDepot(RapidSkyRealms plugin, Player player) {
        Optional<IslandRef> island = plugin.islands().of(player);
        if (island.isEmpty()) {
            plugin.lang().send(player, "errors.no-island");
            return;
        }
        plugin.async(() -> plugin.repo().depot(island.get().id()),
                d -> new RoutesMenu(plugin, island.get(), d).open(player));
    }

    @Override
    protected void render(Player viewer) {
        if (depot == null) {
            renderRoutes();
        } else {
            renderDepot();
        }
        fill(Icons.filler());
    }

    private void renderRoutes() {
        Lang lang = plugin.lang();
        RouteService service = plugin.routes();
        List<Route> list = service.of(island.id());
        Instant now = Instant.now();
        for (int i = 0; i < list.size() && i < 27; i++) {
            Route r = list.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(lang.get("route.menu.terms", service.terms(r, island.id())));
            if (r.state() == Route.State.OFFERED) {
                boolean ours = !r.answeringIsland().equals(island.id());
                lore.add(lang.get(ours ? "route.menu.waiting" : "route.menu.answer"));
            } else {
                String due = r.nextDue().map(d -> duration(Duration.between(now, d))).orElse("?");
                lore.add(lang.get("route.menu.next", Placeholder.unparsed("time", due)));
                lore.add(lang.get("route.menu.deliveries", Placeholder.unparsed("count", Integer.toString(r.deliveries())),
                        Placeholder.unparsed("missed", Integer.toString(r.missed()))));
                if (r.sellerIsland().equals(island.id())) {
                    lore.add(r.chest().map(c -> lang.get("route.menu.chest", Placeholder.unparsed("x", Integer.toString(c.x())),
                            Placeholder.unparsed("y", Integer.toString(c.y())), Placeholder.unparsed("z", Integer.toString(c.z()))))
                            .orElse(lang.get("route.menu.no-chest", Placeholder.unparsed("id", Long.toString(r.id())))));
                }
                lore.add(lang.get("route.menu.cancel"));
            }
            var icon = Icons.icon(r.item(), lang.get("route.menu.route", service.terms(r, island.id())), lore);
            set(i, r.state() == Route.State.ACTIVE ? Icons.glow(icon) : icon, (p, click) -> click(p, r, click));
        }
        if (list.isEmpty()) {
            set(slot(1, 4), Icons.icon(Material.PAPER, lang.get("route.menu.none"), List.of(lang.get("route.menu.none-lore"))));
        }
        set(slot(3, 2), Icons.icon(Material.WRITABLE_BOOK, lang.get("route.menu.how"), lang.list("route.menu.how-lore")));
        set(slot(3, 6), Icons.icon(Material.BARREL, lang.get("route.menu.depot"), List.of(lang.get("route.menu.depot-lore"))),
                (p, click) -> openDepot(plugin, p));
    }

    private void click(Player p, Route r, ClickType click) {
        if (r.state() == Route.State.OFFERED && r.answeringIsland().equals(island.id())) {
            p.closeInventory();
            if (click.isLeftClick()) {
                plugin.routes().accept(p, r.id());
            } else if (click == ClickType.SHIFT_RIGHT) {
                plugin.routes().cancel(p, r.id());
            }
        } else if (click == ClickType.SHIFT_RIGHT) {
            p.closeInventory();
            plugin.routes().cancel(p, r.id());
        }
    }

    private void renderDepot() {
        Lang lang = plugin.lang();
        int i = 0;
        for (Map.Entry<String, Long> e : depot.entrySet()) {
            Material m = Material.matchMaterial(e.getKey());
            if (m == null || i >= 45) {
                continue;
            }
            long amount = e.getValue();
            set(i++, Icons.icon(m, Component.translatable(m.translationKey()), List.of(
                    lang.get("route.menu.depot-amount", Placeholder.unparsed("amount", String.format("%,d", amount))),
                    lang.get("route.menu.depot-take"))), (p, click) -> {
                        long want = click.isShiftClick() ? amount : Math.min(amount, Math.max(1, m.getMaxStackSize()));
                        plugin.routes().withdraw(p, island.id(), m, want, () -> openDepot(plugin, p));
                    });
        }
        if (depot.isEmpty()) {
            set(slot(2, 4), Icons.icon(Material.PAPER, lang.get("route.menu.depot-empty")));
        }
        set(slot(5, 0), Icons.icon(Material.ARROW, lang.get("route.menu.back")),
                (p, click) -> new RoutesMenu(plugin, island, null).open(p));
    }

    /** 3d 4h, 5h 10m, 12m. */
    static String duration(Duration d) {
        long s = Math.max(0, d.getSeconds());
        long days = s / 86_400;
        long hours = s % 86_400 / 3600;
        long minutes = s % 3600 / 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(1, minutes) + "m";
    }
}
