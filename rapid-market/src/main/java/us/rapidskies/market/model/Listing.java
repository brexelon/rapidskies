package us.rapidskies.market.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code market_listings}. {@code item} is the stack as Paper's
 * {@code ItemStack#serializeAsBytes}, which upgrades item data across Minecraft versions.
 * {@code itemName} is the item's real English name, {@code itemKey} what price history is
 * kept under, and {@code price} is for the whole stack.
 */
public record Listing(long id, String mode, UUID seller, String sellerName, byte[] item, String itemName,
                      String itemKey, Category category, int amount, long price, Instant createdAt,
                      Instant expiresAt, Status status, UUID buyer, String buyerName, Instant soldAt) {

    public enum Status {
        /** For sale. */
        ACTIVE,
        /** Bought: the item is in the buyer's collection bin (or their inventory). */
        SOLD,
        /** Ran out of time: the item is back in the seller's bin. */
        EXPIRED,
        /** Taken down by the seller: back in their bin. */
        CANCELLED,
        /** Taken down by staff: back in the seller's bin. */
        REMOVED
    }

    /** The price of one item, for comparing stacks. */
    public double unitPrice() {
        return amount <= 0 ? price : (double) price / amount;
    }
}
