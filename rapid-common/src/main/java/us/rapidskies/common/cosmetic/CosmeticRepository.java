package us.rapidskies.common.cosmetic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * {@code cosmetics_owned} and {@code cosmetics_equipped} (PLAN.md §10). Cosmetic ids are
 * {@code <type>:<name>} ({@code title:pathfinder}); slots are the types. Every method blocks.
 *
 * <p>Granting is {@code INSERT IGNORE}, so it's idempotent: a Chapter reward, a level-up and
 * a retry can all grant the same cosmetic and the player owns it once. Other modules grant
 * inside their own transactions with {@link #grantIn}.
 */
public final class CosmeticRepository {

    private final Database db;

    public CosmeticRepository(Database db) {
        this.db = db;
    }

    /** What a player owns and wears. */
    public record Wardrobe(Set<String> owned, Map<String, String> equipped) {
    }

    public Wardrobe load(UUID player) throws SQLException {
        return db.withConnection(c -> {
            Set<String> owned = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT cosmetic_id FROM cosmetics_owned WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        owned.add(rs.getString(1));
                    }
                }
            }
            Map<String, String> equipped = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT slot, cosmetic_id FROM cosmetics_equipped WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        equipped.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return new Wardrobe(owned, equipped);
        });
    }

    /** True if the player didn't own it before. */
    public boolean grant(UUID player, String cosmetic, String source) throws SQLException {
        return db.withConnection(c -> grantIn(c, player, cosmetic, source));
    }

    /** {@link #grant} inside the caller's transaction. */
    public static boolean grantIn(Connection c, UUID player, String cosmetic, String source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO cosmetics_owned (uuid, cosmetic_id, source) VALUES (?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, cosmetic);
            ps.setString(3, source.length() <= 64 ? source : source.substring(0, 64));
            return ps.executeUpdate() == 1;
        }
    }

    public static boolean ownsIn(Connection c, UUID player, String cosmetic) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM cosmetics_owned WHERE uuid = ? AND cosmetic_id = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, cosmetic);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Takes a cosmetic away (staff), unequipping it. True if they had it. */
    public boolean revoke(UUID player, String cosmetic) throws SQLException {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM cosmetics_equipped WHERE uuid = ? AND cosmetic_id = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, cosmetic);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM cosmetics_owned WHERE uuid = ? AND cosmetic_id = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, cosmetic);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Wears {@code cosmetic} in {@code slot}, replacing what was there. */
    public void equip(UUID player, String slot, String cosmetic) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cosmetics_equipped (uuid, slot, cosmetic_id)"
                    + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE cosmetic_id = VALUES(cosmetic_id)")) {
                ps.setString(1, player.toString());
                ps.setString(2, slot);
                ps.setString(3, cosmetic);
                return ps.executeUpdate();
            }
        });
    }

    public void unequip(UUID player, String slot) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM cosmetics_equipped WHERE uuid = ? AND slot = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, slot);
                return ps.executeUpdate();
            }
        });
    }
}
