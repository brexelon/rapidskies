package us.rapidskies.core.world;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Minecart;
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
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps protected spawn worlds ({@code protect: true}) exactly as they were built. Nobody,
 * staff included, can change blocks, item frames, armor stands or vehicles, or open
 * containers and other decorated blocks (the Trading Post is full of lecterns and barrels),
 * unless they're in {@link BuildMode} ({@code /build}, which needs {@code rapid.world.build}).
 * Doors, gates, buttons, crafting tables and ender chests stay usable, but items can't be
 * used on blocks or mobs or thrown (food, potions, armor and shields still work). Nature is frozen too: no fire, explosions, fading, spreading, leaf decay or mobs
 * changing blocks. PvP, mob spawning and damage are game rules ({@code worlds.*.game-rules}).
 */
public final class WorldProtection implements Listener {

    /** Blocks anyone may use. */
    private static final Set<Material> USABLE = EnumSet.of(Material.CRAFTING_TABLE, Material.ENDER_CHEST);

    private final WorldService worlds;
    private final BuildMode buildMode;

    public WorldProtection(WorldService worlds, BuildMode buildMode) {
        this.worlds = worlds;
        this.buildMode = buildMode;
    }

    private boolean guarded(World world) {
        return worlds.isProtected(world);
    }

    private boolean builder(Entity entity) {
        return entity instanceof Player p && buildMode.canBuild(p);
    }

    private void deny(Cancellable event, World world, Entity actor) {
        if (guarded(world) && !builder(actor)) {
            event.setCancelled(true);
            if (actor instanceof Player p) {
                buildMode.hint(p);
            }
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

    // Not ignoreCancelled: right-clicking air arrives cancelled, but the item in hand still
    // gets used unless it's denied.
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!guarded(player.getWorld()) || builder(player)) {
            return;
        }
        Block block = event.getClickedBlock();
        switch (event.getAction()) {
            case PHYSICAL -> {
                Material type = block == null ? Material.AIR : block.getType();
                if (type == Material.FARMLAND || type == Material.TURTLE_EGG || type == Material.SNIFFER_EGG) {
                    event.setUseInteractedBlock(Event.Result.DENY);
                }
            }
            case RIGHT_CLICK_BLOCK -> {
                if (block != null && !usable(block.getType())) {
                    event.setUseInteractedBlock(Event.Result.DENY);
                }
                denyItem(event, player);
            }
            // Pearls, snowballs, eggs, splash potions, bows, rods, wind charges, spawn eggs...
            case RIGHT_CLICK_AIR -> denyItem(event, player);
            default -> {
            }
        }
    }

    /**
     * Items used on blocks or thrown (bone meal, hoes, axes, flint and steel, spawn eggs,
     * projectiles...) would change the build or get around it. Eating, drinking, putting on
     * armor and holding up a shield still work, and so do rockets while gliding.
     */
    private static void denyItem(PlayerInteractEvent event, Player player) {
        ItemStack item = event.getItem();
        if (item != null && !harmless(item.getType(), player)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    private static boolean harmless(Material type, Player player) {
        if (type.isAir() || type.isEdible() || type == Material.POTION || type == Material.MILK_BUCKET) {
            return true;
        }
        if (type == Material.FIREWORK_ROCKET) {
            return player.isGliding();
        }
        // Armor, elytra and shields (anything that goes in a slot other than the main hand).
        // Wearable blocks like carved pumpkins still can't be placed (onPlace).
        return type.isItem() && type.getEquipmentSlot() != EquipmentSlot.HAND;
    }

    private static boolean usable(Material type) {
        return USABLE.contains(type) || Tag.DOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)
                || Tag.BUTTONS.isTagged(type);
    }

    /**
     * Item frames, armor stands and container boats and minecarts are part of the build.
     * Mobs can't be changed with an item (name tags, leads, shears, dyes, buckets, saddles,
     * spawn eggs, food); an empty hand is fine. Players (Citizens NPCs among them) and
     * interaction entities (plugins' click boxes) are left alone.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (target instanceof Player || target instanceof Interaction) {
            return;
        }
        if (target instanceof Hanging || target instanceof ArmorStand
                || ((target instanceof Boat || target instanceof Minecart) && target instanceof InventoryHolder)) {
            deny(event, target.getWorld(), event.getPlayer());
            return;
        }
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (!held.isEmpty()) {
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

    /** Boats and minecarts that are part of a build. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        deny(event, event.getVehicle().getWorld(), event.getAttacker());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        deny(event, event.getVehicle().getWorld(), event.getAttacker());
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
