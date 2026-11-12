package us.rapidskies.limbo.via;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InfiniburnTest {

    @Test
    void eachDimensionGetsItsVanillaTag() {
        assertEquals("#minecraft:infiniburn_overworld", Infiniburn.tagFor("minecraft:overworld"));
        assertEquals("#minecraft:infiniburn_nether", Infiniburn.tagFor("minecraft:the_nether"));
        assertEquals("#minecraft:infiniburn_end", Infiniburn.tagFor("minecraft:the_end"));
    }

    @Test
    void keysWithoutTheNamespaceWork() {
        assertEquals("#minecraft:infiniburn_nether", Infiniburn.tagFor("the_nether"));
        assertEquals("#minecraft:infiniburn_end", Infiniburn.tagFor("the_end"));
    }

    @Test
    void anyOtherDimensionBurnsLikeTheOverworld() {
        assertEquals("#minecraft:infiniburn_overworld", Infiniburn.tagFor("minecraft:overworld_caves"));
        assertEquals("#minecraft:infiniburn_overworld", Infiniburn.tagFor("rapidskies:spawn"));
    }
}
