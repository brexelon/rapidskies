package us.rapidskies.common.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.registry.ServerStatus.State;

class ServerSelectorTest {

    private static final long NOW = 1_000_000;

    private static ServerStatus hub(String id, State state, int online, long age) {
        return new ServerStatus(id, "hub", "", state, online, 100, 0, NOW - age);
    }

    @Test
    void picksLeastLoadedThenLowestId() {
        var servers = List.of(hub("hub-2", State.ONLINE, 5, 0), hub("hub-1", State.ONLINE, 5, 0),
                hub("hub-3", State.ONLINE, 9, 0));
        assertEquals("hub-1", ServerSelector.leastLoaded(servers, "hub", NOW, s -> true).orElseThrow().id());
    }

    @Test
    void skipsStaleFullStartingAndExcluded() {
        var servers = List.of(
                hub("hub-1", State.ONLINE, 0, ServerRegistry.STALE_MILLIS + 1), // stale
                hub("hub-2", State.STARTING, 0, 0),
                hub("hub-3", State.ONLINE, 100, 0), // full
                hub("hub-4", State.ONLINE, 50, 0),
                hub("hub-5", State.ONLINE, 1, 0),
                new ServerStatus("smp-1", "smp", "", State.ONLINE, 0, 100, 0, NOW));
        assertEquals("hub-4", ServerSelector.leastLoaded(servers, "hub", NOW, s -> !s.id().equals("hub-5"))
                .orElseThrow().id());
    }

    @Test
    void emptyWhenNothingFits() {
        assertTrue(ServerSelector.leastLoaded(List.of(hub("hub-1", State.STOPPING, 0, 0)), "hub", NOW, s -> true)
                .isEmpty());
    }

    @Test
    void heartbeatHashRoundTrips() {
        var s = new ServerStatus("smp-1", "smp", "172.18.0.1:25570", State.RESTRICTED, 12, 11, 200, 5, 6);
        assertEquals(s, ServerStatus.fromHash("smp-1", s.toHash()));
        assertEquals(null, ServerStatus.fromHash("x", java.util.Map.of("state", "BOGUS")));
    }

    @Test
    void anOlderHeartbeatWithoutAVisibleCountShowsEveryone() {
        var hash = new java.util.HashMap<>(new ServerStatus("smp-1", "smp", "", State.ONLINE, 12, 200, 5, 6).toHash());
        hash.remove("visible");
        assertEquals(12, ServerStatus.fromHash("smp-1", hash).visible());
    }
}
