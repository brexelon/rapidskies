package us.rapidskies.essentials.data;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Home and warp names: 1–16 of a–z, 0–9, - and _, stored lowercase so {@code /home Base} finds "base". */
public final class Names {

    public static final int MAX_LENGTH = 16;
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1," + MAX_LENGTH + "}");

    private Names() {
    }

    /** The stored form of {@code input}, or empty if it isn't a valid name. */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String name = input.trim().toLowerCase(Locale.ROOT);
        return VALID.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }
}
