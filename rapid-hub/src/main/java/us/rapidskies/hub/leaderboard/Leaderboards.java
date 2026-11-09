package us.rapidskies.hub.leaderboard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.parkour.ParkourTimes;
import us.rapidskies.hub.HubSettings.Board;
import us.rapidskies.hub.RapidHub;
import us.rapidskies.hub.parkour.ParkourService;
import us.rapidskies.hub.parkour.TimeFormat;

/**
 * Leaderboard holograms (PLAN.md §7): the fastest parkour times, all time or this week,
 * redrawn every minute and right after a finish on their course. Each is one text display,
 * so it reads the same on every client and stays readable from any side.
 */
public final class Leaderboards {

    private static final long REFRESH_TICKS = 60 * 20;

    private record Shown(Board board, TextDisplay display) {
    }

    private final RapidHub hub;
    private final List<Shown> shown = new ArrayList<>();
    private BukkitTask task;

    public Leaderboards(RapidHub hub) {
        this.hub = hub;
    }

    public void start(List<Board> boards) {
        for (Board board : boards) {
            Location at = hub.labelLocation(board.location(), "leaderboards." + board.id() + ".location");
            if (at != null) {
                shown.add(new Shown(board, hub.labels().spawn(at, title(board))));
            }
        }
        refreshAll();
        task = Bukkit.getScheduler().runTaskTimer(hub, this::refreshAll, REFRESH_TICKS, REFRESH_TICKS);
    }

    /** Stops refreshing; the displays themselves go with {@code Labels#removeAll}. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        shown.clear();
    }

    public void refreshAll() {
        shown.forEach(this::refresh);
    }

    /** Redraws the boards of one course (after a finish there). */
    public void refresh(String course) {
        shown.stream().filter(s -> s.board().course().equals(course)).forEach(this::refresh);
    }

    private void refresh(Shown s) {
        Board board = s.board();
        if (!hub.parkour().isOpen(board.course())) {
            s.display().text(render(board, null));
            return;
        }
        String period = board.weekly() ? ParkourTimes.week(Instant.now()) : ParkourTimes.ALL_TIME;
        hub.parkour().top(board.course(), period, board.size(), entries -> {
            if (s.display().isValid()) {
                s.display().text(render(board, entries));
            }
        });
    }

    private Component title(Board board) {
        return hub.lang().get(board.weekly() ? "leaderboard.title-weekly" : "leaderboard.title",
                Placeholder.component("course", ParkourService.name(hub.lang(), board.course())));
    }

    /** The board's text; {@code entries} null for a course that isn't open yet. */
    private Component render(Board board, List<ParkourTimes.Entry> entries) {
        Lang lang = hub.lang();
        List<Component> lines = new ArrayList<>();
        lines.add(title(board));
        if (entries == null) {
            lines.add(lang.get("leaderboard.opening-soon"));
        } else if (entries.isEmpty()) {
            lines.add(lang.get("leaderboard.empty"));
        } else {
            for (ParkourTimes.Entry e : entries) {
                lines.add(lang.get("leaderboard.line",
                        Placeholder.unparsed("rank", Integer.toString(e.rank())),
                        Placeholder.unparsed("player", e.name()),
                        Placeholder.unparsed("time", TimeFormat.run(e.timeMs()))));
            }
        }
        if (board.weekly() && entries != null) {
            Instant now = Instant.now();
            lines.add(lang.get("leaderboard.resets", Placeholder.unparsed("time",
                    TimeFormat.countdown(Duration.between(now, ParkourTimes.weekEnd(now))))));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
