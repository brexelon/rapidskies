package us.rapidskies.core.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.progress.NetworkLevels;
import us.rapidskies.common.staff.StaffActions;
import us.rapidskies.core.cosmetic.Cosmetic;
import us.rapidskies.core.cosmetic.CosmeticService;
import us.rapidskies.core.cosmetic.CosmeticType;
import us.rapidskies.core.cosmetic.CosmeticsMenu;
import us.rapidskies.core.gui.Pages;
import us.rapidskies.core.progress.LevelService;
import us.rapidskies.core.progress.PassMenu;
import us.rapidskies.core.quest.AchievementsMenu;
import us.rapidskies.core.quest.QuestService;
import us.rapidskies.core.quest.QuestsMenu;
import us.rapidskies.core.reward.DailyRewards;
import us.rapidskies.core.shop.BoltShop;
import us.rapidskies.core.shop.ShopMenu;

/**
 * The phase 5 commands (PLAN.md §6, §7): {@code /cosmetics}, {@code /boltshop},
 * {@code /quests}, {@code /achievements}, {@code /level}, {@code /pass}, and the staff
 * {@code /progressadmin}. Also the menu openers other plugins use (rapid-hub's hotbar).
 */
public final class ProgressCommands {

    public static final String ADMIN = "rapid.admin.progress";

    private final Plugin plugin;
    private final Database db;
    private final ProfileRepository profiles;
    private final CosmeticService cosmetics;
    private final BoltShop shop;
    private final LevelService levels;
    private final QuestService quests;
    private final DailyRewards daily;
    private final String serverId;
    private final Lang lang;

    public ProgressCommands(Plugin plugin, Database db, ProfileRepository profiles, CosmeticService cosmetics,
                            BoltShop shop, LevelService levels, QuestService quests, DailyRewards daily,
                            String serverId, Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.profiles = profiles;
        this.cosmetics = cosmetics;
        this.shop = shop;
        this.levels = levels;
        this.quests = quests;
        this.daily = daily;
        this.serverId = serverId;
        this.lang = lang;
    }

    // ------------------------------------------------------------------ menu openers

    public void openWardrobe(Player player) {
        new CosmeticsMenu(cosmetics, lang, CosmeticType.TITLE, this::openShop).open(player);
    }

    public void openShop(Player player) {
        new ShopMenu(shop, this::openWardrobe, this::openPass).open(player);
    }

    public void openQuests(Player player) {
        new QuestsMenu(quests, levels, lang, daily.streak(player), this::openAchievements, this::openPass).open(player);
    }

    public void openAchievements(Player player) {
        new AchievementsMenu(quests, cosmetics.catalog(), lang, this::openQuests).open(player);
    }

    public void openPass(Player player) {
        new PassMenu(levels, cosmetics.catalog(), lang,
                p -> levels.season().ifPresent(s -> shopPass(p))).open(player);
    }

    private void shopPass(Player player) {
        new ShopMenu(shop, this::openWardrobe, this::openPass).pass().open(player);
    }

    // ------------------------------------------------------------------ player commands

    private BasicCommand menu(java.util.function.Consumer<Player> open) {
        return (stack, args) -> {
            if (stack.getSender() instanceof Player p) {
                open.accept(p);
            } else {
                lang.send(stack.getSender(), "errors.players-only");
            }
        };
    }

    public BasicCommand cosmetics() {
        return menu(this::openWardrobe);
    }

    public BasicCommand shop() {
        return menu(this::openShop);
    }

    public BasicCommand quests() {
        return menu(this::openQuests);
    }

    public BasicCommand achievements() {
        return menu(this::openAchievements);
    }

    public BasicCommand pass() {
        return menu(this::openPass);
    }

