package us.rapidskies.claims.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import us.rapidskies.claims.model.Access;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Plot;
import us.rapidskies.claims.model.Role;
import us.rapidskies.claims.model.TagColor;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownPermission;
import us.rapidskies.claims.model.TownRole;
import us.rapidskies.common.db.Database;

/**
 * Every SQL statement rapid-claims runs (tables from {@code db/migration/claims}). Every
 * method blocks: call it through {@link Database#async}, never on the main thread. Changes
 * that touch several rows run in one transaction.
 */
public final class ClaimRepository {

    private final Database db;

    public ClaimRepository(Database db) {
        this.db = db;
    }

    public Database db() {
        return db;
    }

    /** Personal allowance row; all zero / empty for players without one. */
    public record PlayerRow(UUID player, int purchased, int bonus, Optional<Instant> ruinsSince) {
        public static PlayerRow empty(UUID player) {
            return new PlayerRow(player, 0, 0, Optional.empty());
        }
    }

    /** Everything the index holds, read at startup. */
    public record Snapshot(List<Claim> claims, Map<Owner, Access> access, List<Town> towns, List<Plot> plots,
                           Map<UUID, PlayerRow> players) {
    }

    /** One project's progress in one town. */
    public record ProjectRow(UUID town, String project, boolean completed, Map<String, Long> totals,
                             Map<UUID, Long> contributors) {
    }

    // ------------------------------------------------------------------ loading

