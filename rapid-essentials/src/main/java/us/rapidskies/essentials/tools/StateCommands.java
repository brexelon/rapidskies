package us.rapidskies.essentials.tools;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.command.PlayerCommand;
import us.rapidskies.essentials.command.Suggest;

/**
 * Staff commands that change a player rather than their items: {@code /gamemode} (and
 * {@code /gmc}, {@code /gms}, {@code /gma}, {@code /gmsp}), {@code /fly}, {@code /god},
 * {@code /heal}, {@code /feed}, {@code /extinguish} and {@code /speed}. Each works on
 * yourself, or on someone else with its {@code .others} node; uses that change anything are
 * logged to {@code staff_actions}.
 */
public final class StateCommands {

    public static final String FLY_OTHERS = Powers.FLY + ".others";
    public static final String GOD_OTHERS = Powers.GOD + ".others";
    public static final String HEAL = "rapid.command.heal";
    public static final String HEAL_OTHERS = HEAL + ".others";
    public static final String FEED = "rapid.command.feed";
    public static final String FEED_OTHERS = FEED + ".others";
    public static final String EXTINGUISH = "rapid.command.extinguish";
    public static final String EXTINGUISH_OTHERS = EXTINGUISH + ".others";

    private final RapidEssentials plugin;
    private final Powers powers;

    public StateCommands(RapidEssentials plugin, Powers powers) {
        this.plugin = plugin;
        this.powers = powers;
    }

    // ------------------------------------------------------------------ /gamemode

