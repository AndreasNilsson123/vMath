package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.gl.GraphicsCapabilities.Api;
import vmath.gl.GraphicsCapabilities.Feature;

/**
 * {@link GraphicsCapabilities} and {@link GlslVersion}: the table of versions and extensions is
 * checked against the values that the specifications give, version by version.
 */
class GraphicsCapabilitiesTest {

    private static Set<Feature> openGl(int major, int minor, String... extensions) {
        return GraphicsCapabilities.openGl(major, minor, List.of(extensions)).features();
    }

    @Test
    void glslVersionsAreTheDesktopOnesFrom330() {
        assertEquals(330, GlslVersion.V330.number());
        assertSame(GlslVersion.V430, GlslVersion.of(430));
        for (int bad : new int[] {0, 120, 150, 320, 331, 470, 1000, -1}) {
            assertThrows(IllegalArgumentException.class, () -> GlslVersion.of(bad), "number " + bad);
            assertThrows(IllegalArgumentException.class, () -> new GlslVersion(bad), "number " + bad);
        }
        assertEquals("#version 330 core", GlslVersion.V330.versionLine());
        assertEquals("GLSL 4.50", GlslVersion.V450.toString());
        assertTrue(GlslVersion.V450.atLeast(GlslVersion.V420) && GlslVersion.V420.atLeast(GlslVersion.V420) && !GlslVersion.V410.atLeast(GlslVersion.V420));
        assertTrue(GlslVersion.V330.compareTo(GlslVersion.V460) < 0);
    }

    @Test
    void glslVersionOfAnOpenGlVersion() {
        assertEquals(GlslVersion.V330, GlslVersion.ofOpenGl(3, 3));
        assertEquals(GlslVersion.V330, GlslVersion.ofOpenGl(3, 5), "a 3.x context newer than 3.3 still has 3.30 as its newest core GLSL here");
        for (int minor = 0; minor <= 6; minor++) {
            assertEquals(GlslVersion.of(400 + 10 * minor), GlslVersion.ofOpenGl(4, minor), "OpenGL 4." + minor);
        }
        assertEquals(GlslVersion.V460, GlslVersion.ofOpenGl(4, 7), "newer than 4.6 is treated as 4.6");
        assertEquals(GlslVersion.V460, GlslVersion.ofOpenGl(5, 0));
        assertThrows(IllegalArgumentException.class, () -> GlslVersion.ofOpenGl(3, 2));
        assertThrows(IllegalArgumentException.class, () -> GlslVersion.ofOpenGl(2, 1));
    }

    @Test
    void theFloorHasTheFeaturesThatGl33Has() {
        Set<Feature> f = openGl(3, 3);
        assertEquals(EnumSet.of(Feature.MULTI_DRAW, Feature.BASE_VERTEX, Feature.INSTANCED_DRAWS, Feature.INSTANCED_ARRAYS, Feature.UNIFORM_BLOCKS, Feature.TEXTURE_BUFFERS), f);
        assertEquals(GraphicsCapabilities.openGl(3, 3, List.of()), GraphicsCapabilities.baseline());
        assertThrows(IllegalArgumentException.class, () -> GraphicsCapabilities.openGl(3, 2, List.of()));
    }

    @Test
    void everyFeatureComesInWithItsCoreVersion() {
        // feature, the first OpenGL minor version of 4.x with it
        Object[][] table = {
                {Feature.DRAW_INDIRECT, 0}, {Feature.BASE_INSTANCE, 2}, {Feature.MULTI_DRAW_INDIRECT, 3}, {Feature.STORAGE_BUFFERS, 3}, {Feature.COMPUTE_SHADERS, 3},
                {Feature.PERSISTENT_MAPPING, 4}, {Feature.SHADER_DRAW_PARAMETERS, 6}};
        for (Object[] row : table) {
            Feature feature = (Feature) row[0];
            int since = (Integer) row[1];
            assertFalse(openGl(3, 3).contains(feature), feature + " is not in 3.3");
            for (int minor = 0; minor <= 6; minor++) {
                assertEquals(minor >= since, openGl(4, minor).contains(feature), feature + " in 4." + minor);
            }
        }
    }

