package us.rapidskies.core.world;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps protected spawn worlds ({@code protect: true}) exactly as they were built. Only
 * players with {@code rapid.world.build} can change blocks, item frames or armor stands, or
 * open containers and other decorated blocks (the Trading Post is full of lecterns and
 * barrels). Doors, gates, buttons, crafting tables and ender chests stay usable, and food can
 * still be eaten. Nature is frozen too: no fire, explosions, fading, spreading, leaf decay or
 * mobs changing blocks. PvP, mob spawning and damage are game rules ({@code worlds.*.game-rules}).
 */
public final class WorldProtection implements Listener {

    public static final String BUILD_PERMISSION = "rapid.world.build";

    /** Blocks anyone may use. */
    private static final Set<Material> USABLE = EnumSet.of(Material.CRAFTING_TABLE, Material.ENDER_CHEST);

    private final WorldService worlds;

    public WorldProtection(WorldService worlds) {
        this.worlds = worlds;
    }

    private boolean guarded(World world) {
        return worlds.isProtected(world);
    }

    private static boolean builder(Entity entity) {
        return entity instanceof Player p && p.hasPermission(BUILD_PERMISSION);
    }

    private void deny(Cancellable event, World world, Entity actor) {
        if (guarded(world) && !builder(actor)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ players

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        deny(event, event.getBlock().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        deny(event, event.getBlock().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        deny(event, event.getBlock().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        deny(event, event.getBlock().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !guarded(block.getWorld()) || builder(event.getPlayer())) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            Material type = block.getType();
            if (type == Material.FARMLAND || type == Material.TURTLE_EGG || type == Material.SNIFFER_EGG) {
                event.setUseInteractedBlock(Event.Result.DENY);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!usable(block.getType())) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
        // Items used on blocks (bone meal, hoes, axes, flint and steel, spawn eggs...) would
        // change the build; eating still works.
        ItemStack item = event.getItem();
        if (item != null && !item.getType().isAir() && !item.getType().isEdible()) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    private static boolean usable(Material type) {
        return USABLE.contains(type) || Tag.DOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)
                || Tag.BUTTONS.isTagged(type);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (target instanceof Hanging || target instanceof ArmorStand) {
            deny(event, target.getWorld(), event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        deny(event, event.getRightClicked().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageDecoration(EntityDamageByEntityEvent event) {
        Entity target = event.getEntity();
        if (target instanceof Hanging || target instanceof ArmorStand) {
            deny(event, target.getWorld(), event.getDamager());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Entity remover = event instanceof HangingBreakByEntityEvent byEntity ? byEntity.getRemover() : null;
        deny(event, event.getEntity().getWorld(), remover);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        deny(event, event.getEntity().getWorld(), event.getPlayer());
    }

    /** Armor stands, boats, minecarts, end crystals. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        deny(event, event.getEntity().getWorld(), event.getPlayer());
    }

    // ------------------------------------------------------------------ nature

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (guarded(event.getEntity().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (guarded(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        deny(event, event.getBlock().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        deny(event, event.getBlock().getWorld(), null);
    }

    /** Grass, mycelium, vines, fire and sculk spreading. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        deny(event, event.getBlock().getWorld(), null);
    }

    /** Coral dying out of water, ice and snow melting, grass turning to dirt. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        deny(event, event.getBlock().getWorld(), null);
    }

    /** Snow and ice forming, frost walker, concrete powder hardening. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        deny(event, event.getBlock().getWorld(), null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        deny(event, event.getBlock().getWorld(), null);
    }

    /** Endermen, ravagers, silverfish, players trampling or stripping; falling blocks may land. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) {
            deny(event, event.getBlock().getWorld(), event.getEntity());
        }
    }
}
