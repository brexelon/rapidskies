package us.rapidskies.hub.display;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
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
 * </ul>
 *
 * <p>Only loaded when PlaceholderAPI is installed.
 */
public final class HubPlaceholders extends PlaceholderExpansion {

    private static final String PLAYERS = "players_";

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
        return null;
    }
}
