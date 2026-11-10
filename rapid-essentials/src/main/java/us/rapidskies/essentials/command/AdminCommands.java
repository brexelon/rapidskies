package us.rapidskies.essentials.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.chat.Broadcasts;

/** {@code /broadcast <message>} and {@code /essentials reload|version}. */
public final class AdminCommands {

    public static final String ADMIN = "rapid.essentials.admin";

    private final RapidEssentials plugin;

    public AdminCommands(RapidEssentials plugin) {
        this.plugin = plugin;
    }

    public BasicCommand broadcast() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                String message = String.join(" ", args).trim();
                if (message.isEmpty()) {
                    plugin.lang().send(stack.getSender(), "broadcast.usage");
                    return;
                }
                plugin.broadcasts().send(stack.getSender(), message);
            }

            @Override
            public String permission() {
                return Broadcasts.PERMISSION;
            }
        };
    }

    /**
     * {@code /essentials reload}: re-reads config.yml and the messages. Which commands exist
     * ({@code disabled-commands}, {@code info-commands}) is only read at startup.
     */
    public BasicCommand essentials() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                String sub = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
                switch (sub) {
                    case "reload" -> plugin.lang().send(stack.getSender(),
                            plugin.reload() ? "admin.reloaded" : "admin.reload-failed");
                    case "version" -> plugin.lang().send(stack.getSender(), "admin.version",
                            Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()));
                    default -> plugin.lang().send(stack.getSender(), "admin.usage");
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return args.length <= 1 ? Suggest.matching(List.of("reload", "version"), args) : List.of();
            }

            @Override
            public String permission() {
                return ADMIN;
            }
        };
    }
}
