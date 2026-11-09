package us.rapidskies.common.registry;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;

/** Picks where to send a player. Pure logic, so it's unit-tested without Redis. */
public final class ServerSelector {

    private ServerSelector() {
    }

    /**
     * The least-loaded server of {@code group} that has a fresh heartbeat, accepts players
     * and passes {@code usable} (e.g. "the proxy knows this server" and "isn't the one the
     * player was just kicked from"). Ties go to the lowest id, so players fill hub-1 before
     * hub-2 when both are empty.
     */
    public static Optional<ServerStatus> leastLoaded(Collection<ServerStatus> servers, String group, long now,
                                                     Predicate<ServerStatus> usable) {
        return servers.stream()
                .filter(s -> s.group().equals(group))
                .filter(s -> s.isFresh(now, ServerRegistry.STALE_MILLIS))
                .filter(ServerStatus::acceptsPlayers)
                .filter(usable)
                .min(Comparator.comparingInt(ServerStatus::online).thenComparing(ServerStatus::id));
    }
}
