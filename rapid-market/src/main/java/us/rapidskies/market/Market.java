package us.rapidskies.market;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.staff.StaffActions;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.market.custody.Custody;
import us.rapidskies.market.data.MarketRepository;
import us.rapidskies.market.item.Items;
import us.rapidskies.market.model.Category;
import us.rapidskies.market.model.Listing;
import us.rapidskies.market.model.Notice;
import us.rapidskies.market.model.Prices;
import us.rapidskies.market.model.Stored;

/**
 * The auction house's flows (PLAN.md §4.2): listing, buying, cancelling, collecting,
 * expiry, notices and alerts. Menus and commands call in here; the database work is
 * {@link MarketRepository}'s and the item hand-offs are guarded by {@link Custody}.
 *
 * <p>Every flow starts and ends on the main thread, with the database work in between off
 * it. A player can have one listing and one purchase in flight at a time, so double clicks
 * do nothing.
 */
public final class Market implements Listener {

    /** What the sell menu was opened with; the item must still match it when confirmed. */
    public record SellDraft(ItemStack snapshot, int slot, long price, Duration duration, String itemName,
                            String itemKey, Category category, OptionalDouble average, int maxListings) {

        public SellDraft withDuration(Duration d) {
            return new SellDraft(snapshot, slot, price, d, itemName, itemKey, category, average, maxListings);
        }
    }

    private final RapidMarket plugin;
    private final MarketRepository repo;
    private final Custody custody;
    private final ModeEconomy economy;
    private final MarketService service;
    private final String serverId;
    private final Set<UUID> selling = ConcurrentHashMap.newKeySet();
    private final Set<UUID> buying = ConcurrentHashMap.newKeySet();
    private BukkitTask expiryTask;
    private BukkitTask custodyTask;

    Market(RapidMarket plugin, MarketRepository repo, Custody custody, ModeEconomy economy, MarketService service) {
        this.plugin = plugin;
        this.repo = repo;
        this.custody = custody;
        this.economy = economy;
        this.service = service;
        this.serverId = RapidCore.get().settings().serverId();
        RapidCore.get().redis().subscribeJson(Channels.MARKET, Channels.MarketNotice.class, this::onRemoteNotice);
    }

