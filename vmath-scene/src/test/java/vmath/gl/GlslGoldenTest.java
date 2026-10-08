package vmath.gl;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;
import vmath.gl.StructArrayAccess.Mode;

/**
 * Pins the GLSL text that the generators of {@code vmath.gl} produce, byte for byte, so that adding
 * a version parameter (GPU-9) cannot change what is produced without it: every default output is
 * compared with a file under {@code src/test/resources/golden/gl}. The generators are
 * {@link ShaderHeader} (GLSL and Slang, structs, nested structs, arrays, constants, blocks and the
 * array accesses), {@link StructArrayAccess} in every mode at several GLSL versions, the types of
 * {@link GlslType}, and {@link VertexBufferLayout#glslInputs()}.
 */
class GlslGoldenTest {

    private static final Struct STYLE = new Struct("Style", List.of(new Member("color", GlslType.VEC4), new Member("width", GlslType.FLOAT), new Member("flags", GlslType.UINT)));
    private static final Struct LIGHT = new Struct("Light", List.of(new Member("position", GlslType.VEC3), new Member("radius", GlslType.FLOAT), new Member("color", GlslType.VEC3),
            new Member("kind", GlslType.INT)));
    private static final Struct SCENE = new Struct("Scene", List.of(new Member("viewProjection", GlslType.MAT4), new Member("lights", new GlslType.Array(LIGHT, 4)),
            new Member("ambient", GlslType.VEC3), new Member("count", GlslType.UINT)));

    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();
    private static final GraphicsCapabilities GL42 = GraphicsCapabilities.openGl(4, 2, List.of());
    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());

    @Test
    void theStructAndBlockTypesAreWrittenAsTheyAre() {
        Golden.check("gl/struct-declaration", STYLE.glslDeclaration() + "\n" + SCENE.glslDeclaration() + "\n");
        Golden.check("gl/block-std140", SCENE.glslBlock(GpuLayout.STD140, "uniform", "scene") + "\n");
        Golden.check("gl/block-std430", LIGHT.glslBlock(GpuLayout.STD430, "buffer", null) + "\n");
    }

    @Test
    void aHeaderIsWrittenInGlslAndInSlang() {
        ShaderHeader.Builder b = ShaderHeader.builder("VMATH_TEST_HEADER").struct(SCENE.layout(GpuLayout.STD140)).struct(STYLE.layout(GpuLayout.STD430)).constant("MAX_LIGHTS", 4).constant("FLAG_HIDDEN", 4L)
                .constant("SCALE", 0.5f).block(SCENE, GpuLayout.STD140, "uniform", "scene");
        ShaderHeader h = b.build();
        Golden.check("gl/header-glsl", h.emit(ShaderHeader.Language.GLSL));
        Golden.check("gl/header-slang", h.emit(ShaderHeader.Language.SLANG));
    }

    @Test
    void aHeaderWithAccessesInEveryModeIsWrittenAsItIs() {
        StructArrayAccess storage = StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "styles", 0, 3, GL46);
        StructArrayAccess uniform = StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "table", 64, 1, GL46);
        StructArrayAccess texture = StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "texels", 0, 2, GL46);
        StructArrayAccess attribute = StructArrayAccess.of(STYLE, Mode.VERTEX_ATTRIBUTE, "inst", 0, 4, GL33);
        Golden.check("gl/header-accesses-460", ShaderHeader.builder("VMATH_ACCESSES").access(storage).access(uniform).access(texture).access(attribute).build(ShaderHeader.Language.GLSL));
        StructArrayAccess uniform33 = StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "table", 64, 1, GL33);
        StructArrayAccess texture33 = StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "texels", 0, 2, GL33);
        Golden.check("gl/header-accesses-330", ShaderHeader.builder("VMATH_ACCESSES").access(uniform33).access(texture33).access(attribute).build(ShaderHeader.Language.GLSL));
    }

    @Test
    void everyAccessModeIsWrittenAtSeveralVersions() {
        StringBuilder sb = new StringBuilder();
        for (GraphicsCapabilities caps : List.of(GL33, GL42, GL46)) {
            sb.append("//// ").append(caps.glsl()).append('\n');
            for (Mode m : Mode.values()) {
                if (m == Mode.STORAGE_BLOCK && !caps.glsl().atLeast(GlslVersion.V430)) {
                    sb.append("// ").append(m).append(": not available\n");
                    continue;
                }
                int count = m == Mode.UNIFORM_BLOCK ? 32 : 0;
                int slot = m == Mode.VERTEX_ATTRIBUTE ? 2 : 5;
                sb.append("// ").append(m).append('\n').append(StructArrayAccess.of(STYLE, m, "items", count, slot, caps).glsl()).append('\n');
            }
        }
        Golden.check("gl/access-modes", sb.toString());
    }

    @Test
    void vertexInputsAreWrittenWithTheirLocationsAndTypes() {
        VertexBufferLayout layout = VertexBufferLayout.builder().attributeAt("a_position", 0, VertexFormat.FLOAT32X3, 0).attributeAt("a_color", 1, VertexFormat.UINT32, 12)
                .attribute("a_uv", 2, VertexFormat.FLOAT32X2).attribute("a_id", 3, VertexFormat.SINT32).build();
        Golden.check("gl/vertex-inputs", layout.glslInputs());
        Golden.check("gl/vertex-inputs-instanced", VertexBufferLayout.builder().perInstance().attribute("i_offset", 4, VertexFormat.FLOAT32X4).attribute("i_flags", 5, VertexFormat.UINT32X2).build().glslInputs());
    }

    @Test
    void theTypesOfTheLanguageAreWrittenByName() {
        StringBuilder sb = new StringBuilder();
        for (GlslType t : List.of(GlslType.FLOAT, GlslType.INT, GlslType.UINT, GlslType.VEC2, GlslType.VEC3, GlslType.VEC4, GlslType.MAT3, GlslType.MAT4, STYLE, new GlslType.Array(GlslType.VEC4, 8))) {
            sb.append(t.glsl()).append('\n');
        }
        Golden.check("gl/type-names", sb.toString());
    }
}
