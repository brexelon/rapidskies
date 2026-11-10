package us.rapidskies.horizons;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.horizons.chapter.ChapterService;
import us.rapidskies.horizons.data.ChapterRepository;
import us.rapidskies.horizons.data.FrontierRepository;
import us.rapidskies.horizons.data.HorizonsRepository;
import us.rapidskies.horizons.event.Announcer;
import us.rapidskies.horizons.event.EventRewards;
import us.rapidskies.horizons.frontier.DimensionRouter;
import us.rapidskies.horizons.frontier.DragonFight;
import us.rapidskies.horizons.frontier.FrontierService;
import us.rapidskies.horizons.frontier.Modifier;
import us.rapidskies.horizons.frontier.ModifierEffects;
import us.rapidskies.horizons.frontier.WildlandsListener;
import us.rapidskies.horizons.frontier.terrain.FrontierGenerator;
import us.rapidskies.horizons.land.LandHook;
import us.rapidskies.horizons.npc.SpawnNpcs;
import us.rapidskies.horizons.pvp.CombatLog;
import us.rapidskies.horizons.pvp.PvpService;
import us.rapidskies.horizons.skyborne.SkyborneService;
import us.rapidskies.horizons.trade.TradeService;
import us.rapidskies.horizons.travel.PortalArch;
import us.rapidskies.horizons.travel.RtpService;
import us.rapidskies.horizons.world.BukkitWorldHost;
import us.rapidskies.horizons.world.MultiverseWorldHost;
import us.rapidskies.horizons.world.WorldHost;

/**
 * rapid-horizons, the Horizons SMP (PLAN.md §5.2, phase 3b): the way from the spawn into the
 * Heartland ({@code /rtp} and the portal arch at the spawn), the spawn's NPCs (Citizens,
 * optional), opt-in PvP ({@code /pvp}) with combat-log punishment ({@link CombatLog}), Trades
 * and their side-grade perks ({@code /trades}), the monthly Frontier rotation with its
 * modifiers, Wildlands and rotating Nether and End ({@code /frontier}, {@link FrontierService}),
 * the community dragon fight, Skyborne fortresses ({@code /skyborne}) and Chapters
 * ({@code /chapter}).
 *
 * <p>Land claims (rapid-claims) are optional: without them nothing is claimed, PvP follows
 * the personal toggles everywhere, and Trades give no claim chunks.
 *
 * <p>Multiverse-Core is optional too: with it, the Heartland and every Frontier world are
 * Multiverse worlds, and each modifier's terrain generator is registered with it as
 * {@code rapid-horizons:<modifier>} ({@link #getDefaultWorldGenerator}); without it the
 * Frontiers are created through Bukkit directly ({@link WorldHost}).
 *
 * <p>{@code /back}, {@code /spawn}, {@code /home} and {@code /tpa} are rapid-essentials'.
 * Horizons refuses all of them while a player is combat-tagged, and a walk through the spawn
 * portal counts as leaving from the spawn for {@code /back}.
 */
public final class RapidHorizons extends JavaPlugin {

    private HorizonsSettings settings;
    private Lang lang;
    private LandHook land = LandHook.none();
    private boolean claims;
    private RtpService rtp;
    private PortalArch arch;
    private SpawnNpcs npcs;
    private PvpService pvp;
    private TradeService trades;
    private FrontierService frontier;
    private ModifierEffects modifiers;
    private WildlandsListener wildlands;
    private SkyborneService skyborne;
    private ChapterService chapters;
    private WorldHost worldHost;

