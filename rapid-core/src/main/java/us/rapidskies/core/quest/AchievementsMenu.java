package us.rapidskies.core.quest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.cosmetic.Catalog;
import us.rapidskies.core.cosmetic.Cosmetic;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.core.gui.Pages;
import us.rapidskies.core.quest.QuestConfig.Achievement;

/**
 * {@code /achievements} (PLAN.md §7): every achievement with its lifetime progress and
 * reward; earned ones first.
 */
public final class AchievementsMenu extends Menu {

    private final QuestService quests;
    private final Catalog catalog;
    private final Lang lang;
    private final Consumer<Player> back;
    private int page;

    public AchievementsMenu(QuestService quests, Catalog catalog, Lang lang, Consumer<Player> back) {
        super(6, lang.get("achievements.menu.title"));
        this.quests = quests;
        this.catalog = catalog;
        this.lang = lang;
        this.back = back;
    }

    @Override
    protected void render(Player viewer) {
        List<Achievement> all = new ArrayList<>(quests.config().achievements().values());
        all.sort(Comparator.comparing((Achievement a) -> !quests.achieved(viewer, a.id())));
        long earned = all.stream().filter(a -> quests.achieved(viewer, a.id())).count();
        set(4, Icons.icon(Material.GOLDEN_APPLE, lang.get("achievements.menu.info",
                Placeholder.unparsed("earned", Long.toString(earned)),
                Placeholder.unparsed("total", Integer.toString(all.size())))));
        int pages = Pages.count(all.size());
        page = Math.clamp(page, 0, pages - 1);
        List<Achievement> shown = Pages.page(all, page);
        for (int i = 0; i < shown.size(); i++) {
            set(Pages.SLOTS[i], icon(viewer, shown.get(i)));
        }
        set(49, Icons.icon(Material.BOOK, lang.get("achievements.menu.back")), (p, click) -> back.accept(p));
        if (page > 0) {
            set(Pages.PREVIOUS, Icons.icon(Material.ARROW, lang.get("cosmetics.menu.previous")), (p, click) -> {
                page--;
                refresh(p);
            });
        }
        if (page < pages - 1) {
            set(Pages.NEXT, Icons.icon(Material.ARROW, lang.get("cosmetics.menu.next",
                    Placeholder.unparsed("page", Integer.toString(page + 2)),
                    Placeholder.unparsed("pages", Integer.toString(pages)))), (p, click) -> {
                page++;
                refresh(p);
            });
        }
        fill(Icons.filler());
    }

    private ItemStack icon(Player viewer, Achievement a) {
        boolean done = quests.achieved(viewer, a.id());
        long progress = Math.min(a.target(), quests.stat(viewer, a.stat()));
        List<Component> lore = new ArrayList<>();
        lore.add(lang.get("achievements.menu.description", Placeholder.unparsed("description", a.description())));
        lore.add(lang.get("quests.menu.progress",
                Placeholder.component("bar", Lang.parse(Pages.bar(done ? a.target() : progress, a.target(), 10))),
                Placeholder.unparsed("progress", String.format("%,d", done ? a.target() : progress)),
                Placeholder.unparsed("target", String.format("%,d", a.target()))));
        lore.add(lang.get("achievements.menu.reward", Placeholder.unparsed("bolts", Long.toString(a.bolts())),
                Placeholder.unparsed("xp", Long.toString(a.xp()))));
        if (a.cosmetic() != null) {
            catalog.find(a.cosmetic()).ifPresent((Cosmetic c) -> lore.add(lang.get("achievements.menu.cosmetic",
                    Placeholder.unparsed("name", c.name()),
                    Placeholder.component("type", lang.get("cosmetics.type." + c.type().id())))));
        }
        if (done) {
            lore.add(lang.get("achievements.menu.earned"));
        }
        ItemStack icon = Icons.icon(done ? Material.valueOf(a.icon()) : Material.GRAY_DYE,
                lang.get(done ? "achievements.menu.name-done" : "achievements.menu.name",
                        Placeholder.unparsed("name", a.name())), lore);
        return done ? Icons.glow(icon) : icon;
    }
}
