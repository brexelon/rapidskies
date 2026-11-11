package us.rapidskies.core.quest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.progress.NetworkLevels;
import us.rapidskies.common.quest.QuestPeriods;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.core.gui.Pages;
import us.rapidskies.core.progress.LevelService;

/**
 * {@code /quests} (PLAN.md §7): this week's quests, then the open dailies (today's and any
 * banked from the days before), each with its progress, reward and when it closes. Five rows:
 *
 * <pre>
 *   row 0      . . . . [level and streak] . . . .
 *   rows 1–3   . [up to 21 quests] .
 *   row 4      . . [achievements] . [level] . [season pass] . .
 * </pre>
 */
public final class QuestsMenu extends Menu {

    private static final int[] SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34};

    private final QuestService quests;
    private final LevelService levels;
    private final Lang lang;
    private final int streak;
    private final Consumer<Player> openAchievements;
    private final Consumer<Player> openPass;

    public QuestsMenu(QuestService quests, LevelService levels, Lang lang, int streak,
                      Consumer<Player> openAchievements, Consumer<Player> openPass) {
        super(5, lang.get("quests.menu.title"));
        this.quests = quests;
        this.levels = levels;
        this.lang = lang;
        this.streak = streak;
        this.openAchievements = openAchievements;
        this.openPass = openPass;
    }

    @Override
    protected void render(Player viewer) {
        NetworkLevels.Progress lvl = levels.progress(viewer);
        set(4, Icons.icon(Material.EXPERIENCE_BOTTLE, lang.get("quests.menu.info-name",
                Placeholder.unparsed("level", Integer.toString(lvl.level()))), lang.list("quests.menu.info-lore",
                Placeholder.unparsed("into", String.format("%,d", lvl.into())),
                Placeholder.unparsed("needed", String.format("%,d", lvl.needed())),
                Placeholder.component("bar", Lang.parse(Pages.bar(lvl.into(), lvl.needed(), 10))),
                Placeholder.unparsed("streak", Integer.toString(streak)))));

        List<QuestService.View> views = quests.quests(viewer);
        Instant now = Instant.now();
        for (int i = 0; i < views.size() && i < SLOTS.length; i++) {
            set(SLOTS[i], icon(views.get(i), now));
        }
        if (views.isEmpty()) {
            set(22, Icons.icon(Material.GRAY_DYE, lang.get(quests.loaded(viewer) ? "quests.menu.none"
                    : "quests.menu.loading")));
        }
        set(38, Icons.icon(Material.GOLDEN_APPLE, lang.get("quests.menu.achievements")),
                (p, click) -> openAchievements.accept(p));
        set(42, Icons.icon(Material.NETHER_STAR, lang.get("quests.menu.pass")), (p, click) -> openPass.accept(p));
        fill(Icons.filler());
    }

    private ItemStack icon(QuestService.View v, Instant now) {
        boolean weekly = QuestPeriods.isWeek(v.row().period());
        QuestConfig.Reward reward = quests.config().reward(v.row().kind());
        String name = v.quest().map(QuestConfig.Quest::name).orElse(v.row().quest());
        List<Component> lore = new ArrayList<>();
        Duration left = Duration.between(now, v.closes());
        boolean today = !weekly && v.row().period().equals(QuestPeriods.day(now));
        lore.add(lang.get(weekly ? "quests.menu.weekly" : today ? "quests.menu.daily" : "quests.menu.daily-banked",
                Placeholder.unparsed("time", Durations.format(left.isNegative() ? Duration.ZERO : left.withNanos(0)))));
        if (v.quest().isEmpty()) {
            lore.add(lang.get("quests.menu.retired"));
        }
        lore.add(lang.get("quests.menu.progress",
                Placeholder.component("bar", Lang.parse(Pages.bar(v.progress(), v.row().target(), 10))),
                Placeholder.unparsed("progress", String.format("%,d", v.progress())),
                Placeholder.unparsed("target", String.format("%,d", v.row().target()))));
        lore.add(lang.get("quests.menu.reward", Placeholder.unparsed("bolts", Long.toString(reward.bolts())),
                Placeholder.unparsed("xp", Long.toString(reward.xp()))));
        if (v.done()) {
            lore.add(lang.get("quests.menu.done"));
        } else if (v.quest().isPresent() && !v.quest().get().mode().equals("any")) {
            lore.add(lang.get("quests.menu.where." + v.quest().get().mode()));
        }
        Material material = v.done() ? Material.LIME_DYE
                : v.quest().map(q -> Material.valueOf(q.icon())).orElse(Material.PAPER);
        ItemStack icon = Icons.icon(material, lang.get(v.done() ? "quests.menu.name-done" : "quests.menu.name",
                Placeholder.unparsed("name", name)), lore);
        return v.done() ? Icons.glow(icon) : icon;
    }
}
