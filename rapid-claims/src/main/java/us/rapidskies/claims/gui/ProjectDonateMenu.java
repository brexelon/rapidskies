package us.rapidskies.claims.gui;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.town.Project;
import us.rapidskies.claims.town.ProjectService;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * The active Project's donation page: one icon per requirement with the town's progress.
 * Clicking an item requirement gives every plain matching item you carry (up to what's
 * still needed). Crowns are given with {@code /town project donate crowns <amount>}, which
 * the Crowns icon suggests in chat (no text input in menus, PLAN.md §3).
 */
public final class ProjectDonateMenu extends Menu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private final RapidClaims plugin;
    private final Town town;
    private final Project project;
    private final Lang lang;

    public ProjectDonateMenu(RapidClaims plugin, Town town, Project project) {
        super(4, plugin.lang().get("project.donate-title", ProjectService.name(project)));
        this.plugin = plugin;
        this.town = town;
        this.project = project;
        this.lang = plugin.lang();
    }

    @Override
    protected void render(Player viewer) {
        plugin.projects().progress(town, map -> draw(viewer, map), viewer);
    }

    private void draw(Player viewer, Map<String, ProjectService.Progress> map) {
        ProjectService.Progress pr = map.get(project.id());
        int i = 0;
        for (Map.Entry<String, Long> req : project.requires().entrySet()) {
            if (i >= SLOTS.length) {
                break;
            }
            String resource = req.getKey();
            boolean crowns = resource.equals(Project.CROWNS);
            long given = pr == null ? 0 : Math.min(req.getValue(), pr.given(resource));
            Component what = crowns ? lang.get("project.crowns")
                    : Component.translatable(ProjectsMenu.icon(resource, Material.STONE).translationKey());
            List<Component> lore = new java.util.ArrayList<>();
            lore.add(lang.get(given >= req.getValue() ? "project.requirement-done" : "project.requirement",
                    Placeholder.component("item", what), Placeholder.unparsed("given", String.format("%,d", given)),
                    Placeholder.unparsed("needed", String.format("%,d", req.getValue()))));
            if (given < req.getValue()) {
                lore.add(lang.get(crowns ? "project.click-crowns" : "project.click-items"));
            }
            Material icon = crowns ? Material.GOLD_INGOT : ProjectsMenu.icon(resource, Material.STONE);
            set(SLOTS[i++], Icons.icon(icon, what, lore), given >= req.getValue() ? null : (p, click) -> {
                if (crowns) {
                    p.closeInventory();
                    lang.send(p, "project.crowns-hint", Placeholder.parsed("id", project.id()));
                } else {
                    plugin.projects().donateItems(p, town, project, resource, () -> {
                        if (p.getOpenInventory().getTopInventory().getHolder(false) == this) {
                            refresh(p);
                        }
                    });
                }
            });
        }
        set(31, Icons.icon(Material.ARROW, lang.get("menu.back")), (p, c) -> ProjectsMenu.open(plugin, p, town));
        fill(Icons.filler());
    }
}
