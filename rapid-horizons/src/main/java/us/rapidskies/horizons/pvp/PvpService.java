package us.rapidskies.horizons.pvp;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import us.rapidskies.common.time.Durations;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.data.HorizonsRepository;
import us.rapidskies.horizons.land.LandHook.PvpZone;

/**
 * Opt-in PvP (PLAN.md §5.2): off by default, {@code /pvp} switches it with a cooldown, and a
 * hit keeps both players "in combat" for a while so nobody switches off to escape a fight.
 *
 * <p>Inside claims rapid-claims decides first (it cancels fights in claims with PvP off, at
 * a lower priority). This listener handles everything else: melee, projectiles (tridents,
 * fireworks, snowballs...), splash and lingering potions with harmful effects, tamed wolves,
 * TNT, end crystals, fire from flame arrows and fire aspect, and fishing rods pulling players.
 *
 * <p>Wildlands (forced PvP in a Frontier zone) arrive with the Frontier rotation:
 * {@link #setWildlands} is the hook.
 */
public final class PvpService implements Listener {

    public static final String NO_COOLDOWN = "rapid.horizons.pvp.nocooldown";
    /** How long an end crystal remembers who hit it, so its blast counts as theirs. */
    private static final long CRYSTAL_MEMORY_MILLIS = 5_000;
    private static final long NOTICE_GAP_MILLIS = 2_000;

    private record State(boolean on, Instant changedAt) {
    }

    private final RapidHorizons plugin;
    private final HorizonsRepository repository;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> combatUntil = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> crystalHitBy = new ConcurrentHashMap<>();
    private final Map<UUID, Long> crystalHitAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();
    private volatile Predicate<Location> wildlands = at -> false;

    public PvpService(RapidHorizons plugin, HorizonsRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /** Where PvP is forced regardless of toggles (a Frontier's Wildlands). Set by the Frontier rotation. */
    public void setWildlands(Predicate<Location> test) {
        this.wildlands = test;
    }

    public boolean enabled(UUID player) {
        State s = states.get(player);
        return s != null && s.on();
    }

    public boolean inCombat(UUID player) {
        Instant until = combatUntil.get(player);
        return until != null && until.isAfter(Instant.now());
    }

    // ------------------------------------------------------------------ /pvp

    /** Asks to switch {@code player}'s PvP to {@code wanted} and tells them what happened. */
    public void request(Player player, boolean wanted) {
        UUID id = player.getUniqueId();
        State current = states.getOrDefault(id, new State(false, null));
        Instant now = Instant.now();
        Duration cooldown = player.hasPermission(NO_COOLDOWN) ? Duration.ZERO
                : Duration.ofSeconds(plugin.settings().pvp().toggleCooldownSeconds());
        switch (PvpRules.toggle(current.on(), wanted, now, current.changedAt(), combatUntil.get(id), cooldown)) {
            case PvpRules.Toggle.Unchanged u -> plugin.lang().send(player, u.on() ? "pvp.already-on" : "pvp.already-off");
            case PvpRules.Toggle.Cooldown c -> plugin.lang().send(player, "pvp.cooldown",
                    Placeholder.unparsed("time", Durations.format(c.left())));
            case PvpRules.Toggle.InCombat c -> plugin.lang().send(player, "pvp.in-combat",
                    Placeholder.unparsed("time", Durations.format(c.left())));
            case PvpRules.Toggle.Changed c -> {
                states.put(id, new State(c.on(), now));
                plugin.lang().send(player, c.on() ? "pvp.enabled" : "pvp.disabled");
                plugin.core().database().async(() -> {
                    repository.savePvp(id, c.on(), now);
                    return null;
                }).exceptionally(e -> {
                    plugin.getSLF4JLogger().warn("Couldn't save PvP for {}: {}", player.getName(), e.getMessage());
                    return null;
                });
            }
        }
    }

    // ------------------------------------------------------------------ loading

    // MONITOR: only logins that go ahead. Async thread, so blocking is fine.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            HorizonsRepository.PlayerRow row = repository.player(event.getUniqueId());
            states.put(event.getUniqueId(), new State(row.pvp(), row.pvpChangedAt().orElse(null)));
        } catch (SQLException e) {
            // Off is the safe default: nobody gets attacked because the database hiccupped.
            plugin.getSLF4JLogger().warn("Couldn't load PvP state of {}: {}", event.getName(), e.getMessage());
            states.put(event.getUniqueId(), new State(false, null));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        UUID id = event.getPlayerUniqueId();
        states.remove(id);
        lastNotice.remove(id);
        // The combat tag stays until it runs out, so relogging can't reset it.
    }

    // ------------------------------------------------------------------ deciding

