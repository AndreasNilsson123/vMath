package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;
import vmath.gl.StructArrayAccess.Mode;

/** {@link GlslEsVersion} and {@link ShaderHeader.Builder#esVersion}: the ES column of the table, the precision statements, the bindings and the refusals. */
class ShaderHeaderEsTest {

    private static final Struct STYLE = new Struct("Style", List.of(new Member("color", GlslType.VEC4), new Member("width", GlslType.FLOAT)));
    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());

    @Test
    void theEsTableIsWhatTheCompilerSaid() {
        assertEquals(GlslEsVersion.V300, GlslFeature.UNIFORM_BLOCK.minimumEs());
        assertEquals(GlslEsVersion.V300, GlslFeature.STD140_LAYOUT.minimumEs());
        assertEquals(GlslEsVersion.V300, GlslFeature.EXPLICIT_ATTRIBUTE_LOCATION.minimumEs());
        assertEquals(GlslEsVersion.V300, GlslFeature.BIT_CASTS.minimumEs());
        assertEquals(GlslEsVersion.V300, GlslFeature.MIX_WITH_BOOLEAN_SELECTOR.minimumEs());
        for (GlslFeature f : List.of(GlslFeature.EXPLICIT_BINDING, GlslFeature.MEMORY_QUALIFIERS, GlslFeature.STD430_LAYOUT, GlslFeature.STORAGE_BLOCK, GlslFeature.COMPUTE_SHADER,
                GlslFeature.ATOMIC_BUFFER_FUNCTIONS)) {
            assertEquals(GlslEsVersion.V310, f.minimumEs(), f.name());
        }
        assertEquals(GlslEsVersion.V320, GlslFeature.TEXTURE_BUFFER.minimumEs());
        assertEquals(null, GlslFeature.DOUBLE_TYPES.minimumEs());
        assertEquals(null, GlslFeature.BUILTIN_DRAW_ID.minimumEs());
        assertTrue(GlslEsVersion.V310.supports(GlslFeature.COMPUTE_SHADER));
        assertFalse(GlslEsVersion.V300.supports(GlslFeature.COMPUTE_SHADER));
        assertFalse(GlslEsVersion.V320.supports(GlslFeature.DOUBLE_TYPES));
    }

    @Test
    void esVersionsAreValuesWithALine() {
        assertEquals("#version 300 es", GlslEsVersion.V300.versionLine());
        assertEquals("GLSL ES 3.20", GlslEsVersion.V320.toString());
        assertEquals(GlslEsVersion.V310, GlslEsVersion.of(310));
        assertThrows(IllegalArgumentException.class, () -> GlslEsVersion.of(100));
        assertThrows(IllegalArgumentException.class, () -> GlslEsVersion.of(330));
        assertTrue(GlslEsVersion.V320.compareTo(GlslEsVersion.V300) > 0);
        UnsupportedOperationException none = assertThrows(UnsupportedOperationException.class, () -> GlslEsVersion.V320.require(GlslFeature.DOUBLE_TYPES));
        assertTrue(none.getMessage().contains("does not exist in GLSL ES"), none.getMessage());
        UnsupportedOperationException later = assertThrows(UnsupportedOperationException.class, () -> GlslEsVersion.V300.require(GlslFeature.STORAGE_BLOCK));
        assertTrue(later.getMessage().contains("GLSL ES 3.10") && later.getMessage().contains("GLSL ES 3.00"), later.getMessage());
    }

    @Test
    void theHeaderWritesTheVersionLineAndThePrecisionThatEsNeeds() {
        String text = ShaderHeader.builder("H").esVersion(GlslEsVersion.V300).withVersionLine().access(StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "t", 8, 1, GL46)).build()
                .emit(ShaderHeader.Language.GLSL);
        assertTrue(text.startsWith("#version 300 es\n// Generated"), text.substring(0, 60));
        assertTrue(text.contains("#ifndef H\n#define H\n\nprecision highp float;\nprecision highp int;\n"), text.substring(0, 160));
        assertFalse(text.contains("usamplerBuffer"));
        String texture = ShaderHeader.builder("H").esVersion(GlslEsVersion.V320).access(StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "t", 0, 1, GL46)).build().emit(ShaderHeader.Language.GLSL);
        assertTrue(texture.contains("precision highp usamplerBuffer;\n"), texture);
        assertFalse(texture.contains("#version"), "without withVersionLine the includer has the line");
    }

    @Test
    void whatEsLacksIsRefusedAndTheBindingsOfThreeHundredAreReturned() {
        ShaderHeader storage = ShaderHeader.builder("H").esVersion(GlslEsVersion.V300).access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, GL46)).build();
        assertThrows(UnsupportedOperationException.class, () -> storage.emit(ShaderHeader.Language.GLSL));
        ShaderHeader texture = ShaderHeader.builder("H").esVersion(GlslEsVersion.V310).access(StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "t", 0, 1, GL46)).build();
        assertThrows(UnsupportedOperationException.class, () -> texture.emit(ShaderHeader.Language.GLSL));
        ShaderHeader std430 = ShaderHeader.builder("H").esVersion(GlslEsVersion.V300).block(STYLE, GpuLayout.STD430, "buffer", "b").build();
        assertThrows(UnsupportedOperationException.class, () -> std430.emit(ShaderHeader.Language.GLSL));
        // a uniform block read from ES 3.00 has no binding in the text, and the bindings are listed
        ShaderHeader h = ShaderHeader.builder("H").esVersion(GlslEsVersion.V300).access(StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "t", 8, 3, GL46)).build();
        assertFalse(h.emit(ShaderHeader.Language.GLSL).contains("binding = 3)") || h.emit(ShaderHeader.Language.GLSL).contains(", binding"));
        assertEquals(1, h.bindings().size());
        // from 3.10 on they are kept, and a storage block is allowed
        ShaderHeader ok = ShaderHeader.builder("H").esVersion(GlslEsVersion.V310).access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 2, GL46)).build();
        assertTrue(ok.emit(ShaderHeader.Language.GLSL).contains("layout(std430, binding = 2) readonly buffer"));
        assertTrue(ok.bindings().isEmpty());
    }

    @Test
    void anEsVersionIsNotAlsoADesktopOrVulkanVersion() {
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").version(GlslVersion.V450).esVersion(GlslEsVersion.V310).build());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").esVersion(GlslEsVersion.V310).target(ShaderHeader.Target.VULKAN).build());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").esVersion(null));
        assertThrows(IllegalStateException.class, () -> ShaderHeader.builder("H").withVersionLine().build());
    }

    @Test
    void slangIsNotAffectedByAnEsVersion() {
        String plain = ShaderHeader.builder("H").struct(STYLE.layout(GpuLayout.STD430)).build().emit(ShaderHeader.Language.SLANG);
        String es = ShaderHeader.builder("H").struct(STYLE.layout(GpuLayout.STD430)).esVersion(GlslEsVersion.V300).withVersionLine().build().emit(ShaderHeader.Language.SLANG);
        assertEquals(plain, es);
    }
}
