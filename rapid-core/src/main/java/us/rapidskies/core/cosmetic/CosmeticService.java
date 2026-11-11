package us.rapidskies.core.cosmetic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import us.rapidskies.common.cosmetic.CosmeticRepository;
import us.rapidskies.common.cosmetic.CosmeticRepository.Wardrobe;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.core.cosmetic.Cosmetic.Obtain;
import us.rapidskies.core.rank.RankDisplay;

/**
 * The cosmetics engine (PLAN.md §6.1, §6.2): what each online player owns and wears, kept in
 * memory (chat reads it on the async chat thread) and loaded on join.
 *
 * <p>A cosmetic is usable if it's owned ({@code cosmetics_owned}) or it's a rank-set
 * cosmetic and the player holds that rank now. What's worn is remembered even while it isn't
 * usable, so a rank set comes back with the rank.
 *
 * <p>Other systems grant through {@link #grant}, which also works for players who are
 * offline or on another server: the server they're on is told over Redis to reload them.
 */
public final class CosmeticService implements Listener {

    private final Plugin plugin;
    private final Catalog catalog;
    private final Database db;
    private final CosmeticRepository repository;
    private final RankDisplay ranks;
    private final RedisBus redis;
    private final String serverId;
    private final Lang lang;
    private final Map<UUID, Wardrobe> wardrobes = new ConcurrentHashMap<>();

