package us.rapidskies.horizons.trade;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.horizons.RapidHorizons;

/**
 * {@code /trades}: every Trade with its level and progress; click one to take it up,
 * shift-click an active one to drop it (shift, so a stray click never starts the cooldown).
 *
 * <pre>
 *   row 0  . . . . [your trades] . . . .
 *   row 1  . [miner][farmer][hunter] . [builder][enchanter][fisher] .
 *   row 2  . . . . . . . . .
 * </pre>
 */
public final class TradesMenu extends Menu {

    private static final int INFO_SLOT = 4;
    private static final int[] TRADE_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int BAR_LENGTH = 20;

    private final RapidHorizons plugin;
    private final TradeService trades;

    static {
        if (TRADE_SLOTS.length < Trade.values().length) {
            throw new IllegalStateException("TradesMenu needs a slot for every Trade");
        }
    }

    public TradesMenu(RapidHorizons plugin) {
        super(3, plugin.lang().get("trades.menu.title"));
        this.plugin = plugin;
        this.trades = plugin.trades();
    }

    @Override
    protected void render(Player viewer) {
        Lang lang = plugin.lang();
        TradeRules rules = plugin.settings().trades().rules();
        Duration cooldown = trades.dropCooldown(viewer);
        List<Component> info = new ArrayList<>();
        info.add(lang.get("trades.menu.info-active",
                Placeholder.unparsed("count", Integer.toString(trades.activeCount(viewer.getUniqueId()))),
                Placeholder.unparsed("max", Integer.toString(rules.maxActive()))));
        info.add(cooldown.isZero() ? lang.get("trades.menu.info-switch-ready")
                : lang.get("trades.menu.info-switch-wait", Placeholder.unparsed("time", Durations.format(cooldown))));
        info.addAll(lang.list("trades.menu.info-help"));
        set(INFO_SLOT, Icons.icon(Material.BOOK, lang.get("trades.menu.info-name"), info));

        List<TradeService.View> views = trades.view(viewer.getUniqueId());
        for (int i = 0; i < views.size(); i++) {
            TradeService.View v = views.get(i);
            set(TRADE_SLOTS[i], icon(v, rules), (p, click) -> click(p, v, click));
        }
        fill(Icons.filler());
    }

    private ItemStack icon(TradeService.View v, TradeRules rules) {
        Lang lang = plugin.lang();
        Component name = trades.name(v.trade());
        List<Component> lore = new ArrayList<>();
        lore.addAll(lang.list("trades.about." + v.trade().id()));
        lore.add(Component.empty());
        lore.add(lang.get("trades.menu.level", Placeholder.unparsed("level", Integer.toString(v.level())),
                Placeholder.unparsed("max", Integer.toString(rules.maxLevel()))));
        int filled = (int) Math.round(v.progress() * BAR_LENGTH);
        lore.add(lang.get("trades.menu.progress",
                Placeholder.parsed("bar", "<accent>" + "|".repeat(filled) + "</accent><dark>"
                        + "|".repeat(BAR_LENGTH - filled) + "</dark>"),
                Placeholder.unparsed("percent", Integer.toString((int) Math.floor(v.progress() * 100)))));
        lore.add(v.active() ? lang.get("trades.menu.active") : lang.get("trades.menu.inactive"));
        lore.add(v.active() ? lang.get("trades.menu.click-drop") : lang.get("trades.menu.click-pick"));
        Material material = Material.matchMaterial(v.trade().icon);
        ItemStack item = Icons.icon(material == null ? Material.PAPER : material,
                lang.get("trades.menu.trade-name", Placeholder.component("trade", name)), lore);
        return v.active() ? Icons.glow(item) : item;
    }

    private void click(Player player, TradeService.View v, ClickType click) {
        if (v.active()) {
            if (!click.isShiftClick()) {
                plugin.lang().send(player, "trades.shift-to-drop");
                return;
            }
            trades.drop(player, v.trade());
        } else {
            trades.pick(player, v.trade());
        }
        if (player.getOpenInventory().getTopInventory().getHolder(false) == this) {
            refresh(player);
        }
    }
}
