package us.rapidskies.skyrealms.route;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;

/**
 * A Trade Route (PLAN.md §5.1, table {@code skyrealms_trade_routes}): {@code qty} plain
 * {@code item}s from the seller island to the buyer island every {@code intervalHours}, for
 * {@code price} Coins. Immutable; the service swaps in updated copies.
 */
public record Route(long id, UUID sellerIsland, UUID buyerIsland, String sellerName, String buyerName,
                    Material item, int qty, long price, int intervalHours, State state, UUID offeredBy,
                    Side offeredSide, Optional<ChestPos> chest, Optional<Instant> nextDue, int deliveries,
                    int missed, Instant createdAt) {

    public enum State { OFFERED, ACTIVE, ENDED }

    /** Which island made the offer; the other one answers it. */
    public enum Side { SELLER, BUYER }

    /** The seller's contract chest. */
    public record ChestPos(String world, int x, int y, int z) {
    }

    /** Whether the island takes part in the route. */
    public boolean involves(UUID island) {
        return sellerIsland.equals(island) || buyerIsland.equals(island);
    }

    /** The island that has to answer an offer. */
    public UUID answeringIsland() {
        return offeredSide == Side.SELLER ? buyerIsland : sellerIsland;
    }

    /** The name of the island across from {@code island}. */
    public String partnerName(UUID island) {
        return sellerIsland.equals(island) ? buyerName : sellerName;
    }

    public Duration interval() {
        return Duration.ofHours(intervalHours);
    }

    public boolean due(Instant now) {
        return state == State.ACTIVE && nextDue.isPresent() && !now.isBefore(nextDue.get());
    }

    public Route withState(State s) {
        return new Route(id, sellerIsland, buyerIsland, sellerName, buyerName, item, qty, price, intervalHours, s,
                offeredBy, offeredSide, chest, nextDue, deliveries, missed, createdAt);
    }

    public Route signed(Instant firstDue) {
        return new Route(id, sellerIsland, buyerIsland, sellerName, buyerName, item, qty, price, intervalHours,
                State.ACTIVE, offeredBy, offeredSide, chest, Optional.of(firstDue), deliveries, missed, createdAt);
    }

    public Route withChest(ChestPos pos) {
        return new Route(id, sellerIsland, buyerIsland, sellerName, buyerName, item, qty, price, intervalHours, state,
                offeredBy, offeredSide, Optional.ofNullable(pos), nextDue, deliveries, missed, createdAt);
    }

    public Route delivered(Instant next) {
        return new Route(id, sellerIsland, buyerIsland, sellerName, buyerName, item, qty, price, intervalHours, state,
                offeredBy, offeredSide, chest, Optional.of(next), deliveries + 1, 0, createdAt);
    }

    public Route missedOne(Instant next) {
        return new Route(id, sellerIsland, buyerIsland, sellerName, buyerName, item, qty, price, intervalHours, state,
                offeredBy, offeredSide, chest, Optional.of(next), deliveries, missed + 1, createdAt);
    }
}
