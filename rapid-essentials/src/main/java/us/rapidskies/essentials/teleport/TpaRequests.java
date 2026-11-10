package us.rapidskies.essentials.teleport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The open {@code /tpa} and {@code /tpahere} requests, free of Bukkit so the rules are
 * unit-tested. Each player has at most one request out (a new one replaces it); a player can
 * have several coming in, and accepting without a name takes the newest. Requests expire.
 *
 * <p>Not thread-safe: commands and quit events use it on the main thread.
 */
public final class TpaRequests {

    /** {@code THERE}: the sender goes to the receiver ({@code /tpa}). {@code HERE}: the receiver comes ({@code /tpahere}). */
    public enum Direction {
        THERE, HERE
    }

    public record Request(UUID from, UUID to, Direction direction, long expiresAt) {
        /** Who moves when it's accepted. */
        public UUID mover() {
            return direction == Direction.THERE ? from : to;
        }

        /** Who they move to. */
        public UUID anchor() {
            return direction == Direction.THERE ? to : from;
        }

        boolean expired(long now) {
            return now >= expiresAt;
        }
    }

    /** By receiver, then sender, oldest first. */
    private final Map<UUID, LinkedHashMap<UUID, Request>> incoming = new HashMap<>();
    /** By sender. */
    private final Map<UUID, Request> outgoing = new HashMap<>();

    /**
     * Opens a request, replacing the sender's earlier one (to anyone). Returns the request it
     * replaced, if it was still open, so the old receiver's pending list stays honest.
     */
    public Optional<Request> send(UUID from, UUID to, Direction direction, long now, long ttlMillis) {
        Optional<Request> replaced = cancel(from, now);
        Request request = new Request(from, to, direction, now + ttlMillis);
        outgoing.put(from, request);
        incoming.computeIfAbsent(to, k -> new LinkedHashMap<>()).put(from, request);
        return replaced;
    }

    /**
     * Takes (removes and returns) the open request to {@code to}: the one from {@code from}, or
     * the newest if {@code from} is null.
     */
    public Optional<Request> take(UUID to, UUID from, long now) {
        purge(now);
        LinkedHashMap<UUID, Request> mine = incoming.get(to);
        if (mine == null || mine.isEmpty()) {
            return Optional.empty();
        }
        Request request;
        if (from != null) {
            request = mine.get(from);
        } else {
            request = null;
            for (Request r : mine.values()) {
                request = r; // insertion order: the last is the newest
            }
        }
        if (request == null) {
            return Optional.empty();
        }
        remove(request);
        return Optional.of(request);
    }

    /** Withdraws {@code from}'s open request. */
    public Optional<Request> cancel(UUID from, long now) {
        purge(now);
        Request request = outgoing.get(from);
        if (request == null) {
            return Optional.empty();
        }
        remove(request);
        return Optional.of(request);
    }

    /** The open requests to {@code to}, oldest first. */
    public List<Request> pending(UUID to, long now) {
        purge(now);
        LinkedHashMap<UUID, Request> mine = incoming.get(to);
        return mine == null ? List.of() : List.copyOf(mine.values());
    }

    /** Drops every request {@code player} sent or received (they left). */
    public void forget(UUID player) {
        Request out = outgoing.get(player);
        if (out != null) {
            remove(out);
        }
        LinkedHashMap<UUID, Request> in = incoming.remove(player);
        if (in != null) {
            in.keySet().forEach(outgoing::remove);
        }
    }

    private void remove(Request request) {
        outgoing.remove(request.from(), request);
        LinkedHashMap<UUID, Request> in = incoming.get(request.to());
        if (in != null) {
            in.remove(request.from(), request);
            if (in.isEmpty()) {
                incoming.remove(request.to());
            }
        }
    }

    private void purge(long now) {
        List<Request> expired = new ArrayList<>();
        for (Request r : outgoing.values()) {
            if (r.expired(now)) {
                expired.add(r);
            }
        }
        expired.forEach(this::remove);
    }
}
