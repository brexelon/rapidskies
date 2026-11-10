package us.rapidskies.market.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.market.model.AlertRule;
import us.rapidskies.market.model.Category;
import us.rapidskies.market.model.Fees;
import us.rapidskies.market.model.Listing;
import us.rapidskies.market.model.Notice;
import us.rapidskies.market.model.Sort;
import us.rapidskies.market.model.Stored;

/**
 * Every auction house statement (PLAN.md §4.2), for one gamemode's listings. Nothing here
 * touches Bukkit: items are the bytes Paper serialized, so it's all tested against a real
 * MariaDB ({@code MarketRepositoryIT}), including parallel buyers and crashes mid-way.
 *
 * <p>An item is always in exactly one place: an inventory, an ACTIVE listing, or a
 * collection bin row. Each move between a listing and a bin, and each sale with its money,
 * is one transaction:
 * <ul>
 *   <li>Listing charges the fee and inserts the listing together, under a lock on the
 *       seller's balance row, so the active-listing cap can't be raced past either.</li>
 *   <li>Buying is the conditional update {@code ACTIVE → SOLD}, which must change exactly one
 *       row, then the buyer's guarded debit, the seller's credit, the item into the buyer's
 *       bin, the price history and the seller's notice. If any step fails, none happened:
 *       two buyers on two servers can never both win, and nobody pays for nothing.</li>
 *   <li>Cancelling, expiring and staff removal move the item to the seller's bin.</li>
 * </ul>
 * Collection rows carry a unique {@code token}, so a retried or recovered move can't put
 * the same item in a bin twice.
 *
 * <p>Blocking: call from async contexts only.
 */
public final class MarketRepository {

    private static final String COLUMNS = "id, mode, seller, seller_name, item_blob, item_name, item_key, category, "
            + "amount, price, created_at, expires_at, status, buyer, buyer_name, sold_at";

    private final Database db;
    private final Ledger ledger;
    private final String mode;
    private final String source;
    private final int historyDays;

    /** Test hook: runs at named points inside transactions; a test throws from it to simulate a crash. */
    volatile Checkpoint checkpoint = point -> {
    };

    @FunctionalInterface
    interface Checkpoint {
        void reached(String point) throws SQLException;
    }

    /**
     * @param ledger      this mode's currency ({@code Ledger.mode})
     * @param source      this server's id, for ledger rows
     * @param historyDays days the average price is taken over
     */
    public MarketRepository(Database db, Ledger ledger, EconomyMode mode, String source, int historyDays) {
        this.db = db;
        this.ledger = ledger;
        this.mode = mode.id();
        this.source = source;
        this.historyDays = historyDays;
    }

    // ------------------------------------------------------------------ listing

    /**
     * A listing about to be made. {@code token} is the custody journal entry the item is
     * held under while this runs; {@code fee} is charged whatever happens to the listing.
     */
    public record NewListing(UUID token, UUID seller, String sellerName, byte[] item, String itemName,
                             String itemKey, Category category, int amount, long price, long fee,
                             Duration duration) {
    }

    public sealed interface Created {
        /** Listed and the fee charged; {@code balance} is the seller's afterwards. */
        record Listed(long id, long balance, Instant expiresAt) implements Created {
        }

        /** The seller already has {@code active} listings, the most they may have. */
        record TooMany(int active) implements Created {
        }

        /** The seller can't pay the fee. */
        record CantAfford(long balance) implements Created {
        }

        /** This token was already listed (a retry after a lost commit). */
        record AlreadyListed(long id) implements Created {
        }
    }