    void start() {
        long expiry = plugin.settings().expirySeconds() * 20L;
        long retry = plugin.settings().custodySeconds() * 20L;
        expiryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::expireDue, 100L, expiry);
        // The first run settles whatever a crash left behind, before anyone could collect.
        custodyTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::recoverCustody, 0L, retry);
    }

    void stop() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        if (custodyTask != null) {
            custodyTask.cancel();
        }
    }

    private Lang lang() {
        return plugin.lang();
    }

    public MarketRepository repo() {
        return repo;
    }

    public EconomyMode mode() {
        return economy.mode();
    }

    // ------------------------------------------------------------------ selling

    /**
     * {@code /ah sell <price> [hours]}: checks the item in hand and the price, then opens the
     * sell menu with the fee, the average price and the duration choice. Nothing is taken yet.
     */
    public void prepareSell(Player p, String priceArg, String hoursArg) {
        if (!canTrade(p)) {
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            lang().send(p, "sell.empty-hand");
            return;
        }
        String key = hand.getType().getKey().asString();
        if (plugin.settings().blockedItems().contains(key)) {
            lang().send(p, "sell.blocked");
            return;
        }
        var price = Prices.parse(priceArg);
        MarketSettings s = plugin.settings();
        if (price.isEmpty() || price.getAsLong() < s.minPrice() || price.getAsLong() > s.maxPrice()) {
            lang().send(p, "sell.bad-price", money("min", s.minPrice()), money("max", s.maxPrice()));
            return;
        }
        var duration = s.duration(hoursArg);
        if (duration.isEmpty()) {
            lang().send(p, "sell.bad-duration", Placeholder.unparsed("hours", String.join(", ",
                    s.durations().stream().map(d -> d.toHours() + "h").toList())));
            return;
        }
        ItemStack snapshot = hand.clone();
        int slot = p.getInventory().getHeldItemSlot();
        String name = Items.trueName(snapshot);
        String priceKey = Items.priceKey(snapshot);
        int max = service.maxListings(p.getUniqueId());
        UUID id = p.getUniqueId();
        record Pre(int active, OptionalDouble average) {
        }
        plugin.async(() -> new Pre(repo.activeCount(id), repo.averageUnitPrice(priceKey)), pre -> {
            if (!p.isOnline()) {
                return;
            }
            if (pre.active() >= max) {
                lang().send(p, "sell.too-many", Placeholder.unparsed("max", Integer.toString(max)));
                return;
            }
            SellDraft draft = new SellDraft(snapshot, slot, price.getAsLong(), duration.get(), name, priceKey,
                    Items.category(snapshot), pre.average(), max);
            new us.rapidskies.market.gui.SellMenu(plugin, draft).open(p);
        }, p);
    }

    /** The sell menu's confirm: takes the item and lists it. */
    public void list(Player p, SellDraft d) {
        if (!canTrade(p) || !selling.add(p.getUniqueId())) {
            return;
        }
        ItemStack now = p.getInventory().getItem(d.slot());
        if (now == null || !now.isSimilar(d.snapshot()) || now.getAmount() != d.snapshot().getAmount()) {
            selling.remove(p.getUniqueId());
            p.closeInventory();
            lang().send(p, "sell.item-changed");
            return;
        }
        byte[] bytes = Items.serialize(now);
        UUID token = UUID.randomUUID();
        Custody.Entry entry = Custody.Entry.outgoing(token, mode().id(), p.getUniqueId(), bytes, d.itemName(),
                now.getAmount());
        try {
            custody.hold(entry);
        } catch (IOException e) {
            selling.remove(p.getUniqueId());
            plugin.getSLF4JLogger().error("Couldn't write the custody journal; refusing to list", e);
            lang().send(p, "errors.custody");
            return;
        }
        // Out of the inventory and saved before the database knows about it: a crash from here
        // on can't bring the item back to the inventory, and the journal brings it back to the
        // bin if the listing doesn't go through.
        p.getInventory().setItem(d.slot(), null);
        p.saveData();
        p.closeInventory();
        long fee = plugin.settings().fees().listingFee(d.price());
        var n = new MarketRepository.NewListing(token, p.getUniqueId(), p.getName(), bytes, d.itemName(),
                d.itemKey(), d.category(), now.getAmount(), d.price(), fee, d.duration());
        UUID seller = p.getUniqueId();
        plugin.async(() -> repo.create(n, d.maxListings()), result -> {
            selling.remove(seller);
            switch (result) {
                case MarketRepository.Created.Listed l -> {
                    custody.release(entry.id());
                    economy.changed(seller, l.balance());
                    lang().send(p, "sell.listed", Placeholder.unparsed("item", d.itemName()),
                            Placeholder.unparsed("amount", Integer.toString(n.amount())), money("price", d.price()),
                            money("fee", fee), Placeholder.unparsed("time", Durations.format(d.duration())));
                }
                case MarketRepository.Created.AlreadyListed a -> {
                    custody.release(entry.id());
                    lang().send(p, "sell.listed-short");
                }
                case MarketRepository.Created.TooMany t -> {
                    lang().send(p, "sell.too-many", Placeholder.unparsed("max", Integer.toString(d.maxListings())));
                    giveBack(p, entry);
                }
                case MarketRepository.Created.CantAfford c -> {
                    lang().send(p, "sell.cant-afford-fee", money("fee", fee), money("balance", c.balance()));
                    giveBack(p, entry);
                }
            }
        }, () -> {
            // Unknown whether it committed: recovery checks the token and settles it.
            selling.remove(seller);
            custody.unbusy(entry.id());
            lang().send(p, "sell.uncertain");
        }, null);
    }

    /** A refused listing: the item goes back to the inventory if it fits, else to the bin. */
    private void giveBack(Player p, Custody.Entry entry) {
        ItemStack item = Items.deserialize(entry.item());
        if (p.isOnline() && Items.fits(p.getInventory(), item)) {
            p.getInventory().addItem(item);
            p.saveData();
            custody.release(entry.id());
        } else {
            custody.unbusy(entry.id());
            lang().send(p, "sell.returned-to-bin");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::recoverCustody);
        }
    }

    // ------------------------------------------------------------------ buying

    public void buy(Player p, Listing l) {
        if (!canTrade(p) || !buying.add(p.getUniqueId())) {
            return;
        }
        UUID buyer = p.getUniqueId();
        String name = p.getName();
        MarketSettings s = plugin.settings();
        plugin.async(() -> repo.buy(l.id(), buyer, name, s.fees(), s.alerts()), result -> {
            buying.remove(buyer);
            switch (result) {
                case MarketRepository.Bought.Done d -> bought(p, d);
                case MarketRepository.Bought.Gone g -> lang().send(p, "buy.gone");
                case MarketRepository.Bought.Insufficient i -> lang().send(p, "buy.insufficient",
                        money("price", l.price()), money("balance", i.balance()));
                case MarketRepository.Bought.OwnListing o -> lang().send(p, "buy.own");
            }
        }, () -> buying.remove(buyer), p);
    }

    private void bought(Player p, MarketRepository.Bought.Done d) {
        Listing l = d.listing();
        // Quests and achievements (PLAN.md §7).
        RapidCore.get().quests().record(p, "ah-buy", 1);
        economy.changed(p.getUniqueId(), d.buyerBalance());
        economy.changed(l.seller(), d.sellerBalance());
        lang().send(p, "buy.bought", Placeholder.unparsed("item", l.itemName()),
                Placeholder.unparsed("amount", Integer.toString(l.amount())), money("price", l.price()),
                Placeholder.unparsed("seller", l.sellerName()));
        notify(l.seller(), d.noticeId());
        Stored s = new Stored(d.storedId(), l.mode(), p.getUniqueId(), l.item(), l.itemName(), l.amount(),
                Stored.Reason.BOUGHT, l.id(), Instant.now());
        collect(p, s, delivered -> {
            if (!delivered) {
                lang().send(p, "buy.to-collection");
            }
        });
        if (d.flagged()) {
            checkAlert(l, p.getUniqueId(), p.getName());
        }
    }

    // ------------------------------------------------------------------ cancelling and staff removal

    public void cancel(Player p, Listing l) {
        UUID seller = p.getUniqueId();
        plugin.async(() -> repo.cancel(l.id(), seller), result -> {
            switch (result) {
                case MarketRepository.Closed.Done d -> {
                    lang().send(p, "cancel.done", Placeholder.unparsed("item", l.itemName()));
                    Stored s = new Stored(d.storedId(), l.mode(), seller, l.item(), l.itemName(), l.amount(),
                            Stored.Reason.CANCELLED, l.id(), Instant.now());
                    collect(p, s, delivered -> {
                        if (!delivered) {
                            lang().send(p, "cancel.to-collection");
                        }
                    });
                }
                case MarketRepository.Closed.NotActive n -> lang().send(p, "cancel.not-active");
                default -> lang().send(p, "cancel.not-found");
            }
        }, p);
    }

    /** {@code /ah admin}: takes a listing down; the item goes back to the seller's bin with a notice. */
    public void remove(Player staff, long id, Runnable after) {
        UUID by = staff.getUniqueId();
        plugin.async(() -> {
            MarketRepository.Closed c = repo.remove(id, by);
            if (c instanceof MarketRepository.Closed.Done d) {
                new StaffActions(RapidCore.get().database()).log(by, "market-remove", d.listing().seller(), serverId,
                        "#" + id + " " + d.listing().itemName() + " x" + d.listing().amount() + " @ "
                                + d.listing().price());
            }
            return c;
        }, result -> {
            switch (result) {
                case MarketRepository.Closed.Done d -> {
                    lang().send(staff, "admin.removed", Placeholder.unparsed("id", Long.toString(id)),
                            Placeholder.unparsed("item", d.listing().itemName()),
                            Placeholder.unparsed("seller", d.listing().sellerName()));
                    notify(d.listing().seller(), d.noticeId());
                }
                case MarketRepository.Closed.NotActive n -> lang().send(staff, "admin.not-active",
                        Placeholder.unparsed("status", n.status().name().toLowerCase(java.util.Locale.ROOT)));
                default -> lang().send(staff, "admin.not-found", Placeholder.unparsed("id", Long.toString(id)));
            }
            if (after != null) {
                after.run();
            }
        }, staff);
    }

    // ------------------------------------------------------------------ collecting

    /**
     * Moves one bin item into the inventory if it all fits; {@code done} gets whether it
     * arrived (false: it's still in the bin, or on its way back there).
     */
    public void collect(Player p, Stored s, Consumer<Boolean> done) {
        ItemStack item = Items.deserialize(s.item());
        Custody.Entry entry = Custody.Entry.incoming(s.id(), mode().id(), p.getUniqueId(), s.item(), s.itemName(),
                s.amount());
        if (!p.isOnline() || !Items.fits(p.getInventory(), item) || custody.pending(entry.id())) {
            done.accept(false);
            return;
        }
        try {
            custody.hold(entry);
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Couldn't write the custody journal; leaving the item in the bin", e);
            done.accept(false);
            return;
        }
        UUID owner = p.getUniqueId();
        plugin.async(() -> repo.take(s.id(), owner), taken -> {
            if (!taken) {
                // Already gone (collected on another server): that move delivered it.
                custody.release(entry.id());
                done.accept(false);
                return;
            }
            if (p.isOnline() && Items.fits(p.getInventory(), item)) {
                Map<Integer, ItemStack> left = p.getInventory().addItem(item);
                if (!left.isEmpty()) {
                    // Can't happen (it fitted this tick), but if it does, whatever didn't fit is
                    // journaled on its own and goes back to the bin.
                    holdRest(entry, left.values());
                }
                p.saveData();
                custody.release(entry.id());
                done.accept(true);
            } else {
                // Left or filled up meanwhile: the journal puts it back in the bin.
                custody.unbusy(entry.id());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, this::recoverCustody);
                done.accept(false);
            }
        }, () -> {
            custody.unbusy(entry.id());
            done.accept(false);
        }, p);
    }

    private void holdRest(Custody.Entry entry, java.util.Collection<ItemStack> rest) {
        for (ItemStack r : rest) {
            byte[] bytes = Items.serialize(r);
            Custody.Entry e = new Custody.Entry(entry.id() + "-rest", Custody.Kind.INCOMING, entry.mode(),
                    entry.owner(), bytes, entry.itemName(), r.getAmount(), entry.storedId());
            try {
                custody.hold(e);
                custody.unbusy(e.id());
            } catch (IOException ex) {
                plugin.getSLF4JLogger().error("Lost {} x{} of {} (journal write failed)", entry.itemName(),
                        r.getAmount(), entry.owner(), ex);
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::recoverCustody);
    }

    /** {@code /ah collect all}: one item after another until the bin is empty or the inventory full. */
    public void collectAll(Player p, Runnable after) {
        UUID owner = p.getUniqueId();
        plugin.async(() -> repo.collection(owner, 0, 54), items -> collectNext(p, items, 0, 0, after), p);
    }

    private void collectNext(Player p, List<Stored> items, int index, int got, Runnable after) {
        if (index >= items.size() || !p.isOnline()) {
            finishCollectAll(p, got, items.size() - got, after);
            return;
        }
        collect(p, items.get(index), delivered -> {
            if (delivered) {
                collectNext(p, items, index + 1, got + 1, after);
            } else {
                finishCollectAll(p, got, items.size() - got, after);
            }
        });
    }

    private void finishCollectAll(Player p, int got, int left, Runnable after) {
        if (got == 0 && left == 0) {
            lang().send(p, "collect.empty");
        } else if (left == 0) {
            lang().send(p, "collect.all", Placeholder.unparsed("count", Integer.toString(got)));
        } else {
            lang().send(p, "collect.partial", Placeholder.unparsed("count", Integer.toString(got)),
                    Placeholder.unparsed("left", Integer.toString(left)));
        }
        if (after != null) {
            after.run();
        }
    }

    private void recoverCustody() {
        Custody.Resolver resolver = new Custody.Resolver() {
            @Override
            public boolean listed(UUID token) throws Exception {
                return repo.listingIdByToken(token).isPresent();
            }

            @Override
            public boolean stillStored(long storedId) throws Exception {
                return repo.collectionExists(storedId);
            }

            @Override
            public void restore(String token, Custody.Entry e) throws Exception {
                repo.store(token, e.owner(), e.item(), e.itemName(), e.amount(), Stored.Reason.RETURNED, null);
            }
        };
        custody.recover(mode().id(), resolver);
    }

    // ------------------------------------------------------------------ expiry, notices, alerts

    private void expireDue() {
        try {
            for (MarketRepository.Closed.Done d : repo.expireDue(100)) {
                Bukkit.getScheduler().runTask(plugin, () -> notify(d.listing().seller(), d.noticeId()));
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Expiring listings failed (retried next run): {}", e.toString());
        }
    }

    /** Shows a notice to its player: here if they're online here, else on whatever server they're on. Main thread. */
    void notify(UUID player, long noticeId) {
        if (noticeId <= 0) {
            return;
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            showNotice(online, noticeId);
            return;
        }
        var msg = new Channels.MarketNotice(player.toString(), noticeId, mode().id(), serverId);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                RapidCore.get().redis().publishJson(Channels.MARKET, msg);
            } catch (RuntimeException e) {
                // It waits in the database for their next login.
                plugin.getSLF4JLogger().debug("Couldn't publish a market notice: {}", e.toString());
            }
        });
    }

    private void onRemoteNotice(Channels.MarketNotice n) {
        if (serverId.equals(n.origin())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(n.player());
            if (online != null) {
                showNotice(online, n.noticeId());
            }
        });
    }

    private void showNotice(Player p, long noticeId) {
        plugin.async(() -> repo.takeNotice(noticeId), n -> n.ifPresent(notice -> send(p, notice)), null);
    }

    private void send(Player p, Notice n) {
        EconomyMode m = EconomyMode.byId(n.mode());
        TagResolver[] r = {Placeholder.unparsed("item", n.itemName()),
                Placeholder.unparsed("amount", Integer.toString(n.amount())), money("price", n.price(), m),
                money("proceeds", n.proceeds(), m), Placeholder.unparsed("buyer", n.otherName() == null ? "?"
                        : n.otherName()), Placeholder.component("where", lang().get("mode." + m.id()))};
        lang().send(p, "notice." + n.kind().id(), r);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        record Waiting(List<Notice> notices, int stored) {
        }
        plugin.async(() -> new Waiting(repo.takeNotices(id), repo.collectionCount(id)), w -> {
            if (!p.isOnline()) {
                return;
            }
            int shown = 0;
            for (Notice n : w.notices()) {
                if (shown++ >= 8) {
                    lang().send(p, "notice.more", Placeholder.unparsed("count",
                            Integer.toString(w.notices().size() - 8)));
                    break;
                }
                send(p, n);
            }
            if (w.stored() > 0) {
                lang().send(p, "collect.reminder", Placeholder.unparsed("count", Integer.toString(w.stored())));
            }
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        selling.remove(event.getPlayer().getUniqueId());
        buying.remove(event.getPlayer().getUniqueId());
    }

    /** After a suspicious sale: tells staff if the same pair keeps doing it (PLAN.md §4.2). */
    private void checkAlert(Listing l, UUID buyer, String buyerName) {
        var rule = plugin.settings().alerts();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int count = repo.flaggedBetween(l.seller(), buyer, rule.windowDays());
                if (!rule.alert(count)) {
                    return;
                }
                Component text = lang().get("alert.suspicious", Placeholder.unparsed("seller", l.sellerName()),
                        Placeholder.unparsed("buyer", buyerName), Placeholder.unparsed("count", Integer.toString(count)),
                        Placeholder.unparsed("days", Integer.toString(rule.windowDays())),
                        Placeholder.unparsed("item", l.itemName()), money("price", l.price()),
                        Placeholder.component("where", lang().get("mode." + mode().id())));
                String mini = MiniMessage.miniMessage().serialize(text);
                plugin.getSLF4JLogger().warn("Suspicious auction sales: {} -> {} ({} far below average in {} days)",
                        l.sellerName(), buyerName, count, rule.windowDays());
                RapidCore.get().redis().publishJson(Channels.STAFF_ALERT, new Channels.StaffAlert(serverId, mini));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Suspicious-sale check failed: {}", e.toString());
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Whether {@code p} may trade: not in staff or build mode, and not in creative or
     * spectator, so items from those never reach a survival economy.
     */
    public boolean canTrade(Player p) {
        RapidCore core = RapidCore.get();
        if (core.staffMode().isActive(p) || core.buildMode().isActive(p)
                || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            lang().send(p, "errors.not-now");
            return false;
        }
        return true;
    }

    /** {@code <name>} as an amount of this mode's currency ("1,500 Crowns"). */
    public TagResolver money(String name, long amount) {
        return money(name, amount, mode());
    }

    private TagResolver money(String name, long amount, EconomyMode m) {
        return Placeholder.component(name, lang().get("format.money",
                Placeholder.unparsed("amount", Prices.format(amount)),
                Placeholder.unparsed("currency", m.currencyName(amount))));
    }

    /** {@code <name>} as a price per item. */
    public TagResolver unitMoney(String name, double amount) {
        return Placeholder.component(name, lang().get("format.money",
                Placeholder.unparsed("amount", Prices.format(amount)),
                Placeholder.unparsed("currency", mode().currencyName(amount == 1 ? 1 : 2))));
    }

    /** Async helper exposed for menus. */
    public <T> void async(Database.SqlSupplier<T> work, Consumer<T> then, CommandSender who) {
        plugin.async(work, then, who);
    }
}
