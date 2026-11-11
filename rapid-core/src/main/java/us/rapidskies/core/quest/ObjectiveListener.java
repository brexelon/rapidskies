package us.rapidskies.core.quest;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

/**
 * Turns gameplay into quest objectives (PLAN.md §7): natural blocks broken, ripe crops
 * harvested, mobs killed and fish caught. Blocks a player placed this session never count
 * when broken (a place-and-break loop earns nothing); spawner mobs never count; creative and
 * spectator never count.
 */
public final class ObjectiveListener implements Listener {

    /** Placed blocks remembered, most recent last. Enough for a busy server's session. */
    private static final int PLACED_MEMORY = 100_000;

    private static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.PITCHER_CROP);
    /** Grown from a stem: a harvest unless the block was placed. */
    private static final Set<Material> STEM_FRUIT = EnumSet.of(Material.MELON, Material.PUMPKIN);

    private final QuestService quests;
    private final Map<BlockKey, Boolean> placed = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, Boolean> eldest) {
            return size() > PLACED_MEMORY;
        }
    };

    private record BlockKey(java.util.UUID world, int x, int y, int z) {
        static BlockKey of(Block b) {
            return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    public ObjectiveListener(QuestService quests) {
        this.quests = quests;
    }

    private static boolean counts(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        placed.put(BlockKey.of(event.getBlockPlaced()), Boolean.TRUE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        boolean wasPlaced = placed.remove(BlockKey.of(block)) != null;
        Player player = event.getPlayer();
        if (!counts(player)) {
            return;
        }
        Material type = block.getType();
        if (CROPS.contains(type) && block.getBlockData() instanceof Ageable crop
                && crop.getAge() >= crop.getMaximumAge()) {
            // Crops are always planted, so "placed" doesn't apply; only ripe ones count.
            quests.record(player, "harvest-crops", 1, type, null);
            return;
        }
        if (wasPlaced) {
            return;
        }
        if (STEM_FRUIT.contains(type)) {
            quests.record(player, "harvest-crops", 1, type, null);
            return;
        }
        quests.record(player, "break-blocks", 1, type, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || entity instanceof Player || entity.fromMobSpawner() || !counts(killer)) {
            return;
        }
        quests.record(killer, "kill-mobs", 1, null, entity.getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item
                && counts(event.getPlayer())) {
            quests.record(event.getPlayer(), "catch-fish", 1);
        }
    }
}
