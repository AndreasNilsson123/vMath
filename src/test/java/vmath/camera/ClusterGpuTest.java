package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;
import vmath.core.Vec4f;
import vmath.gl.ClusterLight;
import vmath.gl.ClusterLightGpu;
import vmath.gl.GpuWriter;

class ClusterGpuTest {

    @Test
    void theLightRecordIsThreeVec4s() {
        assertEquals(48, ClusterLightGpu.SIZE);
        assertEquals(0, ClusterLightGpu.OFFSET_POSITION_RANGE);
        assertEquals(16, ClusterLightGpu.OFFSET_DIRECTION_COS_OUTER);
        assertEquals(32, ClusterLightGpu.OFFSET_COLOR_INTENSITY);
        MemorySegment seg = MemorySegment.ofArray(new byte[48 * 2 + 8]);
        ClusterLight l = new ClusterLight(new Vec4f(1f, 2f, 3f, 10f), new Vec4f(0f, 0f, -1f, 0.5f), new Vec4f(1f, 0.5f, 0.25f, 100f));
        ClusterLightGpu.write(l, seg, 8 + 48);
        assertEquals(10f, GpuWriter.getFloat(seg, 8 + 48 + 12));
        assertEquals(0.5f, GpuWriter.getFloat(seg, 8 + 48 + 28));
        assertEquals(100f, GpuWriter.getFloat(seg, 8 + 48 + 44));
        assertTrue(ClusterLightGpu.GLSL.contains("positionRange") && ClusterLightGpu.GLSL.contains("vec4"));
        assertEquals(-1f, ClusterLight.POINT);
    }

    @Test
    void theGlslLookupCarriesTheGridConstants() {
        ClusterGrid g = ClusterGrid.of(1f, 16f / 9f, 0.1f, 200f, 1920, 1080, 64, 24, true);
        String glsl = g.glslLookup();
        assertTrue(glsl.contains("uvec3(30u, 17u, 24u)"), glsl);
        assertTrue(glsl.contains("CLUSTER_TILE = 64.0;"), glsl);
        assertTrue(glsl.contains("CLUSTER_SLICE_SCALE = " + g.sliceScale()), glsl);
        assertTrue(glsl.contains("CLUSTER_SLICE_BIAS = " + g.sliceBias()), glsl);
        assertTrue(glsl.contains("uint clusterIndex(vec2 fragCoord, float viewDepth)"));
        assertTrue(glsl.contains("log(viewDepth)"));
        // the formula in the text is the one sliceOf uses
        for (float d : new float[] {0.15f, 1f, 7.5f, 60f, 199f}) {
            int shader = (int) Math.max(0, Math.min(23, Math.floor(Math.log(d) * g.sliceScale() + g.sliceBias())));
            assertEquals(shader, g.sliceOf(d));
        }
    }
}
