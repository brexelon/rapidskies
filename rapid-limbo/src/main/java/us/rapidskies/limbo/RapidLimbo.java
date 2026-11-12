package us.rapidskies.limbo;

import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.player.PlayerJoinEvent;
import com.loohp.limbo.events.player.PlayerMoveEvent;
import com.loohp.limbo.events.player.PlayerQuitEvent;
import com.loohp.limbo.location.Location;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.plugins.LimboPlugin;
import com.loohp.limbo.scheduler.LimboRunnable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;

/**
 * rapid-limbo, on the Limbo server (LOOHP's Limbo). rapid-proxy parks AFK players here and
 * sends them back once they're at the keyboard again; it sees their chat and commands
 * itself, but not their mouse. This tells it: when a player turns their view, it sends a
 * {@value #CHANNEL} plugin message through their connection, which the proxy takes and
 * never passes on to the client.
 *
 * <p>It also puts players who walk off the island back at the spawn: Limbo has no void
 * damage, so they'd fall forever.
 *
 * <p>And it lets clients before 26.2 in through ViaLimbo ({@code via.InfiniburnFix}).
 */
public final class RapidLimbo extends LimboPlugin implements Listener {

    static final String CHANNEL = "rapidskies:activity";
    private static final Key KEY = Key.key(CHANNEL);
    private static final byte[] PAYLOAD = {1};
    /** At most one message a second per player: the proxy only needs the first. */
    private static final long GAP_MILLIS = 1_000;
    /** Ignores the view settling after arriving or being put back at the spawn. */
    private static final long SETTLE_MILLIS = 3_000;
    /** Below the island's underside: a player this low is falling into the void. */
    private static final double VOID_Y = -8;
    private static final String VIA_FIX = "us.rapidskies.limbo.via.InfiniburnFix";
    /** Limbo runs at 5 ticks a second by default: about a second between tries, for a minute. */
    private static final long VIA_RETRY_TICKS = 5;
    private static final int VIA_RETRIES = 60;

    /** Player → when their next turn may be reported (epoch millis). */
    private final Map<UUID, Long> quietUntil = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getServer().getEventsManager().registerEvents(this, this);
        fixOldClients();
    }

    /**
     * Installs {@code via.InfiniburnFix} once ViaLimbo has started ViaVersion (it does so
     * after enabling, so this retries every {@value #VIA_RETRY_TICKS} ticks for a while).
     * Without it, clients before 26.2 can't join at all.
     */
    private void fixOldClients() {
        LimboPlugin viaLimbo = getServer().getPluginManager().getPlugin("ViaLimbo");
        if (viaLimbo == null) {
            return; // Only 26.3 clients get in: nothing to fix.
        }
        Method install;
        try {
            // Never closed: the handler it installs loads classes from it while players join.
            ClassLoader loader = new URLClassLoader(new URL[] {getPluginJar().toURI().toURL()},
                    viaLimbo.getClass().getClassLoader());
            install = Class.forName(VIA_FIX, true, loader).getMethod("install");
        } catch (ReflectiveOperationException | IOException | LinkageError e) {
            viaFixFailed(e);
            return;
        }
        new LimboRunnable() {
            private int tries;

            @Override
            public void run() {
                try {
                    if ((boolean) install.invoke(null)) {
                        cancel();
                    } else if (++tries >= VIA_RETRIES) {
                        cancel();
                        viaFixFailed(new IllegalStateException("ViaVersion didn't start"));
                    }
                } catch (ReflectiveOperationException | LinkageError e) {
                    cancel();
                    viaFixFailed(e instanceof InvocationTargetException ite ? ite.getCause() : e);
                }
            }
        }.runTaskTimer(this, 0, VIA_RETRY_TICKS);
    }

    private void viaFixFailed(Throwable cause) {
        getServer().getConsole().sendMessage("[rapid-limbo] Couldn't patch ViaLimbo's ViaBackwards (" + cause
                + "): clients before 26.2 will be disconnected on joining. A ViaLimbo update may have"
                + " renamed what InfiniburnFix uses.");
    }

    @Override
    public void onDisable() {
        getServer().getEventsManager().unregisterAllListeners(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        quietUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + SETTLE_MILLIS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        quietUntil.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to.getY() < VOID_Y) {
            Location spawn = getServer().getServerProperties().getWorldSpawn();
            event.setTo(spawn.clone());
            player.teleport(spawn.clone());
            quietUntil.put(player.getUniqueId(), System.currentTimeMillis() + SETTLE_MILLIS);
            return;
        }
        Location from = event.getFrom();
        if (Turns.turned(from.getYaw(), from.getPitch(), to.getYaw(), to.getPitch())) {
            active(player);
        }
    }

    private void active(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (quietUntil.getOrDefault(id, 0L) > now) {
            return;
        }
        quietUntil.put(id, now + GAP_MILLIS);
        try {
            player.sendPluginMessage(KEY, PAYLOAD);
        } catch (IOException e) {
            // The connection is closing; nothing to tell.
        }
    }
}
