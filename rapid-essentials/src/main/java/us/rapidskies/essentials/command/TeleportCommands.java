package us.rapidskies.essentials.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.teleport.BackService;
import us.rapidskies.essentials.teleport.TeleportKind;
import us.rapidskies.essentials.teleport.TpaRequests.Direction;
import us.rapidskies.essentials.warp.WarpService;

/**
 * The teleport commands: {@code /spawn}, {@code /setspawn}, {@code /back}, the
 * {@code /tpa} family, homes and warps. The work is in the services; these parse arguments.
 */
public final class TeleportCommands {

    public static final String SPAWN = "rapid.command.spawn";
    public static final String SET_SPAWN = "rapid.command.setspawn";
    public static final String TPA = "rapid.command.tpa";
    public static final String HOME = "rapid.command.home";
    public static final String WARP = "rapid.command.warp";

    private final RapidEssentials plugin;

    public TeleportCommands(RapidEssentials plugin) {
        this.plugin = plugin;
    }

    /** One optional argument at most; the first argument, or null. */
    private static String first(String[] args) {
        return args.length == 0 || args[0].isBlank() ? null : args[0];
    }

    // ------------------------------------------------------------------ spawn

    /** {@code /spawn}: this server's spawn (rapid-core's {@code spawn.location}). */
    public BasicCommand spawn() {
        return new PlayerCommand(plugin, SPAWN) {
            @Override
            protected void run(Player player, String[] args) {
                plugin.teleports().teleport(player, TeleportKind.SPAWN, () -> plugin.core().worlds().spawn(), false,
                        () -> plugin.lang().send(player, "spawn.teleported"));
            }
        };
    }

    /**
     * {@code /setspawn}: moves the spawn to where you stand until the next restart, and prints
     * the line for the server's rapid-core profile in infra/, which is where the spawn really
     * lives (rsdeploy would put the old value back otherwise).
     */
    public BasicCommand setSpawn() {
        return new PlayerCommand(plugin, SET_SPAWN) {
            @Override
            protected void run(Player player, String[] args) {
                Location at = player.getLocation();
                SpawnPoint point = new SpawnPoint(at.getWorld().getKey().asString(), at.getX(), at.getY(), at.getZ(),
                        at.getYaw(), at.getPitch());
                // Round like the printed line, so what's live matches what goes into the profile.
                SpawnPoint rounded = SpawnPoint.parse(point.format());
                plugin.core().worlds().setSpawn(new Location(at.getWorld(), rounded.x(), rounded.y(), rounded.z(),
                        rounded.yaw(), rounded.pitch()));
                String value = rounded.format();
                Component line = Component.text("spawn.location: \"" + value + "\"")
                        .clickEvent(ClickEvent.copyToClipboard(value))
                        .hoverEvent(HoverEvent.showText(plugin.lang().get("spawn.copy-hint")));
                plugin.lang().send(player, "spawn.set", Placeholder.component("line", line));
            }
        };
    }

    // ------------------------------------------------------------------ back

    public BasicCommand back() {
        return new PlayerCommand(plugin, BackService.PERMISSION) {
            @Override
            protected void run(Player player, String[] args) {
                plugin.back().back(player);
            }
        };
    }

    // ------------------------------------------------------------------ tpa

    /** {@code /tpa <player>} or {@code /tpahere <player>}. */
    public BasicCommand tpa(Direction direction) {
        return new PlayerCommand(plugin, TPA) {
            @Override
            protected void run(Player player, String[] args) {
                String target = first(args);
                if (target == null) {
                    plugin.lang().send(player, direction == Direction.THERE ? "tpa.usage" : "tpa.usage-here");
                    return;
                }
                plugin.tpa().request(player, target, direction);
            }

            @Override
            protected Collection<String> complete(Player player, String[] args) {
                return args.length <= 1 ? Suggest.players(plugin, player, args).stream()
                        .filter(n -> !n.equals(player.getName())).toList() : List.of();
            }
        };
    }

    /** {@code /tpaccept [player]} ({@code accept}) or {@code /tpdeny [player]}. */
    public BasicCommand tpaAnswer(boolean accept) {
        return new PlayerCommand(plugin, TPA) {
            @Override
            protected void run(Player player, String[] args) {
                if (accept) {
                    plugin.tpa().accept(player, first(args));
                } else {
                    plugin.tpa().deny(player, first(args));
                }
            }

            @Override
            protected Collection<String> complete(Player player, String[] args) {
                return args.length <= 1 ? Suggest.matching(plugin.tpa().pendingNames(player), args) : List.of();
            }
        };
    }

    public BasicCommand tpaCancel() {
        return new PlayerCommand(plugin, TPA) {
            @Override
            protected void run(Player player, String[] args) {
                plugin.tpa().cancel(player);
            }
        };
    }

    public BasicCommand tpToggle() {
        return new PlayerCommand(plugin, TPA) {
            @Override
            protected void run(Player player, String[] args) {
                plugin.tpa().toggle(player);
            }
        };
    }

    // ------------------------------------------------------------------ homes

    public BasicCommand home() {
        return homeCommand((player, name) -> plugin.homes().home(player, name));
    }

    public BasicCommand setHome() {
        return homeCommand((player, name) -> plugin.homes().set(player, name));
    }

    public BasicCommand delHome() {
        return homeCommand((player, name) -> {
            if (name == null) {
                plugin.lang().send(player, "homes.usage-delete");
            } else {
                plugin.homes().delete(player, name);
            }
        });
    }

    public BasicCommand homes() {
        return homeCommand((player, name) -> plugin.homes().list(player));
    }

    private BasicCommand homeCommand(java.util.function.BiConsumer<Player, String> action) {
        return new PlayerCommand(plugin, HOME) {
            @Override
            protected void run(Player player, String[] args) {
                action.accept(player, first(args));
            }

            @Override
            protected Collection<String> complete(Player player, String[] args) {
                return args.length <= 1 ? Suggest.matching(plugin.homes().names(player), args) : List.of();
            }
        };
    }

    // ------------------------------------------------------------------ warps

    /** {@code /warp [name]}; {@code /warps} is the same with no name. */
    public BasicCommand warp() {
        return new PlayerCommand(plugin, WARP) {
            @Override
            protected void run(Player player, String[] args) {
                plugin.warps().warp(player, first(args));
            }

            @Override
            protected Collection<String> complete(Player player, String[] args) {
                return args.length <= 1 ? Suggest.matching(plugin.warps().names(), args) : List.of();
            }
        };
    }

    public BasicCommand warps() {
        return new PlayerCommand(plugin, WARP) {
            @Override
            protected void run(Player player, String[] args) {
                plugin.warps().list(player);
            }
        };
    }

    /** {@code /setwarp <name>} or {@code /delwarp <name>}. */
    public BasicCommand editWarp(boolean set) {
        return new PlayerCommand(plugin, WarpService.SET_PERMISSION) {
            @Override
            protected void run(Player player, String[] args) {
                String name = first(args);
                if (name == null) {
                    plugin.lang().send(player, set ? "warps.usage-set" : "warps.usage-delete");
                } else if (set) {
                    plugin.warps().set(player, name);
                } else {
                    plugin.warps().delete(player, name);
                }
            }

            @Override
            protected Collection<String> complete(Player player, String[] args) {
                return args.length <= 1 ? Suggest.matching(plugin.warps().names(), args) : List.of();
            }
        };
    }
}
