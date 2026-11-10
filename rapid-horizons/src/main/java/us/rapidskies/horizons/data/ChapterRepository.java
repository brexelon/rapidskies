package us.rapidskies.horizons.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * {@code chapter_progress} and {@code chapter_completions} (migration module {@code horizons},
 * V2). Every method blocks: call it off the main thread.
 */
public final class ChapterRepository {

    private final Database db;

    public ChapterRepository(Database db) {
        this.db = db;
    }

    /**
     * One entry's stored progress.
     *
     * @param seen what a "biomes" entry has counted (biome keys)
     */
    public record EntryRow(int progress, Set<String> seen, Optional<Instant> completedAt) {
        public static final EntryRow EMPTY = new EntryRow(0, Set.of(), Optional.empty());
    }

    /** {@code player}'s entries in {@code chapter}, by entry id. */
    public Map<String, EntryRow> progress(UUID player, String chapter) throws SQLException {
        return db.withConnection(c -> {
            Map<String, EntryRow> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT entry, progress, data, completed_at"
                    + " FROM chapter_progress WHERE uuid = ? AND chapter = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, chapter);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String data = rs.getString(3);
                        Set<String> seen = data == null || data.isEmpty() ? Set.of()
                                : new LinkedHashSet<>(Arrays.asList(data.split(",")));
                        LocalDateTime done = rs.getObject(4, LocalDateTime.class);
                        out.put(rs.getString(1), new EntryRow(rs.getInt(2), Set.copyOf(seen),
                                done == null ? Optional.empty() : Optional.of(done.toInstant(ZoneOffset.UTC))));
                    }
                }
            }
            return out;
        });
    }

    public void save(UUID player, String chapter, String entry, EntryRow row) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO chapter_progress (uuid, chapter, entry,"
                    + " progress, data, completed_at) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE"
                    + " progress = GREATEST(progress, VALUES(progress)), data = VALUES(data),"
                    + " completed_at = COALESCE(completed_at, VALUES(completed_at))")) {
                ps.setString(1, player.toString());
                ps.setString(2, chapter);
                ps.setString(3, entry);
                ps.setInt(4, row.progress());
                ps.setString(5, row.seen().isEmpty() ? null : String.join(",", row.seen()));
                ps.setObject(6, row.completedAt().map(t -> LocalDateTime.ofInstant(t, ZoneOffset.UTC)).orElse(null));
                return ps.executeUpdate();
            }
        });
    }

    /** Records a finished Chapter; false if it already was (so its reward is paid once). */
    public boolean complete(UUID player, String chapter, String cosmetic, Instant at) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO chapter_completions (uuid, chapter,"
                    + " cosmetic, completed_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, chapter);
                ps.setString(3, cosmetic);
                ps.setObject(4, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
                return ps.executeUpdate() == 1;
            }
        });
    }

    public boolean completed(UUID player, String chapter) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM chapter_completions WHERE uuid = ? AND chapter = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, chapter);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** How many players have finished {@code chapter}. */
    public int completions(String chapter) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM chapter_completions WHERE chapter = ?")) {
                ps.setString(1, chapter);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }
}
