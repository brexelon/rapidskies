package us.rapidskies.hub;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.hub.parkour.BlockPos;

/**
 * {@code /rapidhub here}: the position you stand at, as an NPC/leaderboard location and as
 * a parkour block, each clickable to copy; {@code /rapidhub reload}: re-reads the config and
 * lang and rebuilds the NPCs, leaderboards and courses. Positions belong in the hub profile
 * in infra/ (rsdeploy would put the old ones back otherwise), as with /setspawn.
 */
public final class HubAdminCommand implements BasicCommand {

    private final RapidHub hub;

    public HubAdminCommand(RapidHub hub) {
        this.hub = hub;
    }

    @Override
    public String permission() {
        return "rapid.hub.admin";
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "here" -> here(stack);
            case "reload" -> {
                if (hub.reload()) {
                    hub.lang().send(stack.getSender(), "admin.reloaded");
                } else {
                    hub.lang().send(stack.getSender(), "admin.reload-failed");
                }
            }
            default -> hub.lang().send(stack.getSender(), "admin.usage");
        }
    }

    private void here(CommandSourceStack stack) {
        if (!(stack.getSender() instanceof Player player)) {
            hub.lang().send(stack.getSender(), "errors.players-only");
            return;
        }
        Location at = player.getLocation();
        // Same rounding as /setspawn (2 decimals, yaw and pitch to 1).
        String point = new SpawnPoint(at.getWorld().getKey().asString(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch()).format();
        String block = new BlockPos(at.getBlockX(), at.getBlockY(), at.getBlockZ()).format();
        hub.lang().send(player, "admin.here",
                Placeholder.component("point", copyable(point)),
                Placeholder.component("block", copyable(block)));
    }

    private Component copyable(String value) {
        return Component.text("\"" + value + "\"")
                .clickEvent(ClickEvent.copyToClipboard(value))
                .hoverEvent(HoverEvent.showText(hub.lang().get("admin.copy-hint")));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Stream.of("here", "reload").filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
