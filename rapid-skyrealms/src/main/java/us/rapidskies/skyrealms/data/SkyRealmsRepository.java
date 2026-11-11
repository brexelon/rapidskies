package us.rapidskies.skyrealms.data;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.skyrealms.SkyRealmsSettings;
import us.rapidskies.skyrealms.route.Route;
import us.rapidskies.skyrealms.shop.ShopPricing;

/**
 * Every table of migration module {@code skyrealms}. Every method blocks: call it off the main
 * thread. Methods that move Coins take the Coins {@link Ledger} and change balances inside their
 * own transaction, so the money and the row it pays for commit together.
 */
public final class SkyRealmsRepository {

    private final Database db;

    public SkyRealmsRepository(Database db) {
        this.db = db;
    }

    // ================================================================== Realm Tiers

    /** One stored progress count. As a delta, {@code progress} is added. */
    public record Progress(UUID island, String tier, String goal, long progress) {
    }

    /** Every island's tier (islands without a row are on the first tier). */
    public Map<UUID, String> tiers() throws SQLException {
        return db.withConnection(c -> {
            Map<UUID, String> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT island, tier FROM skyrealms_islands");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }
            }
            return out;
        });
    }

    /** Every progress row. */
    public List<Progress> progress() throws SQLException {
        return db.withConnection(c -> {
            List<Progress> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT island, tier, goal, progress FROM skyrealms_tier_progress");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Progress(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                            rs.getLong(4)));
                }
            }
            return out;
        });
    }

    /** Adds every delta in one batch. */
    public void addProgress(List<Progress> deltas) throws SQLException {
        if (deltas.isEmpty()) {
            return;
        }
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO skyrealms_tier_progress (island, tier, goal, "
                    + "progress) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE progress = progress + VALUES(progress)")) {
                for (Progress d : deltas) {
                    ps.setString(1, d.island().toString());
                    ps.setString(2, d.tier());
                    ps.setString(3, d.goal());
                    ps.setLong(4, d.progress());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Gives an island its row on {@code tier} if it has none (a new island, or one from before rapid-skyrealms). */
    public void ensureIsland(UUID island, String tier, Instant at) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO skyrealms_islands (island, tier, tier_since) VALUES (?, ?, ?)")) {
                ps.setString(1, island.toString());
                ps.setString(2, tier);
                ps.setObject(3, utc(at));
                return ps.executeUpdate();
            }
        });
    }

    public enum Ascend { OK, NOT_CURRENT, INSUFFICIENT }

    public record AscendResult(Ascend outcome, long balance) {
    }

    /**
     * Moves an island from {@code from} to {@code to} and charges {@code payer} {@code cost}
     * Coins, in one transaction: either both happen or neither. NOT_CURRENT if the island
     * isn't on {@code from} any more (two members clicked at once).
     */
    public AscendResult ascend(Ledger coins, UUID island, String from, String to, Instant at, UUID payer, long cost,
                               String source, String txn) throws SQLException {
        ensureIsland(island, from, at);
        coins.ensureAccounts(payer);
        return retry(() -> db.transaction(c -> {
            coins.lockIn(c, payer);
            try (PreparedStatement ps = c.prepareStatement("UPDATE skyrealms_islands SET tier = ?, tier_since = ? "
                    + "WHERE island = ? AND tier = ?")) {
                ps.setString(1, to);
                ps.setObject(2, utc(at));
                ps.setString(3, island.toString());
                ps.setString(4, from);
                if (ps.executeUpdate() != 1) {
                    return new AscendResult(Ascend.NOT_CURRENT, coins.balanceIn(c, payer));
                }
            }
            if (cost > 0 && !coins.debitIn(c, payer, cost, "realm:ascend", source, txn)) {
                c.rollback();
                return new AscendResult(Ascend.INSUFFICIENT, coins.balanceIn(c, payer));
            }
            return new AscendResult(Ascend.OK, coins.balanceIn(c, payer));
        }));
    }

    /** Sets an island's tier directly (staff). */
    public void setTier(UUID island, String tier, Instant at) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO skyrealms_islands (island, tier, tier_since) "
                    + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE tier = VALUES(tier), tier_since = VALUES(tier_since)")) {
                ps.setString(1, island.toString());
                ps.setString(2, tier);
                ps.setObject(3, utc(at));
                return ps.executeUpdate();
            }
        });
    }

    /** A disbanded island: its tier, progress and depot go (routes are ended separately). */
    public void forgetIsland(UUID island) throws SQLException {
        db.transaction(c -> {
            for (String table : List.of("skyrealms_islands", "skyrealms_tier_progress", "skyrealms_route_depot")) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE island = ?")) {
                    ps.setString(1, island.toString());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ================================================================== shop

    /** A sale's outcome: Coins paid (after the soft cap and Tailwind) and the balance afterwards. */
    public record Sale(long paid, long balance, int ratePercent) {
    }

    /**
     * Pays for goods worth {@code value} at full price, in one transaction with the day's
     * running total, so parallel sales can't both get the full rate past the threshold.
     * {@code txn} makes a retry pay once.
     */
    public Sale sell(Ledger coins, UUID player, LocalDate day, double value, SkyRealmsSettings.Shop cfg,
                     double tailwind, String source, String txn) throws SQLException {
        coins.ensureAccounts(player);
        return retry(() -> db.transaction(c -> {
            coins.lockIn(c, player);
            long earned = earnedIn(c, player, day);
            long paid = ShopPricing.payout(earned, value, cfg, tailwind);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO skyrealms_shop_daily (uuid, day, earned) "
                    + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE earned = earned + VALUES(earned)")) {
                ps.setString(1, player.toString());
                ps.setObject(2, day);
                ps.setLong(3, Math.round(value));
                ps.executeUpdate();
            }
            if (paid > 0) {
                coins.creditIn(c, player, paid, "shop:sell", source, txn);
            }
            return new Sale(paid, coins.balanceIn(c, player),
                    ShopPricing.ratePercent(earned + Math.round(value), cfg));
        }));
    }

    /** Full-price worth sold today. */
    public long earnedToday(UUID player, LocalDate day) throws SQLException {
        return db.withConnection(c -> earnedIn(c, player, day));
    }

    private static long earnedIn(Connection c, UUID player, LocalDate day) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT earned FROM skyrealms_shop_daily WHERE uuid = ? AND day = ?")) {
            ps.setString(1, player.toString());
            ps.setObject(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    // ================================================================== Trade Routes

    private static final String ROUTE_COLUMNS = "id, seller_island, buyer_island, seller_name, buyer_name, item, qty, "
            + "price, interval_hours, state, offered_by, offered_side, chest_world, chest_x, chest_y, chest_z, next_due, "
            + "deliveries, missed, created_at";

    /** Stores a new offer; returns its id. */
    public long insertRoute(Route r) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO skyrealms_trade_routes (seller_island, "
                    + "buyer_island, seller_name, buyer_name, item, qty, price, interval_hours, state, offered_by, "
                    + "offered_side, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, r.sellerIsland().toString());
                ps.setString(2, r.buyerIsland().toString());
                ps.setString(3, r.sellerName());
                ps.setString(4, r.buyerName());
                ps.setString(5, r.item().getKey().getKey());
                ps.setInt(6, r.qty());
                ps.setLong(7, r.price());
                ps.setInt(8, r.intervalHours());
                ps.setString(9, r.state().name());
                ps.setString(10, r.offeredBy().toString());
                ps.setString(11, r.offeredSide().name());
                ps.setObject(12, utc(r.createdAt()));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Every route that isn't ended. */
    public List<Route> liveRoutes() throws SQLException {
        return db.withConnection(c -> {
            List<Route> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + ROUTE_COLUMNS
                    + " FROM skyrealms_trade_routes WHERE state <> 'ENDED' ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Route r = route(rs);
                    if (r != null) {
                        out.add(r);
                    }
                }
            }
            return out;
        });
    }

    public Optional<Route> route(long id) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + ROUTE_COLUMNS
                    + " FROM skyrealms_trade_routes WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(route(rs)) : Optional.empty();
                }
            }
        });
    }

    private static Route route(ResultSet rs) throws SQLException {
        Material item = Material.matchMaterial(rs.getString(6));
        if (item == null) {
            return null; // an item this version doesn't have: left alone rather than guessed
        }
        String world = rs.getString(13);
        Optional<Route.ChestPos> chest = world == null ? Optional.empty()
                : Optional.of(new Route.ChestPos(world, rs.getInt(14), rs.getInt(15), rs.getInt(16)));
        return new Route(rs.getLong(1), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                rs.getString(4), rs.getString(5), item, rs.getInt(7), rs.getLong(8), rs.getInt(9),
                Route.State.valueOf(rs.getString(10)), UUID.fromString(rs.getString(11)),
                Route.Side.valueOf(rs.getString(12)), chest, instant(rs, 17), rs.getInt(18), rs.getInt(19),
                instant(rs, 20).orElse(Instant.EPOCH));
    }

    /** Accepts an offer; false if it isn't an open offer any more. */
    public boolean signRoute(long id, Instant at, Instant firstDue) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE skyrealms_trade_routes SET state = 'ACTIVE', "
                    + "signed_at = ?, next_due = ? WHERE id = ? AND state = 'OFFERED'")) {
                ps.setObject(1, utc(at));
                ps.setObject(2, utc(firstDue));
                ps.setLong(3, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Ends a route; false if it had already ended. */
    public boolean endRoute(long id, String reason, Instant at) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE skyrealms_trade_routes SET state = 'ENDED', "
                    + "ended_at = ?, end_reason = ? WHERE id = ? AND state <> 'ENDED'")) {
                ps.setObject(1, utc(at));
                ps.setString(2, reason);
                ps.setLong(3, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public void setChest(long id, Route.ChestPos pos) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE skyrealms_trade_routes SET chest_world = ?, "
                    + "chest_x = ?, chest_y = ?, chest_z = ? WHERE id = ?")) {
                ps.setString(1, pos.world());
                ps.setInt(2, pos.x());
                ps.setInt(3, pos.y());
                ps.setInt(4, pos.z());
                ps.setLong(5, id);
                return ps.executeUpdate();
            }
        });
    }

    public enum Delivery { DELIVERED, INSUFFICIENT, GONE }

    public record DeliveryResult(Delivery outcome, long payerBalance, long payeeBalance) {
    }

    /**
     * Delivery number {@code cycle} of a route, all or nothing: the delivery row (once per
     * cycle), the buyer's payment, the seller's income less {@code tax}, the goods into the buyer
     * island's depot, and the route's counters. GONE if the route ended or this cycle already
     * happened; INSUFFICIENT (nothing written) if {@code payer} can't pay. The goods have
     * already left the contract chest; the caller puts them back unless it's DELIVERED.
     */
    public DeliveryResult deliver(Ledger coins, Route r, int cycle, UUID payer, UUID payee, long tax, Instant at,
                                  Instant next, String source) throws SQLException {
        coins.ensureAccounts(payer, payee);
        String txn = "route:" + r.id() + ":" + cycle;
        return retry(() -> db.transaction(c -> {
            coins.lockIn(c, payer, payee);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT state, deliveries FROM skyrealms_trade_routes WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, r.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"ACTIVE".equals(rs.getString(1)) || rs.getInt(2) != cycle - 1) {
                        return new DeliveryResult(Delivery.GONE, 0, 0);
                    }
                }
            }
            if (!payer.equals(payee)) {
                if (!coins.debitIn(c, payer, r.price(), "route:pay", source, txn)) {
                    return new DeliveryResult(Delivery.INSUFFICIENT, coins.balanceIn(c, payer), 0);
                }
                long income = r.price() - tax;
                if (income > 0) {
                    coins.creditIn(c, payee, income, "route:income", source, txn + "/in");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO skyrealms_route_deliveries (route_id, cycle, "
                    + "qty, price, tax, delivered_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, r.id());
                ps.setInt(2, cycle);
                ps.setInt(3, r.qty());
                ps.setLong(4, r.price());
                ps.setLong(5, tax);
                ps.setObject(6, utc(at));
                ps.executeUpdate();
            }
            addToDepot(c, r.buyerIsland(), r.item().getKey().getKey(), r.qty());
            try (PreparedStatement ps = c.prepareStatement("UPDATE skyrealms_trade_routes SET deliveries = ?, "
                    + "missed = 0, next_due = ? WHERE id = ?")) {
                ps.setInt(1, cycle);
                ps.setObject(2, utc(next));
                ps.setLong(3, r.id());
                ps.executeUpdate();
            }
            return new DeliveryResult(Delivery.DELIVERED, coins.balanceIn(c, payer), coins.balanceIn(c, payee));
        }));
    }

    /** Counts a missed delivery and moves the route to its next date; returns the misses in a row. */
    public int recordMiss(long id, Instant next) throws SQLException {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE skyrealms_trade_routes SET missed = missed + 1, "
                    + "next_due = ? WHERE id = ? AND state = 'ACTIVE'")) {
                ps.setObject(1, utc(next));
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT missed FROM skyrealms_trade_routes WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** What waits in an island's depot, by item key, largest first. */
    public Map<String, Long> depot(UUID island) throws SQLException {
        return db.withConnection(c -> {
            Map<String, Long> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT item, amount FROM skyrealms_route_depot "
                    + "WHERE island = ? AND amount > 0 ORDER BY amount DESC, item")) {
                ps.setString(1, island.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return out;
        });
    }

    /** Takes {@code n} out of the depot if there are that many; false (nothing taken) otherwise. */
    public boolean takeFromDepot(UUID island, String item, long n) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE skyrealms_route_depot SET amount = amount - ? "
                    + "WHERE island = ? AND item = ? AND amount >= ?")) {
                ps.setLong(1, n);
                ps.setString(2, island.toString());
                ps.setString(3, item);
                ps.setLong(4, n);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Puts goods (back) into a depot. */
    public void addToDepot(UUID island, String item, long n) throws SQLException {
        db.withConnection(c -> {
            addToDepot(c, island, item, n);
            return null;
        });
    }

    private static void addToDepot(Connection c, UUID island, String item, long n) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO skyrealms_route_depot (island, item, amount) "
                + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)")) {
            ps.setString(1, island.toString());
            ps.setString(2, item);
            ps.setLong(3, n);
            ps.executeUpdate();
        }
    }

    // ================================================================== Expeditions

    public long startExpedition(String theme, Instant at) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO skyrealms_expeditions (theme, started_at) "
                    + "VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, theme);
                ps.setObject(2, utc(at));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** How many Expeditions have arrived so far (the theme rotation carries on from it). */
    public long expeditionCount() throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM skyrealms_expeditions");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    public void endExpedition(long id, Instant at, int contributors, long coins, boolean heart) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE skyrealms_expeditions SET ended_at = ?, "
                    + "contributors = ?, coins_paid = ?, heart_broken = ? WHERE id = ?")) {
                ps.setObject(1, utc(at));
                ps.setInt(2, contributors);
                ps.setLong(3, coins);
                ps.setBoolean(4, heart);
                ps.setLong(5, id);
                return ps.executeUpdate();
            }
        });
    }

    // ================================================================== seasons

    public record Season(int id, Instant startedAt, Instant endsAt) {
    }

    /** The season that hasn't ended, if any. */
    public Optional<Season> openSeason() throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, started_at, ends_at FROM skyrealms_seasons "
                    + "WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Season(rs.getInt(1), instant(rs, 2).orElseThrow(),
                        instant(rs, 3).orElseThrow())) : Optional.empty();
            }
        });
    }

    /** The highest season number used so far (0 before the first). */
    public int lastSeasonId() throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM skyrealms_seasons");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    /** Opens a season with every island's starting worth. Fails if the number is taken. */
    public void startSeason(Season s, Map<UUID, BigDecimal> baselines) throws SQLException {
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO skyrealms_seasons (id, started_at, ends_at) VALUES (?, ?, ?)")) {
                ps.setInt(1, s.id());
                ps.setObject(2, utc(s.startedAt()));
                ps.setObject(3, utc(s.endsAt()));
                ps.executeUpdate();
            }
            insertBaselines(c, s.id(), baselines);
            return null;
        });
    }

    public Map<UUID, BigDecimal> baselines(int season) throws SQLException {
        return db.withConnection(c -> {
            Map<UUID, BigDecimal> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT island, worth FROM skyrealms_season_baseline WHERE season = ?")) {
                ps.setInt(1, season);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(UUID.fromString(rs.getString(1)), rs.getBigDecimal(2));
                    }
                }
            }
            return out;
        });
    }

    /** A baseline for an island the season didn't know (created since); an existing one is kept. */
    public void addBaseline(int season, UUID island, BigDecimal worth) throws SQLException {
        db.withConnection(c -> {
            insertBaselines(c, season, Map.of(island, worth));
            return null;
        });
    }

    private static void insertBaselines(Connection c, int season, Map<UUID, BigDecimal> baselines)
            throws SQLException {
        if (baselines.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO skyrealms_season_baseline (season, island, worth) VALUES (?, ?, ?)")) {
            for (Map.Entry<UUID, BigDecimal> e : baselines.entrySet()) {
                ps.setInt(1, season);
                ps.setString(2, e.getKey().toString());
                ps.setBigDecimal(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** One member's season award. */
    public record AwardRow(int season, UUID player, UUID island, String islandName, int rank, BigDecimal gained,
                           String cosmetic, long bolts) {
    }

    /** Ends a season and records its awards, once: false if it had already ended. */
    public boolean closeSeason(int season, Instant at, List<AwardRow> awards) throws SQLException {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE skyrealms_seasons SET ended_at = ? WHERE id = ? AND ended_at IS NULL")) {
                ps.setObject(1, utc(at));
                ps.setInt(2, season);
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO skyrealms_season_awards (season, uuid, island, "
                    + "island_name, island_rank, gained, cosmetic, bolts, awarded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (AwardRow a : awards) {
                    ps.setInt(1, a.season());
                    ps.setString(2, a.player().toString());
                    ps.setString(3, a.island().toString());
                    ps.setString(4, a.islandName());
                    ps.setInt(5, a.rank());
                    ps.setBigDecimal(6, a.gained());
                    ps.setString(7, a.cosmetic());
                    ps.setLong(8, a.bolts());
                    ps.setObject(9, utc(at));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // The cosmetics engine owns them from here (PLAN.md §6.1), in the same transaction.
            for (AwardRow a : awards) {
                if (a.cosmetic() != null && !a.cosmetic().isBlank()) {
                    us.rapidskies.common.cosmetic.CosmeticRepository.grantIn(c, a.player(), a.cosmetic(),
                            "season:" + a.season());
                }
            }
            return true;
        });
    }

    public List<AwardRow> awards(int season) throws SQLException {
        return db.withConnection(c -> {
            List<AwardRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT season, uuid, island, island_name, island_rank, "
                    + "gained, cosmetic, bolts FROM skyrealms_season_awards WHERE season = ? ORDER BY island_rank, uuid")) {
                ps.setInt(1, season);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new AwardRow(rs.getInt(1), UUID.fromString(rs.getString(2)),
                                UUID.fromString(rs.getString(3)), rs.getString(4), rs.getInt(5), rs.getBigDecimal(6),
                                rs.getString(7), rs.getLong(8)));
                    }
                }
            }
            return out;
        });
    }

    // ================================================================== helpers

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static Optional<Instant> instant(ResultSet rs, int column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? Optional.empty() : Optional.of(t.toInstant(ZoneOffset.UTC));
    }

    /** Retries a transaction InnoDB rolled back as a deadlock victim (SQLState 40001). */
    private static <T> T retry(Database.SqlSupplier<T> work) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (SQLException e) {
                if (attempt >= 3 || !"40001".equals(e.getSQLState())) {
                    throw e;
                }
            }
        }
    }
}
