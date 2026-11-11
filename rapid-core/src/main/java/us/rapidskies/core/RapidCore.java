package us.rapidskies.core;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.config.RedisSettings;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.core.afk.AfkTracker;
import us.rapidskies.core.chat.ChatListener;
import us.rapidskies.core.chat.ChatTags;
import us.rapidskies.core.command.ProgressCommands;
import us.rapidskies.core.cosmetic.Catalog;
import us.rapidskies.core.cosmetic.CosmeticService;
import us.rapidskies.core.cosmetic.ProfileCard;
import us.rapidskies.core.cosmetic.Trails;
import us.rapidskies.core.progress.LevelService;
import us.rapidskies.core.progress.ProgressConfig;
import us.rapidskies.core.quest.ObjectiveListener;
import us.rapidskies.core.quest.QuestConfig;
import us.rapidskies.core.quest.QuestService;
import us.rapidskies.core.reward.DailyRewards;
import us.rapidskies.core.shop.BoltShop;
import us.rapidskies.core.command.BoltsCommand;
import us.rapidskies.core.command.PlayerStatusCommands;
import us.rapidskies.core.command.PlusCommand;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.core.gui.MenuListener;
import us.rapidskies.core.profile.ProfileService;
import us.rapidskies.core.rank.PlusChoice;
import us.rapidskies.core.rank.RankDisplay;
import us.rapidskies.core.rank.RapidPlaceholders;
import us.rapidskies.core.reward.EarnGuard;
import us.rapidskies.core.reward.PlaytimeRewards;
import us.rapidskies.core.staff.Freeze;
import us.rapidskies.core.staff.StaffCommands;
import us.rapidskies.core.staff.StaffLog;
import us.rapidskies.core.staff.StaffMode;
import us.rapidskies.core.staff.Vanish;
import us.rapidskies.core.tailwind.TailwindService;
import us.rapidskies.core.world.BuildMode;
import us.rapidskies.core.world.WorldConfig;
import us.rapidskies.core.world.WorldProtection;
import us.rapidskies.core.world.WorldService;

/**
 * rapid-core: runs on every Paper backend and is the API the other rapid-* plugins build on
 * (PLAN.md §4). Phase 1: profiles, the Bolts and per-mode ledgers, the server heartbeat,
 * chat with rank prefixes and plus colors, the chest-menu framework
 * ({@link us.rapidskies.core.gui.Menu}), the {@code /plus} menu, {@code /bolts}, the
 * PlaceholderAPI expansion, AFK detection, anti-farm rules and
 * playtime Bolts, Tailwind, staff mode (vanish, freeze, inspection), on gamemode
 * servers the Vault economy for Coins or Crowns, and the spawn and spawn worlds
 * ({@link us.rapidskies.core.world.WorldService}) with build mode for changing them
 * ({@link BuildMode}).
 *
 * <p>Other plugins get the services through {@link #get()} after declaring
 * {@code depend: [rapid-core]}.
 *
 * <p>If MariaDB or Redis can't be reached at startup the server shuts itself down; Wings
 * restarts it (crash detection), so it keeps retrying without ever letting players onto a
 * server that can't load their data.
 */
public final class RapidCore extends JavaPlugin {

    private static final int CONNECT_ATTEMPTS = 3;
    private static final long CONNECT_RETRY_MILLIS = 5_000;

    private CoreSettings settings;
    private WorldConfig worldConfig;
    private Lang lang;
    private Database database;
    private RedisBus redis;
    private ProfileRepository profileRepository;
    private ProfileService profiles;
    private Ledger bolts;
    private us.rapidskies.core.economy.BoltsCache boltsCache;
    private final Map<EconomyMode, Ledger> modeLedgers = new EnumMap<>(EconomyMode.class);
    private RankDisplay ranks;
    private final ChatTags chatTags = new ChatTags();
    private final us.rapidskies.core.gui.ChatPrompts prompts = new us.rapidskies.core.gui.ChatPrompts();
    private Heartbeat heartbeat;
    private AfkTracker afk;
    private TailwindService tailwind;
    private EarnGuard earning;
    private PlaytimeRewards playtime;
    private ModeEconomy economy;
    private StaffMode staffMode;
    private WorldService worlds;
    private BuildMode buildMode;
    private boolean vaultRegistered;
    private Catalog catalog;
    private ProgressConfig progressConfig;
    private QuestConfig questConfig;
    private CosmeticService cosmetics;
    private LevelService levels;
    private QuestService quests;
    private BoltShop boltShop;
    private DailyRewards daily;
    private Trails trails;
    private ProgressCommands progress;

