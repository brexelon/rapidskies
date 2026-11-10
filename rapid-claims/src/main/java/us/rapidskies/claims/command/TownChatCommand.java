package us.rapidskies.claims.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import us.rapidskies.claims.RapidClaims;

/**
 * {@code /tc <message>} talks in your town's chat; {@code /tc} alone switches your plain
 * chat to the town and back (PLAN.md §4.1: town chat). The message is plain text, so
 * players can't inject formatting.
 */
public final class TownChatCommand implements BasicCommand {

    private final RapidClaims plugin;

    public TownChatCommand(RapidClaims plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "rapid.command.town";
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player p)) {
            plugin.lang().send(stack.getSender(), "errors.players-only");
            return;
        }
        if (args.length == 0) {
            plugin.towns().toggleChat(p);
        } else {
            plugin.towns().say(p, Component.text(String.join(" ", args)));
        }
    }
}
