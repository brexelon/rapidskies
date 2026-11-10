package us.rapidskies.market.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An item waiting in a player's collection bin ({@code /ah collect}): bought, expired,
 * cancelled, taken down by staff, or returned after something went wrong. Items are never
 * dropped on the ground or lost (PLAN.md §4.2).
 */
public record Stored(long id, String mode, UUID owner, byte[] item, String itemName, int amount, Reason reason,
                     Long listingId, Instant createdAt) {

    public enum Reason {
        BOUGHT,
        EXPIRED,
        CANCELLED,
        REMOVED,
        /** A listing that didn't go through, or a collection that didn't reach the inventory. */
        RETURNED;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
