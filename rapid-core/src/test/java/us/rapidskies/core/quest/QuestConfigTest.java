package us.rapidskies.core.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.quest.QuestPeriods;
import us.rapidskies.core.cosmetic.Catalog;

class QuestConfigTest {

    private static final Catalog CATALOG;
    private static final QuestConfig CONFIG;

    static {
        try {
            CATALOG = Catalog.load(QuestConfigTest.class.getClassLoader());
            CONFIG = QuestConfig.load(QuestConfigTest.class.getClassLoader(), CATALOG);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ConfigView yaml(String text) {
        return ConfigView.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bundledQuestsMatchThePlan() {
        // PLAN.md §7: 3 dailies banked, 2 weeklies alternating between the modes; §6.2 rewards.
        assertEquals(3, CONFIG.dailySlots().size());
        assertEquals(3, CONFIG.bankDays());
        assertEquals(List.of("horizons", "skyrealms"), CONFIG.weeklySlots());
        assertEquals(10, CONFIG.dailyReward().bolts());
        assertEquals(75, CONFIG.weeklyReward().bolts());
    }

    @Test
    void everySlotGetsDistinctQuestsForAnyPlayer() {
        for (String kind : List.of("daily", "weekly")) {
            for (int n = 0; n < 50; n++) {
                UUID player = UUID.randomUUID();
                Set<String> taken = new HashSet<>();
                List<String> slots = CONFIG.slots(kind);
                for (int slot = 0; slot < slots.size(); slot++) {
                    String mode = slots.get(slot);
                    List<String> fitting = CONFIG.pool(kind).values().stream().filter(q -> q.fits(mode))
                            .map(QuestConfig.Quest::id).toList();
                    String pick = QuestPeriods.pick(fitting, taken, player, "2026-11-11", slot).orElseThrow();
                    assertTrue(taken.add(pick));
                    assertTrue(CONFIG.pool(kind).get(pick).fits(mode));
                }
            }
        }
    }

    @Test
    void questsMatchTheirObjective() {
        QuestConfig.Quest ores = CONFIG.daily().get("h-ores-24");
        assertTrue(ores.matches("break-blocks", Material.DEEPSLATE_DIAMOND_ORE, null));
        assertFalse(ores.matches("break-blocks", Material.STONE, null));
        assertFalse(ores.matches("harvest-crops", Material.DIAMOND_ORE, null));
        assertTrue(ores.countsOn("horizons"));
        assertFalse(ores.countsOn("skyrealms"));
        QuestConfig.Quest zombies = CONFIG.daily().get("zombies-15");
        assertTrue(zombies.matches("kill-mobs", null, EntityType.HUSK));
        assertFalse(zombies.matches("kill-mobs", null, EntityType.SKELETON));
        assertTrue(zombies.countsOn("skyrealms") && zombies.countsOn("hub"));
        QuestConfig.Quest fish = CONFIG.daily().get("fish-8");
        assertTrue(fish.matches("catch-fish", null, null));
        // The any slot also takes hub quests.
        assertTrue(CONFIG.daily().get("parkour-1").fits("any"));
        assertFalse(CONFIG.daily().get("parkour-1").countsOn("horizons"));
    }

    @Test
    void achievementsPayWithinThePlan() {
        for (QuestConfig.Achievement a : CONFIG.achievements().values()) {
            assertTrue(a.bolts() >= 0 && a.bolts() <= 250, a.id());
            if (a.cosmetic() != null) {
                assertEquals(us.rapidskies.core.cosmetic.Cosmetic.Obtain.ACHIEVEMENT,
                        CATALOG.find(a.cosmetic()).orElseThrow().obtain(), a.id());
            }
        }
    }

    @Test
    void refusesBrokenQuests() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> QuestConfig.parse(yaml("""
                daily:
                  slots: [any, horizons]
                weekly:
                  slots: [skyrealms]
                quests:
                  daily:
                    a: { mode: any, objective: dance, target: 3 }
                    b: { mode: moon, objective: catch-fish, target: 0, blocks: [NOT_A_BLOCK] }
                  weekly: {}
                achievements:
                  x: { stat: catch-fish, target: 5, bolts: 9000, cosmetic: "title:nope" }
                """), CATALOG));
        String m = e.getMessage();
        for (String expected : List.of("dance", "mode is any", "target", "NOT_A_BLOCK", "only 0 quests fit", "title:nope",
                "bolts 0–250")) {
            assertTrue(m.contains(expected), "expected '" + expected + "' in:\n" + m);
        }
    }
}
