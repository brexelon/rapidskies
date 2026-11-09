package us.rapidskies.common;

/**
 * Brand tokens from PLAN.md §2. Messages are MiniMessage; every supported client (1.20+)
 * shows the hex colors exactly.
 */
public final class RapidBrand {

    public static final String PRIMARY = "#FFD800"; // yellow-gold
    public static final String ACCENT = "#FFAA00";  // gold
    public static final String TEXT = "#FFFFFF";    // white
    public static final String MUTED = "#AAAAAA";   // gray
    public static final String DARK = "#555555";    // dark gray
    public static final String SUCCESS = "#55FF55"; // green
    public static final String ERROR = "#FF5555";   // red

    /** "RAPIDSKIES »" chat prefix, followed by a space. */
    public static final String PREFIX =
            "<" + PRIMARY + "><bold>RAPID</bold></" + PRIMARY + "><white><bold>SKIES</bold></white> "
                    + "<" + DARK + ">»</" + DARK + "> ";

    private RapidBrand() {
    }
}