    public Created create(NewListing n, int maxActive) throws SQLException {
        ledger.ensureAccounts(n.seller());
        return retrying(() -> db.transaction(c -> {
            // The seller's balance row is locked before anything is read: two listings by the
            // same seller queue here. The count is a locking read too, so it sees what the one
            // before committed; a plain read would see the snapshot from this transaction's
            // first read (REPEATABLE READ) and let both through the cap.
            ledger.lockIn(c, n.seller());
            Optional<Long> existing = listingIdByToken(c, n.token());
            if (existing.isPresent()) {
                return new Created.AlreadyListed(existing.get());
            }
            int active = activeCount(c, n.seller(), true);
            if (active >= maxActive) {
                return new Created.TooMany(active);
            }
            if (!ledger.debitIn(c, n.seller(), n.fee(), "market:fee", source, "market:fee:" + n.token())) {
                return new Created.CantAfford(ledger.balanceIn(c, n.seller()));
            }
            checkpoint.reached("create:charged");
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO market_listings (token, mode, seller, "
                    + "seller_name, item_blob, item_name, item_key, category, amount, price, fee, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3) + INTERVAL ? SECOND)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, n.token().toString());
                ps.setString(2, mode);
                ps.setString(3, n.seller().toString());
                ps.setString(4, truncate(n.sellerName(), 16));
                ps.setBytes(5, n.item());
                ps.setString(6, truncate(n.itemName(), 128));
                ps.setString(7, truncate(n.itemKey(), 128));
                ps.setString(8, n.category().id());
                ps.setInt(9, n.amount());
                ps.setLong(10, n.price());
                ps.setLong(11, n.fee());
                ps.setLong(12, n.duration().toSeconds());
                ps.executeUpdate();
                id = generatedKey(ps);
            }
            checkpoint.reached("create:inserted");
            Instant expires;
            try (PreparedStatement ps = c.prepareStatement("SELECT expires_at FROM market_listings WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    expires = instant(rs, 1);
                }
            }
            return new Created.Listed(id, ledger.balanceIn(c, n.seller()), expires);
        }));
    }

    // ------------------------------------------------------------------ buying

    public sealed interface Bought {
        /**
         * Bought. The item is in the buyer's bin as {@code storedId} until it's delivered;
         * {@code noticeId} is the seller's notice; {@code flagged} if the sale looked like
         * an alt transfer ({@link AlertRule}).
         */
        record Done(Listing listing, long storedId, long buyerBalance, long sellerBalance, long tax,
                    long noticeId, boolean flagged) implements Bought {
        }

        /** Sold to someone else, cancelled, expired, or never existed. Nothing changed. */
        record Gone() implements Bought {
        }

        /** The buyer can't pay. Nothing changed. */
        record Insufficient(long balance) implements Bought {
        }

        /** Sellers can't buy their own listings. */
        record OwnListing() implements Bought {
        }
    }

    public Bought buy(long id, UUID buyer, String buyerName, Fees fees, AlertRule alerts) throws SQLException {
        Optional<Listing> found = find(id);
        if (found.isEmpty() || found.get().status() != Listing.Status.ACTIVE) {
            return new Bought.Gone();
        }
        Listing l = found.get();
        if (l.seller().equals(buyer)) {
            return new Bought.OwnListing();
        }
        long tax = fees.tax(l.price());
        long proceeds = l.price() - tax;
        ledger.ensureAccounts(buyer, l.seller());
        try {
            return retrying(() -> db.transaction(c -> {
                // Balances first, in uuid order (as every other money transaction here), then
                // the listing: two players buying each other's listings can't deadlock.
                ledger.lockIn(c, buyer, l.seller());
                try (PreparedStatement ps = c.prepareStatement("UPDATE market_listings SET status = 'SOLD', "
                        + "buyer = ?, buyer_name = ?, sold_at = NOW(3), tax = ? "
                        + "WHERE id = ? AND status = 'ACTIVE' AND expires_at > NOW(3)")) {
                    ps.setString(1, buyer.toString());
                    ps.setString(2, truncate(buyerName, 16));
                    ps.setLong(3, tax);
                    ps.setLong(4, id);
                    if (ps.executeUpdate() != 1) {
                        return new Bought.Gone();
                    }
                }
                checkpoint.reached("buy:claimed");
                if (!ledger.debitIn(c, buyer, l.price(), "market:buy", source, "market:buy:" + id)) {
                    // Undoes the SOLD above.
                    throw new Abort(new Bought.Insufficient(ledger.balanceIn(c, buyer)));
                }
                checkpoint.reached("buy:charged");
                ledger.creditIn(c, l.seller(), proceeds, "market:sale", source, "market:sale:" + id);
                boolean flagged = alerts.suspicious(l.price(), l.amount(), averageUnitPrice(c, l.itemKey()));
                if (flagged) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE market_listings SET flagged = 1 WHERE id = ?")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                }
                recordSale(c, l.itemKey(), l.price(), l.amount());
                long stored = insertStored(c, "listing:" + id, buyer, l.item(), l.itemName(), l.amount(),
                        Stored.Reason.BOUGHT, id);
                long notice = insertNotice(c, l.seller(), Notice.Kind.SOLD, l.itemName(), l.amount(), l.price(),
                        proceeds, buyerName);
                checkpoint.reached("buy:stored");
                return new Bought.Done(l, stored, ledger.balanceIn(c, buyer), ledger.balanceIn(c, l.seller()), tax,
                        notice, flagged);
            }));
        } catch (Abort a) {
            return (Bought) a.result;
        }
    }

