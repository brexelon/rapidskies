package us.rapidskies.core.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.PlusColor;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.rank.PlusChoice;
import us.rapidskies.core.rank.PlusMenu;

/**
 * {@code /plus} opens the plus-color menu (PLAN.md §6.1); {@code /plus <color|reset>} sets
 * it directly.
 */
public final class PlusCommand implements BasicCommand {

    private final PlusChoice choice;
    private final Lang lang;

    public PlusCommand(PlusChoice choice) {
        this.choice = choice;
        this.lang = choice.lang();
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player player)) {
            lang.send(stack.getSender(), "errors.players-only");
            return;
        }
        Rank rank = choice.ranks().rank(player);
        if (rank == Rank.DEFAULT) {
            lang.send(player, "plus.needs-rank");
            return;
        }
        if (args.length == 0) {
            new PlusMenu(choice).open(player);
            return;
        }
        String input = String.join(" ", args);
        if (input.equalsIgnoreCase("reset")) {
            choice.save(player, rank, null, () -> { });
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
            lang.send(player, "plus.locked", Placeholder.unparsed("name", PlusChoice.pretty(color.get())),
                    Placeholder.unparsed("rank", PlusChoice.rankName(color.get().minimum())));
            return;
        }
        choice.save(player, rank, color.get(), () -> { });
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player player) || args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Rank rank = choice.ranks().rank(player);
        List<String> options = new ArrayList<>(PlusColor.availableFor(rank).stream().map(PlusColor::id).toList());
        options.add("reset");
        return options.stream().filter(o -> o.startsWith(typed)).toList();
    }
}
