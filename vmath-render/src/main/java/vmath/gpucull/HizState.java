package vmath.gpucull;

import java.lang.foreign.MemorySegment;
import vmath.geo.DepthRange;
import vmath.gl.GpuWriter;

/**
 * The parts of a view block that the Hi-Z box test needs, read once per pass instead of once per
 * object.
 */
final class HizState {

    private static final DepthRange[] RANGES = DepthRange.values(); // values() clones its array on every call

    final float[] m = new float[16];
    float nearW;
    DepthRange range = DepthRange.ZERO_TO_ONE;

    void load(MemorySegment view, long matrixOffset, long nearWOffset, long depthModeOffset) {
        for (int k = 0; k < 16; k++) {
            m[k] = GpuWriter.getFloat(view, matrixOffset + 4L * k);
        }
        nearW = GpuWriter.getFloat(view, nearWOffset);
        range = RANGES[GpuWriter.getInt(view, depthModeOffset)];
    }
}
