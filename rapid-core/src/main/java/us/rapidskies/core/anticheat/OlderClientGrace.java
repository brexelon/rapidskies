package us.rapidskies.core.anticheat;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A short settling window for players on clients older than the server, during which some of
 * Grim's flags are dropped ({@link GrimBridge}).
 *
 * <p>Why: those clients reach the 26.x backends through ViaBackwards, which rewrites and adds
 * packets for them (teleport velocity sent as a separate motion packet, rotations as a look-at
 * plus a fake rotation packet, the 1.21.2+ tick-end and loaded packets emulated). Grim reads
 * packets in the server's format, before ViaBackwards, so it misses those, and right after a
 * join, respawn or world change it mispredicts the client for a few ticks: a 1.20.1 player
 * fails Simulation and AimModulo360 on joining while a 26.3 player on the same spot passes.
 * Grim's own startup warning says ViaBackwards on 1.21.2+ servers is unsupported.
 *
 * <p>The window is counted in Grim's simulated movement ticks, not wall time, so a slow
 * loading screen can't use it up before Grim starts checking. Only the configured checks
 * are dropped, and only until the count runs out. Grim calls in from its netty threads and
 * Bukkit from the main thread, so state is kept in a concurrent map.
 */
public final class OlderClientGrace {

    private final int ticks;
    private final Set<String> checks;
    private final Map<UUID, Integer> remaining = new ConcurrentHashMap<>();

    /**
     * @param ticks  simulated movement ticks the window lasts (0 turns it off)
     * @param checks Grim check names whose flags are dropped (case doesn't matter)
     */
    public OlderClientGrace(int ticks, Set<String> checks) {
        this.ticks = Math.max(0, ticks);
        this.checks = checks.stream().map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public boolean enabled() {
        return ticks > 0 && !checks.isEmpty();
    }

    /** Opens (or reopens) the window for a player who just joined, respawned or changed worlds. */
    public void start(UUID player) {
        if (enabled()) {
            remaining.put(player, ticks);
        }
    }

    /** Grim simulated one movement tick for {@code player}: the window gets one shorter. */
    public void tick(UUID player) {
        remaining.computeIfPresent(player, (id, left) -> left > 1 ? left - 1 : null);
    }

    /** Whether a flag of {@code check} against {@code player} should be dropped now. */
    public boolean suppresses(UUID player, String check) {
        return check != null && remaining.containsKey(player) && checks.contains(check.toLowerCase(Locale.ROOT));
    }

    public void forget(UUID player) {
        remaining.remove(player);
    }
}
