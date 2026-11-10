package us.rapidskies.claims;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import us.rapidskies.claims.model.Access;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Plot;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.data.ClaimRepository;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;

/**
 * Keeps several Horizons instances' indexes in step (PLAN.md §4.1, engineering: Redis
 * cache invalidation, ready for when Horizons scales past one server). Every change is
 * announced with what changed; other servers reload exactly that from the database, which
 * is the truth, so message order and duplicates don't matter.
 */
public final class Sync {

    private final RapidClaims plugin;
    private final RedisBus redis;
    private final String serverId;

    Sync(RapidClaims plugin, RedisBus redis, String serverId) {
        this.plugin = plugin;
        this.redis = redis;
        this.serverId = serverId;
    }

    void start() {
        redis.subscribeJson(Channels.CLAIMS, Channels.ClaimsChanged.class, this::onChange);
    }

    public void chunk(ChunkKey chunk) {
        publish("chunk", chunk.format());
    }

    public void owner(Owner owner) {
        publish("owner", owner.format());
    }

    public void town(UUID town) {
        publish("town", town.toString());
    }

    private void publish(String kind, String key) {
        Channels.ClaimsChanged msg = new Channels.ClaimsChanged(kind, key, serverId);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                redis.publishJson(Channels.CLAIMS, msg);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't announce a claims change ({} {}): {}", kind, key, e.getMessage());
            }
        });
    }

    // Redis thread: read the database off it, apply on the main thread.
    private void onChange(Channels.ClaimsChanged change) {
        if (serverId.equals(change.origin())) {
            return;
        }
        ClaimRepository repo = plugin.repo();
        try {
            switch (change.kind()) {
                case "chunk" -> {
                    ChunkKey chunk = ChunkKey.parse(change.key());
                    if (!plugin.settings().worlds().contains(chunk.world())) {
                        return;
                    }
                    plugin.async(() -> new ChunkState(repo.claim(chunk), repo.plot(chunk)), state -> {
                        state.claim().ifPresentOrElse(plugin.land()::put, () -> plugin.land().remove(chunk));
                        state.plot().ifPresentOrElse(plugin.land()::putPlot, () -> plugin.land().removePlot(chunk));
                    }, null);
                }
                case "owner" -> {
                    Owner owner = Owner.parse(change.key());
                    plugin.async(() -> repo.access(owner), (Access a) -> plugin.land().setAccess(owner, a), null);
                    if (owner.type() == Owner.Type.PLAYER) {
                        plugin.async(() -> repo.player(owner.id()), plugin.lands()::updateRow, null);
                    }
                }
                case "town" -> {
                    UUID id = UUID.fromString(change.key());
                    plugin.async(() -> repo.town(id), (Optional<Town> town) -> {
                        plugin.projects().invalidate(id);
                        if (town.isPresent()) {
                            plugin.land().putTown(town.get());
                        } else {
                            for (ChunkKey k : plugin.land().chunksOf(Owner.town(id))) {
                                plugin.land().remove(k);
                            }
                            plugin.land().removeTown(id);
                        }
                    }, null);
                }
                default -> plugin.getSLF4JLogger().debug("Unknown claims change kind {}", change.kind());
            }
        } catch (IllegalArgumentException e) {
            plugin.getSLF4JLogger().warn("Ignoring a malformed claims change {}: {}", change, e.getMessage());
        }
    }

    private record ChunkState(Optional<Claim> claim, Optional<Plot> plot) {
    }
}
