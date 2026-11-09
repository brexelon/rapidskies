package us.rapidskies.core.tailwind;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TailwindFormatTest {

    @Test
    void formatsForChat() {
        assertEquals("6h", TailwindService.format(6 * 3600));
        assertEquals("2h 15m", TailwindService.format(2 * 3600 + 15 * 60 + 40));
        assertEquals("45m", TailwindService.format(45 * 60));
        assertEquals("30s", TailwindService.format(30));
        assertEquals("0s", TailwindService.format(-5));
    }
}
