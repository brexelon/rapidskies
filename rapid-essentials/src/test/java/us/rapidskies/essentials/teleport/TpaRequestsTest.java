package us.rapidskies.essentials.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import us.rapidskies.essentials.teleport.TpaRequests.Direction;
import us.rapidskies.essentials.teleport.TpaRequests.Request;

class TpaRequestsTest {

    private static final long TTL = 60_000;
    private final UUID alex = UUID.randomUUID();
    private final UUID steve = UUID.randomUUID();
    private final UUID notch = UUID.randomUUID();
    private final TpaRequests requests = new TpaRequests();

    @Test
    void moverAndAnchorFollowTheDirection() {
        Request there = new Request(alex, steve, Direction.THERE, 0);
        assertEquals(alex, there.mover());
        assertEquals(steve, there.anchor());
        Request here = new Request(alex, steve, Direction.HERE, 0);
        assertEquals(steve, here.mover());
        assertEquals(alex, here.anchor());
    }

    @Test
    void acceptingWithoutANameTakesTheNewest() {
        requests.send(alex, steve, Direction.THERE, 0, TTL);
        requests.send(notch, steve, Direction.HERE, 10, TTL);
        assertEquals(notch, requests.take(steve, null, 20).orElseThrow().from());
        assertEquals(alex, requests.take(steve, null, 20).orElseThrow().from());
        assertTrue(requests.take(steve, null, 20).isEmpty());
    }

    @Test
    void acceptingByName() {
        requests.send(alex, steve, Direction.THERE, 0, TTL);
        requests.send(notch, steve, Direction.THERE, 10, TTL);
        assertEquals(alex, requests.take(steve, alex, 20).orElseThrow().from());
        assertEquals(List.of(notch), requests.pending(steve, 20).stream().map(Request::from).toList());
    }

    @Test
    void aNewRequestReplacesTheSendersLastOne() {
        requests.send(alex, steve, Direction.THERE, 0, TTL);
        Optional<Request> replaced = requests.send(alex, notch, Direction.THERE, 10, TTL);
        assertEquals(steve, replaced.orElseThrow().to());
        assertTrue(requests.pending(steve, 20).isEmpty());
        assertEquals(alex, requests.take(notch, null, 20).orElseThrow().from());
    }

    @Test
    void requestsExpire() {
        requests.send(alex, steve, Direction.THERE, 0, TTL);
        assertTrue(requests.take(steve, null, TTL).isEmpty());
        assertTrue(requests.cancel(alex, TTL).isEmpty());
    }

    @Test
    void cancelWithdrawsIt() {
        requests.send(alex, steve, Direction.THERE, 0, TTL);
        assertEquals(steve, requests.cancel(alex, 10).orElseThrow().to());
        assertTrue(requests.take(steve, null, 10).isEmpty());
    }

    @Test
    void leavingDropsBothDirections() {
        requests.send(alex, steve, Direction.THERE, 0, TTL);
        requests.send(steve, notch, Direction.THERE, 0, TTL);
        requests.forget(steve);
        assertTrue(requests.cancel(alex, 10).isEmpty(), "the request to the leaver is gone");
        assertTrue(requests.pending(notch, 10).isEmpty(), "the leaver's own request is gone");
    }
}
