package us.rapidskies.core.rank;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.PlusColor;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * {@code /plus}: one wool block per plus color with a live preview of the prefix in its
 * lore (PLAN.md §6.1). Colors above the player's rank are shown locked, previewed on the
 * player's own rank so they see what they'd get. Four rows:
 *
 * <pre>
 *   row 0  . . . . [current prefix] . . . .
 *   row 1  . [7 colors]               .
 *   row 2  . [7 colors]               .
 *   row 3  . . . . [reset]          . . . .
 * </pre>
 */
public final class PlusMenu extends Menu {

    /** Wool (and lapis for dark blue) exists on 1.8, so every client sees the right block. */
    private static final Map<PlusColor, Material> BLOCKS = new EnumMap<>(Map.ofEntries(
            Map.entry(PlusColor.WHITE, Material.WHITE_WOOL),
            Map.entry(PlusColor.YELLOW, Material.YELLOW_WOOL),
            Map.entry(PlusColor.GOLD, Material.ORANGE_WOOL),
            Map.entry(PlusColor.AQUA, Material.LIGHT_BLUE_WOOL),
            Map.entry(PlusColor.GREEN, Material.LIME_WOOL),
            Map.entry(PlusColor.LIGHT_PURPLE, Material.MAGENTA_WOOL),
            Map.entry(PlusColor.BLUE, Material.BLUE_WOOL),
            Map.entry(PlusColor.DARK_BLUE, Material.LAPIS_BLOCK),
            Map.entry(PlusColor.DARK_AQUA, Material.CYAN_WOOL),
            Map.entry(PlusColor.DARK_GREEN, Material.GREEN_WOOL),
            Map.entry(PlusColor.DARK_PURPLE, Material.PURPLE_WOOL),
            Map.entry(PlusColor.GRAY, Material.LIGHT_GRAY_WOOL),
            Map.entry(PlusColor.DARK_GRAY, Material.GRAY_WOOL),
            Map.entry(PlusColor.BLACK, Material.BLACK_WOOL)));

    private static final int[] COLOR_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int PREVIEW_SLOT = 4;
    private static final int RESET_SLOT = 31;

    private final PlusChoice choice;
    private final Lang lang;

    public PlusMenu(PlusChoice choice) {
        super(4, choice.lang().get("plus.menu.title"));
        this.choice = choice;
        this.lang = choice.lang();
    }

    static {
        if (BLOCKS.size() != PlusColor.values().length || COLOR_SLOTS.length < PlusColor.values().length) {
            throw new IllegalStateException("PlusMenu needs a block and a slot for every PlusColor");
        }
    }

    @Override
    protected void render(Player viewer) {
        Rank rank = choice.ranks().rank(viewer);
        PlusColor chosen = choice.chosen(viewer);
        PlusColor shown = PlusColor.effective(chosen, rank);

        set(PREVIEW_SLOT, Icons.icon(Material.NAME_TAG, lang.get("plus.menu.preview-name"),
                List.of(lang.get("plus.menu.preview-lore", prefix(rank, shown)))));

        PlusColor[] colors = PlusColor.values();
        for (int i = 0; i < colors.length; i++) {
            PlusColor c = colors[i];
            set(COLOR_SLOTS[i], colorIcon(rank, c, c == shown && chosen != null),
                    c.availableTo(rank) ? (p, click) -> pick(p, c) : null);
        }
        set(RESET_SLOT, Icons.icon(Material.BARRIER, lang.get("plus.menu.reset-name"),
                List.of(lang.get("plus.menu.reset-lore", prefix(rank, PlusColor.DEFAULT)))), (p, click) -> pick(p, null));
        fill(Icons.filler());
    }

    private ItemStack colorIcon(Rank rank, PlusColor c, boolean selected) {
        TagResolver name = Placeholder.unparsed("name", PlusChoice.pretty(c));
        Component title = lang.get("plus.menu.color-name", name).color(c.color());
        List<Component> lore = new ArrayList<>();
        lore.add(lang.get("plus.menu.color-preview", prefix(rank, c)));
        if (!c.availableTo(rank)) {
            lore.add(lang.get("plus.menu.color-locked", Placeholder.unparsed("rank", PlusChoice.rankName(c.minimum()))));
        } else if (selected) {
            lore.add(lang.get("plus.menu.color-selected"));
        } else {
            lore.add(lang.get("plus.menu.color-click"));
        }
        ItemStack icon = Icons.icon(BLOCKS.get(c), title, lore);
        return selected ? Icons.glow(icon) : icon;
    }

    private TagResolver prefix(Rank rank, PlusColor c) {
        return Placeholder.component("preview", choice.ranks().rankPrefix(rank, c));
    }

    private void pick(Player player, PlusColor color) {
        Rank rank = choice.ranks().rank(player);
        if (rank == Rank.DEFAULT) {
            player.closeInventory();
            lang.send(player, "plus.needs-rank");
            return;
        }
        if (color != null && color == choice.chosen(player)) {
            return;
        }
        choice.save(player, rank, color, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) == this) {
                refresh(player);
            }
        });
    }
}