    /** {@code /level [player]}: level, XP to the next and the next reward. */
    public BasicCommand level() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0])
                        : sender instanceof Player p ? p : null;
                if (target == null) {
                    lang.send(sender, args.length > 0 ? "level.not-here" : "errors.players-only",
                            Placeholder.unparsed("player", args.length > 0 ? args[0] : ""));
                    return;
                }
                NetworkLevels.Progress p = levels.progress(target);
                lang.send(sender, p.maxed() ? "level.show-max" : "level.show",
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("level", Integer.toString(p.level())),
                        Placeholder.unparsed("into", String.format("%,d", p.into())),
                        Placeholder.unparsed("needed", String.format("%,d", p.needed())),
                        Placeholder.unparsed("next", Integer.toString(p.level() + 1)),
                        Placeholder.component("bar", Lang.parse(Pages.bar(p.into(), p.needed(), 20))));
                levels.config().nextRewardLevel(p.level()).ifPresent(l -> {
                    String names = String.join(", ", levels.config().levelCosmetics(l).stream()
                            .map(id -> cosmetics.catalog().find(id).map(Cosmetic::name).orElse(id)).toList());
                    lang.send(sender, "level.next-reward", Placeholder.unparsed("level", Integer.toString(l)),
                            Placeholder.unparsed("rewards", names));
                });
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
            }
        };
    }

    // ------------------------------------------------------------------ staff

    /**
     * {@code /progressadmin cosmetic give|take <player> <id>}, {@code cosmetic list <player>},
     * {@code xp <player> <amount>}, {@code featured}. Every change is logged to
     * {@code staff_actions}.
     */
    public BasicCommand admin() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                if (args.length >= 1 && args[0].equalsIgnoreCase("featured")) {
                    featured(sender);
                    return;
                }
                if (args.length >= 3 && args[0].equalsIgnoreCase("cosmetic")) {
                    String action = args[1].toLowerCase(Locale.ROOT);
                    if (action.equals("list")) {
                        withPlayer(sender, args[2], p -> list(sender, p));
                        return;
                    }
                    if (args.length >= 4 && (action.equals("give") || action.equals("take"))) {
                        String id = args[3];
                        if (!cosmetics.catalog().exists(id)) {
                            lang.send(sender, "progressadmin.unknown-cosmetic", Placeholder.unparsed("id", id));
                            return;
                        }
                        withPlayer(sender, args[2], p -> cosmetic(sender, p, id, action.equals("give")));
                        return;
                    }
                }
                if (args.length >= 3 && args[0].equalsIgnoreCase("xp")) {
                    long amount;
                    try {
                        amount = Long.parseLong(args[2]);
                    } catch (NumberFormatException e) {
                        amount = 0;
                    }
                    if (amount <= 0) {
                        lang.send(sender, "progressadmin.bad-amount", Placeholder.unparsed("input", args[2]));
                        return;
                    }
                    long xp = amount;
                    withPlayer(sender, args[1], p -> levels.add(p.uuid(), xp, "admin").thenAccept(gain -> {
                        lang.send(sender, "progressadmin.xp-added", Placeholder.unparsed("player", p.name()),
                                Placeholder.unparsed("amount", String.format("%,d", xp)),
                                Placeholder.unparsed("level", Integer.toString(gain.map(g -> g.levelAfter()).orElse(1))));
                        log(sender, "progress-xp", p.uuid(), "+" + xp);
                    }));
                    return;
                }
                lang.send(sender, "progressadmin.usage");
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
                Stream<String> options = switch (args.length) {
                    case 0, 1 -> Stream.of("cosmetic", "xp", "featured");
                    case 2 -> args[0].equalsIgnoreCase("cosmetic") ? Stream.of("give", "take", "list")
                            : Bukkit.getOnlinePlayers().stream().map(Player::getName);
                    case 3 -> args[0].equalsIgnoreCase("cosmetic")
                            ? Bukkit.getOnlinePlayers().stream().map(Player::getName) : Stream.of();
                    case 4 -> args[0].equalsIgnoreCase("cosmetic") ? cosmetics.catalog().all().stream()
                            .map(Cosmetic::id) : Stream.of();
                    default -> Stream.of();
                };
                return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
            }

            @Override
            public String permission() {
                return ADMIN;
            }
        };
    }

    private void withPlayer(CommandSender sender, String name, java.util.function.Consumer<PlayerProfile> then) {
        db.async(() -> profiles.findByName(name)).whenComplete((found, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("/progressadmin lookup of {} failed: {}", name, e.getMessage());
                lang.send(sender, "progressadmin.failed");
            } else if (found.isEmpty()) {
                lang.send(sender, "bolts.unknown-player", Placeholder.unparsed("player", name));
            } else {
                then.accept(found.get());
            }
        }));
    }

    private void cosmetic(CommandSender sender, PlayerProfile p, String id, boolean give) {
        String by = sender instanceof Player s ? s.getName() : "console";
        (give ? cosmetics.grant(p.uuid(), id, "admin:" + by) : cosmetics.revoke(p.uuid(), id)).whenComplete((changed, e) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (e != null) {
                        lang.send(sender, "progressadmin.failed");
                        return;
                    }
                    lang.send(sender, give ? (changed ? "progressadmin.given" : "progressadmin.already-has")
                                    : (changed ? "progressadmin.taken" : "progressadmin.doesnt-have"),
                            Placeholder.unparsed("player", p.name()), Placeholder.unparsed("id", id));
                    if (changed) {
                        log(sender, give ? "cosmetic-give" : "cosmetic-take", p.uuid(), id);
                    }
                }));
    }

    private void list(CommandSender sender, PlayerProfile p) {
        db.async(() -> cosmetics.load(p.uuid())).whenComplete((w, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (e != null) {
                lang.send(sender, "progressadmin.failed");
                return;
            }
            lang.send(sender, "progressadmin.list", Placeholder.unparsed("player", p.name()),
                    Placeholder.unparsed("count", Integer.toString(w.owned().size())),
                    Placeholder.unparsed("owned", w.owned().isEmpty() ? "-" : String.join(", ", w.owned().stream().sorted().toList())),
                    Placeholder.unparsed("equipped", w.equipped().isEmpty() ? "-" : w.equipped().toString()));
        }));
    }

    private void featured(CommandSender sender) {
        Instant now = Instant.now();
        lang.send(sender, "progressadmin.featured", Placeholder.unparsed("now",
                        String.join(", ", shop.catalog().featured(now).stream().map(Cosmetic::id).toList())),
                Placeholder.unparsed("next", String.join(", ", shop.catalog().featured(now.plus(7, ChronoUnit.DAYS))
                        .stream().map(Cosmetic::id).toList())));
    }

    private void log(CommandSender sender, String action, UUID target, String detail) {
        if (!(sender instanceof Player staff)) {
            plugin.getSLF4JLogger().info("Console {} {} {}", action, target, detail);
            return;
        }
        UUID staffId = staff.getUniqueId();
        db.async(() -> {
            new StaffActions(db).log(staffId, action, target, serverId, detail);
            return null;
        });
    }
}
