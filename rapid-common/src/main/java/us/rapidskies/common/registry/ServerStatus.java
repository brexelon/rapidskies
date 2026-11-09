package us.rapidskies.common.registry;

import java.util.Map;

/**
 * One game server's heartbeat (PLAN.md §3, scaling path). Each Paper backend publishes
 * its own every few seconds; the proxy reads them to balance hubs and to discover new
 * instances without config edits.
 *
 * @param id        the server's name, matching its key in network.yml and velocity.toml (e.g. {@code hub-1})
 * @param group     the role shared by interchangeable servers (e.g. {@code hub}, {@code smp})
 * @param address   {@code host:port} the proxy can reach it at, or empty if the proxy should
 *                  only use the address from velocity.toml
 * @param online    everyone connected, vanished staff included: what fills the server, so
 *                  balancing and the queue's free slots use it
 * @param visible   players others can see (vanished staff left out): what the network shows
 *                  players, in the game menu, NPC labels, sidebar and server list
 * @param updatedAt epoch millis of this heartbeat
 */
public record ServerStatus(String id, String group, String address, State state, int online, int visible,
                           int maxPlayers, long startedAt, long updatedAt) {

    /** A server with no vanished staff on it ({@code visible == online}). */
    public ServerStatus(String id, String group, String address, State state, int online, int maxPlayers,
                        long startedAt, long updatedAt) {
        this(id, group, address, state, online, online, maxPlayers, startedAt, updatedAt);
    }

    public enum State {
        /** Booting; don't send players yet. */
        STARTING,
        /** Accepting players. */
        ONLINE,
        /** Accepting only staff (whitelist or maintenance on that server). */
        RESTRICTED,
        /** Shutting down; move players elsewhere. */
        STOPPING
    }

    public boolean isFresh(long now, long maxAgeMillis) {
        return now - updatedAt <= maxAgeMillis;
    }

    public boolean acceptsPlayers() {
        return state == State.ONLINE && online < maxPlayers;
    }

    Map<String, String> toHash() {
        return Map.of(
                "group", group,
                "address", address == null ? "" : address,
                "state", state.name(),
                "online", Integer.toString(online),
                "visible", Integer.toString(visible),
                "max", Integer.toString(maxPlayers),
                "started", Long.toString(startedAt),
                "updated", Long.toString(updatedAt));
    }

    /**
     * Parses a heartbeat hash, or returns null if it's missing or malformed. A heartbeat from
     * before {@code visible} existed counts everyone as visible.
     */
    static ServerStatus fromHash(String id, Map<String, String> h) {
        if (h == null || h.isEmpty()) {
            return null;
        }
        try {
            int online = Integer.parseInt(h.get("online"));
            String visible = h.get("visible");
            return new ServerStatus(id, h.getOrDefault("group", ""), h.getOrDefault("address", ""),
                    State.valueOf(h.get("state")), online, visible == null ? online : Integer.parseInt(visible),
                    Integer.parseInt(h.get("max")), Long.parseLong(h.get("started")), Long.parseLong(h.get("updated")));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
