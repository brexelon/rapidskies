package us.rapidskies.common.shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongFunction;
import us.rapidskies.common.cosmetic.CosmeticRepository;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;

/**
 * Bolt shop purchases ({@code shop_purchases}, PLAN.md §6.2). Each purchase is one
 * transaction: the player's Bolts balance row is locked, ownership is checked, the guarded
 * debit runs, the purchase row is written and, for cosmetics and the Season Pass, the thing
 * bought is delivered. Two clicks, or two servers, can never charge twice for one thing or
 * deliver without charging.
 *
 * <p>A rank pass is the exception: LuckPerms lives behind the proxy's {@code /grant}, so the
 * purchase is written {@link State#PENDING} with the debit, and the proxy grants it and marks
 * it delivered ({@link #pendingRankPasses}, {@link #markDelivered}), retrying until it has.
 *
 * <p>Nothing but {@link Tag#COSMETIC} and {@link Tag#CONVENIENCE} can be sold (PLAN.md §8):
 * there is no other tag to give an item. Every method blocks.
 */
public final class ShopRepository {

    /** What a purchase is. */
    public enum Kind {
        COSMETIC, RANK_PASS, SEASON_PASS
    }

    /**
     * PLAN.md §8: everything sold for Bolts is cosmetic or pure convenience. The enum has no
     * third value on purpose: a config naming anything else is refused when it loads.
     */
    public enum Tag {
        COSMETIC, CONVENIENCE;

        public static Tag parse(String s) {
            return switch (s == null ? "" : s.trim().toUpperCase(Locale.ROOT)) {
                case "COSMETIC" -> COSMETIC;
                case "CONVENIENCE" -> CONVENIENCE;
                default -> throw new IllegalArgumentException("'" + s + "' isn't sellable: shop items are tagged "
                        + "COSMETIC or CONVENIENCE (PLAN.md §8)");
            };
        }
    }

    public enum State {
        DELIVERED, PENDING
    }

    /** What's being sold and for how much. {@code item} is the shop item id. */
    public record Sale(String item, Kind kind, Tag tag, long price, boolean featured) {
        public Sale {
            if (price < 0) {
                throw new IllegalArgumentException("negative price");
            }
        }
    }

    public sealed interface Result {
        record Bought(long purchaseId, long balance) implements Result {
        }

        record AlreadyOwned() implements Result {
        }

        record Insufficient(long balance) implements Result {
        }
    }

    public record Purchase(long id, UUID player, String item, Kind kind, Tag tag, long price, boolean featured,
                           String txnId, State state, Long grantId, Instant createdAt) {
    }

    private final Database db;
    private final Ledger bolts;

    public ShopRepository(Database db, Ledger bolts) {
        this.db = db;
        this.bolts = bolts;
    }

    /** Buys a cosmetic for keeps. */
    public Result buyCosmetic(UUID player, Sale sale, String cosmetic, String source) throws SQLException {
        return purchase(player, sale, State.DELIVERED, c -> CosmeticRepository.ownsIn(c, player, cosmetic),
                (c, id) -> CosmeticRepository.grantIn(c, player, cosmetic, source));
    }

    /** Buys a rank pass; the proxy grants it ({@link State#PENDING} until then). */
    public Result buyRankPass(UUID player, Sale sale) throws SQLException {
        return purchase(player, sale, State.PENDING, c -> false, (c, id) -> {
        });
    }

