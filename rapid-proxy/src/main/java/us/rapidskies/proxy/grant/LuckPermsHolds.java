package us.rapidskies.proxy.grant;

import java.util.Optional;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import us.rapidskies.common.grant.Hold;

/**
 * Reads and writes the LuckPerms node a grant controls: a global (no context), true node
 * with the grant's key, temporary or permanent. Nodes set in a server context, or set to
 * false, are someone else's business and are left alone, except that granting replaces a
 * global false node with the same key (a grant means "yes").
 *
 * <p>Blocking: every call waits on LuckPerms' storage.
 */
final class LuckPermsHolds {

    private final LuckPerms luckPerms;

    LuckPermsHolds(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    /** Loads (or refreshes, if online) the player's LuckPerms user from storage. */
    User load(UUID player) {
        return luckPerms.getUserManager().loadUser(player).join();
    }

    /** What LuckPerms holds for {@code key} now. */
    static Optional<Hold> hold(User user, String key) {
        Hold best = null;
        for (Node n : user.getNodes()) {
            if (!matches(n, key) || !n.getValue() || n.hasExpired()) {
                continue;
            }
            if (!n.hasExpiry()) {
                return Optional.of(Hold.PERMANENT);
            }
            if (best == null || n.getExpiry().isAfter(best.until())) {
                best = Hold.until(n.getExpiry());
            }
        }
        return Optional.ofNullable(best);
    }

    /** Replaces the global node for {@code key} with {@code hold}, or removes it; then saves and syncs. */
    void set(User user, String key, Optional<Hold> hold) {
        user.data().clear(n -> matches(n, key));
        hold.ifPresent(h -> {
            NodeBuilder<?, ?> node = Node.builder(key).value(true);
            if (!h.permanent()) {
                node.expiry(h.until().getEpochSecond());
            }
            user.data().add(node.build());
        });
        luckPerms.getUserManager().saveUser(user).join();
        // Backends (and their chat prefixes, TAB groups...) pick it up over LuckPerms' Redis messaging.
        luckPerms.getMessagingService().ifPresent(m -> m.pushUserUpdate(user));
    }

    private static boolean matches(Node n, String key) {
        return n.getKey().equalsIgnoreCase(key) && n.getContexts().isEmpty();
    }
}
