package us.rapidskies.core.shop;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.common.shop.ShopRepository;
import us.rapidskies.common.shop.ShopRepository.Kind;
import us.rapidskies.common.shop.ShopRepository.Result;
import us.rapidskies.common.shop.ShopRepository.Sale;
import us.rapidskies.common.shop.ShopRepository.Tag;
import us.rapidskies.core.cosmetic.Catalog;
import us.rapidskies.core.cosmetic.Cosmetic;
import us.rapidskies.core.cosmetic.CosmeticService;
import us.rapidskies.core.economy.BoltsCache;
import us.rapidskies.core.progress.LevelService;
import us.rapidskies.core.progress.ProgressConfig;
import us.rapidskies.core.rank.PlusChoice;
import us.rapidskies.core.rank.RankDisplay;

/**
 * The Bolt shop (PLAN.md §6.2): cosmetics (the catalog's shop items and this week's Featured
 * Shop), 30-day rank passes and the Season Pass premium track. Every sale is tagged
 * {@link Tag#COSMETIC} or {@link Tag#CONVENIENCE} (§8); there is nothing else it can sell.
 * Purchases are single transactions in {@link ShopRepository}; this class checks what's on
 * sale, talks to the player and refreshes what they see.
 */
public final class BoltShop {

    private final Plugin plugin;
    private final Database db;
    private final ShopRepository repository;
    private final Catalog catalog;
    private final CosmeticService cosmetics;
    private final LevelService levels;
    private final RankDisplay ranks;
    private final RedisBus redis;
    private final BoltsCache boltsCache;
    private final Ledger bolts;
    private final Lang lang;

    public BoltShop(Plugin plugin, Database db, Ledger bolts, CosmeticService cosmetics, LevelService levels,
                    RankDisplay ranks, RedisBus redis, BoltsCache boltsCache, Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.bolts = bolts;
        this.repository = new ShopRepository(db, bolts);
        this.catalog = cosmetics.catalog();
        this.cosmetics = cosmetics;
        this.levels = levels;
        this.ranks = ranks;
        this.redis = redis;
        this.boltsCache = boltsCache;
        this.lang = lang;
    }

    public Catalog catalog() {
        return catalog;
    }

    public CosmeticService cosmetics() {
        return cosmetics;
    }

    public LevelService levels() {
        return levels;
    }

    public RankDisplay ranks() {
        return ranks;
    }

    public Lang lang() {
        return lang;
    }

    public BoltsCache boltsCache() {
        return boltsCache;
    }

    public List<Cosmetic> featured() {
        return catalog.featured(Instant.now());
    }

