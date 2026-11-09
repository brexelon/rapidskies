package us.rapidskies.common.rank;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * The color of the pluses in a rank prefix, chosen with {@code /plus} (PLAN.md §6.1).
 *
 * <p>Only the 16 named chat colors are used, so each choice has a clear name. Red and dark
 * red are reserved for staff and are not listed here.
 * Gradients for Rapid+++ arrive with the {@code /plus} GUI in phase 5.
 */
public enum PlusColor {
    WHITE(NamedTextColor.WHITE, Rank.RAPID_PLUS),
    YELLOW(NamedTextColor.YELLOW, Rank.RAPID_PLUS),
    GOLD(NamedTextColor.GOLD, Rank.RAPID_PLUS),
    AQUA(NamedTextColor.AQUA, Rank.RAPID_PLUS),
    GREEN(NamedTextColor.GREEN, Rank.RAPID_PLUS),
    LIGHT_PURPLE(NamedTextColor.LIGHT_PURPLE, Rank.RAPID_PLUS),

    BLUE(NamedTextColor.BLUE, Rank.RAPID_PLUS_PLUS),
    DARK_BLUE(NamedTextColor.DARK_BLUE, Rank.RAPID_PLUS_PLUS),
    DARK_AQUA(NamedTextColor.DARK_AQUA, Rank.RAPID_PLUS_PLUS),
    DARK_GREEN(NamedTextColor.DARK_GREEN, Rank.RAPID_PLUS_PLUS),
    DARK_PURPLE(NamedTextColor.DARK_PURPLE, Rank.RAPID_PLUS_PLUS),

    GRAY(NamedTextColor.GRAY, Rank.RAPID_PLUS_PLUS_PLUS),
    DARK_GRAY(NamedTextColor.DARK_GRAY, Rank.RAPID_PLUS_PLUS_PLUS),
    BLACK(NamedTextColor.BLACK, Rank.RAPID_PLUS_PLUS_PLUS);

    /** Shown when nothing is chosen, or the choice needs a higher rank than the player has now. */
    public static final PlusColor DEFAULT = YELLOW;

    private final NamedTextColor color;
    private final Rank minimum;

    PlusColor(NamedTextColor color, Rank minimum) {
        this.color = color;
        this.minimum = minimum;
    }

    public NamedTextColor color() {
        return color;
    }

    /** The lowest rank that can use this color. */
    public Rank minimum() {
        return minimum;
    }

    /** Stored in {@code players.plus_color} and typed in {@code /plus <id>}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean availableTo(Rank rank) {
        return rank != Rank.DEFAULT && rank.atLeast(minimum);
    }

    public static List<PlusColor> availableFor(Rank rank) {
        return Arrays.stream(values()).filter(c -> c.availableTo(rank)).toList();
    }

    public static Optional<PlusColor> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return Arrays.stream(values()).filter(c -> c.id().equals(key)).findFirst();
    }

    /**
     * The color a player's prefix actually shows. The stored choice is never overwritten
     * when a rank lapses: it simply isn't shown until the rank allows it again.
     */
    public static PlusColor effective(PlusColor chosen, Rank rank) {
        return chosen != null && chosen.availableTo(rank) ? chosen : DEFAULT;
    }

    /** {@code <plus>...</plus>} styling tag for the rank prefix templates in the lang bundle. */
    public TagResolver tag() {
        return TagResolver.resolver("plus", Tag.styling(color));
    }
}
