package us.rapidskies.proxy.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.registry.ServerStatus;
import us.rapidskies.common.registry.ServerStatus.State;

class JoinQueueTest {

    private static final QueueTarget SMP = QueueTarget.group("smp");
    private static final long NOW = 1_000_000;

    private final JoinQueue queue = new JoinQueue();

    private static ServerStatus smp(String id, int online, int max, long updatedAt) {
        return new ServerStatus(id, "smp", "", State.ONLINE, online, max, 0, updatedAt);
    }

    private List<UUID> drained(List<ServerStatus> servers, long now) {
        return queue.drain(servers, now, id -> true).stream().map(d -> d.entry().player()).toList();
    }

    @Test
    void higherPriorityGoesFirstThenArrivalOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID vip = UUID.randomUUID();
        queue.join(first, SMP, 0, NOW);
        queue.join(second, SMP, 0, NOW);
        JoinQueue.Position p = queue.join(vip, SMP, 2, NOW);
        assertEquals(1, p.place());
        assertEquals(3, p.of());
        assertEquals(3, queue.position(second).orElseThrow().place());

        assertEquals(List.of(vip, first), drained(List.of(smp("smp-1", 98, 100, NOW)), NOW));
        assertEquals(1, queue.position(second).orElseThrow().place());
    }

    @Test
    void recentSendsCountUntilAHeartbeatShowsThem() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        queue.join(a, SMP, 0, NOW);
        queue.join(b, SMP, 0, NOW);
        // One free slot: a goes.
        assertEquals(List.of(a), drained(List.of(smp("smp-1", 99, 100, NOW)), NOW));
        // Same (old) heartbeat a second later: a's send still holds the slot, so b waits.
        assertEquals(List.of(), drained(List.of(smp("smp-1", 99, 100, NOW)), NOW + 1000));
        // A heartbeat well after the send that still says 99: a didn't arrive, slot is free.
        long later = NOW + JoinQueue.CONNECT_GRACE_MILLIS + 1000;
        assertEquals(List.of(b), drained(List.of(smp("smp-1", 99, 100, later)), later));
    }

    @Test
    void spreadsAcrossServersLeastLoadedFirst() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        queue.join(a, SMP, 0, NOW);
        queue.join(b, SMP, 0, NOW);
        queue.join(c, SMP, 0, NOW);
        var sends = queue.drain(List.of(smp("smp-1", 50, 100, NOW), smp("smp-2", 49, 100, NOW)), NOW, id -> true);
        assertEquals(List.of("smp-2", "smp-1", "smp-2"), sends.stream().map(JoinQueue.Dispatch::serverId).toList());
    }

    @Test
    void skipsStaleStoppingUnknownAndOtherGroups() {
        UUID a = UUID.randomUUID();
        queue.join(a, SMP, 0, NOW);
        List<ServerStatus> none = List.of(
                smp("smp-stale", 0, 100, NOW - 60_000),
                new ServerStatus("smp-stop", "smp", "", State.STOPPING, 0, 100, 0, NOW),
                new ServerStatus("hub-1", "hub", "", State.ONLINE, 0, 100, 0, NOW));
        assertEquals(List.of(), drained(none, NOW));
        assertEquals(List.of(), queue.drain(List.of(smp("smp-9", 0, 100, NOW)), NOW, id -> false));
        assertTrue(JoinQueue.noServerUp(SMP, none, NOW));
        assertEquals(List.of(a), drained(List.of(smp("smp-1", 0, 100, NOW)), NOW));
    }

    @Test
    void serverTargetsOnlyGoToThatServer() {
        UUID a = UUID.randomUUID();
        queue.join(a, QueueTarget.server("smp-2"), 0, NOW);
        assertEquals(List.of(), drained(List.of(smp("smp-1", 0, 100, NOW)), NOW));
        assertEquals("smp-2", queue.drain(List.of(smp("smp-1", 0, 100, NOW), smp("smp-2", 90, 100, NOW)), NOW,
                id -> true).getFirst().serverId());
    }

    @Test
    void oneLineAtATimeAndRetryKeepsThePlace() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        queue.join(a, SMP, 0, NOW);
        queue.join(a, QueueTarget.group("skyrealms"), 0, NOW);
        assertEquals("skyrealms", queue.position(a).orElseThrow().target().name());
        assertTrue(queue.remove(a));
        assertFalse(queue.remove(a));

        queue.join(a, SMP, 0, NOW);
        queue.join(b, SMP, 0, NOW);
        JoinQueue.Entry sent = queue.drain(List.of(smp("smp-1", 99, 100, NOW)), NOW, id -> true).getFirst().entry();
        queue.retry(sent);
        assertEquals(1, queue.position(a).orElseThrow().place(), "a failed send goes back in front");
    }
}
