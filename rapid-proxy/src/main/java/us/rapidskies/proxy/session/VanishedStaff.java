package us.rapidskies.proxy.session;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;

/**
 * Which staff are vanished (PLAN.md §9), mirrored from Redis: rapid-core's staff mode
 * keeps {@link Channels#VANISHED_KEY} and announces changes on {@link Channels#VANISH}.
 * The proxy's social features ask {@link #hiddenFrom} so friend notices, friend lists and
 * {@code /msg} never give a vanished staff member away.
 */
public final class VanishedStaff {

    /** Staff with this permission see vanished staff everywhere (same node as rapid-core). */
    public static final String SEE = "rapid.staff.vanish.see";

    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    /** Loads the current set (blocking: call off the event threads) and follows changes. */
    public VanishedStaff(RedisBus redis, Logger logger) {
        redis.subscribeJson(Channels.VANISH, Channels.VanishChanged.class, change -> {
            if (change.vanished()) {
                vanished.add(change.player());
            } else {
                vanished.remove(change.player());
            }
        });
        try {
            for (String id : redis.members(Channels.VANISHED_KEY)) {
                vanished.add(UUID.fromString(id));
            }
        } catch (RuntimeException e) {
            logger.warn("Couldn't load vanished staff: {}", e.getMessage());
        }
    }

    public boolean vanished(UUID player) {
        return vanished.contains(player);
    }

    /** Whether {@code viewer} should act as if {@code target} were offline. */
    public boolean hiddenFrom(UUID target, CommandSource viewer) {
        if (!vanished.contains(target)) {
            return false;
        }
        if (viewer instanceof Player p && p.getUniqueId().equals(target)) {
            return false;
        }
        return !viewer.hasPermission(SEE);
    }
}
