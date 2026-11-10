package us.rapidskies.market.model;

import java.util.Locale;

/**
 * An item's English name from its key, for when the server's translations can't give one
 * ({@code pale_oak_log} → "Pale Oak Log"). Listing lore always shows the real name, because
 * clients older than the server see ViaBackwards substitutes for newer items (PLAN.md §4.2).
 */
public final class TrueNames {

    private static final java.util.Set<String> SMALL = java.util.Set.of("of", "on", "a", "the", "and", "with", "o");

    private TrueNames() {
    }

    public static String fromKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        int colon = k.indexOf(':');
        if (colon >= 0) {
            k = k.substring(colon + 1);
        }
        String[] words = k.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            if (i > 0 && SMALL.contains(w)) {
                sb.append(w);
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w, 1, w.length());
            }
        }
        return sb.toString();
    }

    /** Whether {@code rendered} is a translation that didn't happen (still the raw key). */
    public static boolean untranslated(String rendered, String translationKey) {
        return rendered == null || rendered.isBlank() || rendered.equals(translationKey)
                || rendered.startsWith("item.minecraft.") || rendered.startsWith("block.minecraft.");
    }
}
