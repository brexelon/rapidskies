package us.rapidskies.horizons.frontier;

/**
 * The Wildlands of a Frontier (PLAN.md §5.2): the outer ring, everything at least
 * {@code innerRadius} blocks from the centre along either axis (a square ring, like the
 * world border), where PvP is forced and resources are richer. The centre stays a safe
 * landing zone, so nobody arrives in the middle of a fight.
 *
 * @param innerRadius 0 turns the Wildlands off
 */
public record WildlandsZone(int centerX, int centerZ, int innerRadius) {

    /** Distance from the centre along the further axis (Chebyshev), like the world border. */
    public int distance(double x, double z) {
        return (int) Math.floor(Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)));
    }

    public boolean contains(double x, double z) {
        return innerRadius > 0 && distance(x, z) >= innerRadius;
    }

    /**
     * How far from the centre a safe landing ({@code /frontier go}) may be: inside the
     * Wildlands' edge with {@code margin} to spare, and never past {@code borderHalf}.
     */
    public int safeRadius(int borderHalf, int margin) {
        int limit = innerRadius > 0 ? innerRadius - margin : borderHalf - margin;
        return Math.max(16, Math.min(limit, borderHalf - margin));
    }
}
