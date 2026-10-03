package vmath.spatial;

/**
 * A precomputed answer to "can anything in sector {@code to} be seen from sector {@code from}": the hook through which a potentially visible set (PVS) baked by a level tool or
 * computed offline narrows the portal traversal ({@link PortalCuller#setVisibility}). An implementation must be <b>conservative</b>: it may say {@code true} for sectors that cannot be
 * seen, never {@code false} for one that can, or the culling will drop objects that are visible. {@link PvsMatrix} is a ready implementation with a binary format.
 */
@FunctionalInterface
public interface SectorVisibility {

    /** Whether sector {@code to} may be visible from sector {@code from}. A sector can always see itself; the culler never asks for that pair. */
    boolean isVisible(int from, int to);
}