    /** The running instance, for plugins that depend on rapid-core. */
    public static RapidCore get() {
        return JavaPlugin.getPlugin(RapidCore.class);
    }

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();
        Path data = getDataPath();
        ClassLoader cl = getClassLoader();
        try {
            ConfigView config = ConfigView.loadOrCreate(cl, "config.yml", data.resolve("config.yml"));
            settings = CoreSettings.from(config, getServer().getPort(), log);
            worldConfig = worldConfig(config, log);
            lang = Lang.load(cl, Lang.DEFAULT_RESOURCE, data.resolve(Lang.DEFAULT_RESOURCE), log);
            // Phase 5 catalogs, read from the jar so every server agrees (tested in the build).
            catalog = Catalog.load(cl);
            progressConfig = ProgressConfig.load(cl, catalog);
            questConfig = QuestConfig.load(cl, catalog);
            connect(config, cl, log);
        } catch (Exception e) {
            log.error("rapid-core can't start; shutting the server down so Wings restarts it", e);
            getServer().shutdown();
            return;
        }

        profileRepository = new ProfileRepository(database);
        bolts = Ledger.bolts(database);
        for (EconomyMode mode : EconomyMode.values()) {
            modeLedgers.put(mode, Ledger.mode(database, mode));
        }
        LuckPerms luckPerms = LuckPermsProvider.get();
        profiles = new ProfileService(this, profileRepository, redis, settings, lang);
        ranks = new RankDisplay(luckPerms, profiles, lang);

