package us.rapidskies.proxy.grant;

import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import us.rapidskies.common.grant.Grant;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.common.shop.ShopRepository;
import us.rapidskies.common.shop.ShopRepository.Purchase;

/**
 * Grants the rank passes players buy with Bolts (PLAN.md §6.1). rapid-core's Bolt shop
 * charges the Bolts and writes a {@code PENDING} purchase in one transaction; this turns it
 * into a 30-day {@code /grant} (so it stacks with store subscriptions and shows in
 * {@code /grants}) and marks it delivered. The grant's transaction id is the purchase's, so
 * a delivery interrupted after granting finds the first grant instead of granting twice.
 *
 * <p>Runs when a backend announces a purchase on Redis and every minute regardless, so a
 * lost message or a proxy restart only delays a pass.
 */
public final class RankPassDelivery {

    private static final Pattern ITEM = Pattern.compile("rank:([a-z]+):(\\d+)d");

    private final ProxyServer proxy;
    private final ShopRepository shop;
    private final GrantService grants;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean();

    public RankPassDelivery(ProxyServer proxy, ShopRepository shop, GrantService grants, Logger logger) {
        this.proxy = proxy;
        this.shop = shop;
        this.grants = grants;
        this.logger = logger;
    }

    public void start(Object plugin, RedisBus redis) {
        redis.subscribeJson(Channels.RANK_PASS, Channels.RankPassBought.class,
                bought -> proxy.getScheduler().buildTask(plugin, this::deliverPending).schedule());
        proxy.getScheduler().buildTask(plugin, this::deliverPending)
                .delay(10, TimeUnit.SECONDS).repeat(60, TimeUnit.SECONDS).schedule();
    }

    /** Grants every pending pass. One run at a time; a run already going picks up new ones next minute. */
    void deliverPending() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Purchase p : shop.pendingRankPasses()) {
                deliver(p);
            }
        } catch (SQLException | RuntimeException e) {
            logger.warn("Couldn't read pending rank passes: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void deliver(Purchase p) {
        Matcher m = ITEM.matcher(p.item());
        Optional<GrantTarget> rank = m.matches() ? GrantTarget.rank(m.group(1)) : Optional.empty();
        if (rank.isEmpty()) {
            logger.error("Rank pass purchase #{} has an item I can't grant: '{}'. Refund it by hand.", p.id(), p.item());
            return;
        }
        try {
            Grant grant = grants.grantSystem(p.player(), rank.get(), Duration.ofDays(Long.parseLong(m.group(2))),
                    "Bolts pass (purchase #" + p.id() + ")", "boltpass:" + p.id());
            shop.markDelivered(p.id(), grant.id());
            logger.info("Delivered rank pass #{} ({}) to {} as grant #{}", p.id(), p.item(), p.player(), grant.id());
        } catch (SQLException | RuntimeException e) {
            logger.warn("Couldn't deliver rank pass #{} to {} yet: {}", p.id(), p.player(), e.getMessage());
        }
    }
}
