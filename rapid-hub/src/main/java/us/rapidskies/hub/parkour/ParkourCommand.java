package us.rapidskies.hub.parkour;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.parkour.ParkourTimes;
import us.rapidskies.hub.RapidHub;

/**
 * {@code /parkour} (alias {@code /pk}): your best time, {@code start [course]} for a fresh
 * random course, {@code leave}, {@code checkpoint}, {@code restart}, and
 * {@code top [course] [weekly]} for the leaderboard in chat.
 */
public final class ParkourCommand implements BasicCommand {

    private static final int TOP_SIZE = 10;

    private final RapidHub hub;

    public ParkourCommand(RapidHub hub) {
        this.hub = hub;
    }

    @Override
    public String permission() {
        return "rapid.command.parkour";
    }

    private Lang lang() {
        return hub.lang();
    }

    private Optional<Course> firstOpen() {
        return hub.parkour().firstOpen();
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player player)) {
            lang().send(stack.getSender(), "errors.players-only");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> stats(player);
            case "start", "play", "join", "new" -> start(player, args);
            case "leave", "quit" -> hub.parkour().leave(player);
            case "checkpoint", "cp" -> hub.parkour().backToCheckpoint(player);
            case "restart" -> hub.parkour().restart(player);
            case "top" -> top(player, args);
            default -> lang().send(player, "parkour.usage");
        }
    }

    private void stats(Player player) {
        Optional<Course> course = firstOpen();
        if (course.isEmpty()) {
            lang().send(player, "parkour.closed");
            return;
        }
        TagResolver name = Placeholder.component("course", ParkourService.name(lang(), course.get().id()));
        hub.parkour().standing(player, course.get().id(), standing -> {
            if (standing.isEmpty()) {
                lang().send(player, "parkour.stats-none", name);
            } else {
                lang().send(player, "parkour.stats", name,
                        Placeholder.unparsed("time", TimeFormat.run(standing.get().timeMs())),
                        Placeholder.unparsed("rank", Integer.toString(standing.get().rank())));
            }
        });
    }

    private void start(Player player, String[] args) {
        Optional<Course> course = args.length > 1 ? hub.settings().course(args[1].toLowerCase(Locale.ROOT)) : firstOpen();
        if (args.length > 1 && course.isEmpty()) {
            lang().send(player, "parkour.unknown-course", Placeholder.unparsed("course", args[1]));
            return;
        }
        if (course.isEmpty()) {
            lang().send(player, "parkour.closed");
            return;
        }
        hub.parkour().join(player, course.get());
    }

    private void top(Player player, String[] args) {
        Optional<Course> course = Optional.empty();
        boolean weekly = false;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if (arg.equals("weekly") || arg.equals("week")) {
                weekly = true;
            } else {
                course = hub.settings().course(arg);
                if (course.isEmpty()) {
                    lang().send(player, "parkour.unknown-course", Placeholder.unparsed("course", args[i]));
                    return;
                }
            }
        }
        if (course.isEmpty()) {
            course = firstOpen();
        }
        if (course.isEmpty() || !hub.parkour().isOpen(course.get().id())) {
            lang().send(player, "parkour.closed");
            return;
        }
        TagResolver name = Placeholder.component("course", ParkourService.name(lang(), course.get().id()));
        String header = weekly ? "parkour.top-header-weekly" : "parkour.top-header";
        String period = weekly ? ParkourTimes.week(Instant.now()) : ParkourTimes.ALL_TIME;
        hub.parkour().top(course.get().id(), period, TOP_SIZE, entries -> {
            if (!player.isOnline()) {
                return;
            }
            lang().send(player, header, name);
            if (entries.isEmpty()) {
                lang().send(player, "parkour.top-empty");
            }
            for (ParkourTimes.Entry e : entries) {
                lang().send(player, "parkour.top-line",
                        Placeholder.unparsed("rank", Integer.toString(e.rank())),
                        Placeholder.unparsed("player", e.name()),
                        Placeholder.unparsed("time", TimeFormat.run(e.timeMs())));
            }
        });
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Stream.of("start", "leave", "checkpoint", "restart", "top").filter(s -> s.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("start") && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return hub.settings().courses().stream().map(Course::id).filter(s -> s.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("top")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return Stream.concat(Stream.of("weekly"), hub.settings().courses().stream().map(Course::id))
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
