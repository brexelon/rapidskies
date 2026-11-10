package us.rapidskies.skyrealms.route;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.skyrealms.Give;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.SkyRealmsSettings;
import us.rapidskies.skyrealms.data.SkyRealmsRepository;
import us.rapidskies.skyrealms.island.Islands.IslandRef;
import us.rapidskies.skyrealms.tier.TierGoal;

/**
 * Trade Routes (PLAN.md §5.1): recurring contracts between islands. One island offers
 * ({@code /route offer}), the other's leader or a manager accepts. Then every interval the
 * route takes its goods out of the seller's contract chest (a chest or barrel on the seller's
 * island, so hoppers from a farm can fill it), and in one transaction the buyer island's
 * leader pays, the seller island's leader is paid (less tax) and the goods land in the buyer
 * island's depot ({@code /route depot}). Goods that left the chest but couldn't be delivered
 * go to the seller's own depot, never back into a world that may have moved on.
 *
 * <p>A delivery that can't happen (goods missing, chest gone, buyer can't pay) is a miss;
 * {@code max-missed} misses in a row end the route. Live routes are kept in memory.
 */
public final class RouteService {

    private final RapidSkyRealms plugin;
    private final Map<Long, Route> routes = new ConcurrentHashMap<>();
    private final Set<Long> delivering = ConcurrentHashMap.newKeySet();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public RouteService(RapidSkyRealms plugin) {
        this.plugin = plugin;
    }

    /** Loads the live routes (blocking, while enabling) and starts the delivery checks. */
    public void start() throws SQLException {
        for (Route r : plugin.repo().liveRoutes()) {
            routes.put(r.id(), r);
        }
        long period = plugin.settings().routes().checkSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
        plugin.getSLF4JLogger().info("Trade Routes: {} live", routes.size());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private SkyRealmsSettings.Routes cfg() {
        return plugin.settings().routes();
    }

    /** Live routes of an island, newest first. */
    public List<Route> of(UUID island) {
        return routes.values().stream().filter(r -> r.involves(island))
                .sorted(Comparator.comparingLong(Route::id).reversed()).toList();
    }

    public Optional<Route> get(long id) {
        return Optional.ofNullable(routes.get(id));
    }

    private int cap(UUID island) {
        return RouteRules.cap(cfg(), plugin.tiers().tier(island).index());
    }

    // ------------------------------------------------------------------ offers

    /**
     * Offers a route to another island. The held item says what's traded; {@code side} is the
     * offering island's role. Main thread.
     */
    public void offer(Player player, String target, Route.Side side, int qty, long price, String interval) {
        Optional<IslandRef> own = managedIsland(player);
        if (own.isEmpty()) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!Give.plain(hand)) {
            plugin.lang().send(player, "route.hold-item");
            return;
        }
        Optional<IslandRef> other = plugin.islands().find(target);
        if (other.isEmpty()) {
            plugin.lang().send(player, "route.no-such-island", Placeholder.unparsed("island", target));
            return;
        }
        Optional<String> refused = RouteRules.checkOffer(cfg(), qty, price, interval, own.get().id(), other.get().id());
        if (refused.isPresent()) {
            plugin.lang().send(player, refused.get(), limits());
            return;
        }
        if (RouteRules.live(routes.values(), own.get().id()) >= cap(own.get().id())) {
            plugin.lang().send(player, "route.full-own", Placeholder.unparsed("cap", Integer.toString(cap(own.get().id()))));
            return;
        }
        if (RouteRules.live(routes.values(), other.get().id()) >= cap(other.get().id())) {
            plugin.lang().send(player, "route.full-other", Placeholder.unparsed("island", other.get().name()));
            return;
        }
        boolean selling = side == Route.Side.SELLER;
        IslandRef seller = selling ? own.get() : other.get();
        IslandRef buyer = selling ? other.get() : own.get();
        Route draft = new Route(0, seller.id(), buyer.id(), seller.name(), buyer.name(), hand.getType(), qty, price,
                cfg().intervals().get(interval), Route.State.OFFERED, player.getUniqueId(), side, Optional.empty(),
                Optional.empty(), 0, 0, Instant.now());
        plugin.async(() -> plugin.repo().insertRoute(draft), id -> {
            Route r = new Route(id, draft.sellerIsland(), draft.buyerIsland(), draft.sellerName(), draft.buyerName(),
                    draft.item(), draft.qty(), draft.price(), draft.intervalHours(), draft.state(), draft.offeredBy(),
                    draft.offeredSide(), draft.chest(), draft.nextDue(), 0, 0, draft.createdAt());
            routes.put(id, r);
            plugin.lang().send(player, "route.offered", terms(r, own.get().id()));
            for (Player p : plugin.islands().onlineMembers(other.get().id())) {
                plugin.lang().send(p, "route.offer-received", terms(r, other.get().id()),
                        Placeholder.unparsed("from", own.get().name()));
            }
        });
    }

