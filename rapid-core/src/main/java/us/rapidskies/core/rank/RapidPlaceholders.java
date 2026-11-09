package us.rapidskies.core.rank;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import us.rapidskies.common.rank.PlusColor;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.RapidCore;

/**
 * PlaceholderAPI expansion for TAB, scoreboards and holograms (PLAN.md §6.1), so the tab
 * list, nametags and sidebars show the same prefix as chat:
 *
 * <ul>
 *   <li>{@code %rapid_prefix%}: the full chat prefix (team role or rank, with plus color and
 *       trailing space), as section-sign codes; empty for default players</li>
 *   <li>{@code %rapid_plus_color%}: the plus color as a legacy code, e.g. {@code §e}</li>
 *   <li>{@code %rapid_rank%}: {@code Rapid++}, or empty</li>
 *   <li>{@code %rapid_staff%}: the team role's group name, or empty</li>
 * </ul>
 *
 * <p>Hex brand colors are written as {@code §x§F§F§D§8§0§0}; TAB and Via fall back to the
 * nearest legacy color for 1.8–1.15 clients. Only loaded when PlaceholderAPI is installed.
 */
public final class RapidPlaceholders extends PlaceholderExpansion {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final RapidCore core;

    public RapidPlaceholders(RapidCore core) {
        this.core = core;
    }

    @Override
    public String getIdentifier() {
        return "rapid";
    }

    @Override
    public String getAuthor() {
        return "RapidSkies";
    }

    @Override
    public String getVersion() {
        return core.getPluginMeta().getVersion();
    }

    /** Registered by rapid-core itself, so it survives {@code /papi reload}. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) {
            return "";
        }
        RankDisplay ranks = core.ranks();
        return switch (params) {
            case "prefix" -> LEGACY.serialize(ranks.prefix(player));
            case "plus_color" -> legacyCode(ranks.of(player).plusColor());
            case "rank" -> {
                Rank rank = ranks.rank(player);
                yield rank == Rank.DEFAULT ? "" : PlusChoice.rankName(rank);
            }
            case "staff" -> ranks.of(player).staff().map(s -> s.group()).orElse("");
            default -> null; // unknown placeholder: PlaceholderAPI leaves it as typed
        };
    }

    /** "§e" for yellow. An empty component serializes to nothing, so encode one character and drop it. */
    static String legacyCode(PlusColor color) {
        String s = LEGACY.serialize(Component.text("x", color.color()));
        return s.substring(0, s.length() - 1);
    }
}
