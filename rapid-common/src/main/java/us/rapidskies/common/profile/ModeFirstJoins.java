package us.rapidskies.common.profile;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;

/**
 * Which gamemodes each player has visited (table {@code mode_first_joins}), network-wide, so
 * a first visit is recognized even on a different instance of the gamemode. Blocks: call it
 * off the main thread.
 */
public final class ModeFirstJoins {

    private final Database db;

    public ModeFirstJoins(Database db) {
        this.db = db;
    }

    /** Records that {@code player} joined {@code mode}; true only the first time. */
    public boolean record(UUID player, EconomyMode mode) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO mode_first_joins (uuid, mode) VALUES (?, ?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, mode.id());
                return ps.executeUpdate() == 1;
            }
        });
    }
}
