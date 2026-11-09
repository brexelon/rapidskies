package us.rapidskies.proxy.rank;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.rank.StaffRole;

/**
 * Paid rank and team role of players as the proxy sees them, from LuckPerms group
 * membership (not permission checks, which a {@code *} wildcard would make true for every
 * group; see {@link Rank#resolve}).
 */
public final class ProxyRanks {

    private final LuckPerms luckPerms;
    private final ProxyServer proxy;

    public ProxyRanks(LuckPerms luckPerms, ProxyServer proxy) {
        this.luckPerms = luckPerms;
        this.proxy = proxy;
    }

    /** What ranks decide on the proxy. */
    public record Standing(Rank rank, Optional<StaffRole> staff) {
        public static final Standing NONE = new Standing(Rank.DEFAULT, Optional.empty());
    }

    /** An online player's standing, from LuckPerms' in-memory user. */
    public Standing of(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return Standing.NONE;
        }
        return standing(user, luckPerms.getPlayerAdapter(Player.class).getQueryOptions(player));
    }

    public Rank rank(Player player) {
        return of(player).rank();
    }

    /** Anyone's standing; offline players are loaded from the LuckPerms database. */
    public CompletableFuture<Standing> of(UUID uuid) {
        Optional<Player> online = proxy.getPlayer(uuid);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(of(online.get()));
        }
        return luckPerms.getUserManager().loadUser(uuid)
                .thenApply(user -> standing(user, luckPerms.getContextManager().getStaticQueryOptions()));
    }

    private static Standing standing(User user, QueryOptions options) {
        Set<String> groups = user.getInheritedGroups(options).stream()
                .map(Group::getName)
                .collect(Collectors.toUnmodifiableSet());
        return new Standing(Rank.resolve(groups::contains), StaffRole.resolve(groups::contains));
    }
}
