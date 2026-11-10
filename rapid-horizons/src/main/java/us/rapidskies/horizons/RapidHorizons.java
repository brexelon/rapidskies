package us.rapidskies.horizons;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.horizons.data.HorizonsRepository;
import us.rapidskies.horizons.land.LandHook;
import us.rapidskies.horizons.npc.SpawnNpcs;
import us.rapidskies.horizons.pvp.CombatLog;
import us.rapidskies.horizons.pvp.PvpService;
import us.rapidskies.horizons.trade.TradeService;
import us.rapidskies.horizons.travel.PortalArch;
import us.rapidskies.horizons.travel.RtpService;

/**
 * rapid-horizons, the Horizons SMP (PLAN.md §5.2, phase 3b). This first slice: the way from
 * the spawn into the Heartland ({@code /rtp} and the portal arch at the spawn), the spawn's NPCs (Citizens, optional), opt-in PvP ({@code /pvp}) with
 * combat-log punishment ({@link CombatLog}) and Trades ({@code /trades}). Frontier rotation and modifiers, Wildlands, Skyborne events and Chapters
 * come next.
 *
 * <p>Land claims (rapid-claims) are optional: without them nothing is claimed, PvP follows
 * the personal toggles everywhere, and Trades give no claim chunks.
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

        HorizonsRepository repository = new HorizonsRepository(core().database());
        rtp = new RtpService(this);
        arch = new PortalArch(this);
        pvp = new PvpService(this, repository);
        trades = new TradeService(this, repository);
        pm.registerEvents(rtp, this);
        pm.registerEvents(arch, this);
        pm.registerEvents(pvp, this);
        pm.registerEvents(new CombatLog(this), this);
        pm.registerEvents(trades, this);
        pvp.start();
        trades.start();
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
        });
        // After every plugin (Citizens) is enabled; rapid-core has loaded the spawn world by now.
        getServer().getScheduler().runTask(this, this::build);
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
        land.unregisterAllowance(this);
        // rapid-core (and its database) disables after us: we depend on it.
        trades.stop();
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

    /** Personal PvP; {@code pvp().setWildlands(...)} is the hook for the Frontier rotation. */
    public PvpService pvp() {
        return pvp;
    }

    public TradeService trades() {
        return trades;
    }
}