    // ------------------------------------------------------------------ cancelling, expiring, removal

    public sealed interface Closed {
        /** The item is in the seller's bin as {@code storedId}; {@code noticeId} is 0 if none was left. */
        record Done(Listing listing, long storedId, long noticeId) implements Closed {
        }

        record NotFound() implements Closed {
        }

        record NotYours() implements Closed {
        }

        /** Already sold, expired or taken down. */
        record NotActive(Listing.Status status) implements Closed {
        }
    }

    /** The seller takes their listing down; the item goes to their bin. The fee isn't refunded. */
    public Closed cancel(long id, UUID seller) throws SQLException {
        return close(id, seller, null, Listing.Status.CANCELLED);
    }

    /** Staff take a listing down (PLAN.md §4.2, {@code /ah admin}); the item goes back to the seller's bin. */
    public Closed remove(long id, UUID staff) throws SQLException {
        return close(id, null, staff, Listing.Status.REMOVED);
    }

    /**
     * Expires up to {@code limit} listings whose time is up, each in its own transaction.
     * Safe to run on every server of the mode at once: each listing expires once.
     */
    public List<Closed.Done> expireDue(int limit) throws SQLException {
        List<Long> ids = new ArrayList<>();
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM market_listings WHERE mode = ? "
                    + "AND status = 'ACTIVE' AND expires_at <= NOW(3) ORDER BY expires_at LIMIT ?")) {
                ps.setString(1, mode);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            return null;
        });
        List<Closed.Done> out = new ArrayList<>();
        for (long id : ids) {
            if (close(id, null, null, Listing.Status.EXPIRED) instanceof Closed.Done d) {
                out.add(d);
            }
        }
        return out;
    }

    private Closed close(long id, UUID seller, UUID staff, Listing.Status to) throws SQLException {
        return retrying(() -> db.transaction(c -> {
            Listing l;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM market_listings WHERE id = ? AND mode = ? FOR UPDATE")) {
                ps.setLong(1, id);
                ps.setString(2, mode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new Closed.NotFound();
                    }
                    l = listing(rs);
                }
            }
            if (seller != null && !seller.equals(l.seller())) {
                return new Closed.NotYours();
            }
            if (l.status() != Listing.Status.ACTIVE) {
                return new Closed.NotActive(l.status());
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE market_listings SET status = ?, "
                    + "closed_at = NOW(3), closed_by = ? WHERE id = ? AND status = 'ACTIVE'"
                    + (to == Listing.Status.EXPIRED ? " AND expires_at <= NOW(3)" : ""))) {
                ps.setString(1, to.name());
                ps.setString(2, staff == null ? null : staff.toString());
                ps.setLong(3, id);
                if (ps.executeUpdate() != 1) {
                    return new Closed.NotActive(l.status());
                }
            }
            checkpoint.reached("close:closed");
            Stored.Reason reason = switch (to) {
                case EXPIRED -> Stored.Reason.EXPIRED;
                case REMOVED -> Stored.Reason.REMOVED;
                default -> Stored.Reason.CANCELLED;
            };
            long stored = insertStored(c, "listing:" + id, l.seller(), l.item(), l.itemName(), l.amount(), reason, id);
            long notice = 0;
            if (to != Listing.Status.CANCELLED) {
                notice = insertNotice(c, l.seller(), to == Listing.Status.EXPIRED ? Notice.Kind.EXPIRED
                        : Notice.Kind.REMOVED, l.itemName(), l.amount(), l.price(), 0, null);
            }
            return new Closed.Done(l, stored, notice);
        }));
    }

    // ------------------------------------------------------------------ reading listings

    public record Page(List<Listing> listings, int total) {
    }

    /**
     * Active listings for the browse menu. {@code category}, {@code search} (item name, item
     * key or seller name, case-insensitive) and {@code seller} are optional filters.
     */
    public Page browse(Category category, Sort sort, String search, UUID seller, int offset, int limit)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE mode = ? AND status = 'ACTIVE' AND expires_at > NOW(3)");
        List<Object> args = new ArrayList<>();
        args.add(mode);
        if (category != null) {
            where.append(" AND category = ?");
            args.add(category.id());
        }
        if (seller != null) {
            where.append(" AND seller = ?");
            args.add(seller.toString());
        }
        if (search != null && !search.isBlank()) {
            String like = like(search.trim());
            where.append(" AND (item_name LIKE ? OR item_key LIKE ? OR seller_name LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return db.withConnection(c -> {
            int total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM market_listings" + where)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            List<Listing> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM market_listings" + where
                    + " ORDER BY " + sort.orderBy() + " LIMIT ? OFFSET ?")) {
                int i = bind(ps, args);
                ps.setInt(i++, limit);
                ps.setInt(i, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(listing(rs));
                    }
                }
            }
            return new Page(out, total);
        });
    }

    public Optional<Listing> find(long id) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM market_listings WHERE id = ? AND mode = ?")) {
                ps.setLong(1, id);
                ps.setString(2, mode);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(listing(rs)) : Optional.empty();
                }
            }
        });
    }

    /** A seller's listings that are still up (expired ones included until they're processed). */
    public List<Listing> activeOf(UUID seller) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM market_listings "
                    + "WHERE mode = ? AND seller = ? AND status = 'ACTIVE' ORDER BY expires_at")) {
                ps.setString(1, mode);
                ps.setString(2, seller.toString());
                List<Listing> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(listing(rs));
                    }
                }
                return out;
            }
        });
    }

    public int activeCount(UUID seller) throws SQLException {
        return db.withConnection(c -> activeCount(c, seller, false));
    }

    private int activeCount(Connection c, UUID seller, boolean current) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM market_listings "
                + "WHERE mode = ? AND seller = ? AND status = 'ACTIVE' AND expires_at > NOW(3)"
                + (current ? " LOCK IN SHARE MODE" : ""))) {
            ps.setString(1, mode);
            ps.setString(2, seller.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The custody journal's check: did the listing with this token go through? */
    public Optional<Long> listingIdByToken(UUID token) throws SQLException {
        return db.withConnection(c -> listingIdByToken(c, token));
    }

    private static Optional<Long> listingIdByToken(Connection c, UUID token) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM market_listings WHERE token = ?")) {
            ps.setString(1, token.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** A player's sales and purchases here, newest first ({@code /ah admin history}). */
    public List<Listing> history(UUID player, int offset, int limit) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM market_listings "
                    + "WHERE mode = ? AND (seller = ? OR buyer = ?) ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, mode);
                ps.setString(2, player.toString());
                ps.setString(3, player.toString());
                ps.setInt(4, limit);
                ps.setInt(5, Math.max(0, offset));
                List<Listing> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(listing(rs));
                    }
                }
                return out;
            }
        });
    }

    /** Sales from {@code seller} to {@code buyer} flagged as far below average in the last {@code days} days. */
    public int flaggedBetween(UUID seller, UUID buyer, int days) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM market_listings WHERE mode = ? "
                    + "AND seller = ? AND buyer = ? AND status = 'SOLD' AND flagged = 1 "
                    + "AND sold_at >= NOW(3) - INTERVAL ? DAY")) {
                ps.setString(1, mode);
                ps.setString(2, seller.toString());
                ps.setString(3, buyer.toString());
                ps.setInt(4, days);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    // ------------------------------------------------------------------ price history

    /** The average price of one {@code itemKey} over the last history days, or empty if none sold. */
    public OptionalDouble averageUnitPrice(String itemKey) throws SQLException {
        return db.withConnection(c -> averageUnitPrice(c, itemKey));
    }

    private OptionalDouble averageUnitPrice(Connection c, String itemKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT SUM(total), SUM(volume) FROM market_price_history "
                + "WHERE mode = ? AND item_key = ? AND day > UTC_DATE() - INTERVAL ? DAY")) {
            ps.setString(1, mode);
            ps.setString(2, truncate(itemKey, 128));
            ps.setInt(3, historyDays);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long volume = rs.getLong(2);
                return volume <= 0 ? OptionalDouble.empty() : OptionalDouble.of((double) rs.getLong(1) / volume);
            }
        }
    }

    private void recordSale(Connection c, String itemKey, long price, int amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO market_price_history "
                + "(mode, item_key, day, total, volume, sales) VALUES (?, ?, UTC_DATE(), ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE total = total + VALUES(total), volume = volume + VALUES(volume), "
                + "sales = sales + 1")) {
            ps.setString(1, mode);
            ps.setString(2, truncate(itemKey, 128));
            ps.setLong(3, price);
            ps.setLong(4, amount);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ collection bins

    public List<Stored> collection(UUID owner, int offset, int limit) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, mode, uuid, item_blob, item_name, amount, "
                    + "reason, listing_id, created_at FROM market_collection WHERE mode = ? AND uuid = ? "
                    + "ORDER BY id LIMIT ? OFFSET ?")) {
                ps.setString(1, mode);
                ps.setString(2, owner.toString());
                ps.setInt(3, limit);
                ps.setInt(4, Math.max(0, offset));
                List<Stored> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long listing = rs.getLong(8);
                        out.add(new Stored(rs.getLong(1), rs.getString(2), UUID.fromString(rs.getString(3)),
                                rs.getBytes(4), rs.getString(5), rs.getInt(6), Stored.Reason.valueOf(rs.getString(7)),
                                rs.wasNull() ? null : listing, instant(rs, 9)));
                    }
                }
                return out;
            }
        });
    }

    public int collectionCount(UUID owner) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM market_collection WHERE mode = ? AND uuid = ?")) {
                ps.setString(1, mode);
                ps.setString(2, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /**
     * Takes a bin row out for delivery to the inventory. Only one caller can get true for a
     * row, so a double click or two servers can't hand the same item out twice.
     */
    public boolean take(long storedId, UUID owner) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM market_collection WHERE id = ? AND uuid = ? AND mode = ?")) {
                ps.setLong(1, storedId);
                ps.setString(2, owner.toString());
                ps.setString(3, mode);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public boolean collectionExists(long storedId) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM market_collection WHERE id = ?")) {
                ps.setLong(1, storedId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Puts an item in {@code owner}'s bin under {@code token}, once: a second call with the
     * same token changes nothing and returns the first row's id (or 0 if that row has since
     * been collected).
     */
    public long store(String token, UUID owner, byte[] item, String itemName, int amount, Stored.Reason reason,
                      Long listingId) throws SQLException {
        return db.withConnection(c -> {
            long id = insertStored(c, token, owner, item, itemName, amount, reason, listingId);
            if (id != 0) {
                return id;
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM market_collection WHERE token = ?")) {
                ps.setString(1, token);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** INSERT IGNORE on the unique token; 0 if the token was already used. */
    private long insertStored(Connection c, String token, UUID owner, byte[] item, String itemName, int amount,
                              Stored.Reason reason, Long listingId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO market_collection (token, mode, uuid, "
                + "item_blob, item_name, amount, reason, listing_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, truncate(token, 64));
            ps.setString(2, mode);
            ps.setString(3, owner.toString());
            ps.setBytes(4, item);
            ps.setString(5, truncate(itemName, 128));
            ps.setInt(6, amount);
            ps.setString(7, reason.name());
            if (listingId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, listingId);
            }
            if (ps.executeUpdate() != 1) {
                return 0;
            }
            return generatedKey(ps);
        }
    }

    // ------------------------------------------------------------------ notices

    private long insertNotice(Connection c, UUID player, Notice.Kind kind, String itemName, int amount, long price,
                              long proceeds, String otherName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO market_notices (uuid, mode, kind, item_name, "
                + "amount, price, proceeds, other_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, player.toString());
            ps.setString(2, mode);
            ps.setString(3, kind.name());
            ps.setString(4, truncate(itemName, 128));
            ps.setInt(5, amount);
            ps.setLong(6, price);
            ps.setLong(7, proceeds);
            ps.setString(8, otherName == null ? null : truncate(otherName, 16));
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    /** Takes one notice to show it; empty if another server already did. Any mode. */
    public Optional<Notice> takeNotice(long id) throws SQLException {
        return db.transaction(c -> {
            List<Notice> found = notices(c, "id = ?", id);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            delete(c, "DELETE FROM market_notices WHERE id = ?", id);
            return Optional.of(found.getFirst());
        });
    }

    /** Takes every notice waiting for {@code player}, oldest first, from both modes. */
    public List<Notice> takeNotices(UUID player) throws SQLException {
        return db.transaction(c -> {
            List<Notice> found = notices(c, "uuid = ?", player.toString());
            if (!found.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM market_notices WHERE uuid = ? AND id <= ?")) {
                    ps.setString(1, player.toString());
                    ps.setLong(2, found.getLast().id());
                    ps.executeUpdate();
                }
            }
            return found;
        });
    }

    private static List<Notice> notices(Connection c, String where, Object arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, uuid, mode, kind, item_name, amount, price, "
                + "proceeds, other_name, created_at FROM market_notices WHERE " + where + " ORDER BY id FOR UPDATE")) {
            ps.setObject(1, arg);
            List<Notice> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Notice(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                            Notice.Kind.valueOf(rs.getString(4)), rs.getString(5), rs.getInt(6), rs.getLong(7),
                            rs.getLong(8), rs.getString(9), instant(rs, 10)));
                }
            }
            return out;
        }
    }

    private static void delete(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Carries a result out of a transaction that must roll back. */
    private static final class Abort extends RuntimeException {
        private final transient Object result;

        Abort(Object result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    /** Runs {@code work} again (up to 3 times) if InnoDB picked it as a deadlock victim; it was rolled back in full. */
    private static <T> T retrying(Database.SqlSupplier<T> work) throws SQLException {
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

    private static Listing listing(ResultSet rs) throws SQLException {
        String buyer = rs.getString(14);
        LocalDateTime sold = rs.getObject(16, LocalDateTime.class);
        return new Listing(rs.getLong(1), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4),
                rs.getBytes(5), rs.getString(6), rs.getString(7),
                Category.byId(rs.getString(8)).orElse(Category.MISC), rs.getInt(9), rs.getLong(10), instant(rs, 11),
                instant(rs, 12), Listing.Status.valueOf(rs.getString(13)), buyer == null ? null : UUID.fromString(buyer),
                rs.getString(15), sold == null ? null : sold.toInstant(ZoneOffset.UTC));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static int bind(PreparedStatement ps, List<Object> args) throws SQLException {
        int i = 1;
        for (Object a : args) {
            ps.setObject(i++, a);
        }
        return i;
    }

    /** A LIKE pattern matching {@code text} anywhere, with the wildcards in it escaped. */
    static String like(String text) {
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
