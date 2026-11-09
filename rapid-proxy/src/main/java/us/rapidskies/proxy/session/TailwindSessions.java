package us.rapidskies.proxy.session;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.tailwind.TailwindBank;

/**
 * Tailwind accrual (PLAN.md §5.3, {@link TailwindBank}). The proxy is the only place that
 * sees a whole network session, so it turns time away into Tailwind on login and stamps
 * the moment a player leaves.
 *
 * <p>The login side runs as an async {@link EventTask}, so the player only reaches a
 * backend (which loads their profile, bank included) once the bank is settled. A failure
 * is logged and never blocks the login: the absence stays stamped and is settled next time.
 */
public final class TailwindSessions {

    private final ProxyServer proxy;
    private final Database db;
    private final TailwindBank bank;
    private final TailwindBank.Rules rules;
    private final Logger logger;
    /** Players whose login was settled, so a refused login never stamps a departure. */
    private final Set<UUID> settled = ConcurrentHashMap.newKeySet();

    public TailwindSessions(ProxyServer proxy, Database db, TailwindBank.Rules rules, Logger logger) {
        this.proxy = proxy;
        this.db = db;
        this.bank = new TailwindBank(db);
        this.rules = rules;
        this.logger = logger;
    }

    /** Last, so logins refused by the maintenance or version gates are skipped. */
    @Subscribe(priority = Short.MIN_VALUE)
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        Player player = event.getPlayer();
        return EventTask.async(() -> {
            try {
                bank.settleLogin(player.getUniqueId(), rules);
            } catch (SQLException | RuntimeException e) {
                logger.warn("Couldn't settle Tailwind for {}: {}", player.getUsername(), e.getMessage());
            }
            // Even after a failure: the stamp is still there, and the next departure
            // simply moves it later, which loses some accrual but never doubles it.
            settled.add(player.getUniqueId());
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // A second session for the same account (kick-existing-players) is still online.
        if (proxy.getPlayer(id).filter(p -> p != player).isPresent() || !settled.remove(id)) {
            return;
        }
        db.async(() -> {
            bank.markLeft(id);
            return null;
        }).exceptionally(e -> {
            logger.warn("Couldn't stamp Tailwind departure for {}: {}", player.getUsername(), e.getMessage());
            return null;
        });
    }
}