    public Snapshot load(Set<String> worlds) throws SQLException {
        return db.withConnection(c -> {
            List<Claim> claims = new ArrayList<>();
            if (!worlds.isEmpty()) {
                String marks = String.join(", ", Collections.nCopies(worlds.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement("SELECT world, chunk_x, chunk_z, owner_type, owner_id,"
                        + " flags, claimed_by, claimed_at FROM claims WHERE world IN (" + marks + ")")) {
                    int i = 1;
                    for (String w : worlds) {
                        ps.setString(i++, w);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            claims.add(new Claim(new ChunkKey(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                                    new Owner(Owner.Type.valueOf(rs.getString(4)), UUID.fromString(rs.getString(5))),
                                    rs.getInt(6), UUID.fromString(rs.getString(7)), instant(rs, 8)));
                        }
                    }
                }
            }
            Map<Owner, Access> access = loadAccess(c);
            List<Town> towns = loadTowns(c);
            List<Plot> plots = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT world, chunk_x, chunk_z, town, resident FROM town_plots");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    plots.add(new Plot(new ChunkKey(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                            UUID.fromString(rs.getString(4)), UUID.fromString(rs.getString(5))));
                }
            }
            Map<UUID, PlayerRow> players = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, purchased, bonus, ruins_since FROM claim_players");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    players.put(id, new PlayerRow(id, rs.getInt(2), rs.getInt(3), optionalInstant(rs, 4)));
                }
            }
            return new Snapshot(claims, access, towns, plots, players);
        });
    }

    private static Map<Owner, Access> loadAccess(Connection c) throws SQLException {
        Map<Owner, Map<UUID, Role>> trust = new HashMap<>();
        Map<Owner, Set<UUID>> denied = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT owner_type, owner_id, uuid, role FROM claim_trust");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Owner o = new Owner(Owner.Type.valueOf(rs.getString(1)), UUID.fromString(rs.getString(2)));
                Role role = Role.parse(rs.getString(4)).orElse(Role.TRUSTED);
                trust.computeIfAbsent(o, k -> new HashMap<>()).put(UUID.fromString(rs.getString(3)), role);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT owner_type, owner_id, uuid FROM claim_denied");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Owner o = new Owner(Owner.Type.valueOf(rs.getString(1)), UUID.fromString(rs.getString(2)));
                denied.computeIfAbsent(o, k -> new HashSet<>()).add(UUID.fromString(rs.getString(3)));
            }
        }
        Map<Owner, Access> out = new HashMap<>();
        Set<Owner> owners = new HashSet<>(trust.keySet());
        owners.addAll(denied.keySet());
        for (Owner o : owners) {
            out.put(o, new Access(trust.getOrDefault(o, Map.of()), denied.getOrDefault(o, Set.of())));
        }
        return out;
    }

    private static List<Town> loadTowns(Connection c) throws SQLException {
        Map<UUID, Map<UUID, TownRole>> members = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, town, role FROM town_members");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TownRole role = TownRole.parse(rs.getString(3)).orElse(TownRole.RECRUIT);
                members.computeIfAbsent(UUID.fromString(rs.getString(2)), k -> new HashMap<>())
                        .put(UUID.fromString(rs.getString(1)), role);
            }
        }
        Map<UUID, Map<TownRole, Town.RoleSettings>> roles = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT town, role, permissions, land_role FROM town_roles");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Optional<TownRole> role = TownRole.parse(rs.getString(2));
                if (role.isEmpty()) {
                    continue;
                }
                roles.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new EnumMap<>(TownRole.class))
                        .put(role.get(), new Town.RoleSettings(TownPermission.parseAll(rs.getString(3)),
                                Role.parse(rs.getString(4)).orElse(Role.TRUSTED)));
            }
        }
        Map<UUID, Set<String>> completed = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT town, project FROM town_projects WHERE state = 'COMPLETED'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                completed.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new HashSet<>()).add(rs.getString(2));
            }
        }
        List<Town> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, tag, tag_color, mayor, world, center_x,"
                + " center_z, warp, upkeep_paid_until, state, ruins_since FROM towns");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString(1));
                Map<TownRole, Town.RoleSettings> r = new EnumMap<>(TownRole.class);
                r.putAll(Town.defaultRoles());
                r.putAll(roles.getOrDefault(id, Map.of()));
                out.add(new Town(id, rs.getString(2), rs.getString(3),
                        TagColor.parse(rs.getString(4)).orElse(TagColor.WHITE), UUID.fromString(rs.getString(5)),
                        new ChunkKey(rs.getString(6), rs.getInt(7), rs.getInt(8)), Optional.ofNullable(rs.getString(9)),
                        instant(rs, 10), Town.State.valueOf(rs.getString(11)), optionalInstant(rs, 12),
                        members.getOrDefault(id, Map.of()), r, completed.getOrDefault(id, Set.of())));
            }
        }
        return out;
    }

    /** Last known names for these players (from rapid-core's {@code players}), for offline owners and trust lists. */
    public Map<UUID, String> names(Collection<UUID> players) throws SQLException {
        Map<UUID, String> out = new HashMap<>();
        if (players.isEmpty()) {
            return out;
        }
        List<UUID> ids = List.copyOf(players);
        return db.withConnection(c -> {
            for (int from = 0; from < ids.size(); from += 500) {
                List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + 500));
                String marks = String.join(", ", Collections.nCopies(batch.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, name FROM players WHERE uuid IN (" + marks + ")")) {
                    int i = 1;
                    for (UUID id : batch) {
                        ps.setString(i++, id.toString());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                        }
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ claims

    /** False if someone else got the chunk first (on any server). */
    public boolean insertClaim(Claim claim) throws SQLException {
        try {
            return db.withConnection(c -> {
                insertClaim(c, claim);
                return true;
            });
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                return false;
            }
            throw e;
        }
    }

    private static void insertClaim(Connection c, Claim claim) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO claims (world, chunk_x, chunk_z, owner_type,"
                + " owner_id, flags, claimed_by, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindChunk(ps, 1, claim.chunk());
            ps.setString(4, claim.owner().type().name());
            ps.setString(5, claim.owner().id().toString());
            ps.setInt(6, claim.flags());
            ps.setString(7, claim.claimedBy().toString());
            ps.setObject(8, ts(claim.claimedAt()));
            ps.executeUpdate();
        }
    }

    /** Removes the claim if {@code owner} still holds it (and any plot on it). False if not. */
    public boolean deleteClaim(ChunkKey chunk, Owner owner) throws SQLException {
        return db.transaction(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE world = ? AND chunk_x = ?"
                    + " AND chunk_z = ? AND owner_type = ? AND owner_id = ?")) {
                bindChunk(ps, 1, chunk);
                ps.setString(4, owner.type().name());
                ps.setString(5, owner.id().toString());
                n = ps.executeUpdate();
            }
            deletePlot(c, chunk);
            return n == 1;
        });
    }

    /** Removes every claim {@code owner} holds; returns how many. */
    public int deleteAllClaims(Owner owner) throws SQLException {
        return db.transaction(c -> deleteAllClaims(c, owner));
    }

    private static int deleteAllClaims(Connection c, Owner owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE p FROM town_plots p JOIN claims cl ON cl.world = p.world"
                + " AND cl.chunk_x = p.chunk_x AND cl.chunk_z = p.chunk_z WHERE cl.owner_type = ? AND cl.owner_id = ?")) {
            ps.setString(1, owner.type().name());
            ps.setString(2, owner.id().toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE owner_type = ? AND owner_id = ?")) {
            ps.setString(1, owner.type().name());
            ps.setString(2, owner.id().toString());
            return ps.executeUpdate();
        }
    }

    public void setFlags(Collection<ChunkKey> chunks, int flags) throws SQLException {
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE claims SET flags = ? WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
                for (ChunkKey k : chunks) {
                    ps.setInt(1, flags);
                    bindChunk(ps, 2, k);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Hands claims to a new owner (personal land into a new town, staff transfers); drops their plots. */
    public void setOwner(Collection<ChunkKey> chunks, Owner owner) throws SQLException {
        db.transaction(c -> {
            setOwner(c, chunks, owner);
            return null;
        });
    }

    private static void setOwner(Connection c, Collection<ChunkKey> chunks, Owner owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE claims SET owner_type = ?, owner_id = ?"
                + " WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            for (ChunkKey k : chunks) {
                ps.setString(1, owner.type().name());
                ps.setString(2, owner.id().toString());
                bindChunk(ps, 3, k);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        for (ChunkKey k : chunks) {
            deletePlot(c, k);
        }
    }

    // ------------------------------------------------------------------ trust and denials

    /** {@code role} null or VISITOR removes the entry. */
    public void setTrust(Owner owner, UUID player, Role role) throws SQLException {
        db.withConnection(c -> {
            if (role == null || role == Role.VISITOR) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM claim_trust WHERE owner_type = ? AND owner_id = ? AND uuid = ?")) {
                    bindOwner(ps, 1, owner);
                    ps.setString(3, player.toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO claim_trust (owner_type, owner_id, uuid,"
                        + " role) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE role = VALUES(role)")) {
                    bindOwner(ps, 1, owner);
                    ps.setString(3, player.toString());
                    ps.setString(4, role.name());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public void setDenied(Owner owner, UUID player, boolean deny) throws SQLException {
        db.withConnection(c -> {
            String sql = deny
                    ? "INSERT IGNORE INTO claim_denied (owner_type, owner_id, uuid) VALUES (?, ?, ?)"
                    : "DELETE FROM claim_denied WHERE owner_type = ? AND owner_id = ? AND uuid = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bindOwner(ps, 1, owner);
                ps.setString(3, player.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Access access(Owner owner) throws SQLException {
        return db.withConnection(c -> {
            Map<UUID, Role> trust = new HashMap<>();
            Set<UUID> denied = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, role FROM claim_trust WHERE owner_type = ? AND owner_id = ?")) {
                bindOwner(ps, 1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        trust.put(UUID.fromString(rs.getString(1)), Role.parse(rs.getString(2)).orElse(Role.TRUSTED));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid FROM claim_denied WHERE owner_type = ? AND owner_id = ?")) {
                bindOwner(ps, 1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        denied.add(UUID.fromString(rs.getString(1)));
                    }
                }
            }
            return new Access(trust, denied);
        });
    }

    // ------------------------------------------------------------------ personal allowance and inactivity

    public PlayerRow player(UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT purchased, bonus, ruins_since FROM claim_players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new PlayerRow(player, rs.getInt(1), rs.getInt(2), optionalInstant(rs, 3))
                            : PlayerRow.empty(player);
                }
            }
        });
    }

    /** Adds to the purchased or bonus count (bonus may be negative); returns the new row. */
    public PlayerRow addAllowance(UUID player, int purchased, int bonus) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO claim_players (uuid, purchased, bonus)"
                    + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE purchased = purchased + VALUES(purchased),"
                    + " bonus = bonus + VALUES(bonus)")) {
                ps.setString(1, player.toString());
                ps.setInt(2, purchased);
                ps.setInt(3, bonus);
                ps.executeUpdate();
            }
            return null;
        });
        return player(player);
    }

    /** Marks or clears a player's land as ruins. */
    public void setPlayerRuins(UUID player, Instant since) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO claim_players (uuid, ruins_since) VALUES (?, ?)"
                    + " ON DUPLICATE KEY UPDATE ruins_since = VALUES(ruins_since)")) {
                ps.setString(1, player.toString());
                ps.setObject(2, since == null ? null : ts(since));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Owners of personal claims last seen before {@code cutoff} (rapid-core's {@code players.last_join}). */
    public Set<UUID> personalOwnersAwaySince(Instant cutoff) throws SQLException {
        return db.withConnection(c -> {
            Set<UUID> out = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT cl.owner_id FROM claims cl"
                    + " JOIN players p ON p.uuid = cl.owner_id WHERE cl.owner_type = 'PLAYER' AND p.last_join < ?")) {
                ps.setObject(1, ts(cutoff));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(UUID.fromString(rs.getString(1)));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ towns

    /**
     * Founds a town: the town row, its mayor, its role settings, and its center chunk, which
     * is either claimed fresh or taken over from the mayor's personal land. Their other
     * personal claims stay personal.
     */
    public boolean createTown(Town town, Claim center, boolean centerWasPersonal) throws SQLException {
        try {
            return db.transaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO towns (uuid, name, tag, tag_color, mayor,"
                        + " world, center_x, center_z, upkeep_paid_until, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, town.id().toString());
                    ps.setString(2, town.name());
                    ps.setString(3, town.tag());
                    ps.setString(4, town.tagColor().name());
                    ps.setString(5, town.mayor().toString());
                    ps.setString(6, town.center().world());
                    ps.setInt(7, town.center().x());
                    ps.setInt(8, town.center().z());
                    ps.setObject(9, ts(town.upkeepPaidUntil()));
                    ps.setString(10, town.state().name());
                    ps.executeUpdate();
                }
                putMember(c, town.id(), town.mayor(), TownRole.MAYOR);
                for (Map.Entry<TownRole, Town.RoleSettings> e : town.roles().entrySet()) {
                    putRole(c, town.id(), e.getKey(), e.getValue());
                }
                if (centerWasPersonal) {
                    setOwner(c, List.of(center.chunk()), center.owner());
                } else {
                    insertClaim(c, center);
                }
                return true;
            });
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                return false;
            }
            throw e;
        }
    }

    /** Deletes a town with all its land, plots, members, trust, projects and vault. */
    public int deleteTown(UUID town) throws SQLException {
        return db.transaction(c -> {
            Owner owner = Owner.town(town);
            int chunks = deleteAllClaims(c, owner);
            for (String table : new String[] {"claim_trust", "claim_denied"}) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + table + " WHERE owner_type = ? AND owner_id = ?")) {
                    bindOwner(ps, 1, owner);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM towns WHERE uuid = ?")) {
                ps.setString(1, town.toString());
                ps.executeUpdate();
            }
            return chunks;
        });
    }

    /** False if another town already uses the name or tag. */
    public boolean updateTownIdentity(Town town) throws SQLException {
        try {
            return db.withConnection(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE towns SET name = ?, tag = ?, tag_color = ? WHERE uuid = ?")) {
                    ps.setString(1, town.name());
                    ps.setString(2, town.tag());
                    ps.setString(3, town.tagColor().name());
                    ps.setString(4, town.id().toString());
                    ps.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                return false;
            }
            throw e;
        }
    }

    public void updateWarp(UUID town, Optional<String> warp) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE towns SET warp = ? WHERE uuid = ?")) {
                ps.setString(1, warp.orElse(null));
                ps.setString(2, town.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void updateUpkeep(Town town) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE towns SET upkeep_paid_until = ?, state = ?, ruins_since = ? WHERE uuid = ?")) {
                ps.setObject(1, ts(town.upkeepPaidUntil()));
                ps.setString(2, town.state().name());
                ps.setObject(3, town.ruinsSince().map(ClaimRepository::ts).orElse(null));
                ps.setString(4, town.id().toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Hands the town to a new mayor; the old one becomes an assistant. */
    public void setMayor(UUID town, UUID oldMayor, UUID newMayor) throws SQLException {
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE towns SET mayor = ? WHERE uuid = ?")) {
                ps.setString(1, newMayor.toString());
                ps.setString(2, town.toString());
                ps.executeUpdate();
            }
            putMember(c, town, oldMayor, TownRole.ASSISTANT);
            putMember(c, town, newMayor, TownRole.MAYOR);
            return null;
        });
    }

    /** False if the player joined another town meanwhile. */
    public boolean addMember(UUID town, UUID player, TownRole role) throws SQLException {
        try {
            return db.withConnection(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO town_members (uuid, town, role) VALUES (?, ?, ?)")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, town.toString());
                    ps.setString(3, role.name());
                    ps.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                return false;
            }
            throw e;
        }
    }

    public void setMemberRole(UUID town, UUID player, TownRole role) throws SQLException {
        db.withConnection(c -> {
            putMember(c, town, player, role);
            return null;
        });
    }

    /** Removes a member and hands their plots back to the town. */
    public void removeMember(UUID town, UUID player) throws SQLException {
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM town_members WHERE uuid = ? AND town = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, town.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM town_plots WHERE town = ? AND resident = ?")) {
                ps.setString(1, town.toString());
                ps.setString(2, player.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setRole(UUID town, TownRole role, Town.RoleSettings settings) throws SQLException {
        db.withConnection(c -> {
            putRole(c, town, role, settings);
            return null;
        });
    }

    private static void putMember(Connection c, UUID town, UUID player, TownRole role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO town_members (uuid, town, role) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE role = VALUES(role)")) {
            ps.setString(1, player.toString());
            ps.setString(2, town.toString());
            ps.setString(3, role.name());
            ps.executeUpdate();
        }
    }

    private static void putRole(Connection c, UUID town, TownRole role, Town.RoleSettings s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO town_roles (town, role, permissions, land_role)"
                + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE permissions = VALUES(permissions),"
                + " land_role = VALUES(land_role)")) {
            ps.setString(1, town.toString());
            ps.setString(2, role.name());
            ps.setString(3, TownPermission.format(s.permissions()));
            ps.setString(4, s.land().name());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ plots

    public void setPlot(Plot plot) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO town_plots (world, chunk_x, chunk_z, town,"
                    + " resident) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE town = VALUES(town),"
                    + " resident = VALUES(resident)")) {
                bindChunk(ps, 1, plot.chunk());
                ps.setString(4, plot.town().toString());
                ps.setString(5, plot.resident().toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void removePlot(ChunkKey chunk) throws SQLException {
        db.withConnection(c -> {
            deletePlot(c, chunk);
            return null;
        });
    }

    private static void deletePlot(Connection c, ChunkKey chunk) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM town_plots WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            bindChunk(ps, 1, chunk);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ projects

    public List<ProjectRow> projects(UUID town) throws SQLException {
        return db.withConnection(c -> {
            Map<String, Boolean> state = new java.util.LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT project, state FROM town_projects WHERE town = ? ORDER BY started_at")) {
                ps.setString(1, town.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        state.put(rs.getString(1), "COMPLETED".equals(rs.getString(2)));
                    }
                }
            }
            Map<String, Map<String, Long>> totals = new HashMap<>();
            Map<String, Map<UUID, Long>> people = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT project, uuid, resource, amount FROM town_project_contributions WHERE town = ?")) {
                ps.setString(1, town.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String p = rs.getString(1);
                        long amount = rs.getLong(4);
                        totals.computeIfAbsent(p, k -> new HashMap<>()).merge(rs.getString(3), amount, Long::sum);
                        people.computeIfAbsent(p, k -> new HashMap<>())
                                .merge(UUID.fromString(rs.getString(2)), amount, Long::sum);
                    }
                }
            }
            List<ProjectRow> out = new ArrayList<>();
            state.forEach((p, done) -> out.add(new ProjectRow(town, p, done, totals.getOrDefault(p, Map.of()),
                    people.getOrDefault(p, Map.of()))));
            return out;
        });
    }

    /** False if the town already has a row for this project. */
    public boolean startProject(UUID town, String project) throws SQLException {
        try {
            return db.withConnection(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO town_projects (town, project, state) VALUES (?, ?, 'ACTIVE')")) {
                    ps.setString(1, town.toString());
                    ps.setString(2, project);
                    ps.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            if (isDuplicate(e)) {
                return false;
            }
            throw e;
        }
    }

    public void contribute(UUID town, String project, UUID player, String resource, long amount) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO town_project_contributions (town, project, uuid,"
                    + " resource, amount) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)")) {
                ps.setString(1, town.toString());
                ps.setString(2, project);
                ps.setString(3, player.toString());
                ps.setString(4, resource);
                ps.setLong(5, amount);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Marks the project done; false if it was already (another server finished it first). */
    public boolean completeProject(UUID town, String project) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE town_projects SET state = 'COMPLETED',"
                    + " completed_at = CURRENT_TIMESTAMP(3) WHERE town = ? AND project = ? AND state = 'ACTIVE'")) {
                ps.setString(1, town.toString());
                ps.setString(2, project);
                return ps.executeUpdate() == 1;
            }
        });
    }

    // ------------------------------------------------------------------ vault

    public Optional<byte[]> vault(UUID town) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT contents FROM town_vaults WHERE town = ?")) {
                ps.setString(1, town.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
                }
            }
        });
    }

    public void saveVault(UUID town, byte[] contents) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO town_vaults (town, contents) VALUES (?, ?)"
                    + " ON DUPLICATE KEY UPDATE contents = VALUES(contents)")) {
                ps.setString(1, town.toString());
                ps.setBytes(2, contents);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ reloads (other servers changed something)

    public Optional<Claim> claim(ChunkKey chunk) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT owner_type, owner_id, flags, claimed_by, claimed_at"
                    + " FROM claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
                bindChunk(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Claim(chunk, new Owner(Owner.Type.valueOf(rs.getString(1)),
                            UUID.fromString(rs.getString(2))), rs.getInt(3), UUID.fromString(rs.getString(4)),
                            instant(rs, 5)));
                }
            }
        });
    }

    public Optional<Plot> plot(ChunkKey chunk) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT town, resident FROM town_plots WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
                bindChunk(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new Plot(chunk, UUID.fromString(rs.getString(1)),
                            UUID.fromString(rs.getString(2)))) : Optional.empty();
                }
            }
        });
    }

    public Optional<Town> town(UUID id) throws SQLException {
        return db.withConnection(c -> loadTowns(c).stream().filter(t -> t.id().equals(id)).findFirst());
    }

    // ------------------------------------------------------------------ helpers

    private static void bindChunk(PreparedStatement ps, int at, ChunkKey k) throws SQLException {
        ps.setString(at, k.world());
        ps.setInt(at + 1, k.x());
        ps.setInt(at + 2, k.z());
    }

    private static void bindOwner(PreparedStatement ps, int at, Owner o) throws SQLException {
        ps.setString(at, o.type().name());
        ps.setString(at + 1, o.id().toString());
    }

    static LocalDateTime ts(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        return rs.getObject(col, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static Optional<Instant> optionalInstant(ResultSet rs, int col) throws SQLException {
        LocalDateTime t = rs.getObject(col, LocalDateTime.class);
        return t == null ? Optional.empty() : Optional.of(t.toInstant(ZoneOffset.UTC));
    }

    private static boolean isDuplicate(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null && s.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }
}
