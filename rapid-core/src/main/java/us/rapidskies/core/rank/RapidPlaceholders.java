package us.rapidskies.core.rank;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import us.rapidskies.common.rank.PlusColor;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.tailwind.TailwindService;

/**
 * PlaceholderAPI expansion for TAB, scoreboards and holograms (PLAN.md §6.1), so the tab
 * list, nametags and sidebars show the same prefix as chat:
 *
 * <ul>
 *   <li>{@code %rapid_prefix%}: the full chat prefix (team role or rank, with plus color and
 *       trailing space), as section-sign codes; empty for default players</li>
 *   <li>{@code %rapid_plus_color%}: the plus color as a legacy code, e.g. {@code §e}; for
 *       a gradient, its first color ({@code %rapid_prefix%} carries the whole gradient)</li>
 *   <li>{@code %rapid_rank%}: {@code Rapid++}, or empty</li>
 *   <li>{@code %rapid_staff%}: the team role's group name, or empty</li>
 *   <li>{@code %rapid_afk%}: lang {@code afk.tag} while the player is AFK, else empty</li>
 *   <li>{@code %rapid_tailwind%}: Tailwind left ({@code 2h 15m}), or empty</li>
 *   <li>{@code %rapid_balance%}: Coins or Crowns on a gamemode server, else empty</li>
 * </ul>
 *
 * <p>For sidebars, which show a line whether or not it has a value:
 *
 * <ul>
 *   <li>{@code %rapid_rank_name%}: the prefix without its trailing space (team role or rank
 *       with plus color), or lang {@code placeholder.no-rank} for default players</li>
 *   <li>{@code %rapid_bolts%}: the Bolts balance ({@code 1,250}), or lang
 *       {@code placeholder.loading} until it has been read</li>
 *   <li>{@code %rapid_tailwind_status%}: Tailwind left, or lang {@code placeholder.no-tailwind}</li>
 *   <li>{@code %rapid_balance_status%}: like {@code %rapid_balance%}, or lang
 *       {@code placeholder.loading} while it loads</li>
 *   <li>{@code %rapid_world%}: the player's world by its display name ({@code Spawn},
 *       {@code Heartland}; config {@code world-names}) instead of its folder name</li>
 * </ul>
 *
 * <p>Hex brand colors are written as {@code §x§F§F§D§8§0§0}, which TAB reads back as hex.
 * Only loaded when PlaceholderAPI is installed.
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
            case "afk" -> core.afk().isAfk(player) ? LEGACY.serialize(core.lang().get("afk.tag")) : "";
            case "tailwind" -> {
                long left = core.tailwind().remainingSeconds(player);
                yield left > 0 ? TailwindService.format(left) : "";
            }
            case "balance" -> core.economy() == null ? ""
                    : core.economy().cachedBalance(player.getUniqueId()).stream()
                            .mapToObj(b -> String.format("%,d", b)).findFirst().orElse("");
            case "balance_status" -> core.economy() == null ? ""
                    : core.economy().cachedBalance(player.getUniqueId()).stream()
                            .mapToObj(b -> String.format("%,d", b)).findFirst().orElseGet(() -> text("placeholder.loading"));
            case "rank_name" -> {
                String prefix = LEGACY.serialize(ranks.prefix(player)).stripTrailing();
                yield prefix.isEmpty() ? text("placeholder.no-rank") : prefix;
            }
            case "bolts" -> core.boltsCache().cached(player.getUniqueId()).stream()
                    .mapToObj(b -> String.format("%,d", b)).findFirst().orElseGet(() -> text("placeholder.loading"));
            case "tailwind_status" -> {
                long left = core.tailwind().remainingSeconds(player);
                yield left > 0 ? TailwindService.format(left) : text("placeholder.no-tailwind");
            }
            case "world" -> core.worlds().displayName(player.getWorld());
            default -> null; // unknown placeholder: PlaceholderAPI leaves it as typed
        };
    }

    private String text(String key) {
        return LEGACY.serialize(core.lang().get(key));
    }

    /** "§e" for yellow; a gradient's first color. An empty component serializes to nothing, so encode one character and drop it. */
    static String legacyCode(PlusColor color) {
        String s = LEGACY.serialize(Component.text("x", color.color()));
        return s.substring(0, s.length() - 1);
    }
}
