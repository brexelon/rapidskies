package us.rapidskies.core.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.afk.AfkTracker;
import us.rapidskies.core.tailwind.TailwindService;

/** {@code /tailwind}. ({@code /afk} is rapid-essentials', on {@link AfkTracker#setAfk}.) */
public final class PlayerStatusCommands {

    private PlayerStatusCommands() {
    }

    /** Shows the Tailwind bank and whether it's being spent here (PLAN.md §5.3). */
    public static BasicCommand tailwind(TailwindService tailwind, Lang lang) {
        return (stack, args) -> {
            if (!(stack.getSender() instanceof Player player)) {
                lang.send(stack.getSender(), "errors.players-only");
                return;
            }
            long left = tailwind.remainingSeconds(player);
            var bonus = Placeholder.unparsed("bonus", Integer.toString(tailwind.bonusPercent()));
            if (left <= 0) {
                lang.send(player, "tailwind.empty", bonus);
            } else if (!tailwind.spendsHere()) {
                lang.send(player, "tailwind.banked", Placeholder.unparsed("time", TailwindService.format(left)), bonus);
            } else {
                lang.send(player, tailwind.active(player) ? "tailwind.active" : "tailwind.paused",
                        Placeholder.unparsed("time", TailwindService.format(left)), bonus);
            }
        };
    }
}