    public void accept(Player player, long id) {
        Route r = routes.get(id);
        Optional<IslandRef> own = managedIsland(player);
        if (own.isEmpty()) {
            return;
        }
        if (r == null || r.state() != Route.State.OFFERED || !r.answeringIsland().equals(own.get().id())) {
            plugin.lang().send(player, "route.no-offer", Placeholder.unparsed("id", Long.toString(id)));
            return;
        }
        if (RouteRules.live(routes.values().stream().filter(x -> x.state() == Route.State.ACTIVE).toList(),
                own.get().id()) >= cap(own.get().id())) {
            plugin.lang().send(player, "route.full-own", Placeholder.unparsed("cap", Integer.toString(cap(own.get().id()))));
            return;
        }
        Instant now = Instant.now();
        Instant first = now.plus(r.interval());
        plugin.async(() -> plugin.repo().signRoute(id, now, first), ok -> {
            if (!ok) {
                plugin.lang().send(player, "route.no-offer", Placeholder.unparsed("id", Long.toString(id)));
                return;
            }
            Route signed = r.signed(first);
            routes.put(id, signed);
            tellBoth(signed, "route.signed");
            for (Player p : plugin.islands().onlineMembers(signed.sellerIsland())) {
                plugin.lang().send(p, "route.set-chest-hint", Placeholder.unparsed("id", Long.toString(id)));
            }
        });
    }

    /** Declines an offer or cancels a route (either island's managers). */
    public void cancel(Player player, long id) {
        Route r = routes.get(id);
        Optional<IslandRef> own = managedIsland(player);
        if (own.isEmpty()) {
            return;
        }
        if (r == null || !r.involves(own.get().id())) {
            plugin.lang().send(player, "route.no-route", Placeholder.unparsed("id", Long.toString(id)));
            return;
        }
        String reason = r.state() == Route.State.OFFERED ? "declined" : "cancelled";
        end(r, reason, "route.ended-" + reason, player.getName());
    }

    /** Staff: ends any route. */
    public void adminEnd(long id, Runnable done) {
        Route r = routes.get(id);
        if (r != null) {
            end(r, "staff", "route.ended-staff", "staff");
        }
        done.run();
    }

    private void end(Route r, String reason, String messageKey, String who) {
        routes.remove(r.id());
        plugin.async(() -> plugin.repo().endRoute(r.id(), reason, Instant.now()), ok -> {
            if (ok) {
                tellBoth(r, messageKey, Placeholder.unparsed("player", who));
            }
        });
    }

    /** Every live route of a disbanded island ends. Main thread. */
    public void islandDisbanded(UUID island) {
        for (Route r : List.copyOf(routes.values())) {
            if (r.involves(island)) {
                end(r, "disbanded", "route.ended-disbanded", "");
            }
        }
    }

    // ------------------------------------------------------------------ the contract chest