    @Test
    void anExtensionTurnsAFeatureOnInAnOlderContext() {
        Object[][] table = {
                {"ARB_draw_indirect", Feature.DRAW_INDIRECT}, {"ARB_multi_draw_indirect", Feature.MULTI_DRAW_INDIRECT}, {"ARB_base_instance", Feature.BASE_INSTANCE},
                {"ARB_shader_storage_buffer_object", Feature.STORAGE_BUFFERS},
                {"ARB_compute_shader", Feature.COMPUTE_SHADERS}, {"ARB_buffer_storage", Feature.PERSISTENT_MAPPING}};
        for (Object[] row : table) {
            String ext = (String) row[0];
            Feature feature = (Feature) row[1];
            assertTrue(openGl(3, 3, ext).contains(feature), ext);
            assertTrue(openGl(3, 3, "GL_" + ext).contains(feature), "GL_" + ext);
            assertFalse(openGl(3, 3, "ARB_other").contains(feature));
        }
        assertTrue(openGl(3, 3, "ARB_multi_draw_indirect").contains(Feature.DRAW_INDIRECT), "multi-draw indirect implies indirect draws");
        // the draw index through the extension is written for GLSL 4.40 and later only: glslang knows gl_DrawIDARB from there (GPU-12)
        assertFalse(openGl(3, 3, "ARB_shader_draw_parameters").contains(Feature.SHADER_DRAW_PARAMETERS));
        assertFalse(openGl(4, 3, "GL_ARB_shader_draw_parameters").contains(Feature.SHADER_DRAW_PARAMETERS));
        assertTrue(openGl(4, 4, "GL_ARB_shader_draw_parameters").contains(Feature.SHADER_DRAW_PARAMETERS));
        assertTrue(openGl(4, 6).contains(Feature.SHADER_DRAW_PARAMETERS));
        assertEquals(GlslVersion.V330, GraphicsCapabilities.openGl(3, 3, List.of("ARB_compute_shader")).glsl(), "an extension does not raise the GLSL version");
    }

    @Test
    void vulkanHasWhatVulkanAlwaysHasAndTheThreeOptionalFeatures() {
        GraphicsCapabilities none = GraphicsCapabilities.vulkan(false, false, false);
        assertEquals(Api.VULKAN, none.api());
        assertEquals(GlslVersion.V450, none.glsl());
        assertTrue(none.has(Feature.STORAGE_BUFFERS) && none.has(Feature.COMPUTE_SHADERS) && none.has(Feature.DRAW_INDIRECT) && none.has(Feature.INSTANCED_DRAWS));
        assertFalse(none.has(Feature.MULTI_DRAW), "Vulkan has no glMultiDraw");
        assertFalse(none.has(Feature.MULTI_DRAW_INDIRECT) || none.has(Feature.BASE_INSTANCE) || none.has(Feature.SHADER_DRAW_PARAMETERS));
        GraphicsCapabilities all = GraphicsCapabilities.vulkan(true, true, true);
        assertTrue(all.has(Feature.MULTI_DRAW_INDIRECT) && all.has(Feature.BASE_INSTANCE) && all.has(Feature.SHADER_DRAW_PARAMETERS));
    }

    @Test
    void featuresCanBeTakenAwayAndTakeTheirDependentsWithThem() {
        GraphicsCapabilities gl45 = GraphicsCapabilities.openGl(4, 5, List.of());
        GraphicsCapabilities less = gl45.without(Feature.MULTI_DRAW_INDIRECT, Feature.BASE_INSTANCE);
        assertFalse(less.has(Feature.MULTI_DRAW_INDIRECT) || less.has(Feature.BASE_INSTANCE));
        assertTrue(less.has(Feature.DRAW_INDIRECT) && gl45.has(Feature.MULTI_DRAW_INDIRECT), "the original is unchanged");
        assertFalse(gl45.without(Feature.DRAW_INDIRECT).has(Feature.MULTI_DRAW_INDIRECT), "no indirect draws, no multi-draw indirect");
        assertEquals(EnumSet.of(Feature.STORAGE_BUFFERS), gl45.without(Feature.STORAGE_BUFFERS).missing(List.of(Feature.STORAGE_BUFFERS, Feature.COMPUTE_SHADERS)));
    }

    @Test
    void missingListsWhatIsNotThere() {
        GraphicsCapabilities base = GraphicsCapabilities.baseline();
        assertEquals(EnumSet.of(Feature.STORAGE_BUFFERS, Feature.COMPUTE_SHADERS), base.missing(List.of(Feature.STORAGE_BUFFERS, Feature.COMPUTE_SHADERS, Feature.UNIFORM_BLOCKS)));
        assertTrue(base.missing(List.of()).isEmpty());
    }

    @Test
    void theGlslVersionCanBeLoweredButNotRaised() {
        GraphicsCapabilities gl46 = GraphicsCapabilities.openGl(4, 6, List.of());
        GraphicsCapabilities low = gl46.withGlsl(GlslVersion.V330);
        assertEquals(GlslVersion.V330, low.glsl());
        assertEquals(gl46.features(), low.features());
        assertThrows(IllegalArgumentException.class, () -> GraphicsCapabilities.baseline().withGlsl(GlslVersion.V450));
    }

    @Test
    void valuesCompareByContent() {
        assertEquals(GraphicsCapabilities.openGl(4, 3, List.of()), GraphicsCapabilities.openGl(4, 3, List.of("GL_ARB_compute_shader")));
        assertFalse(GraphicsCapabilities.openGl(4, 3, List.of()).equals(GraphicsCapabilities.openGl(4, 4, List.of())));
        assertEquals(GraphicsCapabilities.baseline().hashCode(), GraphicsCapabilities.baseline().hashCode());
        assertTrue(GraphicsCapabilities.baseline().toString().contains("OPENGL 3.3"));
        Set<Feature> copy = GraphicsCapabilities.baseline().features();
        copy.add(Feature.COMPUTE_SHADERS);
        assertFalse(GraphicsCapabilities.baseline().has(Feature.COMPUTE_SHADERS), "features() is a copy");
    }
}
