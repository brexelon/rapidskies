package us.rapidskies.core.rank;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.rank.PlusColor;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.profile.ProfileService;

/**
 * Saving a player's plus color (PLAN.md §6.1), shared by {@code /plus <color>} and the
 * {@code /plus} menu. The choice is stored in their profile and announced network-wide, so
 * every server shows it.
 */
public final class PlusChoice {

    private final Plugin plugin;
    private final Database db;
    private final RankDisplay ranks;
    private final ProfileService profiles;
    private final ProfileRepository repository;
    private final Lang lang;

    public PlusChoice(Plugin plugin, Database db, RankDisplay ranks, ProfileService profiles,
                      ProfileRepository repository, Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.ranks = ranks;
        this.profiles = profiles;
        this.repository = repository;
        this.lang = lang;
    }

    public RankDisplay ranks() {
        return ranks;
    }

    public Lang lang() {
        return lang;
    }

    /** The stored choice (possibly one the current rank can't show), or null. */
    public PlusColor chosen(Player player) {
        return profiles.get(player.getUniqueId()).map(PlayerProfile::plusColor).orElse(null);
    }

    /**
     * Stores {@code color} (null resets it), tells the player, then runs {@code after} on the
     * main thread (the menu uses it to redraw).
     */
    public void save(Player player, Rank rank, PlusColor color, Runnable after) {
        db.async(() -> {
            repository.setPlusColor(player.getUniqueId(), color);
            return repository.find(player.getUniqueId());
        }).thenAccept(profile -> {
            profile.ifPresent(profiles::updated);
            PlusColor shown = PlusColor.effective(profile.map(PlayerProfile::plusColor).orElse(color), rank);
            lang.send(player, color == null ? "plus.reset" : "plus.set",
                    Placeholder.component("preview", ranks.rankPrefix(rank, shown)));
            Bukkit.getScheduler().runTask(plugin, after);
        }).exceptionally(e -> {
            plugin.getSLF4JLogger().warn("Couldn't save the plus color of {}: {}", player.getName(), e.getMessage());
            lang.send(player, "plus.failed");
            return null;
        });
    }

    /** "Light Purple" for {@code LIGHT_PURPLE}. */
    public static String pretty(PlusColor c) {
        StringBuilder out = new StringBuilder();
        for (String w : c.id().split("_")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /** "Rapid++" for {@code RAPID_PLUS_PLUS}. */
    public static String rankName(Rank rank) {
        return "Rapid" + rank.pluses();
    }
}
