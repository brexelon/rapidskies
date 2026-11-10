package us.rapidskies.horizons.frontier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.event.Announcer;
import us.rapidskies.horizons.event.EventRewards;

/**
 * The community dragon fight that opens each new End (PLAN.md §5.2, §7). The End opens
 * {@code dragon-delay-hours} after its Frontier (announced network-wide, {@link FrontierService});
 * everyone who deals at least {@code min-damage} to the dragon shares the win: the rewards
 * ({@link EventRewards}), and the Chapter's {@code dragon} entry. Paid once per Frontier,
 * guarded by {@code frontier_history.dragon_killed_at}; a respawned dragon pays nothing.
 */
public final class DragonFight implements Listener {

    private final RapidHorizons plugin;
    private final FrontierService frontier;
    private final Announcer announcer;
    private final EventRewards rewards;
    /** Damage per player against the current End's dragon. */
    private final Map<UUID, Double> damage = new ConcurrentHashMap<>();
    private int damageFor = -1;

    public DragonFight(RapidHorizons plugin, FrontierService frontier, Announcer announcer, EventRewards rewards) {
        this.plugin = plugin;
        this.frontier = frontier;
        this.announcer = announcer;
        this.rewards = rewards;
    }

    private HorizonsSettings.End settings() {
        return plugin.settings().frontier().end();
    }

    private static Player responsible(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Entity hit = event.getEntity();
        if (hit instanceof ComplexEntityPart part) {
            hit = part.getParent();
        }
        if (!(hit instanceof EnderDragon) || frontier.place(hit.getWorld()) != FrontierService.Place.END) {
            return;
        }
        Player player = responsible(event.getDamager());
        FrontierRow row = frontier.current().orElse(null);
        if (player == null || row == null || row.dragonKilledAt().isPresent()) {
            return;
        }
        if (damageFor != row.id()) {
            damage.clear();
            damageFor = row.id();
        }
        damage.merge(player.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)
                || frontier.place(dragon.getWorld()) != FrontierService.Place.END) {
            return;
        }
        FrontierRow row = frontier.current().orElse(null);
        if (row == null || row.dragonKilledAt().isPresent() || damageFor != row.id()) {
            return;
        }
        Map<UUID, Double> dealt = Map.copyOf(damage);
        damage.clear();
        frontier.dragonKilled(row).thenAccept(first -> {
            if (!first) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> reward(row, dealt));
        }).exceptionally(e -> {
            plugin.getSLF4JLogger().error("Couldn't record the dragon kill of Frontier #{}; no rewards were paid",
                    row.id(), e);
            return null;
        });
    }

    private void reward(FrontierRow row, Map<UUID, Double> dealt) {
        int count = 0;
        for (Map.Entry<UUID, Double> e : dealt.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || e.getValue() < settings().minDamage()) {
                continue;
            }
            count++;
            plugin.lang().send(p, "frontier.end.victory-personal");
            rewards.pay(p, settings().rewards(), "dragon", "dragon:" + row.id() + ":" + p.getUniqueId());
            plugin.chapters().eventWon(p, us.rapidskies.horizons.chapter.ChapterGoal.Kind.DRAGON);
        }
        plugin.getSLF4JLogger().info("The dragon of Frontier #{} fell; {} player(s) rewarded", row.id(), count);
        announcer.network(plugin.lang().get("frontier.end.victory",
                Placeholder.unparsed("count", Integer.toString(count))));
    }
}
