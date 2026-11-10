package us.rapidskies.skyrealms.tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.SkyRealmsSettings.Tier;
import us.rapidskies.skyrealms.island.Islands.IslandRef;

/**
 * {@code /realm}: the tier path across the top (reached, current, locked), the goals of the
 * next tier with progress, and buttons to ascend, change the island's biome and see the
 * generator's odds. Everything is in names and lore, never the material alone (PLAN.md §3).
 */
public final class RealmMenu extends Menu {

    private final RapidSkyRealms plugin;
    private final IslandRef island;
    private final boolean biomes;

    private RealmMenu(RapidSkyRealms plugin, IslandRef island, boolean biomes) {
        super(biomes ? 3 : 5, plugin.lang().get(biomes ? "realm.menu.biome-title" : "realm.menu.title",
                Placeholder.unparsed("island", island.name())));
        this.plugin = plugin;
        this.island = island;
        this.biomes = biomes;
    }

    public static void open(RapidSkyRealms plugin, Player player) {
        Optional<IslandRef> island = plugin.islands().of(player);
        if (island.isEmpty()) {
            plugin.lang().send(player, "errors.no-island");
            return;
        }
        new RealmMenu(plugin, island.get(), false).open(player);
    }

    @Override
    protected void render(Player viewer) {
        if (biomes) {
            renderBiomes();
        } else {
            renderMain(viewer);
        }
        fill(Icons.filler());
    }

    private void renderMain(Player viewer) {
        Lang lang = plugin.lang();
        TierService tiers = plugin.tiers();
        Tier current = tiers.tier(island.id());
        List<Tier> all = plugin.settings().tiers();
        int start = Math.max(0, (9 - all.size()) / 2);
        for (Tier t : all) {
            if (start + t.index() > 8) {
                break;
            }
            String state = t.index() < current.index() ? "reached" : t.index() == current.index() ? "current" : "locked";
            List<Component> lore = new ArrayList<>();
            lore.add(lang.get("realm.tier." + t.id() + ".about"));
            lore.add(Component.empty());
            lore.add(lang.get("realm.menu.state-" + state));
            if (t.cost() > 0 && t.index() > current.index()) {
                lore.add(lang.get("realm.menu.cost", Placeholder.unparsed("cost", String.format("%,d", t.cost()))));
            }
            var icon = Icons.icon(t.index() > current.index() ? Material.GRAY_STAINED_GLASS : t.icon(), tiers.name(t),
                    lore);
            set(t.index() + start, t.index() == current.index() ? Icons.glow(icon) : icon);
        }

        Optional<Tier> next = tiers.next(island.id());
        if (next.isEmpty()) {
            set(slot(2, 4), Icons.icon(Material.NETHER_STAR, lang.get("realm.menu.top"),
                    List.of(lang.get("realm.menu.top-lore"))));
        } else {
            List<TierGoal> goals = next.get().requires();
            int first = Math.max(0, (9 - goals.size()) / 2);
            for (int i = 0; i < goals.size() && i < 9; i++) {
                TierGoal g = goals.get(i);
                long have = tiers.progress(island.id(), g);
                boolean done = g.done(have);
                List<Component> lore = List.of(
                        lang.get("realm.menu.progress", Placeholder.unparsed("have", String.format("%,d", have)),
                                Placeholder.unparsed("need", String.format("%,d", g.target()))),
                        lang.get(done ? "realm.menu.goal-done" : "realm.menu.goal-open"));
                var icon = Icons.icon(goalIcon(g), goalName(g), lore);
                set(slot(2, first + i), done ? Icons.glow(icon) : icon);
            }
            boolean ready = tiers.ready(island.id());
            List<Component> lore = new ArrayList<>();
            lore.add(lang.get("realm.menu.cost", Placeholder.unparsed("cost", String.format("%,d", next.get().cost()))));
            lore.add(lang.get(ready ? "realm.menu.ascend-ready" : "realm.menu.ascend-not-ready"));
            set(slot(4, 4), Icons.icon(ready ? Material.BEACON : Material.GLASS,
                    lang.get("realm.menu.ascend", Placeholder.component("tier", tiers.name(next.get()))), lore),
                    (p, click) -> {
                        p.closeInventory();
                        tiers.ascend(p);
                    });
        }
        set(slot(4, 2), Icons.icon(Material.FILLED_MAP, lang.get("realm.menu.biome"),
                List.of(lang.get("realm.menu.biome-lore"))), (p, click) -> new RealmMenu(plugin, island, true).open(p));
        List<Component> gen = new ArrayList<>();
        Map<Material, Integer> weights = current.generator();
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        weights.forEach((m, w) -> gen.add(lang.get("realm.menu.generator-line",
                Placeholder.component("block", Component.translatable(m.translationKey())),
                Placeholder.unparsed("percent", String.format("%.1f", 100.0 * w / total)))));
        set(slot(4, 6), Icons.icon(Material.COBBLESTONE, lang.get("realm.menu.generator"), gen));
    }

    private void renderBiomes() {
        Lang lang = plugin.lang();
        List<String> open = plugin.tiers().biomes(island.id());
        String now = plugin.islands().biome(island.id()).orElse("");
        int i = 0;
        for (Tier t : plugin.settings().tiers()) {
            if (i >= 7) {
                break;
            }
            boolean unlocked = open.contains(t.biome());
            String biome = t.biome();
            List<Component> lore = new ArrayList<>();
            lore.add(lang.get(biome.equals(now) ? "realm.menu.biome-current"
                    : unlocked ? "realm.menu.biome-pick" : "realm.menu.biome-locked",
                    Placeholder.component("tier", plugin.tiers().name(t))));
            var icon = Icons.icon(unlocked ? t.icon() : Material.GRAY_STAINED_GLASS,
                    lang.get("realm.biome." + biome.replace(':', '.')), lore);
            set(slot(1, 1 + i), biome.equals(now) ? Icons.glow(icon) : icon, unlocked ? (p, click) -> {
                if (!plugin.islands().canManage(p, island.id(), plugin.settings().manageRoleWeight())) {
                    plugin.lang().send(p, "errors.not-manager");
                    return;
                }
                if (plugin.islands().setBiome(island.id(), biome)) {
                    plugin.lang().send(p, "realm.biome-set", Placeholder.component("biome",
                            lang.get("realm.biome." + biome.replace(':', '.'))));
                }
                p.closeInventory();
            } : null);
            i++;
        }
        set(slot(2, 0), Icons.icon(Material.ARROW, lang.get("realm.menu.back")),
                (p, click) -> new RealmMenu(plugin, island, false).open(p));
    }

    private Component goalName(TierGoal g) {
        Lang lang = plugin.lang();
        String amount = String.format("%,d", g.target());
        return switch (g.type()) {
            case MINE, HARVEST -> lang.get("realm.goal." + g.type().id(), Placeholder.unparsed("amount", amount),
                    Placeholder.component("item", Component.translatable(g.material().translationKey())));
            default -> lang.get("realm.goal." + g.type().id(), Placeholder.unparsed("amount", amount));
        };
    }

    private static Material goalIcon(TierGoal g) {
        return switch (g.type()) {
            case LEVEL -> Material.EXPERIENCE_BOTTLE;
            case MINE, HARVEST -> g.material().isItem() ? g.material() : Material.STONE_PICKAXE;
            case SELL -> Material.GOLD_INGOT;
            case FISH -> Material.FISHING_ROD;
            case EXPEDITION -> Material.COMPASS;
            case ROUTE -> Material.CHEST_MINECART;
        };
    }
}