    public CosmeticService(Plugin plugin, Catalog catalog, Database db, RankDisplay ranks, RedisBus redis,
                           String serverId, Lang lang) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.db = db;
        this.repository = new CosmeticRepository(db);
        this.ranks = ranks;
        this.redis = redis;
        this.serverId = serverId;
        this.lang = lang;
        redis.subscribeJson(Channels.COSMETICS, Channels.CosmeticsChanged.class, this::onRemote);
    }

    public Catalog catalog() {
        return catalog;
    }

    public CosmeticRepository repository() {
        return repository;
    }

    // ------------------------------------------------------------------ reading (any thread)

    /** Whether the player may wear {@code c} right now. */
    public boolean has(Player player, Cosmetic c) {
        if (c.obtain() == Obtain.RANK) {
            return ranks.rank(player).atLeast(c.rank());
        }
        Wardrobe w = wardrobes.get(player.getUniqueId());
        return w != null && w.owned().contains(c.id());
    }

    /** Owned for good (bought or earned), ignoring rank sets. */
    public boolean owns(UUID player, String id) {
        Wardrobe w = wardrobes.get(player);
        return w != null && w.owned().contains(id);
    }

    /** What the player wears in a slot, if it's usable now. */
    public Optional<Cosmetic> worn(Player player, CosmeticType type) {
        Wardrobe w = wardrobes.get(player.getUniqueId());
        if (w == null) {
            return Optional.empty();
        }
        String id = w.equipped().get(type.id());
        if (id == null) {
            return Optional.empty();
        }
        return catalog.find(id).filter(c -> c.type() == type && has(player, c));
    }

    /** The id equipped in a slot, usable or not. */
    public Optional<String> equippedId(Player player, CosmeticType type) {
        Wardrobe w = wardrobes.get(player.getUniqueId());
        return w == null ? Optional.empty() : Optional.ofNullable(w.equipped().get(type.id()));
    }

    /**
     * Every cosmetic of a type to list for the player: usable ones first (by rarity, then
     * name), then locked ones; numbered ones only when owned.
     */
    public List<Cosmetic> listing(Player player, CosmeticType type) {
        List<Cosmetic> out = new ArrayList<>(catalog.ofType(type));
        Wardrobe w = wardrobes.get(player.getUniqueId());
        if (w != null) {
            for (String id : w.owned()) {
                if (id.startsWith(type.id() + ":") && !out.stream().anyMatch(c -> c.id().equals(id))) {
                    catalog.find(id).ifPresent(out::add);
                }
            }
        }
        out.sort(Comparator.comparing((Cosmetic c) -> !has(player, c))
                .thenComparing(c -> c.rarity().ordinal())
                .thenComparing(Cosmetic::name));
        return out;
    }

    /** How many cosmetics the player owns for good. */
    public int ownedCount(Player player) {
        Wardrobe w = wardrobes.get(player.getUniqueId());
        return w == null ? 0 : w.owned().size();
    }

    /** A title, tag or badge rendered. */
    public Component render(Cosmetic c) {
        return Lang.parse(c.display());
    }

    // ------------------------------------------------------------------ changing (main thread)

    /** Wears {@code c}; {@code after} runs on the main thread once saved. */
    public void equip(Player player, Cosmetic c, Runnable after) {
        if (!has(player, c)) {
            lang.send(player, "cosmetics.locked", Placeholder.unparsed("name", c.name()));
            return;
        }
        UUID id = player.getUniqueId();
        db.async(() -> {
            repository.equip(id, c.slot(), c.id());
            return null;
        }).whenComplete((v, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("Couldn't equip {} for {}: {}", c.id(), player.getName(), e.getMessage());
                lang.send(player, "cosmetics.failed");
                return;
            }
            wardrobes.computeIfPresent(id, (k, w) -> withEquipped(w, c.slot(), c.id()));
            lang.send(player, "cosmetics.equipped", Placeholder.unparsed("name", c.name()));
            after.run();
        }));
    }

    public void unequip(Player player, CosmeticType type, Runnable after) {
        UUID id = player.getUniqueId();
        db.async(() -> {
            repository.unequip(id, type.id());
            return null;
        }).whenComplete((v, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("Couldn't unequip {} for {}: {}", type.id(), player.getName(),
                        e.getMessage());
                lang.send(player, "cosmetics.failed");
                return;
            }
            wardrobes.computeIfPresent(id, (k, w) -> withEquipped(w, type.id(), null));
            after.run();
        }));
    }

    /**
     * Grants a cosmetic for good, whether the player is online here, elsewhere or offline.
     * Completes with whether it was new to them. Unknown ids are refused (logged).
     */
    public CompletableFuture<Boolean> grant(UUID player, String id, String source) {
        if (!catalog.exists(id)) {
            plugin.getSLF4JLogger().warn("Not granting unknown cosmetic '{}' to {} (from {})", id, player, source);
            return CompletableFuture.completedFuture(false);
        }
        return db.async(() -> repository.grant(player, id, source)).thenApply(isNew -> {
            if (isNew) {
                changed(player, List.of(id));
            }
            return isNew;
        });
    }

    /** Takes a cosmetic away (staff). */
    public CompletableFuture<Boolean> revoke(UUID player, String id) {
        return db.async(() -> repository.revoke(player, id)).thenApply(had -> {
            if (had) {
                changed(player, List.of());
            }
            return had;
        });
    }

    /**
     * After cosmetics were granted in another transaction (a purchase, a level-up, a pass
     * tier): reloads the player here, or tells the server they're on, and names what's new.
     */
    public void changed(UUID player, Collection<String> unlocked) {
        if (Bukkit.getPlayer(player) != null) {
            reload(player, unlocked);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                redis.publishJson(Channels.COSMETICS,
                        new Channels.CosmeticsChanged(player.toString(), List.copyOf(unlocked), serverId));
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't announce cosmetics for {}: {}", player, e.getMessage());
            }
        });
    }

    /** Re-reads a player's wardrobe and announces {@code unlocked}. */
    public void reload(UUID player, Collection<String> unlocked) {
        db.async(() -> repository.load(player)).whenComplete((w, e) -> {
            if (e != null) {
                plugin.getSLF4JLogger().warn("Couldn't load cosmetics of {}: {}", player, e.getMessage());
                return;
            }
            Player online = Bukkit.getPlayer(player);
            if (online == null) {
                return;
            }
            wardrobes.put(player, w);
            announce(online, unlocked);
        });
    }

    /** "Unlocked: Wanderer (title)" for each new cosmetic, with a button to the menu. */
    public void announce(Player player, Collection<String> unlocked) {
        for (String id : unlocked) {
            catalog.find(id).ifPresent(c -> lang.send(player, "cosmetics.unlocked",
                    Placeholder.unparsed("name", c.name()),
                    Placeholder.component("type", lang.get("cosmetics.type." + c.type().id()))));
        }
    }

    private static Wardrobe withEquipped(Wardrobe w, String slot, String id) {
        Map<String, String> equipped = new HashMap<>(w.equipped());
        if (id == null) {
            equipped.remove(slot);
        } else {
            equipped.put(slot, id);
        }
        return new Wardrobe(w.owned(), Map.copyOf(equipped));
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        reload(event.getPlayer().getUniqueId(), List.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        wardrobes.remove(event.getPlayer().getUniqueId());
    }

    // Redis subscriber thread.
    private void onRemote(Channels.CosmeticsChanged change) {
        if (serverId.equals(change.origin())) {
            return;
        }
        UUID id = change.player();
        if (Bukkit.getPlayer(id) != null) {
            reload(id, change.unlocked() == null ? List.of() : change.unlocked());
        }
    }

    /** For tests and admin listings: the owned ids of an online player. */
    public java.util.Set<String> ownedIds(Player player) {
        Wardrobe w = wardrobes.get(player.getUniqueId());
        return w == null ? java.util.Set.of() : new HashSet<>(w.owned());
    }

    /** Loads an offline player's wardrobe (admin). Blocking. */
    public Wardrobe load(UUID player) throws SQLException {
        return repository.load(player);
    }
}
