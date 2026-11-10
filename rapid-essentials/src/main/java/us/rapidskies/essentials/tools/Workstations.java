package us.rapidskies.essentials.tools;

import io.papermc.paper.command.brigadier.BasicCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.command.PlayerCommand;

/**
 * Workstation menus without the block: {@code /craft}, {@code /anvil}, {@code /grindstone},
 * {@code /loom}, {@code /smithingtable}, {@code /stonecutter}, {@code /cartographytable},
 * plus {@code /enderchest} (your own; rapid-core's {@code /endersee} inspects others') and
 * {@code /disposal}. They're staff conveniences and never sold (PLAN.md §6.1). Items left in
 * a menu go back to the player on close, as with the real block; the disposal deletes them.
 */
public final class Workstations {

    /** Parent node for the seven workstation menus (plugin.yml children). */
    public static final String ALL = "rapid.essentials.workstations";
    public static final String ENDER_CHEST = "rapid.command.enderchest";
    public static final String DISPOSAL = "rapid.command.disposal";
    private static final int DISPOSAL_SIZE = 36;

    private final RapidEssentials plugin;

    public Workstations(RapidEssentials plugin) {
        this.plugin = plugin;
    }

    public BasicCommand craft() {
        return menu("rapid.command.craft", MenuType.CRAFTING, "container.crafting");
    }

    public BasicCommand anvil() {
        return menu("rapid.command.anvil", MenuType.ANVIL, "container.repair");
    }

    public BasicCommand grindstone() {
        return menu("rapid.command.grindstone", MenuType.GRINDSTONE, "container.grindstone_title");
    }

    public BasicCommand loom() {
        return menu("rapid.command.loom", MenuType.LOOM, "container.loom");
    }

    public BasicCommand smithingTable() {
        return menu("rapid.command.smithingtable", MenuType.SMITHING, "container.upgrade");
    }

    public BasicCommand stonecutter() {
        return menu("rapid.command.stonecutter", MenuType.STONECUTTER, "container.stonecutter");
    }

    public BasicCommand cartographyTable() {
        return menu("rapid.command.cartographytable", MenuType.CARTOGRAPHY_TABLE, "container.cartography_table");
    }

    /**
     * A workstation menu tied to no block (so nothing checks the player is next to one),
     * titled with the game's own translation key.
     */
    private <V extends InventoryView> BasicCommand menu(String permission,
                                                         MenuType.Typed<V, ? extends LocationInventoryViewBuilder<V>> type,
                                                         String titleKey) {
        return new PlayerCommand(plugin, permission) {
            @Override
            protected void run(Player player, String[] args) {
                if (Target.busy(plugin, player, player)) {
                    return;
                }
                player.openInventory(type.builder()
                        .title(Component.translatable(titleKey))
                        .checkReachable(false)
                        .build(player));
            }
        };
    }

    /** {@code /enderchest}: your own ender chest, wherever you are. */
    public BasicCommand enderChest() {
        return new PlayerCommand(plugin, ENDER_CHEST) {
            @Override
            protected void run(Player player, String[] args) {
                if (!Target.busy(plugin, player, player)) {
                    player.openInventory(player.getEnderChest());
                }
            }
        };
    }

    /** {@code /disposal}: an empty chest whose contents are deleted when it closes. */
    public BasicCommand disposal() {
        return new PlayerCommand(plugin, DISPOSAL) {
            @Override
            protected void run(Player player, String[] args) {
                if (!Target.busy(plugin, player, player)) {
                    player.openInventory(Bukkit.createInventory(null, DISPOSAL_SIZE,
                            plugin.lang().get("disposal.title")));
                }
            }
        };
    }
}
