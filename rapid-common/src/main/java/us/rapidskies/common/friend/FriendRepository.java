package us.rapidskies.common.friend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * Friend lists and pending friend requests (tables {@code friends}, {@code friend_requests}).
 *
 * <p>Size limits depend on rank, which only the caller knows, so they're passed in. Each
 * change runs in one transaction, so a friendship's two rows are always written or removed
 * together. Every method blocks: call it off the main thread.
 */
public final class FriendRepository {

    /** Requests one player may have waiting at once, so nobody can spam the whole server. */
    public static final int MAX_OUTGOING_REQUESTS = 25;

    private final Database db;

    public FriendRepository(Database db) {
        this.db = db;
    }

    /** A friend or a request sender. {@code name} is the last name they joined with. */
    public record Entry(UUID uuid, String name, Instant since) {
    }

    public enum RequestResult {
        /** Request stored; the target can accept it. */
        SENT,
        /** The target had already asked {@code from}: they're friends now. */
        ACCEPTED,
        ALREADY_FRIENDS,
        ALREADY_SENT,
        /** {@code from} is at their friend limit (or has too many requests out). */
        LIMIT_SELF,
        /** The target is at their friend limit. */
        LIMIT_OTHER,
        SELF
    }

    public enum AcceptResult {
        ACCEPTED,
        NO_REQUEST,
        LIMIT_SELF,
        LIMIT_OTHER
    }

    /**
     * {@code from} asks {@code to} to be friends. If {@code to} already asked {@code from},
     * that request is accepted instead (both limits apply then).
     */
    public RequestResult request(UUID from, UUID to, int fromLimit, int toLimit) throws SQLException {
        if (from.equals(to)) {
            return RequestResult.SELF;
        }
        return db.transaction(c -> {
            if (areFriends(c, from, to)) {
                return RequestResult.ALREADY_FRIENDS;
            }
            if (count(c, from) >= fromLimit) {
                return RequestResult.LIMIT_SELF;
            }
            if (requestExists(c, to, from)) {
                if (count(c, to) >= toLimit) {
                    return RequestResult.LIMIT_OTHER;
                }
                befriend(c, from, to);
                return RequestResult.ACCEPTED;
            }
            if (requestExists(c, from, to)) {
                return RequestResult.ALREADY_SENT;
            }
            if (outgoing(c, from) >= MAX_OUTGOING_REQUESTS) {
                return RequestResult.LIMIT_SELF;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO friend_requests (sender, target) VALUES (?, ?)")) {
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
                ps.executeUpdate();
            }
            return RequestResult.SENT;
        });
    }

    /** {@code target} accepts the request {@code sender} sent them. */
    public AcceptResult accept(UUID target, UUID sender, int targetLimit, int senderLimit) throws SQLException {
        return db.transaction(c -> {
            if (!requestExists(c, sender, target)) {
                return AcceptResult.NO_REQUEST;
            }
            if (count(c, target) >= targetLimit) {
                return AcceptResult.LIMIT_SELF;
            }
            if (count(c, sender) >= senderLimit) {
                return AcceptResult.LIMIT_OTHER;
            }
            befriend(c, target, sender);
            return AcceptResult.ACCEPTED;
        });
    }

    /** Drops a pending request. Returns false if there wasn't one. */
    public boolean deny(UUID target, UUID sender) throws SQLException {
        return db.withConnection(c -> deleteRequest(c, sender, target) > 0);
    }

    /** Ends a friendship. Returns false if they weren't friends. */
    public boolean remove(UUID a, UUID b) throws SQLException {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM friends WHERE (uuid = ? AND friend = ?) OR (uuid = ? AND friend = ?)")) {
                ps.setString(1, a.toString());
                ps.setString(2, b.toString());
                ps.setString(3, b.toString());
                ps.setString(4, a.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean areFriends(UUID a, UUID b) throws SQLException {
        return db.withConnection(c -> areFriends(c, a, b));
    }

    /** Friends, oldest friendship first. */
    public List<Entry> friends(UUID player) throws SQLException {
        return db.withConnection(c -> entries(c,
                "SELECT f.friend, p.name, f.since FROM friends f LEFT JOIN players p ON p.uuid = f.friend"
                        + " WHERE f.uuid = ? ORDER BY f.since, f.friend", player));
    }

    /** Requests waiting for {@code player} to answer, oldest first. */
    public List<Entry> incoming(UUID player) throws SQLException {
        return db.withConnection(c -> entries(c,
                "SELECT r.sender, p.name, r.created_at FROM friend_requests r LEFT JOIN players p ON p.uuid = r.sender"
                        + " WHERE r.target = ? ORDER BY r.created_at, r.sender", player));
    }

    public int count(UUID player) throws SQLException {
        return db.withConnection(c -> count(c, player));
    }

    private static void befriend(Connection c, UUID a, UUID b) throws SQLException {
        deleteRequest(c, a, b);
        deleteRequest(c, b, a);
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO friends (uuid, friend) VALUES (?, ?), (?, ?)")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            ps.setString(3, b.toString());
            ps.setString(4, a.toString());
            ps.executeUpdate();
        }
    }

    private static int deleteRequest(Connection c, UUID sender, UUID target) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_requests WHERE sender = ? AND target = ?")) {
            ps.setString(1, sender.toString());
            ps.setString(2, target.toString());
            return ps.executeUpdate();
        }
    }

    private static boolean areFriends(Connection c, UUID a, UUID b) throws SQLException {
        return exists(c, "SELECT 1 FROM friends WHERE uuid = ? AND friend = ?", a, b);
    }

    private static boolean requestExists(Connection c, UUID sender, UUID target) throws SQLException {
        return exists(c, "SELECT 1 FROM friend_requests WHERE sender = ? AND target = ?", sender, target);
    }

    private static int count(Connection c, UUID player) throws SQLException {
        return countOf(c, "SELECT COUNT(*) FROM friends WHERE uuid = ?", player);
    }

    private static int outgoing(Connection c, UUID player) throws SQLException {
        return countOf(c, "SELECT COUNT(*) FROM friend_requests WHERE sender = ?", player);
    }

    private static boolean exists(Connection c, String sql, UUID a, UUID b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int countOf(Connection c, String sql, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<Entry> entries(Connection c, String sql, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            List<Entry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    String name = rs.getString(2);
                    LocalDateTime t = rs.getObject(3, LocalDateTime.class);
                    out.add(new Entry(id, name == null ? id.toString().substring(0, 8) : name, t.toInstant(ZoneOffset.UTC)));
                }
            }
            return out;
        }
    }
}
