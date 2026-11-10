package us.rapidskies.horizons.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.common.db.Database;
import us.rapidskies.horizons.trade.Trade;

/**
 * Horizons' tables ({@code horizons_players}, {@code horizons_trades},
 * {@code horizons_trade_daily}; migration module {@code horizons}). Every method blocks:
 * call it off the main thread.
 */
public final class HorizonsRepository {

    private final Database db;

    public HorizonsRepository(Database db) {
        this.db = db;
    }

    /** A player's row; all defaults for players without one. */
    public record PlayerRow(boolean pvp, Optional<Instant> pvpChangedAt, Optional<Instant> tradesChangedAt) {
        public static final PlayerRow DEFAULT = new PlayerRow(false, Optional.empty(), Optional.empty());
    }

    /** One Trade's stored progress. */
    public record TradeRow(long xp, int level, boolean active) {
    }

    public PlayerRow player(UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pvp, pvp_changed_at, trades_changed_at FROM horizons_players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return PlayerRow.DEFAULT;
                    }
                    return new PlayerRow(rs.getBoolean(1), instant(rs, 2), instant(rs, 3));
                }
            }
        });
    }

    public void savePvp(UUID player, boolean on, Instant at) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO horizons_players (uuid, pvp, pvp_changed_at)"
                    + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE pvp = VALUES(pvp), pvp_changed_at = VALUES(pvp_changed_at)")) {
                ps.setString(1, player.toString());
                ps.setBoolean(2, on);
                ps.setObject(3, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
                return ps.executeUpdate();
            }
        });
    }

    public void saveTradesChanged(UUID player, Instant at) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO horizons_players (uuid, trades_changed_at)"
                    + " VALUES (?, ?) ON DUPLICATE KEY UPDATE trades_changed_at = VALUES(trades_changed_at)")) {
                ps.setString(1, player.toString());
                ps.setObject(2, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
                return ps.executeUpdate();
            }
        });
    }

    public Map<Trade, TradeRow> trades(UUID player) throws SQLException {
        return db.withConnection(c -> {
            Map<Trade, TradeRow> out = new EnumMap<>(Trade.class);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT trade, xp, level, active FROM horizons_trades WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Optional<Trade> t = Trade.parse(rs.getString(1));
                        if (t.isPresent()) {
                            out.put(t.get(), new TradeRow(rs.getLong(2), rs.getInt(3), rs.getBoolean(4)));
                        }
                    }
                }
            }
            return out;
        });
    }

    /** Writes the given Trades' progress as it stands (the online copy is the truth while they play here). */
    public void saveTrades(UUID player, Map<Trade, TradeRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO horizons_trades (uuid, trade, xp, level, active)"
                    + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE xp = VALUES(xp), level = VALUES(level),"
                    + " active = VALUES(active)")) {
                for (Map.Entry<Trade, TradeRow> e : rows.entrySet()) {
                    ps.setString(1, player.toString());
                    ps.setString(2, e.getKey().id());
                    ps.setLong(3, e.getValue().xp());
                    ps.setInt(4, e.getValue().level());
                    ps.setBoolean(5, e.getValue().active());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public long dailyPoints(UUID player, LocalDate day) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT points FROM horizons_trade_daily WHERE uuid = ? AND day = ?")) {
                ps.setString(1, player.toString());
                ps.setObject(2, day);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public void addDailyPoints(UUID player, LocalDate day, long points) throws SQLException {
        if (points <= 0) {
            return;
        }
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO horizons_trade_daily (uuid, day, points)"
                    + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE points = points + VALUES(points)")) {
                ps.setString(1, player.toString());
                ps.setObject(2, day);
                ps.setLong(3, points);
                return ps.executeUpdate();
            }
        });
    }

    private static Optional<Instant> instant(ResultSet rs, int column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? Optional.empty() : Optional.of(t.toInstant(ZoneOffset.UTC));
    }
}
