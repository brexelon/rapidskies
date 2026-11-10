package us.rapidskies.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigViewTest {

    private static Map<String, Object> yaml(String text) {
        return ConfigView.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).asMap();
    }

    private static final String DEFAULTS = """
            frontier:
              length-days: 30
              chapters: [chapter-1]
              end:
                enabled: true
                delay: 24
            npcs:
              heartland: {action: rtp}
              hub: {action: "server:hub"}
            worlds: {}
            chapters:
              chapter-1:
                entries:
                  first: {goal: "visit:frontier"}
            """;

    @Test
    void fillsMissingSettingsAndSections() {
        ConfigView c = ConfigView.of(ConfigView.withDefaults(yaml("""
                frontier:
                  length-days: 14
                  end:
                    enabled: false
                """), yaml(DEFAULTS)));
        assertEquals(14, c.integer("frontier.length-days", 0));
        assertEquals(List.of("chapter-1"), c.stringList("frontier.chapters"));
        assertFalse(c.bool("frontier.end.enabled", true));
        assertEquals(24, c.integer("frontier.end.delay", 0));
        assertEquals("visit:frontier", c.string("chapters.chapter-1.entries.first.goal", ""));
        assertEquals("rtp", c.string("npcs.heartland.action", ""));
    }

    @Test
    void leavesCollectionsTheAdminHasAlone() {
        ConfigView c = ConfigView.of(ConfigView.withDefaults(yaml("""
                npcs:
                  heartland: {action: rtp}
                worlds:
                  "minecraft:overworld": {protect: true}
                chapters:
                  chapter-2:
                    entries:
                      other: {goal: dragon}
                """), yaml(DEFAULTS)));
        assertFalse(c.contains("npcs.hub"));
        assertTrue(c.contains("chapters.chapter-2"));
        assertFalse(c.contains("chapters.chapter-1"));
        assertEquals(1, c.section("worlds").keys().size());
    }

    @Test
    void keepsPresentKeysEvenWhenEmpty() {
        ConfigView c = ConfigView.of(ConfigView.withDefaults(yaml("""
                frontier:
                  chapters: []
                chapters:
                """), yaml(DEFAULTS)));
        assertEquals(List.of(), c.stringList("frontier.chapters"));
        assertTrue(c.asMap().containsKey("chapters"));
        assertNull(c.get("chapters"));
    }

    @Test
    void loadOrCreateFillsDefaultsWithoutTouchingTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "greeting: hi\n");
        ConfigView c = ConfigView.loadOrCreate(ConfigViewTest.class.getClassLoader(), "config/defaults.yml", file);
        assertEquals("hi", c.string("greeting", ""));
        assertEquals(3, c.integer("nested.added", 0));
        assertEquals("greeting: hi\n", Files.readString(file));
    }

    @Test
    void loadOrCreateWritesTheBundledFileFirst(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub/config.yml");
        ConfigView c = ConfigView.loadOrCreate(ConfigViewTest.class.getClassLoader(), "config/defaults.yml", file);
        assertEquals("hello", c.string("greeting", ""));
        assertTrue(Files.readString(file).contains("added: 3"));
    }
}
