package us.rapidskies.proxy.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import us.rapidskies.proxy.session.SharedIps.Session;

class SharedIpsTest {

    private static final InetAddress HOME = address("203.0.113.7");
    private static final InetAddress OTHER = address("198.51.100.20");

    @Test
    void theFirstAccountOnAnAddressKeepsEarning() {
        Map<String, Session> online = Map.of(
                "main", new Session(HOME, 1),
                "alt1", new Session(HOME, 5),
                "alt2", new Session(HOME, 3),
                "friend", new Session(OTHER, 2));
        assertEquals(Set.of("alt1", "alt2"), SharedIps.later(online));
    }

    @Test
    void whenTheFirstLeavesTheNextOldestTakesOver() {
        Map<String, Session> online = Map.of(
                "alt1", new Session(HOME, 5),
                "alt2", new Session(HOME, 3));
        assertEquals(Set.of("alt1"), SharedIps.later(online));
    }

    @Test
    void nobodyIsFlaggedAlone() {
        assertEquals(Set.of(), SharedIps.later(Map.of("a", new Session(HOME, 1), "b", new Session(OTHER, 2))));
        assertEquals(Set.of(), SharedIps.later(Map.<String, Session>of()));
    }

    private static InetAddress address(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
