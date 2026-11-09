package us.rapidskies.common;

/**
 * Brand tokens from PLAN.md §2. Messages are MiniMessage; hex colors downsample
 * to the legacy fallback on 1.8–1.15 clients through ViaBackwards.
 */
public final class RapidBrand {

    public static final String PRIMARY = "#FFD800"; // legacy fallback: gold
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
