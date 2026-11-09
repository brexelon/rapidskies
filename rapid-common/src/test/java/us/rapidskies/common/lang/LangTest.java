package us.rapidskies.common.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import us.rapidskies.common.RapidBrand;

class LangTest {

    private static final ClassLoader CL = LangTest.class.getClassLoader();

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void rendersBrandTagsPrefixAndPlaceholders() throws Exception {
        Lang lang = Lang.fromResource(CL, "lang/test.yml", null);
        Component c = lang.get("greeting", Placeholder.unparsed("name", "Steve"));
        assertEquals("RAPIDSKIES » Hello, Steve!", plain(c));
    }

    @Test
    void brandColorsResolve() {
        Component c = Lang.parse("<primary>x</primary>");
        assertEquals(TextColor.fromHexString(RapidBrand.PRIMARY), c.children().isEmpty()
                ? c.color() : c.children().getFirst().color());
    }

    @Test
    void nestedKeysAndListsFlatten() throws Exception {
        Lang lang = Lang.fromResource(CL, "lang/test.yml", null);
        assertEquals("ok", plain(lang.get("nested.key")));
        assertEquals(2, lang.list("lines").size());
        assertEquals("two", plain(lang.list("lines").get(1)));
        assertEquals("one\ntwo", plain(lang.get("lines")));
    }

    @Test
    void missingKeyIsVisibleNotFatal() throws Exception {
        Lang lang = Lang.fromResource(CL, "lang/test.yml", null);
        assertEquals("[missing: nope]", plain(lang.get("nope")));
    }

    @Test
    void dataFolderCopyOverridesSingleKeys(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lang/en_US.yml");
        Lang first = Lang.load(CL, "lang/test.yml", file, null);
        assertTrue(Files.exists(file), "defaults are written on first load");
        assertEquals("default", plain(first.get("override-me")));

        Files.writeString(file, "override-me: \"custom\"\n");
        Lang second = Lang.load(CL, "lang/test.yml", file, null);
        assertEquals("custom", plain(second.get("override-me")));
        assertEquals("ok", plain(second.get("nested.key")), "keys not in the override fall back to defaults");
    }
}