    /**
     * Buys a season's premium track. {@code rewardsForXp} gives the premium cosmetics already
     * earned with the season XP the player has, which are granted at once.
     */
    public Result buySeasonPass(UUID player, Sale sale, String season, LongFunction<Collection<String>> rewardsForXp)
            throws SQLException {
        return purchase(player, sale, State.DELIVERED, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT premium FROM season_progress WHERE uuid = ? AND season = ? FOR UPDATE")) {
                ps.setString(1, player.toString());
                ps.setString(2, season);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        }, (c, id) -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO season_progress (uuid, season, premium,"
                    + " premium_at) VALUES (?, ?, TRUE, CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE premium = TRUE,"
                    + " premium_at = CURRENT_TIMESTAMP(3)")) {
                ps.setString(1, player.toString());
                ps.setString(2, season);
                ps.executeUpdate();
            }
            long xp;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT xp FROM season_progress WHERE uuid = ? AND season = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, season);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    xp = rs.getLong(1);
                }
            }
            for (String cosmetic : rewardsForXp.apply(xp)) {
                CosmeticRepository.grantIn(c, player, cosmetic, "pass:" + season);
            }
        });
    }

    @FunctionalInterface
    private interface Check {
        boolean owned(Connection c) throws SQLException;
    }

    @FunctionalInterface
    private interface Delivery {
        void deliver(Connection c, long purchaseId) throws SQLException;
    }

    private Result purchase(UUID player, Sale sale, State state, Check owned, Delivery delivery) throws SQLException {
        bolts.ensureAccounts(player);
        String txn = "shop:" + UUID.randomUUID();
        for (int attempt = 1; ; attempt++) {
            try {
                return db.transaction(c -> {
                    bolts.lockIn(c, player);
                    if (owned.owned(c)) {
                        return new Result.AlreadyOwned();
                    }
                    if (sale.price() > 0 && !bolts.debitIn(c, player, sale.price(), "shop:" + sale.item(), "shop", txn)) {
                        return new Result.Insufficient(bolts.balanceIn(c, player));
                    }
                    long id = insert(c, player, sale, txn, state);
                    delivery.deliver(c, id);
                    return new Result.Bought(id, bolts.balanceIn(c, player));
                });
            } catch (SQLException e) {
                if (attempt >= 3 || !"40001".equals(e.getSQLState())) {
                    throw e;
                }
            }
        }
    }

    private static long insert(Connection c, UUID player, Sale sale, String txn, State state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO shop_purchases (uuid, item, kind, tag, price,"
                + " featured, txn_id, state, delivered_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, player.toString());
            ps.setString(2, sale.item());
            ps.setString(3, sale.kind().name());
            ps.setString(4, sale.tag().name());
            ps.setLong(5, sale.price());
            ps.setBoolean(6, sale.featured());
            ps.setString(7, txn);
            ps.setString(8, state.name());
            ps.setObject(9, state == State.DELIVERED ? LocalDateTime.now(ZoneOffset.UTC) : null);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Rank passes bought but not yet granted, oldest first. */
    public List<Purchase> pendingRankPasses() throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM shop_purchases"
                    + " WHERE state = 'PENDING' AND kind = 'RANK_PASS' ORDER BY id LIMIT 100")) {
                return all(ps);
            }
        });
    }

    /** True if it was still pending (so this call delivered it). */
    public boolean markDelivered(long purchaseId, long grantId) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE shop_purchases SET state = 'DELIVERED',"
                    + " grant_id = ?, delivered_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND state = 'PENDING'")) {
                ps.setLong(1, grantId);
                ps.setLong(2, purchaseId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** A player's purchases, newest first. */
    public List<Purchase> recent(UUID player, int limit) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM shop_purchases"
                    + " WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, player.toString());
                ps.setInt(2, limit);
                return all(ps);
            }
        });
    }

    private static final String COLUMNS =
            "id, uuid, item, kind, tag, price, featured, txn_id, state, grant_id, created_at";

    private static List<Purchase> all(PreparedStatement ps) throws SQLException {
        List<Purchase> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long grant = rs.getLong("grant_id");
                Long grantId = rs.wasNull() ? null : grant;
                out.add(new Purchase(rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("item"),
                        Kind.valueOf(rs.getString("kind")), Tag.parse(rs.getString("tag")), rs.getLong("price"),
                        rs.getBoolean("featured"), rs.getString("txn_id"), State.valueOf(rs.getString("state")),
                        grantId, rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)));
            }
        }
        return out;
    }
}
