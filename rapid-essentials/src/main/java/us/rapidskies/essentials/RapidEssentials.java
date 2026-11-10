package us.rapidskies.essentials;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.GameMode;
import org.bukkit.plugin.java.JavaPlugin;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.core.staff.StaffLog;
import us.rapidskies.essentials.chat.Broadcasts;
import us.rapidskies.essentials.command.AdminCommands;
import us.rapidskies.essentials.command.PlayerCommands;
import us.rapidskies.essentials.command.TeleportCommands;
import us.rapidskies.essentials.data.EssentialsRepository;
import us.rapidskies.essentials.home.HomeService;
import us.rapidskies.essentials.money.MoneyCommands;
import us.rapidskies.essentials.teleport.BackService;
import us.rapidskies.essentials.teleport.Teleports;
import us.rapidskies.essentials.teleport.TpaRequests.Direction;
import us.rapidskies.essentials.teleport.TpaService;
import us.rapidskies.essentials.tools.ItemCommands;
import us.rapidskies.essentials.tools.Powers;
import us.rapidskies.essentials.tools.StateCommands;
import us.rapidskies.essentials.tools.Workstations;
import us.rapidskies.essentials.warp.WarpService;

/**
 * rapid-essentials: the everyday commands every Paper backend offers, modeled on EssentialsX
 * but built on rapid-core (its spawn, AFK tracker, vanish, freeze and per-mode economy) so
 * there's one source of truth for each.
 *
 * <ul>
 *   <li>Teleports, all through {@link Teleports} (guards, cooldowns, warmup, safe landing):
 *       {@code /spawn}, {@code /back}, {@code /tpa} and friends, homes and warps; plus
 *       {@code /setspawn}.</li>
 *   <li>The gamemode currency on SkyRealms and Horizons: {@code /balance}, {@code /pay},
 *       {@code /baltop}, {@code /eco}.</li>
 *   <li>{@code /afk}, {@code /ping}, {@code /list}, {@code /seen}, {@code /hat}, text pages
 *       such as {@code /rules} and {@code /store}, and a network-wide {@code /broadcast}.</li>
 *   <li>Staff tools ({@code tools}): {@code /gamemode}, {@code /fly}, {@code /god},
 *       {@code /heal}, {@code /feed}, {@code /speed}, {@code /give}, {@code /more},
 *       {@code /repair}, {@code /clearinventory}, {@code /craft} and the other workstation
 *       menus, {@code /enderchest}, {@code /disposal}. Staff only, never sold (§6.1), tiered
 *       in infra/luckperms/bootstrap.txt, logged to {@code staff_actions}, and refused for
 *       anyone in staff or build mode, whose inventory and flight are on loan.</li>
 * </ul>
 *
 * <p>Left out on purpose: kits (§6.1). {@code /msg}, {@code /r} and {@code /hub} are the
 * proxy's; the hub's {@code /fly} is rapid-hub's (the hub profile switches ours off).
 *
 * <p>Other plugins hook in through {@link #get()}: {@code teleports().addGuard(...)} to
 * refuse teleports (rapid-horizons while combat-tagged) and
 * {@code back().addOriginFilter(...)}.
 */
public final class RapidEssentials extends JavaPlugin {

    private EssentialsSettings settings;
    private Lang lang;
    private StaffLog staffLog;
    private Teleports teleports;
    private BackService back;
    private TpaService tpa;
    private HomeService homes;
    private WarpService warps;
    private Broadcasts broadcasts;
    private Powers powers;

    /** The running instance, for plugins that depend on rapid-essentials. */
    public static RapidEssentials get() {
        return JavaPlugin.getPlugin(RapidEssentials.class);
    }

