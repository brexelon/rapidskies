package us.rapidskies.core.profile;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.core.CoreSettings;
import us.rapidskies.core.RapidCore;

/**
 * Loads each player's profile before they join and keeps it cached while they're online.
 * Other servers announce profile changes on Redis ({@link Channels#PROFILE}) and this
 * server reloads its copy.
 */
public final class ProfileService implements Listener {

    private final RapidCore plugin;
    private final ProfileRepository repository;
    private final RedisBus redis;
    private final CoreSettings settings;
    private final Lang lang;
    private final Map<UUID, PlayerProfile> online = new ConcurrentHashMap<>();

    public ProfileService(RapidCore plugin, ProfileRepository repository, RedisBus redis, CoreSettings settings,
                          Lang lang) {
        this.plugin = plugin;
        this.repository = repository;
        this.redis = redis;
        this.settings = settings;
        this.lang = lang;
        redis.subscribeJson(Channels.PROFILE, Channels.ProfileChanged.class, this::onRemoteChange);
    }

    /** The cached profile of an online player. */
    public Optional<PlayerProfile> get(UUID player) {
        return Optional.ofNullable(online.get(player));
    }

    /** Replaces the cached copy and tells every other server to reload theirs. */
    public void updated(PlayerProfile profile) {
        online.computeIfPresent(profile.uuid(), (k, old) -> profile);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                redis.publishJson(Channels.PROFILE, Channels.ProfileChanged.of(profile.uuid(), settings.serverId()));
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't announce profile change for {}: {}", profile.uuid(), e.getMessage());
            }
        });
    }

    // Runs on an async login thread, so blocking on the database is fine here. HIGHEST so a
    // login another plugin already denied doesn't touch the database.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            online.put(event.getUniqueId(),
                    repository.recordLogin(event.getUniqueId(), event.getName(), settings.serverId()));
        } catch (SQLException | RuntimeException e) {
            plugin.getSLF4JLogger().error("Couldn't load the profile of {} ({})", event.getName(), event.getUniqueId(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, lang.get("login.profile-failed"));
        }
    }

    /**
     * Fires for every closed connection that passed pre-login, including logins refused
     * later (whitelist, full server), which never get a quit event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        online.remove(event.getPlayerUniqueId());
    }

    // Called on the Redis subscriber thread: hand the database read off it.
    private void onRemoteChange(Channels.ProfileChanged change) {
        if (settings.serverId().equals(change.origin()) || !online.containsKey(change.player())) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.find(change.player()).ifPresent(p -> online.computeIfPresent(p.uuid(), (k, old) -> p));
            } catch (SQLException e) {
                plugin.getSLF4JLogger().warn("Couldn't reload profile {}: {}", change.uuid(), e.getMessage());
            }
        });
    }
}
