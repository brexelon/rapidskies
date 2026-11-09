package us.rapidskies.common.economy;

/** A gamemode with its own earn-only currency (PLAN.md §5.1, §5.2). */
public enum EconomyMode {
    SKYREALMS("skyrealms", "Coin", "Coins"),
    HORIZONS("horizons", "Crown", "Crowns");

    private final String id;
    private final String singular;
    private final String plural;

    EconomyMode(String id, String singular, String plural) {
        this.id = id;
        this.singular = singular;
        this.plural = plural;
    }

    /** Stored in {@code mode_economy_ledger.mode}. */
    public String id() {
        return id;
    }

    public String currencyName(long amount) {
        return amount == 1 ? singular : plural;
    }

    public static EconomyMode byId(String id) {
        for (EconomyMode m : values()) {
            if (m.id.equalsIgnoreCase(id)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown economy mode '" + id + "'");
    }
}
