package us.rapidskies.essentials.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import org.bukkit.entity.Player;
import us.rapidskies.essentials.RapidEssentials;

/** A command only players can use; the console is told so. */
public abstract class PlayerCommand implements BasicCommand {

    protected final RapidEssentials plugin;
    private final String permission;

    /** {@code permission} null: everyone. */
    protected PlayerCommand(RapidEssentials plugin, String permission) {
        this.plugin = plugin;
        this.permission = permission;
    }

    protected abstract void run(Player player, String[] args);

    /** Suggestions for the argument being typed (the last one). */
    protected Collection<String> complete(Player player, String[] args) {
        return List.of();
    }

    @Override
    public final void execute(CommandSourceStack stack, String[] args) {
        if (stack.getSender() instanceof Player player) {
            run(player, args);
        } else {
            plugin.lang().send(stack.getSender(), "errors.players-only");
        }
    }

    @Override
    public final Collection<String> suggest(CommandSourceStack stack, String[] args) {
        return stack.getSender() instanceof Player player ? complete(player, args) : List.of();
    }

    @Override
    public String permission() {
        return permission;
    }
}
