package us.rapidskies.claims.display;

import java.util.Optional;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.town.TownService;

/**
 * PlaceholderAPI expansion for the Horizons tab list and sidebar (TAB reads it through
 * TAB-Bridge). Colored values use the same legacy format as rapid-core's placeholders.
 *
 * <ul>
 *   <li>{@code %rapidclaims_town%}: the player's town name in its color, or the "no town" text</li>
 *   <li>{@code %rapidclaims_town_tag%}: {@code [TAG] } in the town's color, empty without a town
 *       (for nametags and tab, before the rank prefix)</li>
 *   <li>{@code %rapidclaims_here%}: whose land the player stands on (a dash outside the Heartland)</li>
 *   <li>{@code %rapidclaims_chunks%}: personal chunks used / allowance</li>
 * </ul>
 *
 * <p>Only loaded when PlaceholderAPI is installed.
 */
public final class ClaimPlaceholders extends PlaceholderExpansion {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final RapidClaims plugin;

    public ClaimPlaceholders(RapidClaims plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "rapidclaims";
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
        if (offline == null) {
            return "";
        }
        Optional<Town> town = plugin.land().townOf(offline.getUniqueId());
        switch (params) {
            case "town" -> {
                return LEGACY.serialize(town.map(t -> TownService.colored(t, t.name()))
                        .orElseGet(() -> plugin.lang().get("placeholder.no-town")));
            }
            case "town_tag" -> {
                return town.map(t -> LEGACY.serialize(plugin.lang().get("town.chat-tag",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("tag",
                                TownService.colored(t, t.tag()))))).orElse("");
            }
            default -> {
            }
        }
        if (!(offline instanceof Player p)) {
            return null;
        }
        return switch (params) {
            case "here" -> {
                if (!plugin.service().claimable(p.getWorld())) {
                    yield LEGACY.serialize(plugin.lang().get("placeholder.not-claimable"));
                }
                Optional<Owner> owner = plugin.land().owner(ClaimService.chunk(p.getLocation()));
                Component c = owner.map(plugin::ownerName).orElseGet(() -> plugin.lang().get("placeholder.wilderness"));
                yield LEGACY.serialize(c);
            }
            case "chunks" -> {
                var a = plugin.lands().allowance(p);
                yield a.used() + "/" + a.total();
            }
            default -> null;
        };
    }
}
