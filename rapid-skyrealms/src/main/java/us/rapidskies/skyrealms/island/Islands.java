package us.rapidskies.skyrealms.island;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

/**
 * rapid-skyrealms' view of SuperiorSkyblock2's islands. Everything else in the plugin goes
 * through this class (and {@link IslandSchematics}), so SuperiorSkyblock2's types stay in one
 * place and the rest is plain Java over {@link IslandRef}s. SuperiorSkyblock2 keeps every
 * island in memory, so these are cheap; call them on the main thread.
 */
public final class Islands {

    /** An island as the rest of the plugin sees it. {@link #name} is never blank. */
    public record IslandRef(UUID id, String name, UUID owner, String ownerName) {
    }

    private static final String NORMAL = "normal";

    private Dimension normal() {
        return Dimension.getByName(NORMAL);
    }

    private static Optional<IslandRef> ref(Island island) {
        if (island == null || island.isSpawn()) {
            return Optional.empty();
        }
        SuperiorPlayer owner = island.getOwner();
        String ownerName = owner == null ? "?" : owner.getName();
        return Optional.of(new IslandRef(island.getUniqueId(), displayName(island.getName(), ownerName),
                owner == null ? new UUID(0, 0) : owner.getUniqueId(), ownerName));
    }

    /**
     * The island's own name without color codes, or "Steve's Island" when it has none (names are
     * optional; PLAN.md keeps onboarding to one click).
     */
    static String displayName(String raw, String ownerName) {
        String plain = raw == null ? "" : PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace('§', '&'))).trim();
        return plain.isEmpty() ? ownerName + "'s Island" : plain;
    }

    private static Island island(UUID id) {
        return SuperiorSkyblockAPI.getGrid().getIslandByUUID(id);
    }

    public Optional<IslandRef> of(Player player) {
        return ref(SuperiorSkyblockAPI.getPlayer(player).getIsland());
    }

    public Optional<IslandRef> of(UUID player) {
        SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(player);
        return sp == null ? Optional.empty() : ref(sp.getIsland());
    }

    /** The island at a spot (not the spawn), if any. */
    public Optional<IslandRef> at(Location location) {
        return ref(SuperiorSkyblockAPI.getIslandAt(location));
    }

    public Optional<IslandRef> byId(UUID id) {
        return ref(island(id));
    }

    /** An island by its name, or by a member's name (the way players think of islands). */
    public Optional<IslandRef> find(String nameOrPlayer) {
        Optional<IslandRef> byName = ref(SuperiorSkyblockAPI.getIsland(nameOrPlayer));
        if (byName.isPresent()) {
            return byName;
        }
        SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(nameOrPlayer);
        return sp == null ? Optional.empty() : ref(sp.getIsland());
    }

    public List<IslandRef> all() {
        List<IslandRef> out = new ArrayList<>();
        for (Island i : SuperiorSkyblockAPI.getGrid().getIslands()) {
            ref(i).ifPresent(out::add);
        }
        return out;
    }

    /** Members including the owner. */
    public List<UUID> members(UUID islandId) {
        Island island = island(islandId);
        if (island == null) {
            return List.of();
        }
        return island.getIslandMembers(true).stream().map(SuperiorPlayer::getUniqueId).toList();
    }

    /** Members online here, including the owner. */
    public List<Player> onlineMembers(UUID islandId) {
        Island island = island(islandId);
        if (island == null) {
            return List.of();
        }
        List<Player> out = new ArrayList<>();
        for (SuperiorPlayer sp : island.getIslandMembers(true)) {
            Player p = sp.asPlayer();
            if (p != null && p.isOnline()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Whether {@code player} leads the island or holds a role of at least {@code weight}. */
    public boolean canManage(Player player, UUID islandId, int weight) {
        Island island = island(islandId);
        if (island == null) {
            return false;
        }
        SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(player);
        if (island.getOwner() != null && island.getOwner().getUniqueId().equals(player.getUniqueId())) {
            return true;
        }
        return island.isMember(sp) && sp.getPlayerRole() != null && sp.getPlayerRole().getWeight() >= weight;
    }

    public BigDecimal level(UUID islandId) {
        Island island = island(islandId);
        return island == null ? BigDecimal.ZERO : island.getIslandLevel();
    }

    public BigDecimal worth(UUID islandId) {
        Island island = island(islandId);
        return island == null ? BigDecimal.ZERO : island.getWorth();
    }

    /** Worth of every island, for the season leaderboard. */
    public Map<UUID, BigDecimal> worths() {
        Map<UUID, BigDecimal> out = new HashMap<>();
        for (Island i : SuperiorSkyblockAPI.getGrid().getIslands()) {
            if (!i.isSpawn()) {
                out.put(i.getUniqueId(), i.getWorth());
            }
        }
        return out;
    }

    /** The island's home in the overworld, or empty. */
    public Optional<Location> home(UUID islandId) {
        Island island = island(islandId);
        return island == null ? Optional.empty() : Optional.ofNullable(island.getIslandHome(normal()));
    }

    public boolean isIslandWorld(World world) {
        return SuperiorSkyblockAPI.getGrid().isIslandsWorld(world);
    }

    /**
     * Makes the island's cobblestone generator produce exactly {@code weights} (Realm Tiers own
     * the generator; SuperiorSkyblock2's generator upgrade is off in the skyrealms profile).
     * Leaves the island alone if it already matches, since every change is a database write for
     * SuperiorSkyblock2. Returns whether anything changed.
     */
    public boolean applyGenerator(UUID islandId, Map<Material, Integer> weights) {
        Island island = island(islandId);
        if (island == null) {
            return false;
        }
        Dimension dim = normal();
        int sum = weights.values().stream().mapToInt(Integer::intValue).sum();
        boolean same = island.getGeneratorTotalAmount(dim) == sum && weights.entrySet().stream()
                .allMatch(e -> island.getGeneratorAmount(Key.of(e.getKey()), dim) == e.getValue());
        if (same) {
            return false;
        }
        island.clearGeneratorAmounts(dim);
        weights.forEach((m, w) -> island.setGeneratorAmount(Key.of(m), w, dim));
        return true;
    }

    /** Sets the island's overworld biome; false if the biome doesn't exist on this server. */
    public boolean setBiome(UUID islandId, String biomeKey) {
        Island island = island(islandId);
        Biome biome = biome(biomeKey);
        if (island == null || biome == null) {
            return false;
        }
        island.setBiome(normal(), biome);
        return true;
    }

    /** The island's current overworld biome key ({@code plains}). */
    public Optional<String> biome(UUID islandId) {
        Island island = island(islandId);
        if (island == null || island.getBiome(normal()) == null) {
            return Optional.empty();
        }
        return Optional.of(island.getBiome(normal()).getKey().getKey());
    }

    static Biome biome(String key) {
        NamespacedKey k = key.contains(":") ? NamespacedKey.fromString(key) : NamespacedKey.minecraft(key);
        return k == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(k);
    }
}
