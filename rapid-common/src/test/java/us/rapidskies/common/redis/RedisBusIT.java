package us.rapidskies.common.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.common.registry.ServerStatus;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class RedisBusIT {

    private static RedisBus a;
    private static RedisBus b;

    @BeforeAll
    static void connect() {
        a = RedisBus.connect(TestServices.redis(), "test-a", TestServices.LOG);
        b = RedisBus.connect(TestServices.redis(), "test-b", TestServices.LOG);
    }

    @AfterAll
    static void close() {
        a.close();
        b.close();
    }

    @Test
    void jsonMessagesReachOtherClients() throws Exception {
        BlockingQueue<Channels.BoltsChanged> got = new ArrayBlockingQueue<>(4);
        b.subscribeJson(Channels.BOLTS, Channels.BoltsChanged.class, got::add);
        Thread.sleep(300); // let the pattern subscription settle
        a.publishJson(Channels.BOLTS, new Channels.BoltsChanged("00000000-0000-0000-0000-000000000001", 5, 10, "x"));
        Channels.BoltsChanged m = got.poll(5, TimeUnit.SECONDS);
        assertEquals(10, m.balance());
    }

    @Test
    void subscriptionOutlivesTheSocketTimeout() throws Exception {
        // The client's socket timeout is 5s; an idle subscription must not drop at that point.
        BlockingQueue<String> got = new ArrayBlockingQueue<>(4);
        b.subscribe("idle-test", got::add);
        Thread.sleep(6_500);
        a.publish("idle-test", "still here");
        assertEquals("still here", got.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void registryRoundTripsAndForgetsRemovedServers() {
        ServerRegistry registry = new ServerRegistry(a);
        long now = System.currentTimeMillis();
        registry.publish(new ServerStatus("hub-1", "hub", "", ServerStatus.State.ONLINE, 3, 100, now, now));
        registry.publish(new ServerStatus("smp-1", "smp", "172.18.0.1:25570", ServerStatus.State.ONLINE, 0, 100, now, now));
        var all = new ServerRegistry(b).fetchAll();
        assertTrue(all.stream().anyMatch(s -> s.id().equals("hub-1") && s.online() == 3));
        registry.remove("hub-1");
        assertTrue(new ServerRegistry(b).fetchAll().stream().noneMatch(s -> s.id().equals("hub-1")));
        registry.remove("smp-1");
    }

    @Test
    void countersAndSetMembership() {
        a.delete("it-counter");
        assertEquals(60, a.incrementBy("it-counter", 60, 30));
        assertEquals(120, b.incrementBy("it-counter", 60, 30));

        a.delete("it-set");
        a.addToSet("it-set", "x", "y");
        assertTrue(b.isMember("it-set", "x"));
        b.removeFromSet("it-set", "x");
        assertEquals(false, a.isMember("it-set", "x"));
        assertTrue(a.isMember("it-set", "y"));
    }
}
