package vmath.gl;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.camera.DualParaboloid;
import vmath.gpucull.GpuCullGlsl;
import vmath.lighting.ClusterGrid;
import vmath.map.AreaRenderPlan;
import vmath.map.AreaStrategy;
import vmath.map.SymbolRenderPlan;
import vmath.map.SymbolStrategy;
import vmath.map.TerrainShader;

/**
 * Pins, byte for byte, the GLSL that the generators of the render module produce: the two culling
 * compute shaders, the cluster lookup, the dual paraboloid function, and the shaders of the symbol,
 * area and terrain plans. A change of generated shader text then shows up as a change of a golden
 * file under {@code src/test/resources/golden}, which is reviewed, and not as a side effect of
 * another change (the default output must not change when a version parameter is added, GPU-9 and
 * GPU-10). The text is compiled by {@code ShaderCompileTest} where a compiler is installed.
 */
class ShaderGoldenTest {

    @Test
    void theCullingShadersAreTheSameAsBefore() {
        for (int group : new int[] {32, 64, 256}) {
            Golden.check("gpucull/compute-" + group, GpuCullGlsl.computeShader(group));
            Golden.check("gpucull/cluster-" + group, GpuCullGlsl.clusterShader(group));
        }
    }

    @Test
    void theClusterLookupIsTheSameAsBefore() {
        Golden.check("lighting/cluster-lookup-yup", ClusterGrid.of(1f, 16f / 9f, 0.1f, 200f, 1920, 1080, 64, 24, false).glslLookup());
        Golden.check("lighting/cluster-lookup-ydown", ClusterGrid.of(1f, 16f / 9f, 0.1f, 200f, 1920, 1080, 64, 24, true).glslLookup());
    }

    @Test
    void theDualParaboloidFunctionIsTheSameAsBefore() {
        Golden.check("camera/dual-paraboloid", DualParaboloid.glsl());
    }

    @Test
    void theMapShadersAreTheSameAsBefore() {
        for (GraphicsCapabilities caps : List.of(GraphicsCapabilities.baseline(), GraphicsCapabilities.openGl(4, 6, List.of()))) {
            String tag = "-" + caps.glsl().number();
            for (SymbolStrategy s : SymbolStrategy.values()) {
                SymbolRenderPlan plan = SymbolRenderPlan.force(s, caps);
                Golden.check("map/symbol-" + s + tag + "-vertex", plan.vertexShader());
                Golden.check("map/symbol-" + s + tag + "-fragment", plan.fragmentShader());
            }
            for (AreaStrategy s : AreaStrategy.values()) {
                AreaRenderPlan plan = AreaRenderPlan.force(s, caps);
                Golden.check("map/area-" + s + tag + "-vertex", plan.vertexShader());
                Golden.check("map/area-" + s + tag + "-fragment", plan.fragmentShader());
            }
            Golden.check("map/terrain" + tag + "-vertex", TerrainShader.vertexSource(caps));
            Golden.check("map/terrain" + tag + "-fragment", TerrainShader.fragmentSource(caps));
        }
    }
}
