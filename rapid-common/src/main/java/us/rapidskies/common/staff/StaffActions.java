package us.rapidskies.common.staff;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * Writes {@code staff_actions} (PLAN.md §9, §10): freezes, inspections and vanish toggles,
 * so every use of a staff tool can be audited. Blocking: call from async contexts only.
 */
public final class StaffActions {

    private final Database db;

    public StaffActions(Database db) {
        this.db = db;
    }

    /** {@code target} and {@code detail} may be null. */
    public void log(UUID staff, String action, UUID target, String server, String detail) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO staff_actions (staff, action, target, server, detail) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, staff.toString());
                ps.setString(2, truncate(action, 32));
                ps.setString(3, target == null ? null : target.toString());
                ps.setString(4, truncate(server, 32));
                ps.setString(5, detail == null ? null : truncate(detail, 255));
                return ps.executeUpdate();
            }
        });
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
