package us.rapidskies.limbo.via;

import com.viaversion.nbt.tag.ListTag;
import com.viaversion.viabackwards.protocol.v26_2to26_1.Protocol26_2To26_1;
import com.viaversion.viaversion.api.Via;

/**
 * Puts the {@code infiniburn} tag back for clients before 26.2 ({@link Infiniburn}), in
 * ViaBackwards' 26.2 → 26.1 step: every older client's translation passes through it, and
 * 26.2+ clients never do, so they keep Limbo's block list.
 *
 * <p>Uses ViaVersion classes, which live in ViaLimbo's jar. Limbo gives each plugin its own
 * class loader, so {@code RapidLimbo} loads this class through one whose parent is ViaLimbo's.
 * Nothing else may refer to it directly.
 */
public final class InfiniburnFix {

    private InfiniburnFix() {
    }

    /** Installs the fix; false while ViaLimbo hasn't started ViaVersion yet (try again later). */
    public static boolean install() {
        if (!Via.isLoaded()) {
            return false;
        }
        Protocol26_2To26_1 protocol = Via.getManager().getProtocolManager().getProtocol(Protocol26_2To26_1.class);
        if (protocol == null) {
            return false;
        }
        protocol.getRegistryDataRewriter().addHandler("dimension_type", (key, dimension) -> {
            if (dimension.get("infiniburn") instanceof ListTag<?>) {
                dimension.putString("infiniburn", Infiniburn.tagFor(key));
            }
        });
        return true;
    }
}
