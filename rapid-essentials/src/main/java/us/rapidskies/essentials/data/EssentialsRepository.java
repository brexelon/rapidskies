package us.rapidskies.essentials.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * rapid-essentials' tables ({@code essentials_homes}, {@code essentials_warps}; migration
 * module {@code essentials}) and the {@code /baltop} read of rapid-core's
 * {@code mode_balance}. Every method blocks: call it off the main thread.
 */
public final class EssentialsRepository {

    /** What {@link #setHome} did. */
    public enum SetHome {
        CREATED, MOVED, LIMIT
    }

    /** A {@code /baltop} line. */
    public record TopEntry(String name, long balance) {
    }

    private final Database db;

    public EssentialsRepository(Database db) {
        this.db = db;
    }

    // ------------------------------------------------------------------ homes

    /** {@code player}'s homes in {@code scope}, by name in name order. */
    public Map<String, Place> homes(String scope, UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, world, x, y, z, yaw, pitch "
                    + "FROM essentials_homes WHERE scope = ? AND uuid = ? ORDER BY name")) {
                ps.setString(1, scope);
                ps.setString(2, player.toString());
                return places(ps);
            }
        });
    }

    /**
     * Sets (or moves) a home. A new name is refused once the player has {@code limit} homes;
     * moving an existing one always works. Counted and written in one transaction, with the
     * player's rows locked, so two servers can't both add the last free home.
     */
    public SetHome setHome(String scope, UUID player, String name, Place place, int limit) throws SQLException {
        return db.transaction(c -> {
            int count = 0;
            boolean exists = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name FROM essentials_homes WHERE scope = ? AND uuid = ? FOR UPDATE")) {
                ps.setString(1, scope);
                ps.setString(2, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        count++;
                        exists |= rs.getString(1).equals(name);
                    }
                }
            }
            if (!exists && count >= limit) {
                return SetHome.LIMIT;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO essentials_homes "
                    + "(scope, uuid, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE world = VALUES(world), x = VALUES(x), y = VALUES(y), z = VALUES(z), "
                    + "yaw = VALUES(yaw), pitch = VALUES(pitch)")) {
                ps.setString(1, scope);
                ps.setString(2, player.toString());
                ps.setString(3, name);
                bind(ps, 4, place);
                ps.executeUpdate();
            }
            return exists ? SetHome.MOVED : SetHome.CREATED;
        });
    }

    public boolean deleteHome(String scope, UUID player, String name) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM essentials_homes WHERE scope = ? AND uuid = ? AND name = ?")) {
                ps.setString(1, scope);
                ps.setString(2, player.toString());
                ps.setString(3, name);
                return ps.executeUpdate() > 0;
            }
        });
    }

    // ------------------------------------------------------------------ warps

    /** Every warp in {@code scope}, by name in name order. */
    public Map<String, Place> warps(String scope) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, world, x, y, z, yaw, pitch "
                    + "FROM essentials_warps WHERE scope = ? ORDER BY name")) {
                ps.setString(1, scope);
                return places(ps);
            }
        });
    }

    /** Creates or moves a warp. Returns true if it already existed. */
    public boolean setWarp(String scope, String name, Place place, UUID by) throws SQLException {
        return db.transaction(c -> {
            boolean existed = warpExists(c, scope, name);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO essentials_warps "
                    + "(scope, name, world, x, y, z, yaw, pitch, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE world = VALUES(world), x = VALUES(x), y = VALUES(y), z = VALUES(z), "
                    + "yaw = VALUES(yaw), pitch = VALUES(pitch), created_by = VALUES(created_by)")) {
                ps.setString(1, scope);
                ps.setString(2, name);
                int i = bind(ps, 3, place);
                ps.setString(i, by == null ? null : by.toString());
                ps.executeUpdate();
            }
            return existed;
        });
    }

    public boolean deleteWarp(String scope, String name) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM essentials_warps WHERE scope = ? AND name = ?")) {
                ps.setString(1, scope);
                ps.setString(2, name);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static boolean warpExists(Connection c, String scope, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM essentials_warps WHERE scope = ? AND name = ? FOR UPDATE")) {
            ps.setString(1, scope);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------------ /baltop

    /**
     * The richest players in a gamemode currency ({@code mode} is an EconomyMode id). Only
     * accounts that are players: town banks (rapid-claims) live in the same table under the
     * town's uuid and have no {@code players} row.
     */
    public List<TopEntry> top(String mode, int limit) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT p.name, b.balance FROM mode_balance b "
                    + "JOIN players p ON p.uuid = b.uuid WHERE b.mode = ? AND b.balance > 0 "
                    + "ORDER BY b.balance DESC, p.name LIMIT ?")) {
                ps.setString(1, mode);
                ps.setInt(2, limit);
                List<TopEntry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new TopEntry(rs.getString(1), rs.getLong(2)));
                    }
                }
                return out;
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Place> places(PreparedStatement ps) throws SQLException {
        Map<String, Place> out = new LinkedHashMap<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), new Place(rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getFloat(6), rs.getFloat(7)));
            }
        }
        return out;
    }

    /** Binds world, x, y, z, yaw, pitch from {@code index}; returns the next index. */
    private static int bind(PreparedStatement ps, int index, Place place) throws SQLException {
        ps.setString(index, place.world());
        ps.setDouble(index + 1, place.x());
        ps.setDouble(index + 2, place.y());
        ps.setDouble(index + 3, place.z());
        ps.setFloat(index + 4, place.yaw());
        ps.setFloat(index + 5, place.pitch());
        return index + 6;
    }
}
