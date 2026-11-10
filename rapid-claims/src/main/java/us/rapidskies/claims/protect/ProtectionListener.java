package us.rapidskies.claims.protect;

import io.papermc.paper.event.entity.EntityDyeEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Role;

/**
 * What players may do on claimed land (PLAN.md §4.1, protection coverage): blocks, buckets
 * and trampling; containers, doors, redstone, item frames, armor stands, lecterns, beds and
 * signs; animals, villagers and display entities; vehicles; projectiles; potions; PvP in
 * claims with the flag off. Nature and cross-border effects are {@link EnvironmentListener},
 * entering land is {@link EntryListener}.
 *
 * <p>Each handler only works out which {@link Action} an event
 * is and where; the rules themselves are {@link BlockRules} and
 * {@link us.rapidskies.claims.model.LandIndex#role}. Handlers run at LOW so other plugins
 * see the final result, and a refusal is shown on the action bar.
 */
public final class ProtectionListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 1500;

    private final RapidClaims plugin;
    private final ClaimService service;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public ProtectionListener(RapidClaims plugin) {
        this.plugin = plugin;
        this.service = plugin.service();
    }

    /** True (and tells the player why) if {@code player} may not do {@code action} at {@code at}. */
    private boolean refused(Player player, Location at, Action action) {
        if (at.getWorld() == null || !service.claimable(at.getWorld())) {
            return false;
        }
        Role role = service.role(player, at);
        if (action.allowedFor(role)) {
            return false;
        }
        tell(player, ClaimService.chunk(at), action);
        return true;
    }

    private void tell(Player player, ChunkKey chunk, Action action) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        plugin.land().owner(chunk).ifPresent(owner -> player.sendActionBar(plugin.lang().get("protect.denied",
                Placeholder.component("owner", plugin.ownerName(owner)),
                Placeholder.component("action", plugin.lang().get("protect.action." + action.id())),
                Placeholder.component("role", plugin.lang().get("role." + action.required.id())))));
    }

    private void check(Cancellable event, Player player, Location at, Action action) {
        if (player != null && refused(player, at, action)) {
            event.setCancelled(true);
        }
    }

    /** The player behind an entity's action: itself, a projectile's shooter, TNT's igniter, a pet's owner. */
    static Player responsible(Entity e) {
        if (e instanceof Player p) {
            return p;
        }
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            return p;
        }
        if (e instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) {
            return p;
        }
        if (e instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) {
            return p;
        }
        if (e instanceof Tameable pet && pet.isTamed() && pet.getOwner() instanceof Player p) {
            return p;
        }
        return null;
    }

    /**
     * What interacting with an entity takes, or null if it's never protected (players and
     * hostile mobs). Decorations are BUILD, vehicles USE (CONTAINER with storage), and every
     * other living thing (animals, villagers, golems, pets) ANIMALS.
     */
    static Action entityAction(Entity e) {
        if (e instanceof Player || e instanceof Enemy) {
            return null;
        }
        if (e instanceof Hanging || e instanceof ArmorStand || e instanceof EnderCrystal || e instanceof Display) {
            return Action.BUILD;
        }
        if (e instanceof LivingEntity) {
            return Action.ANIMALS;
        }
        if (e instanceof Vehicle) {
            return e instanceof InventoryHolder ? Action.CONTAINER
                    : Action.USE;
        }
        return null;
    }

    /** What hurting an entity takes: as {@link #entityAction}, but breaking a vehicle is BUILD. */
    static Action damageAction(Entity e) {
        if (e instanceof Vehicle && !(e instanceof LivingEntity)) {
            return Action.BUILD;
        }
        return entityAction(e);
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        check(event, event.getPlayer(), b.getLocation(), BlockRules.breakOrPlace(b.getType().name()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multi) {
            // Beds and doors can straddle a chunk border: every part must be allowed.
            for (BlockState part : multi.getReplacedBlockStates()) {
                if (refused(event.getPlayer(), part.getLocation(), BlockRules.breakOrPlace(event.getBlock().getType().name()))) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }
        Block b = event.getBlock();
        check(event, event.getPlayer(), b.getLocation(), BlockRules.breakOrPlace(b.getType().name()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        check(event, event.getPlayer(), event.getBlock().getLocation(), Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        check(event, event.getPlayer(), event.getBlockClicked().getLocation(), Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignOpen(PlayerOpenSignEvent event) {
        if (event.getCause() != PlayerOpenSignEvent.Cause.PLUGIN) {
            check(event, event.getPlayer(), event.getSign().getLocation(), Action.BUILD);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        check(event, event.getPlayer(), event.getBlock().getLocation(), Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLecternTake(PlayerTakeLecternBookEvent event) {
        check(event, event.getPlayer(), event.getLectern().getLocation(), Action.CONTAINER);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLecternInsert(PlayerInsertLecternBookEvent event) {
        check(event, event.getPlayer(), event.getBlock().getLocation(), Action.CONTAINER);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlowerPot(PlayerFlowerPotManipulateEvent event) {
        check(event, event.getPlayer(), event.getFlowerpot().getLocation(), Action.CONTAINER);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p) {
            check(event, p, event.getBlock().getLocation(), Action.CONTAINER);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        check(event, event.getPlayer(), event.getHarvestedBlock().getLocation(), Action.FARM);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        Player p = event.getPlayer();
        if (p == null) {
            return;
        }
        if (refused(p, event.getBlock().getLocation(), Action.FARM)) {
            event.setCancelled(true);
            return;
        }
        // Bone meal on your own grass mustn't grow flowers into a neighbour's claim.
        event.getBlocks().removeIf(s -> !Action.FARM
                .allowedFor(service.role(p, s.getLocation())));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Player p = responsible(event.getPrimingEntity());
        check(event, p, event.getBlock().getLocation(), Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !service.claimable(block.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        String type = block.getType().name();
        switch (event.getAction()) {
            case PHYSICAL -> BlockRules.step(type).ifPresent(a -> {
                if (refused(player, block.getLocation(), a)) {
                    event.setUseInteractedBlock(Event.Result.DENY);
                    event.setCancelled(true);
                }
            });
            case RIGHT_CLICK_BLOCK -> {
                BlockRules.rightClick(type).ifPresent(a -> {
                    if (refused(player, block.getLocation(), a)) {
                        event.setUseInteractedBlock(Event.Result.DENY);
                    }
                });
                ItemStack item = event.getItem();
                if (item != null && !item.getType().isEdible()) {
                    BlockRules.itemOnBlock(item.getType().name()).ifPresent(a -> {
                        if (refused(player, block.getLocation(), a)) {
                            event.setUseItemInHand(Event.Result.DENY);
                        }
                    });
                }
            }
            case LEFT_CLICK_BLOCK -> {
                // Putting out a fire on someone's roof, or teleporting their dragon egg.
                Block front = event.getBlockFace() == null ? null : block.getRelative(event.getBlockFace());
                boolean fire = front != null && (front.getType() == Material.FIRE || front.getType() == Material.SOUL_FIRE);
                if ((fire || block.getType() == Material.DRAGON_EGG)
                        && refused(player, block.getLocation(), Action.BUILD)) {
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }

    /** Arrows and tridents pressing buttons and plates, if a player shot them. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        Player p = responsible(event.getEntity());
        if (p != null && event.getEntity() instanceof Projectile) {
            check(event, p, event.getBlock().getLocation(), Action.USE);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Block hit = event.getHitBlock();
        Player p = responsible(event.getEntity());
        if (hit != null && p != null && BlockRules.changedByProjectile(hit.getType().name())) {
            check(event, p, hit.getLocation(), Action.BUILD);
        }
    }

    // ------------------------------------------------------------------ entities

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Action a = entityAction(event.getRightClicked());
        if (a != null) {
            check(event, event.getPlayer(), event.getRightClicked().getLocation(), a);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        check(event, event.getPlayer(), event.getRightClicked().getLocation(), Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = responsible(event.getDamager());
        if (attacker == null) {
            return;
        }
        Entity victim = event.getEntity();
        if (victim instanceof Player target) {
            // PvP inside a claim is off unless its PvP flag is on (rapid-horizons handles the rest).
            if (!attacker.equals(target) && service.claimable(victim.getWorld())
                    && (service.pvp(target.getLocation()) == ClaimService.PvpRule.DENY
                    || service.pvp(attacker.getLocation()) == ClaimService.PvpRule.DENY)) {
                event.setCancelled(true);
                attacker.sendActionBar(plugin.lang().get("protect.no-pvp"));
            }
            return;
        }
        Action a = damageAction(victim);
        if (a != null) {
            check(event, attacker, victim.getLocation(), a);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotion(PotionSplashEvent event) {
        Player thrower = responsible(event.getEntity());
        if (thrower == null) {
            return;
        }
        for (LivingEntity hit : event.getAffectedEntities()) {
            if (!allowedToAffect(thrower, hit)) {
                event.setIntensity(hit, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        Player source = responsible(event.getEntity());
        if (source != null) {
            event.getAffectedEntities().removeIf(hit -> !allowedToAffect(source, hit));
        }
    }

    /** Potions and clouds: other players only where PvP isn't off, protected mobs only with access. */
    private boolean allowedToAffect(Player source, LivingEntity hit) {
        if (hit.equals(source) || !service.claimable(hit.getWorld())) {
            return true;
        }
        if (hit instanceof Player) {
            return service.pvp(hit.getLocation()) != ClaimService.PvpRule.DENY;
        }
        Action a = entityAction(hit);
        return a == null || a.allowedFor(service.role(source, hit.getLocation()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player p = responsible(event.getRemover());
        check(event, p, event.getEntity().getLocation(), Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        check(event, event.getPlayer(), event.getEntity().getLocation(), Action.BUILD);
    }

    /** Boats, minecarts, armor stands and end crystals. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        check(event, event.getPlayer(), event.getEntity().getLocation(), Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        check(event, responsible(event.getAttacker()), event.getVehicle().getLocation(),
                Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        check(event, responsible(event.getAttacker()), event.getVehicle().getLocation(),
                Action.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player p) {
            Action a = entityAction(event.getVehicle());
            check(event, p, event.getVehicle().getLocation(),
                    a == Action.CONTAINER ? Action.USE : a);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        check(event, event.getPlayer(), event.getEntity().getLocation(), Action.ANIMALS);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        check(event, event.getPlayer(), event.getEntity().getLocation(), Action.ANIMALS);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        check(event, event.getPlayer(), event.getEntity().getLocation(), Action.ANIMALS);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        check(event, event.getPlayer(), event.getEntity().getLocation(), Action.ANIMALS);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDye(EntityDyeEvent event) {
        check(event, event.getPlayer(), event.getEntity().getLocation(), Action.ANIMALS);
    }

    /** Reeling in someone's animal or armor stand with a fishing rod. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Entity caught = event.getCaught();
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && caught != null) {
            Action a = entityAction(caught);
            if (a != null) {
                check(event, event.getPlayer(), caught.getLocation(), a);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }
}
