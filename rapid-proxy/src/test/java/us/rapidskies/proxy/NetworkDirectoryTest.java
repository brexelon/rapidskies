package us.rapidskies.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class NetworkDirectoryTest {

    @Test
    void acceptsOnlyPrivateIpAddresses() {
        assertEquals(25570, NetworkDirectory.parseInternal("172.18.0.1:25570").getPort());
        assertEquals(25565, NetworkDirectory.parseInternal("10.0.0.5:25565").getPort());
        assertEquals(25565, NetworkDirectory.parseInternal("127.0.0.1:25565").getPort());

        assertNull(NetworkDirectory.parseInternal("8.8.8.8:25565"), "public IP");
        assertNull(NetworkDirectory.parseInternal("0.0.0.0:25565"), "wildcard");
        assertNull(NetworkDirectory.parseInternal("mc.rapidskies.us:25565"), "hostnames would need DNS");
        assertNull(NetworkDirectory.parseInternal("172.18.0.1"), "no port");
        assertNull(NetworkDirectory.parseInternal("172.18.0.1:99999"), "bad port");
        assertNull(NetworkDirectory.parseInternal(":25565"));
    }
}
