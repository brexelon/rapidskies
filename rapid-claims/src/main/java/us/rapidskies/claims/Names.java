package us.rapidskies.claims;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import us.rapidskies.claims.data.ClaimRepository;
import us.rapidskies.claims.model.Access;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;

/**
 * Player names for messages and menus. Land owners and trusted players are mostly offline,
 * so names are loaded once for everyone the index mentions and kept current as players join.
 * Lookups by name ({@code /claim trust Steve}) go to rapid-core's network-wide
 * {@code players} table, so a player who has never been on Horizons can still be trusted.
 */
public final class Names implements Listener {

    private final ClaimRepository repo;
    private final ProfileRepository profiles;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public Names(ClaimRepository repo, ProfileRepository profiles) {
        this.repo = repo;
        this.profiles = profiles;
    }

    /** Blocking: loads the names of {@code players}. */
    public void preload(Collection<UUID> players) throws SQLException {
        Set<UUID> ids = new HashSet<>(players);
        ids.remove(Access.EVERYONE);
        names.putAll(repo.names(ids));
    }

    /** The last known name, or a short form of the uuid if it's unknown. */
    public String name(UUID player) {
        if (Access.EVERYONE.equals(player)) {
            return "*";
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            return online.getName();
        }
        String n = names.get(player);
        return n != null ? n : player.toString().substring(0, 8);
    }

    public void remember(UUID player, String name) {
        names.put(player, name);
    }

    /**
     * Blocking: a player by name, online here first, then anyone who has ever joined the
     * network. Empty if nobody by that name has played.
     */
    public Optional<Known> find(String name) throws SQLException {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new Known(online.getUniqueId(), online.getName()));
        }
        Optional<PlayerProfile> p = profiles.findByName(name);
        p.ifPresent(profile -> names.put(profile.uuid(), profile.name()));
        return p.map(profile -> new Known(profile.uuid(), profile.name()));
    }

    public record Known(UUID uuid, String name) {
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        names.put(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}
