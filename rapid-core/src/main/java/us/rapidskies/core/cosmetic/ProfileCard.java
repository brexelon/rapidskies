package us.rapidskies.core.cosmetic;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.chat.ChatStyle;
import us.rapidskies.core.progress.LevelService;
import us.rapidskies.core.rank.RankDisplay;

/**
 * The cosmetics engine's part of chat: the worn chat tag and name color, and the profile
 * card shown when a name is hovered (rank, network level, title, badge). Reads only
 * in-memory state, so it's safe on the async chat thread.
 */
public final class ProfileCard implements ChatStyle {

    private final CosmeticService cosmetics;
    private final LevelService levels;
    private final RankDisplay ranks;
    private final Lang lang;

    public ProfileCard(CosmeticService cosmetics, LevelService levels, RankDisplay ranks, Lang lang) {
        this.cosmetics = cosmetics;
        this.levels = levels;
        this.ranks = ranks;
        this.lang = lang;
    }

    @Override
    public Component tag(Player player) {
        return cosmetics.worn(player, CosmeticType.TAG)
                .map(c -> cosmetics.render(c).append(Component.space()))
                .orElse(Component.empty());
    }

    @Override
    public TextColor nameColor(Player player) {
        return cosmetics.worn(player, CosmeticType.NAME_COLOR).map(Cosmetic::nameColor).orElse(null);
    }

    @Override
    public Component card(Player player) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get("profile.card.name", Placeholder.component("rank", ranks.prefix(player)),
                Placeholder.unparsed("player", player.getName())));
        lines.add(lang.get("profile.card.level", Placeholder.unparsed("level", Integer.toString(levels.level(player)))));
        cosmetics.worn(player, CosmeticType.TITLE).ifPresent(c -> lines.add(lang.get("profile.card.title",
                Placeholder.component("title", cosmetics.render(c)))));
        cosmetics.worn(player, CosmeticType.BADGE).ifPresent(c -> lines.add(lang.get("profile.card.badge",
                Placeholder.component("badge", cosmetics.render(c)))));
        lines.add(lang.get("profile.card.click"));
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
