package us.rapidskies.core.gui;

import java.util.List;

/**
 * The 28-slot content area (rows 1–4, columns 1–7) the six-row list menus share, and paging
 * through it.
 */
public final class Pages {

    /** Slots for items, left to right then top to bottom. */
    public static final int[] SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43};
    public static final int PREVIOUS = 45;
    public static final int NEXT = 53;

    private Pages() {
    }

    public static int count(int items) {
        return Math.max(1, (items + SLOTS.length - 1) / SLOTS.length);
    }

    /** Page {@code page} (0-based, clamped) of {@code items}. */
    public static <T> List<T> page(List<T> items, int page) {
        int p = Math.clamp(page, 0, count(items.size()) - 1);
        int from = p * SLOTS.length;
        return items.subList(Math.min(from, items.size()), Math.min(from + SLOTS.length, items.size()));
    }

    /** A progress bar of {@code width} cells, for lore ({@code ■■■■□□□□}). */
    public static String bar(long done, long total, int width) {
        int filled = total <= 0 ? width : (int) Math.min(width, done * width / total);
        return "<accent>" + "■".repeat(filled) + "</accent><dark>" + "■".repeat(width - filled) + "</dark>";
    }
}
