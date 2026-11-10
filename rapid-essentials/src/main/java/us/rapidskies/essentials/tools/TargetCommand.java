package us.rapidskies.essentials.tools;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.essentials.RapidEssentials;

/**
 * {@code /<label> [player]}: acts on the sender, or on a named player for senders with
 * {@code others}. The console has to name someone.
 */
abstract class TargetCommand implements BasicCommand {

    protected final RapidEssentials plugin;
    private final String label;
    private final String permission;
    private final String others;

    TargetCommand(RapidEssentials plugin, String label, String permission, String others) {
        this.plugin = plugin;
        this.label = label;
        this.permission = permission;
        this.others = others;
    }

    protected abstract void run(CommandSender sender, Player target);

    @Override
    public final void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (args.length > 1) {
            plugin.lang().send(sender, "errors.usage-target", Placeholder.unparsed("command", label));
            return;
        }
        Player target = Target.resolve(plugin, sender, args, 0, others);
        if (target != null) {
            run(sender, target);
        }
    }

    @Override
    public final Collection<String> suggest(CommandSourceStack stack, String[] args) {
        return args.length <= 1 ? Target.players(plugin, stack.getSender(), args, others) : List.of();
    }

    @Override
    public final String permission() {
        return permission;
    }
}
