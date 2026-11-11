package us.rapidskies.core.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.progress.NetworkLevels;
import us.rapidskies.common.progress.ProgressRepository;
import us.rapidskies.common.progress.ProgressRepository.Gain;
import us.rapidskies.common.progress.ProgressRepository.LevelReward;
import us.rapidskies.common.progress.ProgressRepository.SeasonStanding;
import us.rapidskies.core.cosmetic.CosmeticService;
import us.rapidskies.core.economy.BoltsCache;
import us.rapidskies.core.profile.ProfileService;
import us.rapidskies.core.reward.EarnGuard;

/**
 * Network XP and levels, and the Season Pass's XP (PLAN.md §7). Every XP gain goes through
 * {@link #add}: one transaction raises the XP, pays each level's Bolts once, grants level and
 * pass-tier cosmetics, and adds the same XP to the running season. Online players' XP and
 * season standing are cached for menus, chat cards and placeholders.
 *
 * <p>Gamemode plugins pay event XP with {@link #award(Player, String)}; the amounts are in
 * {@code progression.yml} so they're tuned in one place.
 */
public final class LevelService implements Listener {

    private final Plugin plugin;
    private final Database db;
    private final ProgressRepository repository;
    private final ProgressConfig config;
    private final ProfileService profiles;
    private final CosmeticService cosmetics;
    private final EarnGuard earning;
    private final BoltsCache boltsCache;
    private final Lang lang;
    private final Map<UUID, Long> xp = new ConcurrentHashMap<>();
    private final Map<UUID, SeasonStanding> seasons = new ConcurrentHashMap<>();

    public LevelService(Plugin plugin, Database db, ProgressRepository repository, ProgressConfig config,
                        ProfileService profiles, CosmeticService cosmetics, EarnGuard earning, BoltsCache boltsCache,
                        Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.repository = repository;
        this.config = config;
        this.profiles = profiles;
        this.cosmetics = cosmetics;
        this.earning = earning;
        this.boltsCache = boltsCache;
        this.lang = lang;
    }

    public ProgressConfig config() {
        return config;
    }

    public NetworkLevels levels() {
        return config.levels();
    }

    public ProgressRepository repository() {
        return repository;
    }

    // ------------------------------------------------------------------ reading (any thread)

    public long xp(Player player) {
        return xp.getOrDefault(player.getUniqueId(), 0L);
    }

    public int level(Player player) {
        return config.levels().levelOf(xp(player));
    }

    public NetworkLevels.Progress progress(Player player) {
        return config.levels().progress(xp(player));
    }

    /** The season running now. */
    public Optional<ProgressConfig.Season> season() {
        return config.season(Instant.now());
    }

    /** The player's standing in the running season (zero until loaded). */
    public SeasonStanding standing(Player player) {
        return seasons.getOrDefault(player.getUniqueId(), SeasonStanding.NONE);
    }

    // ------------------------------------------------------------------ gaining XP

    /** Event XP from {@code progression.yml} ({@code xp.events.<event>}); nothing for unknown events. */
    public void award(Player player, String event) {
        Long amount = config.eventXp().get(event);
        if (amount == null) {
            plugin.getSLF4JLogger().warn("No XP configured for event '{}' (progression.yml xp.events)", event);
            return;
        }
        add(player.getUniqueId(), amount, event);
    }

    /** Playtime XP for an interval of active play (called by the playtime counter). */
    public void playtimeInterval(UUID player, long interval) {
        if (config.playtimeXp() > 0 && interval <= config.playtimeDailyIntervals()) {
            add(player, config.playtimeXp(), "playtime");
        }
    }

    /**
     * Adds XP and tells the player (if online here) about level-ups, tiers and unlocks.
     * Completes with what changed, or empty if the player has no profile.
     */
    public CompletableFuture<Optional<Gain>> add(UUID player, long amount, String reason) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Player online = Bukkit.getPlayer(player);
        boolean payBolts = online == null || !earning.sharesIp(online);
        Optional<ProgressConfig.Season> season = season();
        ProgressRepository.Rules rules = new ProgressRepository.Rules() {
            @Override
            public NetworkLevels levels() {
                return config.levels();
            }

            @Override
            public LevelReward reward(int level) {
                return new LevelReward(config.boltsPerLevel(), config.levelCosmetics(level));
            }

            @Override
            public boolean payBolts() {
                return payBolts;
            }

            @Override
            public Optional<ProgressRepository.Season> season() {
                return season.map(ProgressConfig.Season::forRepository);
            }
        };
        return db.async(() -> repository.add(player, amount, rules)).whenComplete((gain, e) -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("Couldn't add {} XP ({}) to {}: {}", amount, reason, player,
                        e.getMessage());
                return;
            }
            gain.ifPresent(g -> Bukkit.getScheduler().runTask(plugin, () -> applied(player, g)));
        });
    }

    private void applied(UUID id, Gain g) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            if (!g.newCosmetics().isEmpty()) {
                cosmetics.changed(id, g.newCosmetics());
            }
            return;
        }
        xp.put(id, g.xpAfter());
        if (g.season() != null) {
            seasons.put(id, new SeasonStanding(g.seasonXp(), g.premium()));
        }
        if (g.leveledUp()) {
            lang.send(player, "level.up", Placeholder.unparsed("level", Integer.toString(g.levelAfter())));
            if (g.boltsPaid() > 0) {
                lang.send(player, "level.bolts", Placeholder.unparsed("amount", Long.toString(g.boltsPaid())));
                boltsCache.refresh(id);
            }
            player.showTitle(Title.title(lang.get("level.title"),
                    lang.get("level.subtitle", Placeholder.unparsed("level", Integer.toString(g.levelAfter()))),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
        if (g.tieredUp()) {
            season().ifPresent(s -> lang.send(player, "pass.tier-up",
                    Placeholder.unparsed("tier", Integer.toString(g.tierAfter())),
                    Placeholder.unparsed("season", s.name())));
        }
        if (!g.newCosmetics().isEmpty()) {
            cosmetics.reload(id, g.newCosmetics());
        }
    }

    /** After a premium purchase (or anything else that changed the standing elsewhere). */
    public void reloadSeason(UUID player) {
        season().ifPresent(s -> db.async(() -> repository.season(player, s.id())).thenAccept(st -> {
            if (Bukkit.getPlayer(player) != null) {
                seasons.put(player, st);
            }
        }));
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        profiles.get(id).map(PlayerProfile::networkXp).ifPresent(x -> xp.put(id, x));
        // The cached profile is from the login; re-read in case XP was added since.
        db.async(() -> repository.xp(id)).thenAccept(x -> {
            if (Bukkit.getPlayer(id) != null) {
                xp.put(id, x);
            }
        });
        reloadSeason(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        xp.remove(event.getPlayer().getUniqueId());
        seasons.remove(event.getPlayer().getUniqueId());
    }

    /** The cosmetics of levels from {@code level} up to the next reward level, for /level. */
    public List<String> nextRewards(int level) {
        return config.nextRewardLevel(level).map(config::levelCosmetics).orElse(List.of());
    }
}