    /** {@code /gamemode <mode> [player]}: each mode has its own node, so moderators can lack creative. */
    public BasicCommand gamemode() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                if (args.length < 1 || args.length > 2) {
                    plugin.lang().send(sender, "gamemode.usage");
                    return;
                }
                GameMode mode = GameModes.parse(args[0]);
                if (mode == null) {
                    plugin.lang().send(sender, "gamemode.unknown", Placeholder.unparsed("input", args[0]));
                    return;
                }
                setMode(sender, mode, args, 1);
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                return switch (Suggest.position(args)) {
                    case 0 -> Suggest.matching(GameModes.allowed(sender).stream().map(GameModes::id).toList(), args);
                    case 1 -> Target.players(plugin, sender, args, GameModes.OTHERS);
                    default -> List.of();
                };
            }

            @Override
            public boolean canUse(CommandSender sender) {
                return !GameModes.allowed(sender).isEmpty();
            }
        };
    }

    /** {@code /gmc [player]} and friends: {@code /gamemode} with the mode filled in. */
    public BasicCommand gamemode(String label, GameMode mode) {
        return new TargetCommand(plugin, label, GameModes.permission(mode), GameModes.OTHERS) {
            @Override
            protected void run(CommandSender sender, Player target) {
                apply(sender, target, mode);
            }
        };
    }

    private void setMode(CommandSender sender, GameMode mode, String[] args, int index) {
        if (!sender.hasPermission(GameModes.permission(mode))) {
            plugin.lang().send(sender, "gamemode.no-permission", Placeholder.component("mode", GameModes.name(mode)));
            return;
        }
        Player target = Target.resolve(plugin, sender, args, index, GameModes.OTHERS);
        if (target != null) {
            apply(sender, target, mode);
        }
    }

    private void apply(CommandSender sender, Player target, GameMode mode) {
        if (Target.busy(plugin, sender, target)) {
            return;
        }
        target.setGameMode(mode);
        Target.report(plugin, sender, target, "gamemode.set", "gamemode.set-other", "gamemode.changed",
                Placeholder.component("mode", GameModes.name(mode)));
        Target.log(plugin, sender, "gamemode", target, GameModes.id(mode));
    }

    // ------------------------------------------------------------------ /fly, /god

    /** {@code /fly [player]}: flight on or off, in survival and adventure. */
    public BasicCommand fly() {
        return new TargetCommand(plugin, "fly", Powers.FLY, FLY_OTHERS) {
            @Override
            protected void run(CommandSender sender, Player target) {
                if (Target.busy(plugin, sender, target)) {
                    return;
                }
                if (!Powers.survivalLike(target)) {
                    plugin.lang().send(sender, "fly.innate", Placeholder.unparsed("player", target.getName()),
                            Placeholder.component("mode", GameModes.name(target.getGameMode())));
                    return;
                }
                boolean on = !target.getAllowFlight();
                powers.setFly(target, on);
                Target.report(plugin, sender, target, on ? "fly.enabled" : "fly.disabled",
                        on ? "fly.enabled-other" : "fly.disabled-other", on ? "fly.given" : "fly.taken");
                Target.log(plugin, sender, "fly", target, on ? "on" : "off");
            }
        };
    }

    /** {@code /god [player]}: no damage or hunger, and no hurting other players. */
    public BasicCommand god() {
        return new TargetCommand(plugin, "god", Powers.GOD, GOD_OTHERS) {
            @Override
            protected void run(CommandSender sender, Player target) {
                boolean on = !powers.isGod(target);
                powers.setGod(target, on);
                Target.report(plugin, sender, target, on ? "god.enabled" : "god.disabled",
                        on ? "god.enabled-other" : "god.disabled-other", on ? "god.given" : "god.taken");
                Target.log(plugin, sender, "god", target, on ? "on" : "off");
            }
        };
    }

    // ------------------------------------------------------------------ /heal, /feed, /extinguish

    /** {@code /heal [player]}: full health and food, no fire, no harmful effects. */
    public BasicCommand heal() {
        return new TargetCommand(plugin, "heal", HEAL, HEAL_OTHERS) {
            @Override
            protected void run(CommandSender sender, Player target) {
                if (dead(sender, target)) {
                    return;
                }
                Powers.heal(target);
                Target.report(plugin, sender, target, "heal.self", "heal.other", "heal.by-staff");
                Target.log(plugin, sender, "heal", target, null);
            }
        };
    }

    /** {@code /feed [player]}: full food and saturation. */
    public BasicCommand feed() {
        return new TargetCommand(plugin, "feed", FEED, FEED_OTHERS) {
            @Override
            protected void run(CommandSender sender, Player target) {
                if (dead(sender, target)) {
                    return;
                }
                Powers.feed(target);
                Target.report(plugin, sender, target, "feed.self", "feed.other", "feed.by-staff");
                Target.log(plugin, sender, "feed", target, null);
            }
        };
    }

    /** {@code /extinguish [player]}: puts out the fire. Not logged; it changes nothing that lasts. */
    public BasicCommand extinguish() {
        return new TargetCommand(plugin, "extinguish", EXTINGUISH, EXTINGUISH_OTHERS) {
            @Override
            protected void run(CommandSender sender, Player target) {
                target.setFireTicks(0);
                Target.report(plugin, sender, target, "extinguish.self", "extinguish.other", "extinguish.by-staff");
            }
        };
    }

    private boolean dead(CommandSender sender, Player target) {
        if (!target.isDead()) {
            return false;
        }
        plugin.lang().send(sender, "errors.dead", Placeholder.unparsed("player", target.getName()));
        return true;
    }

    // ------------------------------------------------------------------ /speed

    /**
     * {@code /speed <0-10>} sets the fly speed while flying and the walk speed otherwise;
     * {@code /speed walk|fly <0-10>} picks one. 1 is normal.
     */
    public BasicCommand speed() {
        return new PlayerCommand(plugin, Powers.SPEED) {
            @Override
            protected void run(Player player, String[] args) {
                boolean fly;
                String level;
                if (args.length == 1) {
                    fly = player.isFlying();
                    level = args[0];
                } else if (args.length == 2 && (args[0].equalsIgnoreCase("walk") || args[0].equalsIgnoreCase("fly"))) {
                    fly = args[0].toLowerCase(Locale.ROOT).equals("fly");
                    level = args[1];
                } else {
                    plugin.lang().send(player, "speed.usage");
                    return;
                }
                Float parsed = Speeds.parseLevel(level);
                if (parsed == null) {
                    plugin.lang().send(player, "speed.usage");
                    return;
                }
                powers.setSpeed(player, fly, parsed);
                String shown = Speeds.format(parsed);
                plugin.lang().send(player, fly ? "speed.fly" : "speed.walk", Placeholder.unparsed("speed", shown));
                plugin.staffLog().log(player, "speed", null, (fly ? "fly " : "walk ") + shown);
            }

            @Override
            protected Collection<String> complete(Player player, String[] args) {
                return switch (Suggest.position(args)) {
                    case 0 -> Suggest.matching(List.of("walk", "fly", "1", "2", "5", "10"), args);
                    case 1 -> args[0].matches("(?i)walk|fly") ? Suggest.matching(List.of("1", "2", "5", "10"), args)
                            : List.of();
                    default -> List.of();
                };
            }
        };
    }
}
