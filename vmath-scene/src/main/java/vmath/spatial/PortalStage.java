package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.geo.DepthRange;

/**
 * Portal culling as a {@link CullStage}: locates the camera in the {@link PortalGraph} (starting from the sector of the previous frame), traverses the portals ({@link PortalCuller}) and
 * clears the bits of the objects that are not seen through them. Put it in a {@link CullPipeline} next to the frustum stage; the order does not matter, each only clears bits.
 *
 * <p>A stage needs the projection of the frame, which {@link CullContext} does not carry: call {@link #setView} each frame with the same view-projection matrix that the frustum of the
 * context was made from, before running the pipeline. When the camera is in no sector the stage culls nothing, unless a fallback sector is set ({@link #setFallbackSector}).
 *
 * <p>One stage per thread (it owns a {@link PortalCuller}). <b>Thread safety.</b> Not thread-safe.
 */
public final class PortalStage implements CullStage {

    private final PortalGraph graph;
    private final PortalCuller culler;
    private boolean viewSet;
    private boolean cullUnassigned;
    private int fallback = -1;
    private int lastSector = -1;

    /** A stage for {@code graph}. */
    public PortalStage(PortalGraph graph) {
        this.graph = graph;
        this.culler = new PortalCuller(graph);
    }

    /** Sets the view-projection matrix and its depth convention for the next {@link #cull} calls: the ones the frustum of the {@link CullContext} comes from. */
    public PortalStage setView(Mat4f viewProjection, DepthRange depth) {
        culler.setView(viewProjection, depth);
        viewSet = true;
        return this;
    }

    /** Whether objects that belong to no sector are culled (default false: they are left alone). */
    public PortalStage setCullUnassigned(boolean cullUnassigned) {
        this.cullUnassigned = cullUnassigned;
        return this;
    }

    /** The sector to start from when the camera is in none (for instance the one it was last known to be in), or -1 to cull nothing in that case (the default). */
    public PortalStage setFallbackSector(int sector) {
        this.fallback = sector;
        return this;
    }

    /** Narrows the traversal with a precomputed visibility set (see {@link SectorVisibility}), or removes it when {@code null}. */
    public PortalStage setVisibility(SectorVisibility visibility) {
        culler.setVisibility(visibility);
        return this;
    }

    /** The sector the camera was in at the last {@link #cull}, or -1 when it was in none. */
    public int lastSector() {
        return lastSector;
    }

    /** The culler, for the results of the last frame (the visible sectors and their rectangles). */
    public PortalCuller culler() {
        return culler;
    }

    @Override
    public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
        if (!viewSet) {
            throw new IllegalStateException("call setView before the pipeline runs");
        }
        float ex = ctx.camera().x(), ey = ctx.camera().y(), ez = ctx.camera().z();
        int sector = graph.locate(ex, ey, ez, lastSector);
        lastSector = sector;
        if (sector < 0) {
            sector = fallback;
        }
        if (sector < 0 || sector >= graph.sectorCount()) {
            return;
        }
        culler.traverse(ex, ey, ez, sector);
        culler.cullObjects(bounds, visible, cullUnassigned);
    }
}