    private boolean allowed(Player attacker, Player victim) {
        PvpZone zone = plugin.land().pvp(victim.getLocation());
        return PvpRules.allowed(zone, wildlands.test(victim.getLocation()), enabled(attacker.getUniqueId()),
                enabled(victim.getUniqueId()));
    }

    private void tag(Player a, Player b) {
        Instant until = Instant.now().plusSeconds(plugin.settings().pvp().combatTagSeconds());
        combatUntil.put(a.getUniqueId(), until);
        combatUntil.put(b.getUniqueId(), until);
    }

    private void notice(Player attacker, Player victim) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(attacker.getUniqueId());
        if (last != null && now - last < NOTICE_GAP_MILLIS) {
            return;
        }
        lastNotice.put(attacker.getUniqueId(), now);
        String key = !enabled(attacker.getUniqueId()) ? "pvp.blocked-self" : "pvp.blocked-other";
        attacker.sendActionBar(plugin.lang().get(key, Placeholder.unparsed("player", victim.getName())));
    }

    /** The player behind {@code damager}, or null if it isn't one (mobs, the environment). */
    Player responsible(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile) {
            return fromSource(projectile.getShooter());
        }
        if (damager instanceof Tameable pet && pet.isTamed() && pet.getOwner() instanceof Player owner) {
            return owner.isOnline() ? owner : null;
        }
        if (damager instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            return source == null || source instanceof TNTPrimed ? null : responsible(source);
        }
        if (damager instanceof AreaEffectCloud cloud) {
            return fromSource(cloud.getSource());
        }
        if (damager instanceof EnderCrystal crystal) {
            Long at = crystalHitAt.get(crystal.getUniqueId());
            UUID by = crystalHitBy.get(crystal.getUniqueId());
            if (at != null && by != null && System.currentTimeMillis() - at < CRYSTAL_MEMORY_MILLIS) {
                return Bukkit.getPlayer(by);
            }
        }
        return null;
    }

    private static Player fromSource(ProjectileSource source) {
        return source instanceof Player p ? p : null;
    }

    // ------------------------------------------------------------------ events

    /** Remembers who hit an end crystal, so the blast is theirs. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalHit(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof EnderCrystal crystal) {
            Player by = responsible(event.getDamager());
            if (by != null) {
                long now = System.currentTimeMillis();
                // Forget crystals hit long ago (they exploded or were left alone).
                crystalHitAt.entrySet().removeIf(e -> {
                    boolean stale = now - e.getValue() > CRYSTAL_MEMORY_MILLIS;
                    if (stale) {
                        crystalHitBy.remove(e.getKey());
                    }
                    return stale;
                });
                crystalHitBy.put(crystal.getUniqueId(), by.getUniqueId());
                crystalHitAt.put(crystal.getUniqueId(), now);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = responsible(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (allowed(attacker, victim)) {
            tag(attacker, victim);
        } else {
            event.setCancelled(true);
            notice(attacker, victim);
        }
    }

    /** Flame arrows and fire aspect set players alight even when the hit itself was stopped. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent event) {
        if (event.getEntity() instanceof Player victim) {
            Player attacker = responsible(event.getCombuster());
            if (attacker != null && !attacker.equals(victim) && !allowed(attacker, victim)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        Player thrower = fromSource(event.getPotion().getShooter());
        if (thrower == null || !harmful(event.getPotion().getEffects())) {
            return;
        }
        for (LivingEntity hit : event.getAffectedEntities()) {
            if (hit instanceof Player victim && !victim.equals(thrower)) {
                if (allowed(thrower, victim)) {
                    tag(thrower, victim);
                } else {
                    event.setIntensity(victim, 0);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        Player thrower = fromSource(cloud.getSource());
        if (thrower == null) {
            return;
        }
        List<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        if (cloud.getBasePotionType() != null) {
            effects.addAll(cloud.getBasePotionType().getPotionEffects());
        }
        if (!harmful(effects)) {
            return;
        }
        event.getAffectedEntities().removeIf(hit -> hit instanceof Player victim && !victim.equals(thrower)
                && !allowed(thrower, victim));
    }

    /** Tamed wolves don't go after players their owner may not fight. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player victim && event.getEntity() instanceof Tameable pet && pet.isTamed()
                && pet.getOwner() instanceof Player owner && !owner.equals(victim) && !allowed(owner, victim)) {
            event.setCancelled(true);
        }
    }

    /** Hooking a player with a rod pulls them: a way to drag someone into lava. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() instanceof Player victim
                && !victim.equals(event.getPlayer()) && !allowed(event.getPlayer(), victim)) {
            event.setCancelled(true);
        }
    }

    private static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect e : effects) {
            if (e.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                return true;
            }
        }
        return false;
    }
}