    /** Buys a cosmetic; {@code after} runs on the main thread when it's settled. */
    public void buy(Player player, Cosmetic c, Runnable after) {
        if (!catalog.onSale(c, Instant.now())) {
            lang.send(player, "shop.not-for-sale", Placeholder.unparsed("name", c.name()));
            return;
        }
        if (cosmetics.has(player, c)) {
            lang.send(player, "shop.already-owned", Placeholder.unparsed("name", c.name()));
            return;
        }
        Sale sale = new Sale(c.id(), Kind.COSMETIC, Tag.COSMETIC, c.price(), c.obtain() == Cosmetic.Obtain.FEATURED);
        UUID id = player.getUniqueId();
        db.async(() -> repository.buyCosmetic(id, sale, c.id(), "shop")).whenComplete((result, e) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (e != null) {
                        failed(player, c.id(), e);
                        return;
                    }
                    switch (result) {
                        case Result.Bought b -> {
                            lang.send(player, "shop.bought", Placeholder.unparsed("name", c.name()),
                                    Placeholder.unparsed("price", String.format("%,d", c.price())),
                                    Placeholder.unparsed("balance", String.format("%,d", b.balance())));
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                            cosmetics.reload(id, List.of());
                            plugin.getSLF4JLogger().info("{} bought {} for {} Bolts (purchase #{})", player.getName(),
                                    c.id(), c.price(), b.purchaseId());
                        }
                        case Result.AlreadyOwned ignored -> lang.send(player, "shop.already-owned",
                                Placeholder.unparsed("name", c.name()));
                        case Result.Insufficient low -> insufficient(player, c.price(), low.balance());
                    }
                    boltsCache.refresh(id);
                    after.run();
                }));
    }

    /**
     * Buys a 30-day rank pass. Refused when the player already holds a higher rank (the pass
     * would run out alongside it unseen). The proxy grants it (Redis {@link Channels#RANK_PASS},
     * and its own check every minute).
     */
    public void buy(Player player, Catalog.RankPass pass, Runnable after) {
        Rank current = ranks.rank(player);
        if (current.tier() > pass.rank().tier()) {
            lang.send(player, "shop.rank.higher", Placeholder.unparsed("rank", PlusChoice.rankName(current)));
            return;
        }
        Sale sale = new Sale("rank:" + pass.rank().group() + ":" + pass.days() + "d", Kind.RANK_PASS, pass.tag(),
                pass.price(), false);
        UUID id = player.getUniqueId();
        db.async(() -> repository.buyRankPass(id, sale)).whenComplete((result, e) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (e != null) {
                        failed(player, sale.item(), e);
                        return;
                    }
                    switch (result) {
                        case Result.Bought b -> {
                            lang.send(player, "shop.rank.bought",
                                    Placeholder.unparsed("rank", PlusChoice.rankName(pass.rank())),
                                    Placeholder.unparsed("days", Integer.toString(pass.days())),
                                    Placeholder.unparsed("price", String.format("%,d", pass.price())),
                                    Placeholder.unparsed("balance", String.format("%,d", b.balance())));
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                            plugin.getSLF4JLogger().info("{} bought a {} pass for {} Bolts (purchase #{})",
                                    player.getName(), pass.rank().group(), pass.price(), b.purchaseId());
                            announcePass(b.purchaseId(), id);
                        }
                        case Result.AlreadyOwned ignored -> {
                        }
                        case Result.Insufficient low -> insufficient(player, pass.price(), low.balance());
                    }
                    boltsCache.refresh(id);
                    after.run();
                }));
    }

    private void announcePass(long purchaseId, UUID player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                redis.publishJson(Channels.RANK_PASS, new Channels.RankPassBought(purchaseId, player.toString()));
            } catch (RuntimeException e) {
                // The proxy also checks for pending passes every minute.
                plugin.getSLF4JLogger().warn("Couldn't tell the proxy about rank pass #{}: {}", purchaseId,
                        e.getMessage());
            }
        });
    }

    /** Buys the running season's premium track, granting the premium rewards already reached. */
    public void buyPremium(Player player, ProgressConfig.Season season, Runnable after) {
        if (!season.running(Instant.now())) {
            lang.send(player, "pass.not-running");
            return;
        }
        if (levels.standing(player).premium()) {
            lang.send(player, "pass.already-premium", Placeholder.unparsed("season", season.name()));
            return;
        }
        Sale sale = new Sale("pass:" + season.id(), Kind.SEASON_PASS, Tag.COSMETIC, season.premiumPrice(), false);
        UUID id = player.getUniqueId();
        db.async(() -> repository.buySeasonPass(id, sale, season.id(),
                xp -> season.forRepository().premiumUpTo(xp))).whenComplete((result, e) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (e != null) {
                        failed(player, sale.item(), e);
                        return;
                    }
                    switch (result) {
                        case Result.Bought b -> {
                            lang.send(player, "pass.bought", Placeholder.unparsed("season", season.name()),
                                    Placeholder.unparsed("price", String.format("%,d", season.premiumPrice())),
                                    Placeholder.unparsed("balance", String.format("%,d", b.balance())));
                            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                            levels.reloadSeason(id);
                            cosmetics.reload(id, season.forRepository().premiumUpTo(levels.standing(player).xp()));
                        }
                        case Result.AlreadyOwned ignored -> lang.send(player, "pass.already-premium",
                                Placeholder.unparsed("season", season.name()));
                        case Result.Insufficient low -> insufficient(player, season.premiumPrice(), low.balance());
                    }
                    boltsCache.refresh(id);
                    after.run();
                }));
    }

    private void insufficient(Player player, long price, long balance) {
        lang.send(player, "shop.insufficient", Placeholder.unparsed("price", String.format("%,d", price)),
                Placeholder.unparsed("balance", String.format("%,d", balance)),
                Placeholder.unparsed("missing", String.format("%,d", price - balance)));
    }

    private void failed(Player player, String item, Throwable e) {
        plugin.getSLF4JLogger().warn("Purchase of {} by {} failed: {}", item, player.getName(), e.getMessage());
        lang.send(player, "shop.failed");
    }

    public Ledger bolts() {
        return bolts;
    }
}
