package us.rapidskies.claims.town;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.claims.ClaimSettings;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.data.ClaimRepository.PlayerRow;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Town;
import us.rapidskies.core.economy.ModeEconomy;

/**
 * Charges daily town upkeep and turns neglected land into ruins (PLAN.md §4.1). Runs every
 * ten minutes; the rules are {@link UpkeepRules}.
 *
 * <p>Every charge carries a transaction id made of the town and the day it pays for, so a
 * retried charge is never applied twice. All upkeep work runs on one thread, and across
 * servers under a MariaDB named lock, so a deposit's immediate catch-up charge
 * ({@link #chargeNow}) and the scheduled run never race each other.
 *
 * <p>Personal land: owners away {@code inactivity.days} (rapid-core's {@code players.last_join},
 * so any server counts) have their land marked as ruins, and it's released
 * {@code inactivity.ruins-days} later unless they come back.
 */
public final class Upkeep {

    private static final long PERIOD_TICKS = 20L * 60 * 10;
    private static final String LOCK = "rapid_claims_upkeep";
    /** At most this many missed days are caught up in one run. */
    private static final int MAX_DAYS_PER_RUN = 60;

    private final RapidClaims plugin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("rapid-claims-upkeep").factory());
    private BukkitTask task;

    public Upkeep(RapidClaims plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> worker.execute(this::runLocked), 20L * 60, PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getSLF4JLogger().warn("Upkeep still running at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Charges a town that's behind right away (after a deposit into its bank). */
    public void chargeNow(Town town) {
        worker.execute(() -> {
            try {
                processTown(town.id(), Instant.now());
            } catch (SQLException | RuntimeException e) {
                plugin.getSLF4JLogger().error("Upkeep for town {} failed", town.name(), e);
            }
        });
    }

    private void runLocked() {
        try {
            plugin.repo().db().withConnection(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                    ps.setString(1, LOCK);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) != 1) {
                            return null;
                        }
                    }
                }
                try {
                    Instant now = Instant.now();
                    for (Town t : plugin.land().towns()) {
                        processTown(t.id(), now);
                    }
                    sweepPersonal(now);
                } finally {
                    try (PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        ps.setString(1, LOCK);
                        ps.execute();
                    }
                }
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            plugin.getSLF4JLogger().error("Upkeep run failed", e);
        }
    }

    /** Worker thread: charges what's due, then records the result and updates the index on the main thread. */
    private void processTown(UUID id, Instant now) throws SQLException {
        Optional<Town> found = plugin.land().town(id);
        ModeEconomy crowns = plugin.crowns();
        if (found.isEmpty() || crowns == null || !UpkeepRules.due(found.get(), now)) {
            return;
        }
        ClaimSettings.Towns cfg = plugin.settings().towns();
        Town start = found.get();
        Town t = start;
        long cost = cfg.upkeep(plugin.land().countOf(Owner.town(id)));
        for (int day = 0; day < MAX_DAYS_PER_RUN && UpkeepRules.due(t, now); day++) {
            String txn = "upkeep:" + id + ":" + t.upkeepPaidUntil().toEpochMilli();
            if (cost > 0 && !crowns.withdraw(id, cost, "town-upkeep", txn).success()) {
                break;
            }
            t = UpkeepRules.paid(t, now);
        }
        boolean restored = start.ruined() && !t.ruined();
        if (UpkeepRules.due(t, now)) {
            Optional<Town> next = UpkeepRules.unpaid(t, now, cfg.graceDays(), cfg.ruinsDays());
            if (next.isEmpty()) {
                Town gone = t;
                plugin.towns().deleteBlocking(gone);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.towns().forget(id);
                    plugin.towns().broadcast(gone, "town.ruins-expired", Placeholder.component("town",
                            TownService.colored(gone, gone.name())));
                    plugin.getSLF4JLogger().info("Town {} ({}) was in ruins for {} days and has been released",
                            gone.name(), id, cfg.ruinsDays());
                });
                return;
            }
            t = next.get();
        }
        boolean ruined = !start.ruined() && t.ruined();
        if (t.equals(start)) {
            return;
        }
        plugin.repo().updateUpkeep(t);
        Town result = t;
        Bukkit.getScheduler().runTask(plugin, () -> plugin.land().town(id).ifPresent(current -> {
            Town merged = current.withUpkeep(result.upkeepPaidUntil(), result.state(), result.ruinsSince());
            plugin.land().putTown(merged);
            plugin.sync().town(id);
            var name = Placeholder.component("town", TownService.colored(merged, merged.name()));
            if (ruined) {
                plugin.towns().broadcast(merged, "town.became-ruins", name,
                        Placeholder.unparsed("days", Integer.toString(cfg.ruinsDays())));
            } else if (restored) {
                plugin.towns().broadcast(merged, "town.ruins-restored", name);
            }
        }));
    }

    /** Worker thread: marks long-gone owners' land as ruins and releases expired ruins. */
    private void sweepPersonal(Instant now) throws SQLException {
        ClaimSettings.Inactivity cfg = plugin.settings().inactivity();
        Set<UUID> away = plugin.repo().personalOwnersAwaySince(now.minus(Duration.ofDays(cfg.days())));
        for (UUID owner : away) {
            PlayerRow row = plugin.lands().row(owner);
            if (row.ruinsSince().isEmpty()) {
                plugin.repo().setPlayerRuins(owner, now);
                PlayerRow updated = plugin.repo().player(owner);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.lands().updateRow(updated);
                    plugin.sync().owner(Owner.player(owner));
                });
            } else if (UpkeepRules.personalRuinsExpired(row.ruinsSince().get(), now, cfg.ruinsDays())) {
                int released = plugin.lands().unclaimAllBlocking(Owner.player(owner));
                plugin.repo().setPlayerRuins(owner, null);
                PlayerRow updated = plugin.repo().player(owner);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.lands().forgetAll(Owner.player(owner));
                    plugin.lands().updateRow(updated);
                    plugin.sync().owner(Owner.player(owner));
                });
                plugin.getSLF4JLogger().info("Released {} chunk(s) of {} (away {}+ days, ruins expired)", released,
                        owner, cfg.days());
            }
        }
    }
}
