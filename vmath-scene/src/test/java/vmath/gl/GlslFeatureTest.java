package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link GlslFeature} and {@link GlslVersion#supports}: the table, the gate, and the message of a refusal. */
class GlslFeatureTest {

    private static final List<GlslVersion> VERSIONS = List.of(GlslVersion.V330, GlslVersion.V400, GlslVersion.V410, GlslVersion.V420, GlslVersion.V430, GlslVersion.V440, GlslVersion.V450,
            GlslVersion.V460);

    @Test
    void theTableSaysWhatTheSpecificationsSay() {
        // confirmed by glslang in GlslFeatureCompileTest (GPU-12)
        assertEquals(330, GlslFeature.UNIFORM_BLOCK.minimum().number());
        assertEquals(330, GlslFeature.STD140_LAYOUT.minimum().number());
        assertEquals(330, GlslFeature.EXPLICIT_ATTRIBUTE_LOCATION.minimum().number());
        assertEquals(330, GlslFeature.TEXTURE_BUFFER.minimum().number());
        assertEquals(330, GlslFeature.BIT_CASTS.minimum().number());
        assertEquals(400, GlslFeature.DOUBLE_TYPES.minimum().number());
        assertEquals(420, GlslFeature.EXPLICIT_BINDING.minimum().number());
        assertEquals(420, GlslFeature.MEMORY_QUALIFIERS.minimum().number());
        assertEquals(430, GlslFeature.STD430_LAYOUT.minimum().number());
        assertEquals(430, GlslFeature.STORAGE_BLOCK.minimum().number());
        assertEquals(430, GlslFeature.COMPUTE_SHADER.minimum().number());
        assertEquals(430, GlslFeature.ATOMIC_BUFFER_FUNCTIONS.minimum().number());
        assertEquals(330, GlslFeature.MIX_WITH_BOOLEAN_SELECTOR.minimum().number(), "glslang accepts it from 3.30: the first version of this table said 4.50");
        assertEquals(460, GlslFeature.BUILTIN_DRAW_ID.minimum().number());
    }

    @Test
    void aVersionSupportsExactlyTheFeaturesAtOrBelowIt() {
        for (GlslVersion v : VERSIONS) {
            for (GlslFeature f : GlslFeature.values()) {
                assertEquals(v.number() >= f.minimum().number(), v.supports(f), v + " and " + f);
            }
        }
        // a feature that exists in a version exists in every later one
        for (GlslFeature f : GlslFeature.values()) {
            boolean seen = false;
            for (GlslVersion v : VERSIONS) {
                seen |= v.supports(f);
                assertEquals(seen, v.supports(f), f + " must not disappear in " + v);
            }
        }
    }

    @Test
    void everyFeatureIsDescribedAndTheFloorHasTheBasics() {
        for (GlslFeature f : GlslFeature.values()) {
            assertFalse(f.description().isBlank(), f.name());
            assertFalse(f.probeBody().isBlank() || !(f.probeStage().equals("vert") || f.probeStage().equals("comp")), f.name());
        }
        assertTrue(GlslVersion.V330.supports(GlslFeature.UNIFORM_BLOCK), "uniform blocks are at the floor: the library relies on them");
        assertFalse(GlslVersion.V330.supports(GlslFeature.COMPUTE_SHADER));
    }

    @Test
    void aMissingFeatureIsRefusedWithTheFeatureTheVersionAndTheLowestVersion() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> GlslVersion.V420.require(GlslFeature.STORAGE_BLOCK));
        assertTrue(e.getMessage().contains("a shader storage block") && e.getMessage().contains("GLSL 4.30") && e.getMessage().contains("GLSL 4.20"), e.getMessage());
        GlslVersion.V430.require(GlslFeature.STORAGE_BLOCK);          // no exception
    }

    @Test
    void theGateOfTheAccessModesReadsTheTable() {
        var storage330 = GraphicsCapabilities.openGl(3, 3, List.of());
        var storage46 = GraphicsCapabilities.openGl(4, 6, List.of());
        var item = new GlslType.Struct("Item", List.of(new GlslType.Member("a", GlslType.FLOAT)));
        assertFalse(StructArrayAccess.of(item, StructArrayAccess.Mode.TEXTURE_BUFFER, "t", 0, 2, storage330).hasExplicitBinding());
        assertTrue(StructArrayAccess.of(item, StructArrayAccess.Mode.TEXTURE_BUFFER, "t", 0, 2, storage46).hasExplicitBinding());
    }
}
