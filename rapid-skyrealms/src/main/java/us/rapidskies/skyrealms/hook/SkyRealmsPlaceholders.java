package us.rapidskies.skyrealms.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.season.SeasonRanking;

/**
 * PlaceholderAPI expansion for the SkyRealms sidebar (TAB reads it through TAB-Bridge).
 * SuperiorSkyblock2 provides the island level and worth itself ({@code %superior_island_level%}).
 *
 * <ul>
 *   <li>{@code %rapidskyrealms_tier%}: the player's island Realm Tier, colored</li>
 *   <li>{@code %rapidskyrealms_expedition%}: "in 42m", "arriving 3m" or "here! 12m"</li>
 *   <li>{@code %rapidskyrealms_season_rank%}: the island's place this season, or a dash</li>
 * </ul>
 *
 * <p>Only loaded when PlaceholderAPI is installed.
 */
public final class SkyRealmsPlaceholders extends PlaceholderExpansion {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final RapidSkyRealms plugin;

    public SkyRealmsPlaceholders(RapidSkyRealms plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "rapidskyrealms";
    }

    @Override
    public String getAuthor() {
        return "RapidSkies";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        if (!(offline instanceof Player player) || !player.isOnline()) {
            return "";
        }
        switch (params) {
            case "tier" -> {
                return LEGACY.serialize(plugin.islands().of(player)
                        .map(i -> plugin.tiers().name(plugin.tiers().tier(i.id())))
                        .orElse(plugin.lang().get("placeholder.no-island")));
            }
            case "expedition" -> {
                var e = plugin.expeditions();
                long minutes = Math.max(1, (e.remaining().getSeconds() + 59) / 60);
                String key = switch (e.phase()) {
                    case ACTIVE -> "placeholder.expedition-here";
                    case WARNED, BUILDING -> "placeholder.expedition-arriving";
                    case LEAVING -> "placeholder.expedition-leaving";
                    default -> "placeholder.expedition-next";
                };
                return LEGACY.serialize(plugin.lang().get(key,
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("minutes",
                                Long.toString(minutes))));
            }
            case "season_rank" -> {
                return plugin.islands().of(player).map(i -> {
                    int rank = SeasonRanking.rankOf(plugin.seasons().standings(), i.id());
                    return rank > 0 ? "#" + rank : "-";
                }).orElse("-");
            }
            default -> {
                return null;
            }
        }
    }
}
