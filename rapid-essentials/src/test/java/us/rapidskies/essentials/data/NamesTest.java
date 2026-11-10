package us.rapidskies.essentials.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void storedLowercase() {
        assertEquals(Optional.of("base"), Names.normalize("Base"));
        assertEquals(Optional.of("farm-2"), Names.normalize(" farm-2 "));
        assertEquals(Optional.of("my_home"), Names.normalize("MY_HOME"));
        assertEquals(Optional.of("a234567890123456"), Names.normalize("a234567890123456"));
    }

    @Test
    void refused() {
        for (String bad : new String[] {"", " ", "two words", "a2345678901234567", "café", "home!", "x/y"}) {
            assertTrue(Names.normalize(bad).isEmpty(), bad);
        }
        assertTrue(Names.normalize(null).isEmpty());
    }
}
