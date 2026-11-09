package us.rapidskies.core.rank;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.rank.PlusColor;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.rank.StaffRole;
import us.rapidskies.core.profile.ProfileService;

/**
 * Works out a player's rank, team role and plus color, and renders their prefix
 * (PLAN.md §6.1). Chat uses it now; TAB, nametags and the sidebar will use the same
 * prefix through a placeholder.
 */
public final class RankDisplay {

    private final LuckPerms luckPerms;
    private final ProfileService profiles;
    private final Lang lang;

    public RankDisplay(LuckPerms luckPerms, ProfileService profiles, Lang lang) {
        this.luckPerms = luckPerms;
        this.profiles = profiles;
        this.lang = lang;
    }

    /** What a player's prefix is made of right now. */
    public record Display(Rank rank, Optional<StaffRole> staff, PlusColor plusColor) {
    }

    public Display of(Player player) {
        Set<String> groups = groups(player);
        Rank rank = Rank.resolve(groups::contains);
        PlusColor chosen = profiles.get(player.getUniqueId()).map(PlayerProfile::plusColor).orElse(null);
        return new Display(rank, StaffRole.resolve(groups::contains), PlusColor.effective(chosen, rank));
    }

    /** The paid rank only, ignoring team roles (for perks such as /plus). */
    public Rank rank(Player player) {
        return Rank.resolve(groups(player)::contains);
    }

    /** The prefix to show before the name, with its trailing space; empty for default players. */
    public Component prefix(Player player) {
        return prefix(of(player));
    }

    public Component prefix(Display d) {
        if (d.staff().isPresent()) {
            return lang.get("rank.staff." + d.staff().get().group());
        }
        return rankPrefix(d.rank(), d.plusColor());
    }

    /** A rank prefix in a given plus color (also used for the /plus previews). */
    public Component rankPrefix(Rank rank, PlusColor color) {
        if (rank == Rank.DEFAULT) {
            return Component.empty();
        }
        return lang.get("rank.prefix." + rank.group(), color.tag());
    }

    /**
     * Groups the player is in, inherited ones included, in this server's context. Read from
     * LuckPerms' loaded user (in memory), not from permission checks, which a {@code *}
     * wildcard would make true for every group.
     */
    private Set<String> groups(Player player) {
        User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
        return user.getInheritedGroups(luckPerms.getPlayerAdapter(Player.class).getQueryOptions(player)).stream()
                .map(Group::getName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
