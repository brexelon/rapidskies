package us.rapidskies.claims.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.junit.jupiter.api.Test;

/**
 * What touching each kind of entity takes (PLAN.md §4.1: damage to animals, villagers and
 * display entities; vehicles). Entities are stand-ins built from the Bukkit interfaces, so
 * no server is needed.
 */
class EntityRulesTest {

    @SuppressWarnings("unchecked")
    private static <T extends Entity> T fake(Class<T> type) {
        return (T) Proxy.newProxyInstance(EntityRulesTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName();
                    default -> null;
                });
    }

    @Test
    void decorationsTakeBuilder() {
        assertEquals(Action.BUILD, ProtectionListener.entityAction(fake(ItemFrame.class)));
        assertEquals(Action.BUILD, ProtectionListener.entityAction(fake(Painting.class)));
        assertEquals(Action.BUILD, ProtectionListener.entityAction(fake(ArmorStand.class)));
        assertEquals(Action.BUILD, ProtectionListener.entityAction(fake(EnderCrystal.class)));
        assertEquals(Action.BUILD, ProtectionListener.entityAction(fake(TextDisplay.class)));
    }

    @Test
    void animalsVillagersAndPetsTakeMember() {
        assertEquals(Action.ANIMALS, ProtectionListener.entityAction(fake(Cow.class)));
        assertEquals(Action.ANIMALS, ProtectionListener.entityAction(fake(Villager.class)));
        assertEquals(Action.ANIMALS, ProtectionListener.entityAction(fake(Wolf.class)));
        assertEquals(Action.ANIMALS, ProtectionListener.entityAction(fake(Allay.class)));
    }

    @Test
    void playersAndMonstersAreNeverProtected() {
        assertNull(ProtectionListener.entityAction(fake(Player.class)));
        assertNull(ProtectionListener.entityAction(fake(Creeper.class)));
        assertNull(ProtectionListener.entityAction(fake(Slime.class)), "Enemy, not just Monster");
    }

    @Test
    void vehicles() {
        assertEquals(Action.USE, ProtectionListener.entityAction(fake(Boat.class)), "riding");
        assertEquals(Action.USE, ProtectionListener.entityAction(fake(RideableMinecart.class)));
        assertEquals(Action.CONTAINER, ProtectionListener.entityAction(fake(ChestBoat.class)), "opening its chest");
        assertEquals(Action.CONTAINER, ProtectionListener.entityAction(fake(StorageMinecart.class)));
        assertEquals(Action.BUILD, ProtectionListener.damageAction(fake(Boat.class)), "breaking it");
        assertEquals(Action.ANIMALS, ProtectionListener.damageAction(fake(Cow.class)));
    }

    @Test
    void theResponsiblePlayer() {
        Player steve = fake(Player.class);
        assertSame(steve, ProtectionListener.responsible(steve));
        Projectile arrow = (Projectile) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Projectile.class}, (proxy, method, args) ->
                        method.getName().equals("getShooter") ? steve : null);
        assertSame(steve, ProtectionListener.responsible(arrow), "a projectile's shooter");
        assertNull(ProtectionListener.responsible(fake(Creeper.class)));
    }
}
