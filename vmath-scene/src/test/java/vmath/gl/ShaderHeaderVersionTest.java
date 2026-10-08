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

/** {@link ShaderHeader.Builder#version}: nothing changes without it, a missing feature is refused, and below 4.20 the bindings are left out and returned. */
class ShaderHeaderVersionTest {

    private static final Struct STYLE = new Struct("Style", List.of(new Member("color", GlslType.VEC4), new Member("width", GlslType.FLOAT)));
    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();
    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());

    private static ShaderHeader.Builder withAccesses(GraphicsCapabilities caps) {
        return ShaderHeader.builder("H").access(StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "table", 16, 1, caps)).access(StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "texels", 0, 2, caps));
    }

    @Test
    void withoutAVersionNothingIsCheckedAndNothingIsStripped() {
        ShaderHeader h = withAccesses(GL46).build();
        assertTrue(h.emit(ShaderHeader.Language.GLSL).contains("binding = 1"));
        assertTrue(h.bindings().isEmpty());
        ShaderHeader storage = ShaderHeader.builder("H").access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, GL46)).build();
        assertTrue(storage.emit(ShaderHeader.Language.GLSL).contains("readonly buffer"));
    }

    @Test
    void aVersionThatHasEverythingChangesNothing() {
        String plain = withAccesses(GL46).build().emit(ShaderHeader.Language.GLSL);
        for (GlslVersion v : List.of(GlslVersion.V420, GlslVersion.V430, GlslVersion.V450, GlslVersion.V460)) {
            ShaderHeader h = withAccesses(GL46).version(v).build();
            assertEquals(plain, h.emit(ShaderHeader.Language.GLSL), v.toString());
            assertTrue(h.bindings().isEmpty(), v.toString());
        }
    }

    @Test
    void aStorageBlockOrStd430BelowFourThirtyIsRefusedWithTheFeatureAndTheVersion() {
        ShaderHeader storage = ShaderHeader.builder("H").access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, GL46)).version(GlslVersion.V420).build();
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> storage.emit(ShaderHeader.Language.GLSL));
        assertTrue(e.getMessage().contains("a shader storage block") && e.getMessage().contains("GLSL 4.30") && e.getMessage().contains("GLSL 4.20"), e.getMessage());
        ShaderHeader block = ShaderHeader.builder("H").block(STYLE, GpuLayout.STD430, "buffer", "b").version(GlslVersion.V400).build();
        assertThrows(UnsupportedOperationException.class, () -> block.emit(ShaderHeader.Language.GLSL));
        ShaderHeader std430Uniform = ShaderHeader.builder("H").block(STYLE, GpuLayout.STD430, "uniform", "b").version(GlslVersion.V420).build();
        assertThrows(UnsupportedOperationException.class, () -> std430Uniform.emit(ShaderHeader.Language.GLSL), "std430 is a 4.30 layout whatever it is used for");
        ShaderHeader ok = ShaderHeader.builder("H").block(STYLE, GpuLayout.STD140, "uniform", "b").version(GlslVersion.V330).build();
        assertTrue(ok.emit(ShaderHeader.Language.GLSL).contains("layout(std140) uniform Style"));
        ShaderHeader fine = ShaderHeader.builder("H").access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, GL46)).version(GlslVersion.V430).build();
        assertTrue(fine.emit(ShaderHeader.Language.GLSL).contains("readonly buffer"));
    }

    @Test
    void belowFourTwentyTheBindingsAreLeftOutAndReturned() {
        ShaderHeader h = withAccesses(GL46).version(GlslVersion.V330).build();
        String text = h.emit(ShaderHeader.Language.GLSL);
        assertFalse(text.contains("binding = 1)") || text.contains(", binding"), "no explicit binding is written");
        assertFalse(text.contains("layout(binding"), "nor on the sampler");
        assertEquals(2, h.bindings().size());
        assertEquals(new ShaderHeader.Binding("table", Mode.UNIFORM_BLOCK, 1, "glUniformBlockBinding(program, glGetUniformBlockIndex(program, \"table_Block\"), 1)"), h.bindings().get(0));
        assertEquals(new ShaderHeader.Binding("texels", Mode.TEXTURE_BUFFER, 2, "glUniform1i(glGetUniformLocation(program, \"texels_texels\"), 2)"), h.bindings().get(1));
        assertTrue(text.contains("// no explicit binding points before GLSL 4.20"));
        assertTrue(text.contains("//   glUniform1i(glGetUniformLocation(program, \"texels_texels\"), 2)"));
        // what is left is the text that an access made for 3.30 has
        String withoutComment = text.replaceAll("(?s)// no explicit binding points.*?\\n\\n", "");
        assertEquals(withAccesses(GL33).build().emit(ShaderHeader.Language.GLSL), withoutComment);
        for (GlslVersion v : List.of(GlslVersion.V400, GlslVersion.V410)) {
            assertEquals(2, withAccesses(GL46).version(v).build().bindings().size(), v.toString());
        }
    }

    @Test
    void theVersionLineIsOptionalAndNeedsAVersion() {
        String text = withAccesses(GL46).version(GlslVersion.V450).withVersionLine().build().emit(ShaderHeader.Language.GLSL);
        assertTrue(text.startsWith("#version 450 core\n// Generated by vmath ShaderHeader from the Java struct layouts. Do not edit.\n#ifndef H\n"), text.substring(0, 120));
        assertFalse(withAccesses(GL46).version(GlslVersion.V450).build().emit(ShaderHeader.Language.GLSL).contains("#version"));
        assertThrows(IllegalStateException.class, () -> withAccesses(GL46).withVersionLine().build());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").version(null));
    }

    @Test
    void slangIsNotAffectedByAVersion() {
        ShaderHeader.Builder b = ShaderHeader.builder("H").struct(STYLE.layout(GpuLayout.STD430)).constant("N", 3).access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, GL46));
        String plain = b.build().emit(ShaderHeader.Language.SLANG);
        ShaderHeader.Builder c = ShaderHeader.builder("H").struct(STYLE.layout(GpuLayout.STD430)).constant("N", 3).access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, GL46))
                .version(GlslVersion.V330).withVersionLine();
        assertEquals(plain, c.build().emit(ShaderHeader.Language.SLANG));
        assertThrows(UnsupportedOperationException.class, () -> c.build().emit(ShaderHeader.Language.GLSL));
    }
}
