package us.rapidskies.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;

/**
 * Finds a player named in a staff or console command: a UUID (what Tebex sends), someone
 * online, or anyone who has joined before. Blocking (database): call from command threads
 * or async tasks.
 */
public final class PlayerLookup {

    private final ProxyServer proxy;
    private final ProfileRepository profiles;

    public PlayerLookup(ProxyServer proxy, ProfileRepository profiles) {
        this.proxy = proxy;
        this.profiles = profiles;
    }

    public record Found(UUID uuid, String name) {
    }

    public Optional<Found> find(String input) throws SQLException {
        try {
            UUID uuid = UUID.fromString(input);
            String name = proxy.getPlayer(uuid).map(Player::getUsername)
                    .or(() -> storedName(uuid)).orElse(input);
            return Optional.of(new Found(uuid, name));
        } catch (IllegalArgumentException notUuid) {
            Optional<Player> online = proxy.getPlayer(input);
            if (online.isPresent()) {
                return Optional.of(new Found(online.get().getUniqueId(), online.get().getUsername()));
            }
            return profiles.findByName(input).map(p -> new Found(p.uuid(), p.name()));
        }
    }

    private Optional<String> storedName(UUID uuid) {
        try {
            return profiles.find(uuid).map(PlayerProfile::name);
        } catch (SQLException e) {
            return Optional.empty();
        }
    }
}
