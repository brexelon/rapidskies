package us.rapidskies.claims.protect;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.model.LandIndex;

/**
 * Protection from things no player does directly (PLAN.md §4.1, protection coverage):
 * explosions (always blocked in claims), fire spread and burning (the fire flag), leaf
 * decay and natural monster spawning (flags), and anything acting across a claim border:
 * water and lava, pistons, dispensers, hoppers, trees, mushrooms and sculk growing, sponges,
 * and portals generated into someone's land. Mobs griefing (endermen, ravagers, withers,
 * silverfish, zombies on doors) can't change claimed blocks.
 *
 * <p>The border rule is {@link LandIndex#crossAllowed}: into wilderness always, into a
 * claim only from the same owner's land. Each check first compares chunk coordinates, so
 * the hot events (fluids, hoppers) cost almost nothing away from borders.
 */
public final class EnvironmentListener implements Listener {

    private final RapidClaims plugin;
    private final ClaimService service;
    private final LandIndex land;

    public EnvironmentListener(RapidClaims plugin) {
        this.plugin = plugin;
        this.service = plugin.service();
        this.land = plugin.land();
    }

    private boolean watched(World world) {
        return world != null && service.claimable(world);
    }

    private boolean claimed(Block b) {
        return land.isClaimed(ClaimService.chunk(b));
    }

    private Claim claimAt(Block b) {
        return land.claim(ClaimService.chunk(b)).orElse(null);
    }

    /** Whether something in {@code from} may change {@code to}. */
    private boolean cross(Block from, Block to) {
        if ((from.getX() >> 4) == (to.getX() >> 4) && (from.getZ() >> 4) == (to.getZ() >> 4)) {
            return true;
        }
        return land.crossAllowed(ClaimService.chunk(from), ClaimService.chunk(to));
    }

    private boolean cross(Location from, Location to) {
        if (from.getWorld() != to.getWorld()) {
            return true;
        }
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4) && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return true;
        }
        return land.crossAllowed(ClaimService.chunk(from), ClaimService.chunk(to));
    }

    // ------------------------------------------------------------------ explosions

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (watched(event.getEntity().getWorld())) {
            event.blockList().removeIf(this::claimed);
        }
    }

    /** Beds and respawn anchors set off in the wrong dimension. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (watched(event.getBlock().getWorld())) {
            event.blockList().removeIf(this::claimed);
        }
    }

    /** Item frames, armor stands, animals and vehicles in claims survive blasts too. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }
        Entity victim = event.getEntity();
        if (watched(victim.getWorld()) && ProtectionListener.damageAction(victim) != null
                && land.isClaimed(ClaimService.chunk(victim.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && watched(event.getEntity().getWorld())
                && land.isClaimed(ClaimService.chunk(event.getEntity().getLocation()))) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ fire

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Block b = event.getBlock();
        if (!watched(b.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        if (player != null) {
            if (!Action.BUILD.allowedFor(service.role(player, b.getLocation()))) {
                event.setCancelled(true);
            }
            return;
        }
        Claim claim = claimAt(b);
        if (claim == null) {
            return;
        }
        Block source = event.getIgnitingBlock();
        if (!claim.flag(Flag.FIRE_SPREAD) || (source != null && !cross(source, b))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block b = event.getBlock();
        if (!watched(b.getWorld())) {
            return;
        }
        Claim claim = claimAt(b);
        Block source = event.getIgnitingBlock();
        if (claim != null && (!claim.flag(Flag.FIRE_SPREAD) || (source != null && !cross(source, b)))) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ nature

    /** Fire, grass, mycelium, vines, mushrooms and sculk spreading. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Block b = event.getBlock();
        if (!watched(b.getWorld())) {
            return;
        }
        if (!cross(event.getSource(), b)) {
            event.setCancelled(true);
            return;
        }
        Material from = event.getSource().getType();
        if (from == Material.FIRE || from == Material.SOUL_FIRE) {
            Claim claim = claimAt(b);
            if (claim != null && !claim.flag(Flag.FIRE_SPREAD)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        Block b = event.getBlock();
        if (watched(b.getWorld())) {
            Claim claim = claimAt(b);
            if (claim != null && !claim.flag(Flag.LEAF_DECAY)) {
                event.setCancelled(true);
            }
        }
    }

    /** Trees and huge mushrooms only grow into land of the same owner (or wilderness). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        Location origin = event.getLocation();
        if (watched(origin.getWorld())) {
            event.getBlocks().removeIf(state -> !cross(origin, state.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent event) {
        Block sponge = event.getBlock();
        if (watched(sponge.getWorld())) {
            event.getBlocks().removeIf(state -> !cross(sponge, state.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL && reason != CreatureSpawnEvent.SpawnReason.PATROL
                && reason != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return;
        }
        if (event.getEntity() instanceof Enemy && watched(event.getLocation().getWorld())) {
            Claim claim = land.claim(ClaimService.chunk(event.getLocation())).orElse(null);
            if (claim != null && !claim.flag(Flag.MOB_SPAWNING)) {
                event.setCancelled(true);
            }
        }
    }

    /** Endermen, ravagers, withers, silverfish and zombies (on doors) changing claimed blocks. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Entity e = event.getEntity();
        if (e instanceof Player || e instanceof FallingBlock || !watched(event.getBlock().getWorld())) {
            return;
        }
        if ((e instanceof Enemy || e instanceof WitherSkull) && claimed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Mobs trampling claimed farmland (projectiles pressing buttons are ProtectionListener's). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof Projectile) && event.getBlock().getType() == Material.FARMLAND
                && watched(event.getBlock().getWorld()) && claimed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ across borders

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (watched(event.getBlock().getWorld()) && !cross(event.getBlock(), event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (watched(event.getBlock().getWorld()) && !pistonAllowed(event.getBlock(), event.getBlocks(),
                event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (watched(event.getBlock().getWorld()) && !pistonAllowed(event.getBlock(), event.getBlocks(),
                event.getDirection())) {
            event.setCancelled(true);
        }
    }

    /** Every moved block, where it ends up, and the piston head must all be reachable from the piston. */
    private boolean pistonAllowed(Block piston, List<Block> moved, BlockFace direction) {
        if (!cross(piston, piston.getRelative(direction))) {
            return false;
        }
        for (Block b : moved) {
            if (!cross(piston, b) || !cross(piston, b.getRelative(direction))) {
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block dispenser = event.getBlock();
        if (watched(dispenser.getWorld()) && dispenser.getBlockData() instanceof Directional d
                && !cross(dispenser, dispenser.getRelative(d.getFacing()))) {
            event.setCancelled(true);
        }
    }

    /** Hoppers (and hopper minecarts) pulling items out of someone else's claim. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        Location from = event.getSource().getLocation();
        Location to = event.getDestination().getLocation();
        if (from != null && to != null && watched(from.getWorld()) && !cross(to, from)) {
            event.setCancelled(true);
        }
    }

    /** Nether portals generated into a claim by someone not allowed to build there. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortal(PortalCreateEvent event) {
        if (!watched(event.getWorld())) {
            return;
        }
        Player player = event.getEntity() instanceof Player p ? p : null;
        for (BlockState state : event.getBlocks()) {
            ChunkKey chunk = ClaimService.chunk(state.getLocation());
            if (!land.isClaimed(chunk)) {
                continue;
            }
            if (player == null || !Action.BUILD.allowedFor(service.role(player, state.getLocation()))) {
                event.setCancelled(true);
                if (player != null) {
                    player.sendActionBar(plugin.lang().get("protect.portal"));
                }
                return;
            }
        }
    }
}
