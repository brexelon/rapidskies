package us.rapidskies.market.model;

/** Browse order (PLAN.md §4.2). Prices sort by the price of one item, so stacks compare fairly. */
public enum Sort {
    NEWEST("newest", "created_at DESC, id DESC"),
    PRICE_LOW("price-low", "price / amount ASC, id ASC"),
    PRICE_HIGH("price-high", "price / amount DESC, id DESC"),
    ENDING("ending", "expires_at ASC, id ASC");

    private final String id;
    private final String orderBy;

    Sort(String id, String orderBy) {
        this.id = id;
        this.orderBy = orderBy;
    }

    /** Lang key suffix. */
    public String id() {
        return id;
    }

    /** The SQL ORDER BY clause; a constant, never user input. */
    public String orderBy() {
        return orderBy;
    }

    public Sort next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
