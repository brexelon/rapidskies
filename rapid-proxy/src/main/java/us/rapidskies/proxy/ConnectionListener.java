package us.rapidskies.proxy;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;
import com.velocitypowered.api.event.player.KickedFromServerEvent.RedirectPlayer;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.proxy.limbo.LimboService;

/**
 * Version gate, maintenance gate, and hub selection on join and after a kick. With no hub
 * up, players go to the Limbo server to wait for one ({@link LimboService}).
 */
final class ConnectionListener {

    static final String MAINTENANCE_BYPASS = "rapid.maintenance.bypass";

    private final Lang lang;
    private final NetworkDirectory directory;
    private final ProxySettings settings;
    private final LimboService limbo;

    ConnectionListener(Lang lang, NetworkDirectory directory, ProxySettings settings, LimboService limbo) {
        this.lang = lang;
        this.directory = directory;
        this.settings = settings;
        this.limbo = limbo;
    }

    /** Velocity accepts 1.7.2+; the network starts at 1.20 (PLAN.md §3, version support). */
    @Subscribe(priority = Short.MAX_VALUE) // first
    public void onPreLogin(PreLoginEvent event) {
        if (event.getConnection().getProtocolVersion().getProtocol() < settings.minimumProtocol()) {
            event.setResult(PreLoginComponentResult.denied(lang.get("kick.version")));
        }
    }

    /** LATE so LuckPerms has loaded the player's permissions for the bypass check. */
    @Subscribe(priority = Short.MIN_VALUE / 2) // late
    public void onLogin(LoginEvent event) {
        if (event.getResult().isAllowed() && directory.maintenance()
                && !event.getPlayer().hasPermission(MAINTENANCE_BYPASS)) {
            event.setResult(ComponentResult.denied(lang.get("kick.maintenance")));
        }
    }

    /**
     * Sends a joining player to the least-loaded live hub. If the registry is current and no
     * hub is up, to limbo to wait for one. If the registry can't be read (Redis trouble),
     * Velocity's own pick from {@code try} stands: the hubs may well be up.
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> hub = directory.bestHub(null);
        if (hub.isPresent()) {
            event.setInitialServer(hub.get());
        } else if (directory.registryHealthy()) {
            limbo.server().ifPresent(server -> {
                limbo.sendingToWait(event.getPlayer());
                event.setInitialServer(server);
            });
        }
    }

    /**
     * A backend kicked or dropped the player (restart, crash, anti-cheat kick). Move them to a
     * hub instead of disconnecting them, or with no hub up to limbo, unless they were only
     * trying to switch servers and are still connected somewhere, in which case Velocity's
     * default (stay, show reason) is right.
     */
    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        if (event.kickedDuringServerConnect() && player.getCurrentServer().isPresent()) {
            return;
        }
        Component reason = event.getServerKickReason().orElse(Component.empty());
        Optional<RegisteredServer> hub = directory.bestHub(event.getServer());
        Optional<RegisteredServer> toLimbo = limbo.server().filter(s -> !limbo.isLimbo(event.getServer()));
        if (hub.isPresent()) {
            event.setResult(RedirectPlayer.create(hub.get(),
                    lang.get("kick.redirected", Placeholder.component("reason", reason))));
        } else if (toLimbo.isPresent()
                && (directory.registryHealthy() || event.getResult() instanceof DisconnectPlayer)) {
            // Unless the registry can't be read, the next server in `try` is a hub that's down too.
            limbo.sendingToWait(player);
            event.setResult(event.getServerKickReason().isPresent()
                    ? RedirectPlayer.create(toLimbo.get(),
                            lang.get("kick.to-limbo", Placeholder.component("reason", reason)))
                    : RedirectPlayer.create(toLimbo.get()));
        } else if (event.getResult() instanceof DisconnectPlayer) {
            // Nowhere to go. Velocity's own result already covers failover through `try`;
            // only its disconnect screen is replaced, keeping the backend's reason.
            event.setResult(DisconnectPlayer.create(lang.get("kick.no-hub", Placeholder.component("reason", reason))));
        }
    }
}
