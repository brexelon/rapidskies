package us.rapidskies.horizons.trade;

import java.util.Set;

/** Sorts a fishing catch into Fisher reward groups ({@code fish}, {@code treasure}, {@code junk}). */
public final class FishCatch {

    private FishCatch() {
    }

    private static final Set<String> FISH = Set.of("COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH");
    private static final Set<String> TREASURE = Set.of("NAME_TAG", "NAUTILUS_SHELL", "SADDLE", "ENCHANTED_BOOK");

    /**
     * @param material  the caught item's Material name
     * @param enchanted whether it carries enchantments (an enchanted bow or rod is treasure;
     *                  a plain, damaged one is junk)
     */
    public static String classify(String material, boolean enchanted) {
        if (FISH.contains(material)) {
            return "fish";
        }
        if (TREASURE.contains(material) || (enchanted && (material.equals("BOW") || material.equals("FISHING_ROD")))) {
            return "treasure";
        }
        return "junk";
    }
}
