package us.rapidskies.essentials.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.staff.Vanish;
import us.rapidskies.essentials.RapidEssentials;

/**
 * Everyday commands that aren't teleports: {@code /afk}, {@code /ping}, {@code /list},
 * {@code /seen}, {@code /hat} and the text pages ({@code /rules}, {@code /store}...).
 */
public final class PlayerCommands {

    public static final String SEEN = "rapid.command.seen";
    /** A Horizons Rapid+ perk (PLAN.md §6.1), granted per server in LuckPerms. */
    public static final String HAT = "rapid.command.hat";

    private final RapidEssentials plugin;
    private final ProfileRepository profiles;

    public PlayerCommands(RapidEssentials plugin) {
        this.plugin = plugin;
        this.profiles = new ProfileRepository(plugin.core().database());
    }

    private Vanish vanish() {
        return plugin.core().staffMode().vanish();
    }

    /** {@code /afk}: AFK now (rapid-core's tracker); moving the mouse or chatting ends it. */
    public BasicCommand afk() {
        return new PlayerCommand(plugin, null) {
            @Override
            protected void run(Player player, String[] args) {
                plugin.core().afk().setAfk(player);
            }
        };
    }

    // ------------------------------------------------------------------ /ping

    /** {@code /ping [player]}: round-trip time to this server. */
    public BasicCommand ping() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                if (args.length == 0) {
                    if (sender instanceof Player player) {
                        plugin.lang().send(player, "ping.self",
                                Placeholder.unparsed("ping", Integer.toString(player.getPing())));
                    } else {
                        plugin.lang().send(sender, "errors.players-only");
                    }
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || (sender instanceof Player viewer && !vanish().canSee(viewer, target))) {
                    plugin.lang().send(sender, "errors.not-here", Placeholder.unparsed("player", args[0]));
                    return;
                }
                plugin.lang().send(sender, "ping.other", Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("ping", Integer.toString(target.getPing())));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return args.length <= 1 ? Suggest.players(plugin, stack.getSender(), args) : List.of();
            }
        };
    }

    // ------------------------------------------------------------------ /list

    /**
     * {@code /list}: who's on this server, with rank prefixes. Vanished staff are left out,
     * except for viewers who can see them. The proxy's {@code /glist} covers the network.
     */
    public BasicCommand list() {
        return (stack, args) -> {
            CommandSender sender = stack.getSender();
            List<Player> shown = Bukkit.getOnlinePlayers().stream()
                    .filter(p -> !(sender instanceof Player viewer) || vanish().canSee(viewer, p))
                    .sorted(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(p -> (Player) p)
                    .toList();
            Component names = Component.join(JoinConfiguration.separator(plugin.lang().get("list.separator")),
                    shown.stream().map(p -> plugin.lang().get(vanish().isVanished(p) ? "list.entry-vanished"
                                    : "list.entry", Placeholder.component("rank", plugin.core().ranks().prefix(p)),
                            Placeholder.unparsed("player", p.getName()))).toList());
            plugin.lang().send(sender, shown.isEmpty() ? "list.empty" : "list.players",
                    Placeholder.unparsed("count", Integer.toString(shown.size())),
                    Placeholder.unparsed("server", plugin.core().settings().serverId()),
                    Placeholder.component("players", names));
        };
    }

    // ------------------------------------------------------------------ /seen

    /** {@code /seen <player>} (staff): online here, or when they last joined which server. */
    public BasicCommand seen() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                if (args.length != 1) {
                    plugin.lang().send(sender, "seen.usage");
                    return;
                }
                String name = args[0];
                Player online = Bukkit.getPlayerExact(name);
                if (online != null && (!(sender instanceof Player viewer) || vanish().canSee(viewer, online))) {
                    plugin.lang().send(sender, "seen.online-here", Placeholder.unparsed("player", online.getName()),
                            Placeholder.unparsed("server", plugin.core().settings().serverId()));
                    return;
                }
                plugin.core().database().async(() -> profiles.findByName(name))
                        .thenAccept(found -> show(sender, name, found))
                        .exceptionally(e -> {
                            plugin.getSLF4JLogger().warn("/seen {} failed", name, e);
                            plugin.lang().send(sender, "seen.failed");
                            return null;
                        });
            }

            private void show(CommandSender sender, String name, Optional<PlayerProfile> found) {
                if (found.isEmpty()) {
                    plugin.lang().send(sender, "seen.never", Placeholder.unparsed("player", name));
                    return;
                }
                PlayerProfile p = found.get();
                Instant now = Instant.now();
                plugin.lang().send(sender, "seen.last", Placeholder.unparsed("player", p.name()),
                        Placeholder.unparsed("server", p.lastServer() == null ? "?" : p.lastServer()),
                        Placeholder.unparsed("ago", Durations.format(Duration.between(p.lastJoin(), now))),
                        Placeholder.unparsed("first", Durations.format(Duration.between(p.firstJoin(), now))));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return args.length <= 1 ? Suggest.players(plugin, stack.getSender(), args) : List.of();
            }

            @Override
            public String permission() {
                return SEEN;
            }
        };
    }

    // ------------------------------------------------------------------ /hat

    /**
     * {@code /hat}: wears the held item, and the old helmet goes back to the hand (or the
     * inventory, from a stack). Not in staff or build mode, whose inventories are borrowed.
     */
    public BasicCommand hat() {
        return new PlayerCommand(plugin, HAT) {
            @Override
            protected void run(Player player, String[] args) {
                if (plugin.core().staffMode().isActive(player) || plugin.core().buildMode().isActive(player)) {
                    plugin.lang().send(player, "hat.not-now");
                    return;
                }
                PlayerInventory inv = player.getInventory();
                ItemStack hand = inv.getItemInMainHand();
                if (hand.getType() == Material.AIR || hand.getAmount() == 0) {
                    plugin.lang().send(player, "hat.empty");
                    return;
                }
                ItemStack helmet = inv.getHelmet();
                boolean wearing = helmet != null && !helmet.getType().isAir();
                if (wearing && helmet.containsEnchantment(Enchantment.BINDING_CURSE)) {
                    plugin.lang().send(player, "hat.cursed");
                    return;
                }
                if (hand.getAmount() == 1) {
                    inv.setHelmet(hand);
                    inv.setItemInMainHand(wearing ? helmet : null);
                } else {
                    // One from the stack goes on; the old helmet needs a free slot.
                    if (wearing && inv.firstEmpty() == -1) {
                        plugin.lang().send(player, "hat.full");
                        return;
                    }
                    ItemStack one = hand.asOne();
                    hand.subtract();
                    inv.setItemInMainHand(hand);
                    inv.setHelmet(one);
                    if (wearing) {
                        inv.addItem(helmet);
                    }
                }
                plugin.lang().send(player, "hat.done");
            }
        };
    }

    // ------------------------------------------------------------------ text pages

    /** A text page from lang {@code info.<id>} ({@code /rules}, {@code /store}...). */
    public BasicCommand info(String id) {
        return (stack, args) -> plugin.lang().send(stack.getSender(), "info." + id);
    }
}
