package vmath.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vmath.codegen.Transformer.Mode;
import vmath.codegen.Transformer.Options;
import vmath.codegen.Transformer.TemplateException;

class GpuStructGeneratorTest {

    private static String source(String imports, String record) {
        return "package p;\n\nimport vmath.annotations.GpuArray;\nimport vmath.annotations.GpuStruct;\n"
                + "import vmath.annotations.GpuUint;\n" + imports + "\n" + record + "\n";
    }

    private static String generate(String src) {
        GpuStructGenerator g = new GpuStructGenerator();
        g.register(src, "T.java");
        List<GpuStructGenerator.Output> out = g.generate(src, "T.java");
        assertEquals(1, out.size());
        return out.get(0).source();
    }

    @Test
    void emitsLayoutOffsetsAndWriterForASimpleRecord() {
        String out = generate(source("import vmath.core.Vec3f;",
                "@GpuStruct(layout = GpuStruct.Layout.STD430)\nrecord Particle(Vec3f position, float life, @GpuUint int flags) {}"));
        assertTrue(out.startsWith("package p;\n"), out);
        assertTrue(out.contains("public final class ParticleGpu {"), out);
        assertTrue(out.contains("new GlslType.Member(\"position\", GlslType.VEC3),"), out);
        assertTrue(out.contains("new GlslType.Member(\"life\", GlslType.FLOAT),"), out);
        assertTrue(out.contains("new GlslType.Member(\"flags\", GlslType.UINT)))"), out);
        assertTrue(out.contains(".layout(GpuLayout.STD430);"), out);
        assertTrue(out.contains("public static final long OFFSET_POSITION = LAYOUT.offsetOf(\"position\");"), out);
        assertTrue(out.contains("GpuWriter.putVec3(dst, base + OFFSET_POSITION, v.position());"), out);
        assertTrue(out.contains("GpuWriter.putInt(dst, base + OFFSET_FLAGS, v.flags());"), out);
        assertTrue(out.contains("import vmath.core.Vec3f;"), "the record's own imports are carried over: " + out);
        assertFalse(out.contains("vmath.annotations"), "annotations are build-time only: " + out);
    }

    @Test
    void defaultLayoutIsStd140() {
        String out = generate(source("", "@GpuStruct\nrecord A(float x) {}"));
        assertTrue(out.contains(".layout(GpuLayout.STD140);"), out);
    }

    @Test
    void arraysGetAStrideConstantAndALengthCheck() {
        String out = generate(source("import vmath.core.Vec4f;",
                "@GpuStruct\nrecord L(@GpuArray(4) Vec4f[] cascades, float radius) {}"));
        assertTrue(out.contains("GlslType.array(GlslType.VEC4, 4)"), out);
        assertTrue(out.contains("STRIDE_CASCADES"), out);
        assertTrue(out.contains("a0.length != 4"), out);
        assertTrue(out.contains("GpuWriter.putVec4(dst, base + OFFSET_CASCADES + i * STRIDE_CASCADES, a0[i]);"), out);
    }

    @Test
    void matricesReceiveTheirColumnStrideForTheChosenLayout() {
        String out = generate(source("import vmath.core.Mat3f;\nimport vmath.core.Mat4x3f;",
                "@GpuStruct(layout = GpuStruct.Layout.SCALAR)\nrecord M(Mat3f n, Mat4x3f model) {}"));
        assertTrue(out.contains("COLUMN_N = GlslType.MAT3.columnStride(GpuLayout.SCALAR);"), out);
        assertTrue(out.contains("COLUMN_MODEL = GlslType.mat(4, 3).columnStride(GpuLayout.SCALAR);"), out);
        assertTrue(out.contains("GpuWriter.putMat3(dst, base + OFFSET_N, v.n(), COLUMN_N);"), out);
        assertTrue(out.contains("GpuWriter.putMat4x3(dst, base + OFFSET_MODEL, v.model(), COLUMN_MODEL);"), out);
    }

    @Test
    void mapsEverySupportedType() {
        String out = generate(source("import vmath.core.*;",
                "@GpuStruct\nrecord All(float a, int b, Vec2f c, Vec3f d, Vec4f e, Quatf f, Vec2i g, Vec3i h, Mat4f i) {}"));
        for (String expected : new String[] {"GlslType.FLOAT", "GlslType.INT", "GlslType.VEC2", "GlslType.VEC3", "GlslType.VEC4",
                "GlslType.IVEC2", "GlslType.IVEC3", "GlslType.MAT4", "putQuat", "putIVec2", "putIVec3", "putMat4"}) {
            assertTrue(out.contains(expected), expected + " in " + out);
        }
    }

