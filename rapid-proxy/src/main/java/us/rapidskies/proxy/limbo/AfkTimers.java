package us.rapidskies.proxy.limbo;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is AFK on which server, from the backends' reports (rapid-core's AFK tracker), and who
 * has been AFK long enough to go to limbo. Pure state with no Velocity types;
 * {@link LimboService} runs it. Thread-safe: reports arrive on the Redis thread.
 *
 * <p>A report only counts for the server the player is on, so one that arrives late from a
 * server they already left is ignored. Switching servers clears it ({@link #clear}): the
 * new server starts everyone as active.
 */
final class AfkTimers {

    /** {@code since}: last input. {@code notBefore}: not due again until then (snoozed). */
    private record Idle(String server, long since, long notBefore) {
    }

    private final Map<UUID, Idle> idle = new ConcurrentHashMap<>();

    /**
     * A backend says {@code player} became AFK on {@code server} (last input at
     * {@code idleSince}) or came back. {@code current} is the server they're on now.
     */
    void report(UUID player, String server, boolean afk, long idleSince, Optional<String> current, long now) {
        if (current.isEmpty() || !current.get().equals(server)) {
            return;
        }
        if (afk) {
            // A backend clock running ahead must not push the deadline out.
            idle.put(player, new Idle(server, Math.min(idleSince, now), 0));
        } else {
            idle.remove(player);
        }
    }

    void clear(UUID player) {
        idle.remove(player);
    }

    /** Players AFK for at least {@code afkMillis} and not snoozed, with the server they're AFK on. */
    Map<UUID, String> due(long now, long afkMillis) {
        Map<UUID, String> out = new HashMap<>();
        idle.forEach((player, i) -> {
            if (i.since() + afkMillis <= now && i.notBefore() <= now) {
                out.put(player, i.server());
            }
        });
        return out;
    }

    /** Leaves {@code player} out of {@link #due} until {@code until} (exempt for now, or moving them failed). */
    void snooze(UUID player, long until) {
        idle.computeIfPresent(player, (k, i) -> new Idle(i.server(), i.since(), until));
    }
}
