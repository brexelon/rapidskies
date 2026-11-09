package us.rapidskies.core.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.rank.PlusColor;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.profile.ProfileService;
import us.rapidskies.core.rank.RankDisplay;

/**
 * {@code /plus [color|reset]} (PLAN.md §6.1). With no argument it lists every color as a
 * clickable chat line with a preview of the prefix; click events work on 1.8 too. The
 * chest-GUI version arrives with the GUI framework.
 */
public final class PlusCommand implements BasicCommand {

    private final Database db;
    private final RankDisplay ranks;
    private final ProfileService profiles;
    private final ProfileRepository repository;
    private final Lang lang;

    public PlusCommand(Database db, RankDisplay ranks, ProfileService profiles, ProfileRepository repository,
                       Lang lang) {
        this.db = db;
        this.ranks = ranks;
        this.profiles = profiles;
        this.repository = repository;
        this.lang = lang;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player player)) {
            lang.send(stack.getSender(), "errors.players-only");
            return;
        }
        Rank rank = ranks.rank(player);
        if (rank == Rank.DEFAULT) {
            lang.send(player, "plus.needs-rank");
            return;
        }
        if (args.length == 0) {
            list(player, rank);
            return;
        }
        String input = String.join(" ", args);
        if (input.equalsIgnoreCase("reset")) {
            save(player, rank, null, "plus.reset");
            return;
        }
        String key = input.toLowerCase(Locale.ROOT).replace(' ', '_');
        if (key.equals("red") || key.equals("dark_red")) {
            lang.send(player, "plus.reserved");
            return;
        }
        Optional<PlusColor> color = PlusColor.byId(input);
        if (color.isEmpty()) {
            lang.send(player, "plus.unknown", Placeholder.unparsed("input", input));
            return;
        }
        if (!color.get().availableTo(rank)) {
            lang.send(player, "plus.locked", Placeholder.unparsed("name", pretty(color.get())),
                    Placeholder.unparsed("rank", rankName(color.get().minimum())));
            return;
        }
        save(player, rank, color.get(), "plus.set");
    }

    private void list(Player player, Rank rank) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get("plus.header"));
        for (PlusColor c : PlusColor.values()) {
            TagResolver name = Placeholder.unparsed("name", pretty(c));
            // Locked colors preview on the player's own rank, so they see what they'd get.
            TagResolver preview = Placeholder.component("preview", ranks.rankPrefix(rank, c));
            if (c.availableTo(rank)) {
                lines.add(lang.get("plus.entry", preview, name)
                        .clickEvent(ClickEvent.runCommand("/plus " + c.id()))
                        .hoverEvent(HoverEvent.showText(lang.get("plus.entry-hover", name))));
            } else {
                lines.add(lang.get("plus.locked-entry", preview, name,
                        Placeholder.unparsed("rank", rankName(c.minimum()))));
            }
        }
        player.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
    }

    private void save(Player player, Rank rank, PlusColor color, String doneKey) {
        db.async(() -> {
            repository.setPlusColor(player.getUniqueId(), color);
            return repository.find(player.getUniqueId());
        }).thenAccept(profile -> {
            profile.ifPresent(profiles::updated);
            PlusColor shown = PlusColor.effective(profile.map(PlayerProfile::plusColor).orElse(color), rank);
            lang.send(player, doneKey, Placeholder.component("preview", ranks.rankPrefix(rank, shown)));
        }).exceptionally(e -> {
            lang.send(player, "plus.failed");
            return null;
        });
    }

    private static String pretty(PlusColor c) {
        String[] words = c.id().split("_");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    private static String rankName(Rank rank) {
        return "Rapid" + rank.pluses();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player player) || args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Rank rank = ranks.rank(player);
        List<String> options = new ArrayList<>(PlusColor.availableFor(rank).stream().map(PlusColor::id).toList());
        options.add("reset");
        return options.stream().filter(o -> o.startsWith(typed)).toList();
    }
}
