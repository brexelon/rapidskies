package us.rapidskies.common.redis;

import java.util.UUID;

/** Pub/sub channel names and their JSON payloads (all under {@code rapid:}). */
public final class Channels {

    /** A player's profile row changed (plus color, ...): servers holding it reload it. */
    public static final String PROFILE = "profile";
    /** A Bolts balance changed: the server the player is on tells them. */
    public static final String BOLTS = "bolts";
    /** Maintenance mode was switched on or off. */
    public static final String MAINTENANCE = "maintenance";

    /**
     * A backend asks the proxy to send a player somewhere (hub selector, NPCs). The proxy
     * queues them like {@code /play} would.
     */
    public static final String CONNECT = "connect";

    /** Redis key holding "true" while maintenance mode is on. */
    public static final String MAINTENANCE_KEY = "maintenance";

    private Channels() {
    }

    public record ProfileChanged(String uuid, String origin) {
        public static ProfileChanged of(UUID uuid, String origin) {
            return new ProfileChanged(uuid.toString(), origin);
        }

        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    public record BoltsChanged(String uuid, long delta, long balance, String reason) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    public record MaintenanceChanged(boolean enabled, String by) {
    }

    /** {@code target} is a server group ({@code smp}) or a server id ({@code smp-1}). */
    public record ConnectRequest(String uuid, String target) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }
}