    @Test
    void nestedStructsReferenceTheirGeneratedClass() {
        String inner = source("import vmath.core.Vec3f;", "@GpuStruct\nrecord Inner(Vec3f v) {}");
        String outer = source("", "@GpuStruct\nrecord Outer(Inner a, @GpuArray(2) Inner[] many) {}");
        GpuStructGenerator g = new GpuStructGenerator();
        g.register(inner, "Inner.java");
        g.register(outer, "Outer.java");
        String out = g.generate(outer, "Outer.java").get(0).source();
        assertTrue(out.contains("InnerGpu.LAYOUT.struct()"), out);
        assertTrue(out.contains("InnerGpu.write(v.a(), dst, base + OFFSET_A);"), out);
        assertTrue(out.contains("InnerGpu.write(a1[i], dst, base + OFFSET_MANY + i * STRIDE_MANY);"), out);
    }

    @Test
    void nestedStructFromAnotherPackageIsImported() {
        String inner = "package q;\n\nimport vmath.annotations.GpuStruct;\n\n@GpuStruct\npublic record Inner(float v) {}\n";
        String outer = source("import q.Inner;", "@GpuStruct\nrecord Outer(Inner a) {}");
        GpuStructGenerator g = new GpuStructGenerator();
        g.register(inner, "Inner.java");
        g.register(outer, "Outer.java");
        assertTrue(g.generate(outer, "Outer.java").get(0).source().contains("import q.InnerGpu;"));
    }

    @Test
    void staticMembersAreNotComponents() {
        String out = generate(source("", "@GpuStruct\nrecord S(float a) {\n    static final int LIMIT = 4;\n    static float helper() { return 1f; }\n}"));
        assertFalse(out.contains("LIMIT"), out);
        assertTrue(out.contains("OFFSET_A"), out);
    }

    @Test
    void filesWithoutTheAnnotationProduceNothing() {
        GpuStructGenerator g = new GpuStructGenerator();
        assertTrue(g.generate("package p;\nrecord Plain(float x) {}\n", "Plain.java").isEmpty());
    }

    // ------------------------------------------------------------ diagnostics

    private static String messageOf(String src) {
        GpuStructGenerator g = new GpuStructGenerator();
        g.register(src, "T.java");
        return assertThrows(TemplateException.class, () -> g.generate(src, "T.java")).getMessage();
    }

    @Test
    void rejectsDoublePrecisionTypes() {
        for (String type : new String[] {"Vec3d", "Mat4d", "Quatd", "double", "long"}) {
            String msg = messageOf(source("import vmath.core.*;", "@GpuStruct\nrecord D(" + type + " x) {}"));
            assertTrue(msg.contains("not supported") && msg.contains(type), msg);
            assertTrue(msg.startsWith("T.java:"), msg);
        }
    }

    @Test
    void rejectsUnknownTypesAndMisusedAnnotations() {
        assertTrue(messageOf(source("", "@GpuStruct\nrecord U(String s) {}")).contains("unsupported GPU type 'String'"));
        assertTrue(messageOf(source("import vmath.core.Vec4f;", "@GpuStruct\nrecord A(Vec4f[] xs) {}")).contains("@GpuArray"));
        assertTrue(messageOf(source("", "@GpuStruct\nrecord B(@GpuArray(2) float x) {}")).contains("only valid on array"));
        assertTrue(messageOf(source("", "@GpuStruct\nrecord C(@GpuUint float x) {}")).contains("int components only"));
        assertTrue(messageOf(source("import vmath.core.Vec4f;", "@GpuStruct\nrecord Z(@GpuArray(0) Vec4f[] xs) {}")).contains("at least 1"));
    }

    @Test
    void rejectsANestedStructWithADifferentLayout() {
        String inner = source("", "@GpuStruct(layout = GpuStruct.Layout.STD430)\nrecord Inner(float v) {}");
        String outer = source("", "@GpuStruct(layout = GpuStruct.Layout.STD140)\nrecord Outer(Inner a) {}");
        GpuStructGenerator g = new GpuStructGenerator();
        g.register(inner, "Inner.java");
        g.register(outer, "Outer.java");
        String msg = assertThrows(TemplateException.class, () -> g.generate(outer, "Outer.java")).getMessage();
        assertTrue(msg.contains("std430") && msg.contains("std140") && msg.contains("must match"), msg);
    }

    // ------------------------------------------------------------ the annotations vanish from pass-through sources

    @Test
    void gpuAnnotationsAreStrippedFromPassThroughSources() {
        String src = source("import vmath.core.Vec4f;",
                "@GpuStruct(layout = GpuStruct.Layout.STD430)\nrecord L(@GpuArray(4) Vec4f[] cascades, @GpuUint int flags) {}");
        String out = Transformer.transform(src, "T.java", new Options(Mode.PLAIN, true,
                new Renames(Map.of(), Map.of()), null));
        assertFalse(out.contains("Gpu"), "no GPU annotation or import may remain: " + out);
        assertTrue(out.contains("record L(Vec4f[] cascades, int flags)") || out.contains("value record L"), out);
    }

    @Test
    void snakeCaseConversion() {
        assertEquals("VIEW_PROJ", GpuStructGenerator.snake("viewProj"));
        assertEquals("POSITION", GpuStructGenerator.snake("position"));
        assertEquals("UV_SCALE_X", GpuStructGenerator.snake("uvScaleX"));
        assertEquals("A", GpuStructGenerator.snake("a"));
    }
}
