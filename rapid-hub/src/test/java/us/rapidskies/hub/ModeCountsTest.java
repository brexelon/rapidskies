package us.rapidskies.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.registry.ServerStatus;
import us.rapidskies.common.registry.ServerStatus.State;

class ModeCountsTest {

    private static final long NOW = 1_000_000;

    private static ServerStatus server(String id, String group, State state, int online, long updatedAt) {
        return new ServerStatus(id, group, "", state, online, 100, 0, updatedAt);
    }

    @Test
    void sumsLiveServersPerGroup() {
        Map<String, ModeCounts.Status> counts = ModeCounts.summarize(List.of(
                server("smp-1", "smp", State.ONLINE, 12, NOW),
                server("smp-2", "smp", State.ONLINE, 3, NOW - 1000),
                server("skyrealms-1", "skyrealms", State.ONLINE, 7, NOW)), NOW);
        assertEquals(new ModeCounts.Status(15, true), counts.get("smp"));
        assertEquals(new ModeCounts.Status(7, true), counts.get("skyrealms"));
    }

    @Test
    void staleStartingAndStoppingServersDontCount() {
        Map<String, ModeCounts.Status> counts = ModeCounts.summarize(List.of(
                server("smp-1", "smp", State.ONLINE, 12, NOW - 60_000),
                server("smp-2", "smp", State.STARTING, 0, NOW),
                server("smp-3", "smp", State.STOPPING, 4, NOW)), NOW);
        assertEquals(Map.of(), counts);
    }

    @Test
    void vanishedStaffArentCounted() {
        Map<String, ModeCounts.Status> counts = ModeCounts.summarize(List.of(
                new ServerStatus("skyrealms-1", "skyrealms", "", State.ONLINE, 3, 2, 100, 0, NOW),
                new ServerStatus("skyrealms-2", "skyrealms", "", State.ONLINE, 1, 0, 100, 0, NOW)), NOW);
        assertEquals(new ModeCounts.Status(2, true), counts.get("skyrealms"));
    }

    @Test
    void aWhitelistedModeCountsPlayersButIsClosed() {
        Map<String, ModeCounts.Status> counts = ModeCounts.summarize(List.of(
                server("skyrealms-1", "skyrealms", State.RESTRICTED, 2, NOW)), NOW);
        assertEquals(new ModeCounts.Status(2, false), counts.get("skyrealms"));
    }
}