        var pm = getServer().getPluginManager();
        pm.registerEvents(profiles, this);
        ChatListener chat = new ChatListener(ranks, chatTags, lang);
        pm.registerEvents(chat, this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(prompts, this);

        afk = new AfkTracker(this, settings.afk(), lang);
        pm.registerEvents(afk, this);
        afk.start();
        tailwind = new TailwindService(this, database, profiles, afk, settings, lang);
        pm.registerEvents(tailwind, this);
        tailwind.start();
        boltsCache = new us.rapidskies.core.economy.BoltsCache(this, database, bolts);
        pm.registerEvents(boltsCache, this);
        boltsCache.start();
        earning = new EarnGuard(this, database, bolts, redis, afk, settings.serverId());
        pm.registerEvents(earning, this);
        earning.start();
        playtime = new PlaytimeRewards(this, earning, redis, settings.playtime(), lang);
        playtime.start();

        StaffLog staffLog = new StaffLog(this, database);
        Vanish vanish = new Vanish(this, redis, staffLog, lang);
        Freeze freeze = new Freeze(this, redis, staffLog, lang, settings.staff().freezeReminderSeconds());
        staffMode = new StaffMode(this, vanish, freeze, staffLog, lang, settings.staff());
        pm.registerEvents(vanish, this);
        pm.registerEvents(freeze, this);
        pm.registerEvents(staffMode, this);
        freeze.start();
        vanish.restoreOnline();
        freeze.restoreOnline();

        worlds = new WorldService(this, worldConfig);
        worlds.start();
        buildMode = new BuildMode(this, worlds, staffMode, staffLog, lang);
        pm.registerEvents(worlds, this);
        pm.registerEvents(buildMode, this);
        pm.registerEvents(new WorldProtection(worlds, buildMode), this);
        StaffCommands staffCommands = new StaffCommands(staffMode, buildMode, lang);

        // Phase 5: cosmetics, levels, quests, achievements, the Season Pass, the Bolt shop.
        cosmetics = new CosmeticService(this, catalog, database, ranks, redis, settings.serverId(), lang);
        pm.registerEvents(cosmetics, this);
        levels = new LevelService(this, database, new us.rapidskies.common.progress.ProgressRepository(database, bolts),
                progressConfig, profiles, cosmetics, earning, boltsCache, lang);
        pm.registerEvents(levels, this);
        quests = new QuestService(this, database, questConfig, serverMode(), profiles, levels, cosmetics, earning, afk,
                boltsCache, lang);
        pm.registerEvents(quests, this);
        pm.registerEvents(new ObjectiveListener(quests), this);
        quests.start();
        playtime.onActiveMinute(p -> quests.record(p, "play-minutes", 1));
        playtime.onInterval(levels::playtimeInterval);
        boltShop = new BoltShop(this, database, bolts, cosmetics, levels, ranks, redis, boltsCache, lang);
        daily = new DailyRewards(this, database, bolts, earning, ranks, boltsCache, progressConfig,
                settings.serverId(), lang);
        pm.registerEvents(daily, this);
        daily.start();
        trails = new Trails(this, cosmetics, staffMode, buildMode, settings.trails());
        pm.registerEvents(trails, this);
        trails.start();
        chat.style(new ProfileCard(cosmetics, levels, ranks, lang));
        progress = new ProgressCommands(this, database, profileRepository, cosmetics, boltShop, levels, quests, daily,
                settings.serverId(), lang);

        settings.gamemode().ifPresent(mode -> {
            economy = new ModeEconomy(this, mode, modeLedgers.get(mode), redis, settings.serverId());
            pm.registerEvents(economy, this);
            if (pm.getPlugin("Vault") != null) {
                // Its own class, so Vault's types are only loaded when it's installed.
                us.rapidskies.core.economy.VaultEconomy.register(this, economy);
                vaultRegistered = true;
            } else {
                getSLF4JLogger().warn("Vault (VaultUnlocked) isn't installed: plugins that charge {} through "
                        + "Vault won't work here", mode.currencyName(2));
            }
        });

        PlusCommand plus = new PlusCommand(new PlusChoice(this, database, ranks, profiles, profileRepository, lang));
        BoltsCommand boltsCommand = new BoltsCommand(database, bolts, profileRepository, lang);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("plus", "Choose the color of the pluses in your rank prefix", List.of(), plus);
            event.registrar().register("bolts", "Show your Bolts balance", List.of("bolt"), boltsCommand);
            event.registrar().register("tailwind", "Show your Tailwind bonus", List.of("rested"),
                    PlayerStatusCommands.tailwind(tailwind, lang));
            event.registrar().register("staff", "Toggle staff mode", List.of("staffmode", "mod"),
                    staffCommands.staff());
            event.registrar().register("vanish", "Toggle vanish", List.of("v"), staffCommands.vanish());
            event.registrar().register("freeze", "Freeze or release a player", List.of("ss"),
                    staffCommands.freeze());
            event.registrar().register("invsee", "Inspect a player's inventory", List.of(), staffCommands.invsee());
            event.registrar().register("endersee", "Inspect a player's ender chest", List.of("echest"),
                    staffCommands.endersee());
            event.registrar().register("cosmetics", "Wear your titles, tags, name colors, badges and trails",
                    List.of("cosmetic", "wardrobe", "cos"), progress.cosmetics());
            event.registrar().register("boltshop", "Spend Bolts on cosmetics, rank passes and the Season Pass",
                    List.of("bshop", "boltstore"), progress.shop());
            event.registrar().register("quests", "Your daily and weekly quests", List.of("quest", "dailies"),
                    progress.quests());
            event.registrar().register("achievements", "Your achievements", List.of("achievement", "ach"),
                    progress.achievements());
            event.registrar().register("level", "Your network level", List.of("lvl", "networklevel"), progress.level());
            event.registrar().register("pass", "The Season Pass", List.of("seasonpass", "battlepass"), progress.pass());
            event.registrar().register("progressadmin", "Grant cosmetics and XP (staff)", List.of("padmin"),
                    progress.admin());
            // /spawn, /setspawn, /afk and the other everyday commands are rapid-essentials'.
            event.registrar().register("build", "Toggle build mode (change the protected spawn worlds)",
                    List.of("buildmode", "bm"), buildMode.command());
        });

        if (pm.getPlugin("PlaceholderAPI") != null) {
            // Its own class, so PlaceholderAPI's types are only loaded when it's installed.
            new RapidPlaceholders(this).register();
        }

        heartbeat = new Heartbeat(this, redis, settings);
        heartbeat.start();
        log.info("rapid-core {} enabled as {} (group {})", getPluginMeta().getVersion(), settings.serverId(),
                settings.group());
    }

    /** What quests count as "this server": its gamemode, or {@code hub} on lobbies. */
    private String serverMode() {
        return settings.gamemode().map(EconomyMode::id).orElse(settings.group());
    }

    /**
     * The spawn and spawn worlds. A mistake there is logged rather than fatal: players still
     * get onto the server (at vanilla's spawn) while it's fixed.
     */
    private static WorldConfig worldConfig(ConfigView config, Logger log) {
        try {
            return WorldConfig.from(config);
        } catch (IllegalArgumentException e) {
            log.error("The spawn/worlds config is invalid, so rapid-core leaves spawns and worlds alone: {}",
                    e.getMessage());
            return WorldConfig.from(ConfigView.empty());
        }
    }

    private void connect(ConfigView config, ClassLoader cl, Logger log) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            try {
                database = Database.connect(DatabaseSettings.from(config.section("database")),
                        "rapid-core-" + settings.serverId(), log);
                database.migrate(cl, "core");
                redis = RedisBus.connect(RedisSettings.from(config.section("redis")), settings.serverId(), log);
                return;
            } catch (SQLException | RuntimeException e) {
                last = e;
                log.warn("Connecting to the database/Redis failed (attempt {}/{}): {}", attempt, CONNECT_ATTEMPTS,
                        e.getMessage());
                closeServices();
                if (attempt < CONNECT_ATTEMPTS) {
                    Thread.sleep(CONNECT_RETRY_MILLIS);
                }
            }
        }
        throw last;
    }

    @Override
    public void onDisable() {
        MenuListener.closeAll();
        if (buildMode != null) {
            // Before anything else, so nobody keeps a creative inventory.
            buildMode.leaveAll();
        }
        if (staffMode != null) {
            // Before anything else, so nobody is left holding staff tools.
            staffMode.leaveAll();
            staffMode.vanish().showAll();
            staffMode.freeze().stop();
        }
        if (vaultRegistered) {
            us.rapidskies.core.economy.VaultEconomy.unregister(this);
        }
        if (heartbeat != null) {
            heartbeat.stop();
        }
        if (trails != null) {
            trails.stop();
        }
        if (daily != null) {
            daily.stop();
        }
        if (quests != null) {
            // Writes quest progress still in memory, so it must run before the database closes.
            quests.stop();
        }
        if (playtime != null) {
            playtime.stop();
        }
        if (earning != null) {
            earning.stop();
        }
        if (afk != null) {
            afk.stop();
        }
        if (boltsCache != null) {
            boltsCache.stop();
        }
        if (tailwind != null) {
            // Writes unsaved spending, so it must run before the database closes.
            tailwind.stop();
        }
        closeServices();
    }

    private void closeServices() {
        if (redis != null) {
            redis.close();
            redis = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }

    // ------------------------------------------------------------------ API for other rapid-* plugins

    public CoreSettings settings() {
        return settings;
    }

    /** rapid-core's message bundle. */
    public Lang lang() {
        return lang;
    }

    public Database database() {
        return database;
    }

    public RedisBus redis() {
        return redis;
    }

    public ProfileService profiles() {
        return profiles;
    }

    /** The network-wide Bolts ledger. */
    public Ledger bolts() {
        return bolts;
    }

    /** Bolts balances of the players online here, for displays (never for spending). */
    public us.rapidskies.core.economy.BoltsCache boltsCache() {
        return boltsCache;
    }

    /** A gamemode's own currency (Crowns, Coins). Vault providers per mode build on this. */
    public Ledger modeLedger(EconomyMode mode) {
        return modeLedgers.get(mode);
    }

    public RankDisplay ranks() {
        return ranks;
    }

    /** Tags in front of chat lines (a Horizons town tag); register from onEnable, unregister in onDisable. */
    public ChatTags chatTags() {
        return chatTags;
    }

    /** Typed input through chat (searches, names) instead of anvils or signs. */
    public us.rapidskies.core.gui.ChatPrompts prompts() {
        return prompts;
    }

    /** AFK state, for anything that should pause while a player is away. */
    public AfkTracker afk() {
        return afk;
    }

    /** Tailwind: mode plugins multiply mode XP and Trade income by {@code tailwind().multiplier(player)}. */
    public TailwindService tailwind() {
        return tailwind;
    }

    /** Every activity reward in Bolts goes through this (AFK, shared-IP and daily-cap rules). */
    public EarnGuard earning() {
        return earning;
    }

    /** This server's gamemode currency, or null on servers without one (hub, build). */
    public ModeEconomy economy() {
        return economy;
    }

    public StaffMode staffMode() {
        return staffMode;
    }

    /** This server's spawn and the spawn worlds rapid-core looks after. */
    public WorldService worlds() {
        return worlds;
    }

    /** Who may change the protected spawn worlds right now ({@code /build}). */
    public BuildMode buildMode() {
        return buildMode;
    }

    /** The cosmetics engine: what players own and wear; {@code grant} for other plugins' rewards. */
    public CosmeticService cosmetics() {
        return cosmetics;
    }

    /** Network XP and levels; {@code award(player, event)} pays event XP from progression.yml. */
    public LevelService levels() {
        return levels;
    }

    /**
     * Quests and achievements; gamemode plugins count their objectives with
     * {@code quests().record(player, objective, amount)} (quests.yml lists them).
     */
    public QuestService quests() {
        return quests;
    }

    public BoltShop boltShop() {
        return boltShop;
    }

    /** Opens the phase 5 menus (wardrobe, shop, quests, pass) for other plugins' buttons. */
    public ProgressCommands progress() {
        return progress;
    }

    /**
     * Asks the proxy to send {@code player} to {@code target}: a server group players may
     * join ({@code smp}, {@code skyrealms}, {@code hub}) or one of its servers
     * ({@code smp-1}). They join the proxy's queue as if they'd typed {@code /play}. For the
     * hub selector and NPCs.
     */
    public void connect(Player player, String target) {
        Channels.ConnectRequest request = new Channels.ConnectRequest(player.getUniqueId().toString(), target);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                redis.publishJson(Channels.CONNECT, request);
            } catch (RuntimeException e) {
                getSLF4JLogger().warn("Couldn't ask the proxy to move {} to {}: {}", player.getName(), target,
                        e.getMessage());
                lang.send(player, "errors.connect-failed");
            }
        });
    }
}
