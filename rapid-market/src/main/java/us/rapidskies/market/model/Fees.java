package us.rapidskies.market.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The auction house's sinks (PLAN.md §4.2): a non-refundable listing fee and a sales tax on
 * completed sales, both percentages of the asking price. Both round up (in the economy's
 * favour), the fee is at least 1, and the tax always leaves the seller at least 1.
 */
public record Fees(BigDecimal listingPercent, BigDecimal taxPercent) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Fees {
        if (listingPercent.signum() < 0 || listingPercent.compareTo(HUNDRED) >= 0) {
            throw new IllegalArgumentException("the listing fee must be 0 to under 100%, not " + listingPercent);
        }
        if (taxPercent.signum() < 0 || taxPercent.compareTo(HUNDRED) >= 0) {
            throw new IllegalArgumentException("the sales tax must be 0 to under 100%, not " + taxPercent);
        }
    }

    /** Charged when listing at {@code price}: never refunded. */
    public long listingFee(long price) {
        return Math.max(1, percentUp(price, listingPercent));
    }

    /** Kept from a sale at {@code price}. */
    public long tax(long price) {
        return Math.max(0, Math.min(price - 1, percentUp(price, taxPercent)));
    }

    /** What the seller receives for a sale at {@code price}. */
    public long proceeds(long price) {
        return price - tax(price);
    }

    private static long percentUp(long price, BigDecimal percent) {
        return BigDecimal.valueOf(price).multiply(percent).divide(HUNDRED, 0, RoundingMode.CEILING).longValueExact();
    }
}
