package us.rapidskies.core.cosmetic;

import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import us.rapidskies.common.rank.Rank;

/**
 * One cosmetic from {@code cosmetics.yml}. {@code display} is what it renders: MiniMessage
 * text for titles, tags and badges; for name colors and particles the parsed form is in
 * {@link #nameColor} and {@link #particle}.
 */
public record Cosmetic(String id, CosmeticType type, String name, String display, Rarity rarity, Obtain obtain,
                       long price, Rank rank, String icon, String hint, NamedTextColor nameColor, Effect particle) {

    public enum Rarity {
        COMMON, RARE, EPIC, LEGENDARY;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How a cosmetic is obtained. */
    public enum Obtain {
        /** Bought with Bolts in the Bolt shop. */
        SHOP,
        /** Bought with Bolts only while it's in the weekly Featured Shop. */
        FEATURED,
        /** Worn while the player holds {@link #rank} or higher; never stored. */
        RANK,
        LEVEL, ACHIEVEMENT, PASS, CHAPTER, SEASON, EVENT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean bought() {
            return this == SHOP || this == FEATURED;
        }
    }

    /** A particle, optionally colored dust. */
    public record Effect(Particle particle, Color dust) {

        /** Shows {@code count} of it at {@code at} to one viewer. */
        public void show(Player viewer, Location at, int count, double spread, double speed) {
            if (dust != null) {
                viewer.spawnParticle(Particle.DUST, at, count, spread, spread, spread, speed,
                        new Particle.DustOptions(dust, 1.0f));
            } else {
                viewer.spawnParticle(particle, at, count, spread, spread, spread, speed);
            }
        }

        /** Shows it at {@code at} to everyone near. */
        public void show(Location at, int count, double spread, double speed) {
            if (at.getWorld() == null) {
                return;
            }
            if (dust != null) {
                at.getWorld().spawnParticle(Particle.DUST, at, count, spread, spread, spread, speed,
                        new Particle.DustOptions(dust, 1.0f));
            } else {
                at.getWorld().spawnParticle(particle, at, count, spread, spread, spread, speed);
            }
        }
    }

    /** The slot it fills. */
    public String slot() {
        return type.id();
    }

    public Optional<String> hintText() {
        return hint == null || hint.isBlank() ? Optional.empty() : Optional.of(hint);
    }
}