    /** Makes the chest or barrel the player looks at the route's contract chest. */
    public void setChest(Player player, long id) {
        Route r = routes.get(id);
        Optional<IslandRef> own = managedIsland(player);
        if (own.isEmpty()) {
            return;
        }
        if (r == null || !r.sellerIsland().equals(own.get().id())) {
            plugin.lang().send(player, "route.not-seller", Placeholder.unparsed("id", Long.toString(id)));
            return;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Container)) {
            plugin.lang().send(player, "route.look-at-chest");
            return;
        }
        Optional<IslandRef> at = plugin.islands().at(block.getLocation());
        if (at.isEmpty() || !at.get().id().equals(own.get().id())) {
            plugin.lang().send(player, "route.chest-not-on-island");
            return;
        }
        Route.ChestPos pos = new Route.ChestPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        boolean taken = routes.values().stream().anyMatch(o -> o.id() != id && o.chest().map(pos::equals).orElse(false));
        if (taken) {
            plugin.lang().send(player, "route.chest-taken");
            return;
        }
        plugin.async(() -> {
            plugin.repo().setChest(id, pos);
            return null;
        }, v -> {
            routes.computeIfPresent(id, (k, old) -> old.withChest(pos));
            plugin.lang().send(player, "route.chest-set", terms(r, own.get().id()));
        });
    }

    // ------------------------------------------------------------------ deliveries

    private void tick() {
        Instant now = Instant.now();
        for (Route r : List.copyOf(routes.values())) {
            if (RouteRules.offerExpired(r, now, cfg().offerHours())) {
                end(r, "expired", "route.ended-expired", "");
            } else if (r.due(now) && delivering.add(r.id())) {
                try {
                    deliver(r, now);
                } catch (RuntimeException e) {
                    delivering.remove(r.id());
                    plugin.getSLF4JLogger().warn("Trade Route {} delivery failed: {}", r.id(), e.toString());
                }
            }
        }
    }

    /** Runs one due delivery (main thread); {@link #delivering} holds the route until it's settled. */
    private void deliver(Route r, Instant now) {
        Instant next = RouteRules.next(r.nextDue().orElse(now), r, now);
        Optional<IslandRef> seller = plugin.islands().byId(r.sellerIsland());
        Optional<IslandRef> buyer = plugin.islands().byId(r.buyerIsland());
        if (seller.isEmpty() || buyer.isEmpty()) {
            delivering.remove(r.id());
            end(r, "disbanded", "route.ended-disbanded", "");
            return;
        }
        Optional<Route.ChestPos> pos = r.chest();
        World world = pos.map(p -> Bukkit.getWorld(p.world())).orElse(null);
        if (world == null) {
            miss(r, next, "no-chest");
            return;
        }
        world.getChunkAtAsync(pos.get().x() >> 4, pos.get().z() >> 4).thenAccept(chunk -> {
            BlockState state = world.getBlockAt(pos.get().x(), pos.get().y(), pos.get().z()).getState();
            if (!(state instanceof Container box)) {
                miss(r, next, "no-chest");
                return;
            }
            if (Give.count(box.getInventory(), r.item()) < r.qty()) {
                miss(r, next, "short");
                return;
            }
            int taken = Give.take(box.getInventory(), r.item(), r.qty());
            if (taken < r.qty()) {
                returnToSeller(r, taken);
                miss(r, next, "short");
                return;
            }
            settle(r, seller.get(), buyer.get(), now, next);
        }).exceptionally(e -> {
            delivering.remove(r.id());
            plugin.getSLF4JLogger().warn("Couldn't load the contract chest of route {}: {}", r.id(), e.toString());
            return null;
        });
    }

    private void settle(Route r, IslandRef seller, IslandRef buyer, Instant now, Instant next) {
        int cycle = r.deliveries() + 1;
        long tax = RouteRules.tax(r.price(), cfg().taxPercent());
        plugin.async(() -> plugin.repo().deliver(plugin.core().modeLedger(EconomyMode.SKYREALMS), r, cycle,
                buyer.owner(), seller.owner(), tax, now, next, plugin.core().settings().serverId()), result -> {
            ModeEconomy economy = plugin.core().economy();
            switch (result.outcome()) {
                case DELIVERED -> {
                    delivering.remove(r.id());
                    routes.computeIfPresent(r.id(), (k, old) -> old.delivered(next));
                    if (economy != null) {
                        economy.changed(buyer.owner(), result.payerBalance());
                        economy.changed(seller.owner(), result.payeeBalance());
                    }
                    plugin.tiers().record(r.sellerIsland(), TierGoal.Type.ROUTE, null, 1);
                    tellBoth(r, "route.delivered", Placeholder.unparsed("income", String.format("%,d", r.price() - tax)));
                }
                case INSUFFICIENT -> {
                    if (economy != null) {
                        economy.changed(buyer.owner(), result.payerBalance());
                    }
                    returnToSeller(r, r.qty());
                    miss(r, next, "unpaid");
                }
                case GONE -> {
                    delivering.remove(r.id());
                    returnToSeller(r, r.qty());
                    reload(r.id());
                }
            }
        }, null, () -> {
            delivering.remove(r.id());
            returnToSeller(r, r.qty());
        });
    }

    /** Goods that left the chest but weren't delivered: into the seller island's depot. */
    private void returnToSeller(Route r, int amount) {
        if (amount <= 0) {
            return;
        }
        String item = r.item().getKey().getKey();
        plugin.async(() -> {
            plugin.repo().addToDepot(r.sellerIsland(), item, amount);
            return null;
        }, v -> {
            for (Player p : plugin.islands().onlineMembers(r.sellerIsland())) {
                plugin.lang().send(p, "route.returned", terms(r, r.sellerIsland()),
                        Placeholder.unparsed("amount", Integer.toString(amount)));
            }
        });
    }

    private void miss(Route r, Instant next, String why) {
        plugin.async(() -> plugin.repo().recordMiss(r.id(), next), misses -> {
            delivering.remove(r.id());
            routes.computeIfPresent(r.id(), (k, old) -> old.missedOne(next));
            tellBoth(r, "route.missed-" + why, Placeholder.unparsed("misses", Integer.toString(misses)),
                    Placeholder.unparsed("max", Integer.toString(cfg().maxMissed())));
            if (misses >= cfg().maxMissed()) {
                end(r, "missed", "route.ended-missed", "");
            }
        }, null, () -> delivering.remove(r.id()));
    }

    private void reload(long id) {
        plugin.async(() -> plugin.repo().route(id), fresh -> {
            if (fresh.isEmpty() || fresh.get().state() == Route.State.ENDED) {
                routes.remove(id);
            } else {
                routes.put(id, fresh.get());
            }
        });
    }

    // ------------------------------------------------------------------ the depot

    /** Takes up to {@code wanted} of an item out of the island's depot into the player's inventory. */
    public void withdraw(Player player, UUID island, Material item, long wanted, Runnable after) {
        int room = Give.room(player.getInventory(), item);
        long n = Math.min(wanted, room);
        if (n <= 0) {
            plugin.lang().send(player, "route.depot-full");
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            return;
        }
        String key = item.getKey().getKey();
        plugin.async(() -> plugin.repo().takeFromDepot(island, key, n), ok -> {
            busy.remove(player.getUniqueId());
            if (ok) {
                Give.items(player, List.of(Give.stacks(item, n)));
                plugin.lang().send(player, "route.depot-taken", Placeholder.unparsed("amount", Long.toString(n)),
                        Placeholder.component("item", Component.translatable(item.translationKey())));
            }
            after.run();
        }, player, () -> busy.remove(player.getUniqueId()));
    }

    // ------------------------------------------------------------------ helpers

    /** The player's island if they may manage its routes; tells them otherwise. */
    private Optional<IslandRef> managedIsland(Player player) {
        Optional<IslandRef> own = plugin.islands().of(player);
        if (own.isEmpty()) {
            plugin.lang().send(player, "errors.no-island");
            return Optional.empty();
        }
        if (!plugin.islands().canManage(player, own.get().id(), plugin.settings().manageRoleWeight())) {
            plugin.lang().send(player, "errors.not-manager");
            return Optional.empty();
        }
        return own;
    }

    private void tellBoth(Route r, String key, TagResolver... extra) {
        for (UUID island : List.of(r.sellerIsland(), r.buyerIsland())) {
            TagResolver[] all = new TagResolver[extra.length + 1];
            all[0] = terms(r, island);
            System.arraycopy(extra, 0, all, 1, extra.length);
            for (Player p : plugin.islands().onlineMembers(island)) {
                plugin.lang().send(p, key, all);
            }
        }
    }

    /** The route's terms as seen from {@code island}: id, partner, item, amount, price, interval, role. */
    public TagResolver terms(Route r, UUID island) {
        String interval = cfg().intervals().entrySet().stream().filter(e -> e.getValue() == r.intervalHours())
                .map(Map.Entry::getKey).findFirst().orElse(r.intervalHours() + "h");
        return TagResolver.resolver(
                Placeholder.unparsed("id", Long.toString(r.id())),
                Placeholder.unparsed("partner", r.partnerName(island)),
                Placeholder.unparsed("seller", r.sellerName()),
                Placeholder.unparsed("buyer", r.buyerName()),
                Placeholder.component("item", Component.translatable(r.item().translationKey())),
                Placeholder.unparsed("qty", String.format("%,d", r.qty())),
                Placeholder.unparsed("price", String.format("%,d", r.price())),
                Placeholder.unparsed("interval", interval),
                Placeholder.component("role", plugin.lang().get(r.sellerIsland().equals(island)
                        ? "route.role-selling" : "route.role-buying")));
    }

    private TagResolver limits() {
        return TagResolver.resolver(
                Placeholder.unparsed("min_qty", Integer.toString(cfg().minQuantity())),
                Placeholder.unparsed("max_qty", Integer.toString(cfg().maxQuantity())),
                Placeholder.unparsed("min_price", String.format("%,d", cfg().minPrice())),
                Placeholder.unparsed("max_price", String.format("%,d", cfg().maxPrice())),
                Placeholder.unparsed("intervals", String.join(", ", cfg().intervals().keySet())));
    }

    public Collection<String> intervals() {
        return cfg().intervals().keySet();
    }
}
