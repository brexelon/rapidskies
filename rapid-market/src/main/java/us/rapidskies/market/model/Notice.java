package us.rapidskies.market.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Something a seller is told about once: a sale, an expiry, or staff taking a listing down
 * (PLAN.md §4.2). Shown on whatever server they're on, or at their next login.
 *
 * @param amount    items in the listing
 * @param price     the asking price
 * @param proceeds  what the seller received (sales only)
 * @param otherName the buyer (sales) or nobody
 */
public record Notice(long id, UUID player, String mode, Kind kind, String itemName, int amount, long price,
                     long proceeds, String otherName, Instant createdAt) {

    public enum Kind {
        SOLD,
        EXPIRED,
        REMOVED;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
