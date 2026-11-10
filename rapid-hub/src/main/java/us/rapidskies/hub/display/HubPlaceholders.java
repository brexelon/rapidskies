package us.rapidskies.hub.display;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import us.rapidskies.hub.ModeCounts;
import us.rapidskies.hub.RapidHub;

/**
 * PlaceholderAPI expansion for the hub sidebar (TAB on the proxy reads it through
 * TAB-Bridge):
 *
 * <ul>
 *   <li>{@code %rapidhub_players_<group>%}: players on a gamemode's servers right now
 *       ({@code %rapidhub_players_skyrealms%}), from the same counts as the game menu</li>
 *   <li>{@code %rapidhub_status_<group>%}: lang {@code sidebar.players} ("12 playing"), or
 *       {@code sidebar.offline} when none of the gamemode's servers takes players (the
 *       same test as the menu's "Not open right now")</li>
 * </ul>
 *
 * <p>Only loaded when PlaceholderAPI is installed.
 */
public final class HubPlaceholders extends PlaceholderExpansion {

    private static final String PLAYERS = "players_";
    private static final String STATUS = "status_";

    /** TAB reads section codes, with hex colors as §x§r§r§g§g§b§b. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final RapidHub hub;

    public HubPlaceholders(RapidHub hub) {
        this.hub = hub;
    }

    @Override
    public String getIdentifier() {
        return "rapidhub";
    }

    @Override
    public String getAuthor() {
        return "RapidSkies";
    }

    @Override
    public String getVersion() {
        return hub.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params.startsWith(PLAYERS)) {
            ModeCounts.Status status = hub.counts().status(params.substring(PLAYERS.length()));
            return String.format("%,d", status.players());
        }
        if (params.startsWith(STATUS)) {
            ModeCounts.Status status = hub.counts().status(params.substring(STATUS.length()));
            return LEGACY.serialize(status.open()
                    ? hub.lang().get("sidebar.players",
                            Placeholder.unparsed("count", String.format("%,d", status.players())))
                    : hub.lang().get("sidebar.offline"));
        }
        return null;
    }
}
