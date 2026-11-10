package us.rapidskies.claims.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownPermission;
import us.rapidskies.claims.town.Project;
import us.rapidskies.claims.town.ProjectService;
import us.rapidskies.claims.town.ProjectService.Progress;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * {@code /town projects} (PLAN.md §5.2): every Project in the catalog with this town's
 * progress. The active one opens its donation page; an available one can be started by
 * members with the Projects permission. One Project runs at a time.
 */
public final class ProjectsMenu extends Menu {

    private static final int[] SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43};

    private final RapidClaims plugin;
    private final Town town;
    private final Map<String, Progress> progress;
    private final Lang lang;

    public ProjectsMenu(RapidClaims plugin, Town town, Map<String, Progress> progress) {
        super(6, plugin.lang().get("project.menu-title", Placeholder.unparsed("level",
                Integer.toString(plugin.towns().level(town)))));
        this.plugin = plugin;
        this.town = town;
        this.progress = progress;
        this.lang = plugin.lang();
    }

    /** Loads the town's progress, then opens the menu. */
    public static void open(RapidClaims plugin, Player p, Town town) {
        plugin.projects().progress(town, map -> new ProjectsMenu(plugin, town, map).open(p), p);
    }

    static Material icon(String key, Material fallback) {
        Material m = Material.matchMaterial(key);
        return m != null && m.isItem() && !m.isAir() ? m : fallback;
    }

    @Override
    protected void render(Player viewer) {
        List<Project> catalog = plugin.settings().projects();
        Optional<String> active = ProjectService.active(progress);
        boolean canStart = town.may(viewer.getUniqueId(), TownPermission.PROJECTS);
        for (int i = 0; i < catalog.size() && i < SLOTS.length; i++) {
            Project p = catalog.get(i);
            Progress pr = progress.get(p.id());
            List<Component> lore = new ArrayList<>();
            lore.addAll(requirementLines(p, pr));
            lore.add(Component.empty());
            lore.addAll(unlockLines(p));
            ItemStack item;
            Menu.ClickHandler click = null;
            if (pr != null && pr.completed()) {
                lore.add(lang.get("project.state-done"));
                item = Icons.glow(Icons.icon(icon(p.icon(), Material.BRICKS), lang.get("project.name",
                        ProjectService.name(p)), lore));
            } else if (pr != null) {
                lore.add(lang.get("project.state-active"));
                item = Icons.icon(icon(p.icon(), Material.BRICKS), lang.get("project.name", ProjectService.name(p)), lore);
                click = (pl, c) -> new ProjectDonateMenu(plugin, town, p).open(pl);
            } else if (active.isPresent()) {
                lore.add(lang.get("project.state-waiting"));
                item = Icons.icon(Material.GRAY_DYE, lang.get("project.name", ProjectService.name(p)), lore);
            } else if (canStart) {
                lore.add(lang.get("project.state-start"));
                item = Icons.icon(icon(p.icon(), Material.BRICKS), lang.get("project.name", ProjectService.name(p)), lore);
                click = (pl, c) -> {
                    pl.closeInventory();
                    plugin.projects().start(pl, town, p);
                };
            } else {
                lore.add(lang.get("project.state-available"));
                item = Icons.icon(icon(p.icon(), Material.BRICKS), lang.get("project.name", ProjectService.name(p)), lore);
            }
            set(SLOTS[i], item, click);
        }
        set(49, Icons.icon(Material.BARRIER, lang.get("menu.close")), (p, c) -> p.closeInventory());
        fill(Icons.filler());
    }

    List<Component> requirementLines(Project p, Progress pr) {
        List<Component> out = new ArrayList<>();
        for (Map.Entry<String, Long> req : p.requires().entrySet()) {
            long given = pr == null ? 0 : Math.min(req.getValue(), pr.given(req.getKey()));
            Component what = req.getKey().equals(Project.CROWNS) ? lang.get("project.crowns")
                    : Component.translatable(icon(req.getKey(), Material.STONE).translationKey());
            out.add(lang.get(given >= req.getValue() ? "project.requirement-done" : "project.requirement",
                    Placeholder.component("item", what),
                    Placeholder.unparsed("given", String.format("%,d", given)),
                    Placeholder.unparsed("needed", String.format("%,d", req.getValue()))));
        }
        return out;
    }

    List<Component> unlockLines(Project p) {
        List<Component> out = new ArrayList<>();
        for (Project.Unlock u : p.unlocks()) {
            out.add(lang.get("project.unlock-line", Placeholder.parsed("unlock",
                    lang.raw("project.unlock." + u.id()).replace("<amount>", Integer.toString(u.amount())))));
        }
        return out;
    }
}