    @Override
    public void onEnable() {
        try {
            lang = loadLang();
            settings = loadSettings();
        } catch (IOException | IllegalArgumentException e) {
            getSLF4JLogger().error("rapid-essentials can't start (fix the config, then restart): {}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            core().database().migrate(getClassLoader(), "essentials");
        } catch (SQLException | RuntimeException e) {
            getSLF4JLogger().error("rapid-essentials can't migrate its tables; disabling it", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        EssentialsRepository repository = new EssentialsRepository(core().database());
        staffLog = new StaffLog(core(), core().database());
        teleports = new Teleports(this);
        back = new BackService(this);
        tpa = new TpaService(this);
        homes = new HomeService(this, repository, !settings.disabled("home"));
        warps = new WarpService(this, repository);
        broadcasts = new Broadcasts(this);
        powers = new Powers(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(teleports, this);
        pm.registerEvents(back, this);
        pm.registerEvents(tpa, this);
        pm.registerEvents(homes, this);
        pm.registerEvents(powers, this);
        if (!settings.disabled("warp")) {
            warps.load();
        }
        broadcasts.start();

        TeleportCommands tp = new TeleportCommands(this);
        PlayerCommands player = new PlayerCommands(this);
        AdminCommands admin = new AdminCommands(this);
        StateCommands state = new StateCommands(this, powers);
        ItemCommands items = new ItemCommands(this);
        Workstations stations = new Workstations(this);
        ModeEconomy economy = core().economy();
        MoneyCommands money = economy == null ? null : new MoneyCommands(this, economy, repository);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Registrar r = new Registrar(event.registrar());
            r.add("spawn", "Go to the spawn", List.of(), tp.spawn());
            r.add("setspawn", "Move the spawn to where you stand", List.of(), tp.setSpawn());
            r.add("back", "Go back to where you were before your last teleport or death", List.of(), tp.back());
            r.add("tpa", "Ask to teleport to a player", List.of("tpask", "call"), tp.tpa(Direction.THERE));
            r.add("tpahere", "Ask a player to teleport to you", List.of(), tp.tpa(Direction.HERE));
            r.add("tpaccept", "Accept a teleport request", List.of("tpyes"), tp.tpaAnswer(true));
            r.add("tpdeny", "Deny a teleport request", List.of("tpno"), tp.tpaAnswer(false));
            r.add("tpacancel", "Withdraw your teleport request", List.of(), tp.tpaCancel());
            r.add("tptoggle", "Stop or allow teleport requests until you leave", List.of(), tp.tpToggle());
            r.add("home", "Go to one of your homes", List.of("h"), tp.home());
            r.add("sethome", "Set a home where you stand", List.of(), tp.setHome());
            r.add("delhome", "Delete one of your homes", List.of("remhome", "rmhome"), tp.delHome());
            r.add("homes", "List your homes", List.of(), tp.homes());
            r.add("warp", "Go to a server warp", List.of(), tp.warp());
            r.add("warps", "List the server warps", List.of(), tp.warps());
            r.add("setwarp", "Create or move a warp where you stand", List.of(), tp.editWarp(true));
            r.add("delwarp", "Delete a warp", List.of("remwarp", "rmwarp"), tp.editWarp(false));
            r.add("afk", "Mark yourself as away", List.of(), player.afk());
            r.add("ping", "Show your connection's ping", List.of("pong"), player.ping());
            r.add("list", "Who's on this server", List.of("online", "who"), player.list());
            r.add("seen", "When a player was last on, and where", List.of(), player.seen());
            r.add("hat", "Wear the item you're holding", List.of("head"), player.hat());
            for (Map.Entry<String, List<String>> info : settings.info().entrySet()) {
                r.add(info.getKey(), "Show the " + info.getKey() + " page", info.getValue(),
                        player.info(info.getKey()));
            }
            if (money != null) {
                String currency = economy.mode().currencyName(2);
                r.add("balance", "Show your " + currency, List.of("bal", "money"), money.balance());
                r.add("pay", "Pay another player " + currency, List.of(), money.pay());
                r.add("baltop", "The richest players in " + currency, List.of("balancetop", "moneytop"),
                        money.baltop());
                r.add("eco", "Give or take " + currency + " (admin)", List.of("economy"), money.eco());
            }
            // Staff tools. /gamemode and /give replace vanilla's (still there as /minecraft:...).
            r.add("gamemode", "Change a game mode (staff)", List.of("gm"), state.gamemode());
            if (!settings.disabled("gamemode")) {
                r.add("gms", "Survival mode (staff)", List.of(), state.gamemode("gms", GameMode.SURVIVAL));
                r.add("gmc", "Creative mode (staff)", List.of(), state.gamemode("gmc", GameMode.CREATIVE));
                r.add("gma", "Adventure mode (staff)", List.of(), state.gamemode("gma", GameMode.ADVENTURE));
                r.add("gmsp", "Spectator mode (staff)", List.of(), state.gamemode("gmsp", GameMode.SPECTATOR));
            }
            r.add("fly", "Flight on or off (staff)", List.of(), state.fly());
            r.add("god", "God mode on or off (staff)", List.of("godmode"), state.god());
            r.add("heal", "Full health and food (staff)", List.of(), state.heal());
            r.add("feed", "Full food (staff)", List.of("eat"), state.feed());
            r.add("extinguish", "Put out a fire (staff)", List.of("ext"), state.extinguish());
            r.add("speed", "Walk or fly speed (staff)", List.of(), state.speed());
            r.add("give", "Give a player an item (staff)", List.of(), items.give());
            r.add("i", "Give yourself an item (staff)", List.of(), items.item());
            r.add("more", "Fill the held stack (staff)", List.of(), items.more());
            r.add("repair", "Repair the held item or everything (staff)", List.of("fix"), items.repair());
            r.add("clearinventory", "Empty an inventory (staff)", List.of("ci"), items.clearInventory());
            r.add("craft", "Open a crafting table (staff)", List.of("workbench", "wb"), stations.craft());
            r.add("anvil", "Open an anvil (staff)", List.of(), stations.anvil());
            r.add("grindstone", "Open a grindstone (staff)", List.of(), stations.grindstone());
            r.add("loom", "Open a loom (staff)", List.of(), stations.loom());
            r.add("smithingtable", "Open a smithing table (staff)", List.of("smithing"), stations.smithingTable());
            r.add("stonecutter", "Open a stonecutter (staff)", List.of(), stations.stonecutter());
            r.add("cartographytable", "Open a cartography table (staff)", List.of("cartography"),
                    stations.cartographyTable());
            r.add("enderchest", "Open your ender chest (staff)", List.of("ec", "echest"), stations.enderChest());
            r.add("disposal", "Delete items (staff)", List.of("trash"), stations.disposal());
            r.add("broadcast", "Announce something on every server", List.of("bc", "announce"), admin.broadcast());
            r.add("essentials", "rapid-essentials admin: reload, version", List.of("ess"), admin.essentials());
            if (!r.skipped.isEmpty()) {
                getSLF4JLogger().info("Commands off on this server (disabled-commands): {}", r.skipped);
            }
        });
        getSLF4JLogger().info("rapid-essentials {} enabled (scope {}, {})", getPluginMeta().getVersion(), scope(),
                money == null ? "no gamemode currency" : economy.mode().currencyName(2));
    }

    /** Registers commands unless {@code disabled-commands} names them. */
    private final class Registrar {
        private final Commands commands;
        private final List<String> skipped = new ArrayList<>();

        Registrar(Commands commands) {
            this.commands = commands;
        }

        void add(String label, String description, List<String> aliases, BasicCommand command) {
            if (settings.disabled(label)) {
                skipped.add(label);
                return;
            }
            commands.register(label, description, aliases, command);
        }
    }

    @Override
    public void onDisable() {
        if (teleports != null) {
            teleports.cancelAll();
        }
    }

    private Lang loadLang() throws IOException {
        Path data = getDataPath();
        return Lang.load(getClassLoader(), Lang.DEFAULT_RESOURCE, data.resolve(Lang.DEFAULT_RESOURCE),
                getSLF4JLogger());
    }

    private EssentialsSettings loadSettings() throws IOException {
        return EssentialsSettings.from(ConfigView.loadOrCreate(getClassLoader(), "config.yml",
                getDataPath().resolve("config.yml")));
    }

    /** Re-reads config.yml and the messages. Returns false (changing nothing) if the new config is invalid. */
    public boolean reload() {
        try {
            EssentialsSettings next = loadSettings();
            Lang nextLang = loadLang();
            settings = next;
            lang = nextLang;
            return true;
        } catch (IOException | IllegalArgumentException e) {
            getSLF4JLogger().error("rapid-essentials reload failed, keeping the running config: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ parts, for each other and other plugins

    public RapidCore core() {
        return RapidCore.get();
    }

    public EssentialsSettings settings() {
        return settings;
    }

    public Lang lang() {
        return lang;
    }

    /**
     * Whose homes and warps these are: the server group ({@code smp}, {@code skyrealms}), so
     * every instance of a mode shares them and modes never mix. The server id if there's no group.
     */
    public String scope() {
        String group = core().settings().group();
        return group == null || group.isBlank() ? core().settings().serverId() : group;
    }

    public StaffLog staffLog() {
        return staffLog;
    }

    /** Every teleport command goes through here; {@code addGuard} to refuse teleports. */
    public Teleports teleports() {
        return teleports;
    }

    /** {@code /back}; {@code addOriginFilter} to change what it remembers. */
    public BackService back() {
        return back;
    }

    public TpaService tpa() {
        return tpa;
    }

    public HomeService homes() {
        return homes;
    }

    public WarpService warps() {
        return warps;
    }

    public Broadcasts broadcasts() {
        return broadcasts;
    }

    /** {@code /fly}, {@code /god} and {@code /speed} state. */
    public Powers powers() {
        return powers;
    }
}
