package vmath.spatial;

/**
 * A precomputed answer to "can anything in sector {@code to} be seen from sector {@code from}": the
 * hook through which a potentially visible set (PVS) baked by a level tool or computed offline
 * narrows the portal traversal ({@link PortalCuller#setVisibility}).
 *
 * <p>An implementation must be <b>conservative</b>: it may say {@code true} for sectors that cannot
 * be seen, never {@code false} for one that can, or the culling will drop objects that are visible.
 * {@link PvsMatrix} is a ready implementation with a binary format.
 *
 * <p><b>Thread safety.</b> Not specified: the library does not define the threading behavior of
 * implementations of this interface; see the methods for what they promise.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SectorVisibility everything = (from, to) -> true;                           // conservative: never narrows
 * PortalGraph.Builder builder = PortalGraph.builder();
 * builder.addBox(Aabbf.of(Vec3f.ZERO, Vec3f.ONE));
 * PortalCuller culler = new PortalCuller(builder.build());
 * culler.setVisibility(everything);
 * }</pre>
 */
@FunctionalInterface
public interface SectorVisibility {

    /**
     * Returns whether sector {@code to} may be visible from sector {@code from}.
     *
     * <p>A sector can always see itself; the culler never asks for that pair.
     *
     * @param from the sector that sees
     * @param to the sector that is seen
     * @return {@code true} if sector {@code to} may be visible from sector {@code from}
     */
    boolean isVisible(int from, int to);
}
