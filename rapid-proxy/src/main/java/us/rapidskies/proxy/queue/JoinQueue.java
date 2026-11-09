package us.rapidskies.proxy.queue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import us.rapidskies.common.registry.ServerRegistry;
import us.rapidskies.common.registry.ServerStatus;

/**
 * Who is waiting for which server, and who goes next (PLAN.md §4: join queue; §6.1: queue
 * priority). Pure state with no Velocity types; {@link QueueService} runs it.
 *
 * <p>Each target has one line, ordered by priority (higher first), then by arrival. A
 * player waits in one line at a time. Every {@link #drain} hands out the free slots of the
 * target's live servers, fullest last. A heartbeat only reports players who had arrived
 * when it was sent, so recent sends count against a server until a heartbeat taken a
 * little after them comes in; that keeps a burst of sends from overfilling it.
 */
public final class JoinQueue {

    /** How long after a send a heartbeat must be before it's assumed to include that player. */
    static final long CONNECT_GRACE_MILLIS = 2_000;
    private static final long FORGET_SENDS_MILLIS = 30_000;

    private final Map<QueueTarget, List<Entry>> lines = new LinkedHashMap<>();
    private final Map<UUID, Entry> byPlayer = new HashMap<>();
    private final Map<String, Deque<Long>> recentSends = new HashMap<>();
    private long sequence;

    public record Entry(UUID player, QueueTarget target, int priority, long seq, long joinedAt, int attempts) {
        Entry retried() {
            return new Entry(player, target, priority, seq, joinedAt, attempts + 1);
        }
    }

    /** "Send {@code player} to {@code serverId} now." */
    public record Dispatch(Entry entry, String serverId) {
    }

    /** 1-based place in line and the line's length. */
    public record Position(QueueTarget target, int place, int of) {
    }

    /** Puts {@code player} in line for {@code target}, leaving any other line they were in. */
    public synchronized Position join(UUID player, QueueTarget target, int priority, long now) {
        remove(player);
        Entry entry = new Entry(player, target, priority, sequence++, now, 0);
        insert(entry);
        return position(player).orElseThrow();
    }

    /** Puts a failed send back at the front of its priority band, to be tried again. */
    public synchronized void retry(Entry entry) {
        if (byPlayer.containsKey(entry.player())) {
            return; // joined another line meanwhile
        }
        insert(entry.retried());
    }

    public synchronized boolean remove(UUID player) {
        Entry e = byPlayer.remove(player);
        if (e == null) {
            return false;
        }
        List<Entry> line = lines.get(e.target());
        line.remove(e);
        if (line.isEmpty()) {
            lines.remove(e.target());
        }
        return true;
    }

    public synchronized Optional<Position> position(UUID player) {
        Entry e = byPlayer.get(player);
        if (e == null) {
            return Optional.empty();
        }
        List<Entry> line = lines.get(e.target());
        return Optional.of(new Position(e.target(), line.indexOf(e) + 1, line.size()));
    }

    /** Everyone waiting, with their place in line. */
    public synchronized Map<UUID, Position> all() {
        Map<UUID, Position> out = new LinkedHashMap<>();
        lines.forEach((target, line) -> {
            for (int i = 0; i < line.size(); i++) {
                out.put(line.get(i).player(), new Position(target, i + 1, line.size()));
            }
        });
        return out;
    }

    /**
     * Takes everyone who can go now off their lines. {@code known} says which server ids
     * the proxy can actually connect to.
     */
    public synchronized List<Dispatch> drain(Collection<ServerStatus> servers, long now, Predicate<String> known) {
        forgetOldSends(now);
        List<Dispatch> out = new ArrayList<>();
        for (var it = lines.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            List<Entry> line = e.getValue();
            Map<String, Integer> free = new LinkedHashMap<>();
            Map<String, Integer> load = new HashMap<>();
            for (ServerStatus s : servers) {
                if (!e.getKey().matches(s) || !s.isFresh(now, ServerRegistry.STALE_MILLIS)
                        || s.state() != ServerStatus.State.ONLINE || !known.test(s.id())) {
                    continue;
                }
                int pending = pendingSends(s);
                int slots = s.maxPlayers() - s.online() - pending;
                if (slots > 0) {
                    free.put(s.id(), slots);
                    load.put(s.id(), s.online() + pending);
                }
            }
            while (!line.isEmpty() && !free.isEmpty()) {
                // Least loaded first; ties go to the lowest id, as in ServerSelector.
                String target = free.keySet().stream()
                        .min(Comparator.comparingInt((String id) -> load.get(id)).thenComparing(id -> id))
                        .orElseThrow();
                Entry next = line.removeFirst();
                byPlayer.remove(next.player());
                out.add(new Dispatch(next, target));
                recentSends.computeIfAbsent(target, k -> new ArrayDeque<>()).addLast(now);
                load.merge(target, 1, Integer::sum);
                if (free.merge(target, -1, Integer::sum) <= 0) {
                    free.remove(target);
                }
            }
            if (line.isEmpty()) {
                it.remove();
            }
        }
        return out;
    }

    /** True if the target has no live server at all (as opposed to all of them being full). */
    public static boolean noServerUp(QueueTarget target, Collection<ServerStatus> servers, long now) {
        return servers.stream().noneMatch(s -> target.matches(s) && s.isFresh(now, ServerRegistry.STALE_MILLIS)
                && s.state() == ServerStatus.State.ONLINE);
    }

    private void insert(Entry entry) {
        List<Entry> line = lines.computeIfAbsent(entry.target(), k -> new ArrayList<>());
        int i = 0;
        while (i < line.size() && before(line.get(i), entry)) {
            i++;
        }
        line.add(i, entry);
        byPlayer.put(entry.player(), entry);
    }

    /** Whether {@code a} stays ahead of {@code b}. */
    private static boolean before(Entry a, Entry b) {
        if (a.priority() != b.priority()) {
            return a.priority() > b.priority();
        }
        return a.seq() < b.seq();
    }

    private int pendingSends(ServerStatus s) {
        Deque<Long> sends = recentSends.get(s.id());
        if (sends == null) {
            return 0;
        }
        long counted = s.updatedAt() - CONNECT_GRACE_MILLIS;
        int n = 0;
        for (long t : sends) {
            if (t > counted) {
                n++;
            }
        }
        return n;
    }

    private void forgetOldSends(long now) {
        for (var it = recentSends.values().iterator(); it.hasNext(); ) {
            Deque<Long> d = it.next();
            while (!d.isEmpty() && d.peekFirst() < now - FORGET_SENDS_MILLIS) {
                d.removeFirst();
            }
            if (d.isEmpty()) {
                it.remove();
            }
        }
    }
}
