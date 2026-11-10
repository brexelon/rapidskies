package us.rapidskies.essentials.tools;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What {@code /give} and {@code /i} were asked for: an item in vanilla's syntax
 * ({@code diamond_sword}, {@code minecraft:potion[potion_contents={potion:"swiftness"}]}),
 * which may contain spaces inside its components, then optionally an amount.
 *
 * @param item   the item text, as typed
 * @param amount the amount, or null to give a full stack
 */
public record ItemInput(String item, Integer amount) {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** The arguments after the player; null if there's no item in them. */
    public static ItemInput parse(List<String> tokens) {
        if (tokens.isEmpty()) {
            return null;
        }
        int end = tokens.size();
        Integer amount = null;
        String last = tokens.get(end - 1);
        if (end > 1 && DIGITS.matcher(last).matches()) {
            // Too many digits for an int is still an amount, just one that's too big.
            amount = last.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(last);
            end--;
        }
        String item = String.join(" ", tokens.subList(0, end)).trim();
        return item.isEmpty() ? null : new ItemInput(item, amount);
    }

    /** The item id in lowercase ({@code Diamond_Sword} works too); components are left as typed. */
    public String normalized() {
        int components = item.indexOf('[');
        String id = components < 0 ? item : item.substring(0, components);
        String rest = components < 0 ? "" : item.substring(components);
        return id.trim().toLowerCase(Locale.ROOT) + rest;
    }
}
