package us.rapidskies.core.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.progress.ProgressRepository.SeasonStanding;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.cosmetic.Catalog;
import us.rapidskies.core.cosmetic.Cosmetic;
import us.rapidskies.core.cosmetic.CosmeticsMenu;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.core.gui.Pages;

/**
 * {@code /pass}: the Season Pass track (PLAN.md §7), seven tiers a page. Six rows:
 *
 * <pre>
 *   row 0   . . [season] . . . [premium] . .
 *   row 1   [free]    7 tier rewards
 *   row 2   [tiers]   7 tier markers
 *   row 3   [premium] 7 tier rewards
 *   row 5   [prev] . . . . . . . [next]
 * </pre>
 */
public final class PassMenu extends Menu {

    private static final int PER_PAGE = 7;

    private final LevelService levels;
    private final Catalog catalog;
    private final Lang lang;
    private final Consumer<Player> openPremium;
    private int page = -1;

    public PassMenu(LevelService levels, Catalog catalog, Lang lang, Consumer<Player> openPremium) {
        super(6, lang.get("pass.menu.title"));
        this.levels = levels;
        this.catalog = catalog;
        this.lang = lang;
        this.openPremium = openPremium;
    }

    @Override
    protected void render(Player viewer) {
        Optional<ProgressConfig.Season> running = levels.season();
        if (running.isEmpty()) {
            Optional<ProgressConfig.Season> next = levels.config().nextSeason(Instant.now());
            set(22, Icons.icon(Material.CLOCK, lang.get("pass.none-name"), next.map(s -> lang.list("pass.next-lore",
                    Placeholder.unparsed("season", s.name()), Placeholder.unparsed("date", s.starts().toString())))
                    .orElseGet(() -> lang.list("pass.none-lore"))));
            fill(Icons.filler());
            return;
        }
        ProgressConfig.Season s = running.get();
        SeasonStanding standing = levels.standing(viewer);
        int tier = s.tierOf(standing.xp());
        int pages = (s.tiers() + PER_PAGE - 1) / PER_PAGE;
        if (page < 0) {
            page = Math.clamp(tier / PER_PAGE, 0, pages - 1);
        }
        long into = tier >= s.tiers() ? s.xpPerTier() : standing.xp() - (long) tier * s.xpPerTier();
        Duration left = Duration.between(Instant.now(), s.endsAt());
        set(2, Icons.icon(Material.EXPERIENCE_BOTTLE, lang.get("pass.menu.info-name",
                Placeholder.unparsed("season", s.name())), lang.list("pass.menu.info-lore",
                Placeholder.unparsed("tier", Integer.toString(tier)),
                Placeholder.unparsed("tiers", Integer.toString(s.tiers())),
                Placeholder.component("bar", Lang.parse(Pages.bar(into, s.xpPerTier(), 10))),
                Placeholder.unparsed("into", String.format("%,d", into)),
                Placeholder.unparsed("per", String.format("%,d", s.xpPerTier())),
                Placeholder.unparsed("time", Durations.format(left.isNegative() ? Duration.ZERO : left.withNanos(0))))));
        if (standing.premium()) {
            set(6, Icons.glow(Icons.icon(Material.NETHER_STAR, lang.get("pass.menu.premium-owned"))));
        } else {
            set(6, Icons.icon(Material.NETHER_STAR, lang.get("pass.menu.premium-buy"), lang.list("pass.menu.premium-lore",
                    Placeholder.unparsed("price", String.format("%,d", s.premiumPrice())))),
                    (p, click) -> openPremium.accept(p));
        }
        set(9, Icons.icon(Material.OAK_SIGN, lang.get("pass.menu.free-row")));
        set(18, Icons.icon(Material.EXPERIENCE_BOTTLE, lang.get("pass.menu.tier-row")));
        set(27, Icons.icon(Material.NETHER_STAR, lang.get("pass.menu.premium-row")));
        for (int i = 0; i < PER_PAGE; i++) {
            int t = page * PER_PAGE + i + 1;
            if (t > s.tiers()) {
                break;
            }
            boolean reached = tier >= t;
            set(10 + i, reward(viewer, s.free(t), reached, true));
            ItemStack marker = Icons.icon(reached ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    lang.get(reached ? "pass.menu.tier-reached" : "pass.menu.tier", Placeholder.unparsed("tier",
                            Integer.toString(t))), List.of(lang.get("pass.menu.tier-xp",
                            Placeholder.unparsed("xp", String.format("%,d", (long) t * s.xpPerTier())))));
            set(19 + i, marker);
            set(28 + i, reward(viewer, s.premium(t), reached, standing.premium()));
        }
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

    private ItemStack reward(Player viewer, List<String> ids, boolean reached, boolean track) {
        if (ids.isEmpty()) {
            return Icons.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, lang.get("pass.menu.no-reward"));
        }
        Optional<Cosmetic> first = catalog.find(ids.getFirst());
        if (first.isEmpty()) {
            return Icons.icon(Material.BARRIER, Component.text(ids.getFirst()));
        }
        Cosmetic c = first.get();
        List<Component> lore = new ArrayList<>(CosmeticsMenu.describe(lang, viewer, c));
        lore.add(Component.empty());
        lore.add(lang.get(reached && track ? "pass.menu.unlocked" : !track ? "pass.menu.needs-premium"
                : "pass.menu.locked"));
        ItemStack icon = Icons.icon(Material.valueOf(c.icon()), CosmeticsMenu.title(lang, c), lore);
        return reached && track ? Icons.glow(icon) : icon;
    }
}
