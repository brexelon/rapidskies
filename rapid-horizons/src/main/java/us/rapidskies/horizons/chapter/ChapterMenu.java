package us.rapidskies.horizons.chapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.horizons.HorizonsSettings.Chapter;
import us.rapidskies.horizons.HorizonsSettings.ChapterEntry;
import us.rapidskies.horizons.RapidHorizons;

/**
 * {@code /chapter}: the current Chapter's collection log. Each entry shows its goal and
 * progress; finished ones glow. Read-only (clicks do nothing).
 *
 * <pre>
 *   row 0  . . . . [chapter] . . . [reward]
 *   rows 1-4  entries, 7 per row (columns 1..7)
 *   row 5  filler
 * </pre>
 */
public final class ChapterMenu extends Menu {

    private static final int INFO_SLOT = 4;
    private static final int REWARD_SLOT = 8;

    private final RapidHorizons plugin;
    private final Chapter chapter;

    public ChapterMenu(RapidHorizons plugin, Chapter chapter) {
        super(6, plugin.lang().get("chapter.menu.title", Placeholder.component("chapter",
                plugin.lang().get("chapter." + chapter.id() + ".name"))));
        this.plugin = plugin;
        this.chapter = chapter;
    }

    @Override
    protected void render(Player viewer) {
        Lang lang = plugin.lang();
        ChapterService chapters = plugin.chapters();
        String base = "chapter." + chapter.id();
        int done = 0;
        for (ChapterEntry e : chapter.entries()) {
            if (chapters.done(viewer.getUniqueId(), e.id())) {
                done++;
            }
        }
        List<Component> info = new ArrayList<>(lang.list(base + ".lore"));
        info.add(Component.empty());
        info.add(lang.get("chapter.menu.progress", Placeholder.unparsed("done", Integer.toString(done)),
                Placeholder.unparsed("total", Integer.toString(chapter.entries().size()))));
        info.add(lang.get("chapter.menu.frontier-only"));
        ItemStack book = Icons.icon(Material.WRITTEN_BOOK, lang.get(base + ".name"), info);
        set(INFO_SLOT, chapters.completed(viewer.getUniqueId()) ? Icons.glow(book) : book);

        List<Component> reward = new ArrayList<>();
        reward.add(lang.get(base + ".reward"));
        if (chapter.bolts() > 0) {
            reward.add(lang.get("chapter.menu.reward-bolts", Placeholder.unparsed("amount",
                    Long.toString(chapter.bolts()))));
        }
        reward.add(chapters.completed(viewer.getUniqueId()) ? lang.get("chapter.menu.reward-earned")
                : lang.get("chapter.menu.reward-locked"));
        set(REWARD_SLOT, Icons.icon(Material.GOLD_INGOT, lang.get("chapter.menu.reward-name"), reward));

        int i = 0;
        for (ChapterEntry e : chapter.entries()) {
            int row = 1 + i / 7;
            int column = 1 + i % 7;
            set(slot(row, column), entry(viewer, e));
            i++;
        }
        fill(Icons.filler());
    }

    private ItemStack entry(Player viewer, ChapterEntry e) {
        Lang lang = plugin.lang();
        ChapterService chapters = plugin.chapters();
        String base = "chapter." + chapter.id() + ".entries." + e.id();
        boolean finished = chapters.done(viewer.getUniqueId(), e.id());
        List<Component> lore = new ArrayList<>(lang.list(base + ".hint"));
        lore.add(Component.empty());
        if (e.amount() > 1) {
            lore.add(lang.get("chapter.menu.entry-progress",
                    Placeholder.unparsed("progress", Integer.toString(Math.min(e.amount(),
                            chapters.progress(viewer.getUniqueId(), e.id())))),
                    Placeholder.unparsed("amount", Integer.toString(e.amount()))));
        }
        lore.add(finished ? lang.get("chapter.menu.entry-done") : lang.get("chapter.menu.entry-open"));
        ItemStack item = Icons.icon(finished ? icon(e.goal()) : Material.GRAY_DYE, lang.get(base + ".name"), lore);
        return finished ? Icons.glow(item) : item;
    }

    /** An icon that exists in 1.20 for each goal; unfinished entries are gray dye. */
    static Material icon(ChapterGoal goal) {
        Material m = Material.matchMaterial(iconName(goal));
        return m == null || !m.isItem() ? Material.PAPER : m;
    }

    /** The Material name of {@link #icon} (before checking it exists as an item). */
    static String iconName(ChapterGoal goal) {
        return switch (goal.kind()) {
            case VISIT -> switch (goal.target()) {
                case "wildlands" -> "IRON_SWORD";
                case "nether" -> "NETHERRACK";
                case "end" -> "END_STONE";
                default -> "FILLED_MAP";
            };
            case DISTANCE -> "COMPASS";
            case BIOMES -> "OAK_SAPLING";
            case SKYBORNE -> "FEATHER";
            case DRAGON -> "DRAGON_HEAD";
            case KILL -> goal.target().toUpperCase(Locale.ROOT) + "_SPAWN_EGG";
            case MINE, FISH -> goal.target().toUpperCase(Locale.ROOT);
        };
    }
}