    @Override
    public void onEnable() {
        try {
            lang = loadLang();
            settings = loadSettings();
        } catch (IOException | IllegalArgumentException e) {
            getSLF4JLogger().error("rapid-horizons can't start (fix the config, then restart): {}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            core().database().migrate(getClassLoader(), "horizons");
        } catch (SQLException | RuntimeException e) {
            getSLF4JLogger().error("rapid-horizons can't migrate its tables; disabling it", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var pm = getServer().getPluginManager();
        if (pm.isPluginEnabled("rapid-claims")) {
            // Its own class, so rapid-claims' types are only loaded when it's installed.
            LandHook hook = us.rapidskies.horizons.land.ClaimsLandHook.create();
            if (hook != null) {
                land = hook;
                claims = true;
            } else {
                getSLF4JLogger().warn("rapid-claims is enabled but hasn't registered its ClaimService; "
                        + "running as if nothing were claimed");
            }
        }

        getSLF4JLogger().info("Horizons worlds are managed by {}", worldHost().describe());

        HorizonsRepository repository = new HorizonsRepository(core().database());
        Announcer announcer = new Announcer(this);
        EventRewards rewards = new EventRewards(this);
        rtp = new RtpService(this);
        arch = new PortalArch(this);
        pvp = new PvpService(this, repository);
        trades = new TradeService(this, repository);
        frontier = new FrontierService(this, new FrontierRepository(core().database()), announcer);
        modifiers = new ModifierEffects(this, frontier);
        wildlands = new WildlandsListener(this, frontier);
        skyborne = new SkyborneService(this, frontier, announcer, rewards);
        chapters = new ChapterService(this, new ChapterRepository(core().database()), frontier);
        pm.registerEvents(rtp, this);
        pm.registerEvents(arch, this);
        pm.registerEvents(pvp, this);
        pm.registerEvents(new CombatLog(this), this);
        pm.registerEvents(trades, this);
        pm.registerEvents(frontier, this);
        pm.registerEvents(new DimensionRouter(this, frontier), this);
        pm.registerEvents(modifiers, this);
        pm.registerEvents(wildlands, this);
        pm.registerEvents(new DragonFight(this, frontier, announcer, rewards), this);
        pm.registerEvents(skyborne, this);
        pm.registerEvents(chapters, this);
        pvp.start();
        trades.start();
        chapters.start();
        modifiers.start();
        // The Frontier's Wildlands force PvP and pay Trades more.
        pvp.setWildlands(frontier::inWildlands);
        trades.setIncomeModifier(frontier::tradeMultiplier);
        frontier.onRotate(row -> skyborne.onRotate());
        land.registerAllowance(this, trades::claimChunks);
        hookTeleports();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("rtp", "Go to a random spot in the Heartland", List.of("wild", "heartland"),
                    Commands.rtp(this));
            event.registrar().register("pvp", "Switch your PvP on or off", List.of(), Commands.pvp(this));
            event.registrar().register("trades", "Pick your Trades and see their levels", List.of("trade", "jobs"),
                    Commands.trades(this));
            event.registrar().register("horizons", "rapid-horizons admin: here, reload", List.of(),
                    Commands.admin(this));
            event.registrar().register("frontier", "This month's Frontier: what's open, and travel there",
                    List.of("fr"), Commands.frontier(this));
            event.registrar().register("skyborne", "When and where the next Skyborne fortress appears",
                    List.of("fortress"), Commands.skyborne(this));
            event.registrar().register("chapter", "The current Chapter's collection log",
                    List.of("chapters", "log"), Commands.chapter(this));
        });
        // After every plugin (Citizens, Chunky) is enabled; rapid-core has loaded the spawn world by now.
        getServer().getScheduler().runTask(this, () -> {
            build();
            frontier.start();
            skyborne.start();
        });
        getSLF4JLogger().info("rapid-horizons {} enabled ({})", getPluginMeta().getVersion(),
                claims ? "with rapid-claims" : "no claims");
    }

    @Override
    public void onDisable() {
        if (trades == null) {
            return;
        }
        stopNpcs();
        arch.clear();
        skyborne.stop();
        frontier.stop();
        modifiers.stop();
        wildlands.clear();
        land.unregisterAllowance(this);
        // rapid-core (and its database) disables after us: we depend on it.
        trades.stop();
        if (worldHost != null) {
            worldHost.close();
        }
    }

    // ------------------------------------------------------------------ worlds and generators

    /**
     * The world host: Multiverse-Core when it's enabled (softdepend, so it enabled first),
     * else Bukkit. Created once; the Frontier worlds are always loaded through it.
     */
    public WorldHost worldHost() {
        if (worldHost == null) {
            if (getServer().getPluginManager().isPluginEnabled("Multiverse-Core")) {
                // Its own class, so Multiverse's types are only loaded when it's installed.
                worldHost = MultiverseWorldHost.create(getSLF4JLogger(),
                        getServer().getPluginManager().getPlugin("Multiverse-Core").getPluginMeta().getVersion());
                if (worldHost == null) {
                    getSLF4JLogger().warn("Multiverse-Core is enabled but its API isn't; Frontier worlds go "
                            + "through Bukkit this time");
                }
            }
            if (worldHost == null) {
                worldHost = new BukkitWorldHost(getSLF4JLogger(), this::generator);
            }
        }
        return worldHost;
    }

    /** The terrain generator of a Frontier modifier, tuned by the current config. */
    public ChunkGenerator generator(Modifier modifier) {
        return FrontierGenerator.create(modifier, settings.frontier());
    }

    /**
     * Bukkit's (and so Multiverse's) way to a plugin's generator: {@code rapid-horizons:<modifier>}
     * in a world's generator setting ({@code rapid-horizons:deep_dark}). Multiverse asks with an
     * empty id when it probes plugins for generators; that gets null rather than an error.
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Modifier modifier = FrontierGenerator.modifierOf(id).orElseThrow(() -> new IllegalArgumentException(
                "rapid-horizons has no generator '" + id + "'; use one of " + java.util.Arrays.stream(Modifier.values())
                        .map(m -> FrontierGenerator.PLUGIN + ":" + m.id()).toList()));
        if (settings == null) {
            throw new IllegalStateException("rapid-horizons isn't configured, so it can't generate " + worldName);
        }
        return generator(modifier);
    }

    /**
     * rapid-essentials' teleports: no {@code /spawn}, {@code /home}, {@code /tpa} or
     * {@code /back} out of a fight, and {@code /back} after the portal returns to the spawn,
     * not into the portal blocks. rapid-essentials drops both when we disable.
     */
    private void hookTeleports() {
        RapidEssentials essentials = RapidEssentials.get();
        essentials.teleports().addGuard(this, player ->
                pvp.inCombat(player.getUniqueId()) ? lang.get("pvp.no-teleport") : null);
        essentials.back().addOriginFilter(this, from -> inPortal(from) ? core().worlds().spawn() : from);
    }

    private boolean inPortal(Location at) {
        return settings.rtp().portal()
                .map(r -> r.contains(at.getWorld().getKey().asString(), at.getBlockX(), at.getBlockY(), at.getBlockZ()))
                .orElse(false);
    }

    private Lang loadLang() throws IOException {
        Path data = getDataPath();
        return Lang.load(getClassLoader(), Lang.DEFAULT_RESOURCE, data.resolve(Lang.DEFAULT_RESOURCE),
                getSLF4JLogger());
    }

    private HorizonsSettings loadSettings() throws IOException {
        return HorizonsSettings.from(ConfigView.loadOrCreate(getClassLoader(), "config.yml",
                getDataPath().resolve("config.yml")));
    }

    /** The things placed in the world from the config: the portal arch, its hologram and the NPCs. */
    private void build() {
        arch.build();
        if (getServer().getPluginManager().isPluginEnabled("Citizens")) {
            // Its own class, so Citizens' types are only loaded when it's installed.
            npcs = new us.rapidskies.horizons.npc.CitizensSpawnNpcs(this);
            npcs.start();
        } else if (settings.npcs().stream().anyMatch(n -> n.location().isPresent())) {
            getSLF4JLogger().warn("Citizens isn't installed, so there are no NPCs at the spawn");
        }
    }

    private void stopNpcs() {
        if (npcs != null) {
            npcs.stop();
            npcs = null;
        }
    }

    /**
     * Re-reads the config and messages and rebuilds the NPCs and the portal arch from them.
     * Returns false (changing nothing) if the new config is invalid.
     */
    public boolean reload() {
        try {
            HorizonsSettings next = loadSettings();
            Lang nextLang = loadLang();
            stopNpcs();
            settings = next;
            lang = nextLang;
            build();
            if (frontier != null) {
                frontier.fillMissingChapters(); // a Chapter added to frontier.chapters for the open Frontier
            }
            return true;
        } catch (IOException | IllegalArgumentException e) {
            getSLF4JLogger().error("rapid-horizons reload failed, keeping the running config: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ parts, for each other

    public RapidCore core() {
        return RapidCore.get();
    }

    public HorizonsSettings settings() {
        return settings;
    }

    public Lang lang() {
        return lang;
    }

    /** Land claims, or a stand-in that says nothing is claimed. */
    public LandHook land() {
        return land;
    }

    public RtpService rtp() {
        return rtp;
    }

    /** Personal PvP; the Frontier's Wildlands force it on ({@code setWildlands}). */
    public PvpService pvp() {
        return pvp;
    }

    public TradeService trades() {
        return trades;
    }

    /** The Frontier rotation: the open Frontier, its worlds, the Wildlands. */
    public FrontierService frontier() {
        return frontier;
    }

    public SkyborneService skyborne() {
        return skyborne;
    }

    public ChapterService chapters() {
        return chapters;
    }
}
