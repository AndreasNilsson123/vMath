package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;
import vmath.gl.GraphicsCapabilities.Feature;
import vmath.gl.StructArrayAccess.Mode;
import vmath.gl.StructArrayAccess.Need;

/**
 * {@link StructArrayAccess}: the text of every mode, the layouts and strides that the Java side
 * needs, the gating by capabilities, and the integration with {@link ShaderHeader}.
 */
class StructArrayAccessTest {

    private static final Struct STYLE = new Struct("Style", List.of(new Member("color", GlslType.VEC4), new Member("width", GlslType.FLOAT), new Member("flags", GlslType.UINT)));

    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();
    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());

    @Test
    void aStorageBlockIsAnUnsizedArrayAndFetchIsAnIndex() {
        StructArrayAccess a = StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "styles", 0, 3, GL46);
        assertEquals("layout(std430, binding = 3) readonly buffer styles_Block {\n    Style styles[];\n};\n\nStyle fetch_styles(int i) {\n    return styles[i];\n}\n", a.glsl());
        assertEquals(GpuLayout.STD430, a.layout().layout());
        assertEquals(32, a.elementStride());
        assertEquals(3200, a.byteSize(100));
        assertTrue(a.hasExplicitBinding());
        assertEquals("fetch_styles", a.fetchFunction());
        assertEquals(Mode.STORAGE_BLOCK, a.mode());
        assertEquals("styles", a.name());
        assertEquals(STYLE, a.struct());
        assertEquals(3, a.fields().size());
    }

    @Test
    void aUniformBlockHasALengthAndTheStd140Stride() {
        StructArrayAccess a = StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "styles", 64, -1, GL33);
        assertEquals("layout(std140) uniform styles_Block {\n    Style styles[64];\n};\n\nStyle fetch_styles(int i) {\n    return styles[i];\n}\n", a.glsl());
        assertEquals(GpuLayout.STD140, a.layout().layout());
        assertEquals(32, a.elementStride());
        assertFalse(a.hasExplicitBinding());
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "styles", 513, -1, GL33), "513 * 32 bytes is over 16384");
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "styles", 0, -1, GL33));
        StructArrayAccess max = StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "styles", 512, -1, GL33);
        assertEquals(16384, max.byteSize(512));
    }

    @Test
    void aTextureBufferReadsTexelsAndDecodesTheBits() {
        StructArrayAccess a = StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "styles", 0, -1, GL33);
        assertEquals("uniform usamplerBuffer styles_texels;\n\n"
                + "Style fetch_styles(int i) {\n"
                + "    uvec4 t0 = texelFetch(styles_texels, i * 2);\n"
                + "    uvec4 t1 = texelFetch(styles_texels, i * 2 + 1);\n"
                + "    Style r;\n"
                + "    r.color = vec4(uintBitsToFloat(t0.x), uintBitsToFloat(t0.y), uintBitsToFloat(t0.z), uintBitsToFloat(t0.w));\n"
                + "    r.width = uintBitsToFloat(t1.x);\n"
                + "    r.flags = t1.y;\n"
                + "    return r;\n}\n", a.glsl());
        assertEquals(2, a.texelsPerElement());
        assertEquals(32, a.elementStride());
        assertEquals(GpuLayout.STD430, a.layout().layout());
    }

    @Test
    void aTextureBufferPadsAnElementToWholeTexelsAndDecodesSignedIntegers() {
        Struct small = new Struct("Pair", List.of(new Member("a", GlslType.INT), new Member("b", GlslType.IVEC2)));
        StructArrayAccess a = StructArrayAccess.of(small, Mode.TEXTURE_BUFFER, "pairs", 0, 2, GraphicsCapabilities.openGl(4, 2, List.of()));
        assertEquals(16, a.elementStride(), "the element is 16 bytes at most: padded to one texel");
        assertEquals(1, a.texelsPerElement());
        assertTrue(a.glsl().startsWith("layout(binding = 2) uniform usamplerBuffer pairs_texels;"), a.glsl());
        assertTrue(a.glsl().contains("r.a = int(t0.x);") && a.glsl().contains("r.b = ivec2(int(t0.z), int(t0.w));"), a.glsl());
        Struct odd = new Struct("Triple", List.of(new Member("a", GlslType.FLOAT), new Member("b", GlslType.FLOAT), new Member("c", GlslType.FLOAT)));
        assertEquals(16, StructArrayAccess.of(odd, Mode.TEXTURE_BUFFER, "t", 0, -1, GL33).elementStride(), "12 bytes padded to 16");
        assertEquals(12, StructArrayAccess.of(odd, Mode.STORAGE_BLOCK, "t", 0, -1, GL46).elementStride());
    }

    @Test
    void vertexAttributesHoldTheInstanceThatIsBeingDrawn() {
        StructArrayAccess a = StructArrayAccess.of(STYLE, Mode.VERTEX_ATTRIBUTE, "styles", 0, 4, GL33);
        assertEquals("layout(location = 4) in vec4 styles_color;\nlayout(location = 5) in float styles_width;\nlayout(location = 6) in uint styles_flags;\n\n"
                + "// the attributes hold the instance that is being drawn: the argument is not used\n"
                + "Style fetch_styles(int i) {\n    Style r;\n    r.color = styles_color;\n    r.width = styles_width;\n    r.flags = styles_flags;\n    return r;\n}\n", a.glsl());
        VertexBufferLayout v = a.vertexLayout();
        assertTrue(v.perInstance());
        assertEquals(32, v.stride());
        assertEquals(VertexFormat.FLOAT32X4, v.attribute("styles_color").format());
        assertEquals(16, v.attribute("styles_width").offset());
        assertEquals(VertexFormat.UINT32, v.attribute("styles_flags").format());
        assertEquals(20, v.attribute("styles_flags").offset());
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.VERTEX_ATTRIBUTE, "styles", 0, -1, GL33), "needs a first location");
    }

    @Test
    void membersThatTheSimpleModesCannotReadAreRefusedByName() {
        Struct withMatrix = new Struct("Instance", List.of(new Member("model", GlslType.MAT4), new Member("id", GlslType.UINT)));
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> StructArrayAccess.of(withMatrix, Mode.TEXTURE_BUFFER, "inst", 0, -1, GL33));
        assertTrue(e.getMessage().contains("Instance.model") && e.getMessage().contains("a matrix") && e.getMessage().contains("TEXTURE_BUFFER"), e.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> StructArrayAccess.of(withMatrix, Mode.VERTEX_ATTRIBUTE, "inst", 0, 0, GL33));
        Struct inner = new Struct("Inner", List.of(new Member("x", GlslType.FLOAT)));
        Struct nested = new Struct("Outer", List.of(new Member("in", inner)));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> StructArrayAccess.of(nested, Mode.TEXTURE_BUFFER, "o", 0, -1, GL33)).getMessage().contains("a nested struct"));
        // the block modes take anything the layouts handle
        assertEquals(80, StructArrayAccess.of(withMatrix, Mode.STORAGE_BLOCK, "inst", 0, -1, GL46).elementStride());
        assertEquals(80, StructArrayAccess.of(withMatrix, Mode.UNIFORM_BLOCK, "inst", 8, -1, GL33).elementStride());
    }

    @Test
    void theBindingIsWrittenOnlyWhereTheGlslVersionHasIt() {
        GraphicsCapabilities oldWithStorage = GraphicsCapabilities.openGl(3, 3, List.of("GL_ARB_shader_storage_buffer_object"));
        StructArrayAccess ssbo = StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "styles", 0, 3, oldWithStorage);
        assertFalse(ssbo.hasExplicitBinding());
        assertFalse(ssbo.glsl().contains("binding"), "GLSL 3.30 has no layout(binding): the caller binds through the API");
        assertTrue(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "styles", 0, 3, GL46.withGlsl(GlslVersion.V420)).glsl().contains("binding = 3"));
        assertFalse(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "styles", 0, 3, GL46.withGlsl(GlslVersion.V410)).glsl().contains("binding"));
        assertFalse(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "styles", 0, -1, GL46).glsl().contains("binding"), "a negative slot writes none");
    }

    @Test
    void aModeTheCapabilitiesLackIsRefused() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "styles", 0, -1, GL33));
        assertTrue(e.getMessage().contains("STORAGE_BUFFERS"), e.getMessage());
        GraphicsCapabilities noInstancing = GL33.without(Feature.INSTANCED_ARRAYS);
        assertThrows(UnsupportedOperationException.class, () -> StructArrayAccess.of(STYLE, Mode.VERTEX_ATTRIBUTE, "styles", 0, 0, noInstancing));
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "not an identifier", 0, -1, GL33));
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, null, 0, -1, GL33));
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "styles", -1, -1, GL33));
    }

    @Test
    void theChoiceFollowsWhatTheContextHas() {
        assertEquals(Mode.TEXTURE_BUFFER, StructArrayAccess.choose(GL33, Need.RANDOM_ACCESS));
        assertEquals(Mode.STORAGE_BLOCK, StructArrayAccess.choose(GL46, Need.RANDOM_ACCESS));
        assertEquals(Mode.VERTEX_ATTRIBUTE, StructArrayAccess.choose(GL33, Need.PER_INSTANCE));
        assertEquals(Mode.VERTEX_ATTRIBUTE, StructArrayAccess.choose(GL46, Need.PER_INSTANCE));
        assertEquals(Mode.STORAGE_BLOCK, StructArrayAccess.choose(GL46.without(Feature.INSTANCED_ARRAYS), Need.PER_INSTANCE));
        assertEquals(Mode.UNIFORM_BLOCK, StructArrayAccess.choose(GL33.without(Feature.TEXTURE_BUFFERS), Need.RANDOM_ACCESS));
        assertThrows(UnsupportedOperationException.class, () -> StructArrayAccess.choose(GL33.without(Feature.TEXTURE_BUFFERS, Feature.UNIFORM_BLOCKS), Need.RANDOM_ACCESS));
        for (Mode m : Mode.values()) {
            assertEquals(m == Mode.STORAGE_BLOCK ? Feature.STORAGE_BUFFERS : m == Mode.UNIFORM_BLOCK ? Feature.UNIFORM_BLOCKS : m == Mode.TEXTURE_BUFFER ? Feature.TEXTURE_BUFFERS
                    : Feature.INSTANCED_ARRAYS, m.requires());
        }
    }

    @Test
    void theAccessorsThatBelongToOtherModesRefuse() {
        StructArrayAccess ssbo = StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "styles", 0, -1, GL46);
        assertThrows(IllegalStateException.class, ssbo::texelsPerElement);
        assertThrows(IllegalStateException.class, ssbo::vertexLayout);
    }

    @Test
    void theHeaderDeclaresTheStructBeforeTheFetchFunction() {
        StructArrayAccess a = StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "styles", 0, -1, GL33);
        String h = ShaderHeader.builder("STYLES").access(a).build(ShaderHeader.Language.GLSL);
        int declaration = h.indexOf("struct Style {");
        int fetch = h.indexOf("Style fetch_styles(int i)");
        assertTrue(declaration > 0 && fetch > declaration, h);
        assertTrue(h.contains("// std430, 32 bytes, alignment 16: color@0, width@16, flags@20"), h);
        assertTrue(h.endsWith("#endif // STYLES\n"));
        assertFalse(ShaderHeader.builder("STYLES").access(a).build(ShaderHeader.Language.SLANG).contains("fetch_"), "the Slang output does not carry GLSL functions");
        // a struct that was added by hand as well is declared once
        String twice = ShaderHeader.builder("STYLES").struct(a.layout()).access(a).build(ShaderHeader.Language.GLSL);
        assertEquals(twice.indexOf("struct Style {"), twice.lastIndexOf("struct Style {"));
        // two arrays of one struct in different modes
        StructArrayAccess b = StructArrayAccess.of(STYLE, Mode.VERTEX_ATTRIBUTE, "perInstance", 0, 0, GL33);
        String both = ShaderHeader.builder("BOTH").access(a).access(b).build(ShaderHeader.Language.GLSL);
        assertTrue(both.contains("fetch_styles") && both.contains("fetch_perInstance"));
    }
}
