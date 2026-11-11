package us.rapidskies.common.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.cosmetic.CosmeticRepository;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.shop.ShopRepository.Kind;
import us.rapidskies.common.shop.ShopRepository.Result;
import us.rapidskies.common.shop.ShopRepository.Sale;
import us.rapidskies.common.shop.ShopRepository.Tag;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class ShopRepositoryIT {

    private static final Sale STAR = new Sale("tag:star", Kind.COSMETIC, Tag.COSMETIC, 150, false);

    private Ledger bolts;
    private ShopRepository shop;
    private CosmeticRepository cosmetics;
    private UUID steve;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("bolts_ledger", "bolts_balance", "shop_purchases", "cosmetics_owned",
                "cosmetics_equipped", "season_progress", "players");
        bolts = Ledger.bolts(TestServices.database());
        shop = new ShopRepository(TestServices.database(), bolts);
        cosmetics = new CosmeticRepository(TestServices.database());
        steve = new ProfileRepository(TestServices.database()).recordLogin(UUID.randomUUID(), "Steve", "hub-1").uuid();
    }

    @Test
    void buysACosmeticOnce() throws Exception {
        bolts.credit(steve, 400, "test", "test", null);
        Result first = shop.buyCosmetic(steve, STAR, "tag:star", "shop");
        assertEquals(250, assertInstanceOf(Result.Bought.class, first).balance());
        assertTrue(cosmetics.load(steve).owned().contains("tag:star"));
        assertInstanceOf(Result.AlreadyOwned.class, shop.buyCosmetic(steve, STAR, "tag:star", "shop"));
        assertEquals(250, bolts.balance(steve));
        assertEquals(1, shop.recent(steve, 10).size());
    }

    @Test
    void insufficientBoltsChangeNothing() throws Exception {
        bolts.credit(steve, 100, "test", "test", null);
        Result r = shop.buyCosmetic(steve, STAR, "tag:star", "shop");
        assertEquals(100, assertInstanceOf(Result.Insufficient.class, r).balance());
        assertFalse(cosmetics.load(steve).owned().contains("tag:star"));
        assertTrue(shop.recent(steve, 10).isEmpty());
    }

    @Test
    void parallelClicksChargeOnce() throws Exception {
        bolts.credit(steve, 1000, "test", "test", null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Result>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> shop.buyCosmetic(steve, STAR, "tag:star", "shop"));
            }
            int bought = 0;
            for (Future<Result> f : pool.invokeAll(tasks)) {
                if (f.get() instanceof Result.Bought) {
                    bought++;
                }
            }
            assertEquals(1, bought);
            assertEquals(850, bolts.balance(steve));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rankPassWaitsForTheProxy() throws Exception {
        bolts.credit(steve, 1000, "test", "test", null);
        Sale pass = new Sale("rank:rapidplus:30d", Kind.RANK_PASS, Tag.CONVENIENCE, 1000, false);
        long id = assertInstanceOf(Result.Bought.class, shop.buyRankPass(steve, pass)).purchaseId();
        assertEquals(0, bolts.balance(steve));
        List<ShopRepository.Purchase> pending = shop.pendingRankPasses();
        assertEquals(1, pending.size());
        assertEquals(ShopRepository.State.PENDING, pending.getFirst().state());
        assertEquals(Tag.CONVENIENCE, pending.getFirst().tag());
        assertTrue(shop.markDelivered(id, 77));
        assertFalse(shop.markDelivered(id, 78), "delivered twice");
        assertTrue(shop.pendingRankPasses().isEmpty());
        assertEquals(77L, shop.recent(steve, 1).getFirst().grantId());
    }

    @Test
    void premiumTrackGrantsWhatWasAlreadyReached() throws Exception {
        bolts.credit(steve, 2000, "test", "test", null);
        Sale premium = new Sale("pass:s1", Kind.SEASON_PASS, Tag.COSMETIC, 900, false);
        TestServices.database().withConnection(c -> {
            try (var ps = c.prepareStatement("INSERT INTO season_progress (uuid, season, xp) VALUES (?, 's1', 1200)")) {
                ps.setString(1, steve.toString());
                return ps.executeUpdate();
            }
        });
        assertInstanceOf(Result.Bought.class, shop.buySeasonPass(steve, premium, "s1",
                xp -> xp >= 1000 ? List.of("tag:dawnstar", "trail:goldleaf") : List.of()));
        assertTrue(cosmetics.load(steve).owned().containsAll(Set.of("tag:dawnstar", "trail:goldleaf")));
        assertInstanceOf(Result.AlreadyOwned.class, shop.buySeasonPass(steve, premium, "s1", xp -> List.of()));
        assertEquals(1100, bolts.balance(steve));
    }

    @Test
    void wardrobeEquipAndRevoke() throws Exception {
        assertTrue(cosmetics.grant(steve, "title:wanderer", "admin:test"));
        assertFalse(cosmetics.grant(steve, "title:wanderer", "admin:test"));
        cosmetics.equip(steve, "title", "title:wanderer");
        assertEquals("title:wanderer", cosmetics.load(steve).equipped().get("title"));
        assertTrue(cosmetics.revoke(steve, "title:wanderer"));
        CosmeticRepository.Wardrobe w = cosmetics.load(steve);
        assertTrue(w.owned().isEmpty());
        assertTrue(w.equipped().isEmpty());
    }
}
