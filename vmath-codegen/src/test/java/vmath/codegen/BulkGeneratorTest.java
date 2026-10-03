package vmath.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.codegen.Transformer.Mode;
import vmath.codegen.Transformer.Options;
import vmath.codegen.Transformer.TemplateException;

class BulkGeneratorTest {

    private static final String HEAD = "package p;\n\nimport vmath.annotations.Bulk;\nimport vmath.annotations.GenerateDouble;\n\n";
    private static final String V2 = "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    V2f add(V2f o) {\n        return new V2f(x + o.x, y + o.y);\n    }\n}\n";

    private static String generate(String src) {
        BulkGenerator g = new BulkGenerator();
        g.register(src, "T.java");
        List<BulkGenerator.Output> out = g.generate(src, "T.java");
        assertEquals(1, out.size());
        return out.get(0).source();
    }

    /** The text with every run of white space (including the line breaks of a wrapped signature) as one space. */
    private static String flat(String s) {
        return s.replaceAll("\\s+", " ");
    }

    private static TemplateException failure(String src) {
        return assertThrows(TemplateException.class, () -> generate(src));
    }

    @Test
    void aSimpleMethodBecomesAnInterleavedAndAPlanarLoop() {
        String out = generate(HEAD + V2);
        assertTrue(out.contains("public final class V2fBulk {"), out);
        assertTrue(out.contains("@GenerateDouble\npublic final class V2fBulk"), "the twin is made by the usual pipeline: " + out);
        assertTrue(flat(out).contains("public static void add(float[] self, int selfOffset, float[] o, int oOffset, float[] out, int outOffset, int count) {"), out);
        assertTrue(out.contains("float x = self[selfOffset + _i * 2];"), out);
        assertTrue(out.contains("float o_y = o[oOffset + _i * 2 + 1];"), out);
        assertTrue(out.contains("float _r0 = x + o_x;"), out);
        assertTrue(out.contains("out[_o + 1] = _r1;"), out);
        assertTrue(flat(out).contains("public static void addPlanar(float[] selfX, float[] selfY, int selfOffset, float[] oX, float[] oY, int oOffset,"), out);
        assertTrue(out.contains("outX[outOffset + _i] = _r0;"), out);
        assertFalse(out.contains("continue;"), "the last statement of the method needs no continue: " + out);
        assertFalse(out.contains("Bulk;"), "the annotation is build-time only: " + out);
    }

    @Test
    void theClassAndTheMethodsAreDocumented() {
        String out = generate(HEAD + V2);
        assertTrue(out.contains("Provides batch versions of the {@link V2f} operations"), out);
        assertTrue(out.contains("<p><b>Thread safety.</b>"), out);
        assertTrue(out.contains("<pre>{@code"), "an example: " + out);
        assertTrue(out.contains("V2fBulk.add(self, 0, o, 0, out, 0, 4);"), out);
        assertTrue(out.contains("Applies {@link V2f#add(V2f)} to {@code count} elements of interleaved arrays."), out);
        assertTrue(out.contains("@param count the number of elements; zero or less does nothing"), out);
        assertTrue(out.contains("@throws ArrayIndexOutOfBoundsException"), out);
        assertTrue(out.contains("@throws NullPointerException"), out);
        for (String line : out.split("\n")) {
            if (line.stripLeading().startsWith("*") && !line.contains("<pre>") && !line.contains("V2fBulk.")) {
                assertTrue(line.length() <= 100, "line too long: " + line);
            }
        }
    }

    @Test
    void scalarParametersAndResultsAndEarlyReturns() {
        String out = generate(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n"
                + "    @Bulk\n    float dot(V2f o) {\n        return x * o.x + y * o.y;\n    }\n"
                + "    @Bulk\n    V2f clampLength(float max) {\n        float len2 = x * x + y * y;\n        if (len2 <= max * max) {\n            return this;\n        }\n"
                + "        float k = max / (float) Math.sqrt(len2);\n        return new V2f(x * k, y * k);\n    }\n}\n");
        assertTrue(out.contains("out[outOffset + _i] = x * o_x + y * o_y;"), out);
        assertTrue(flat(out).contains("float[] self, int selfOffset, float max, float[] out, int outOffset, int count"), out);
        assertTrue(out.contains("continue;"), "an early return skips the rest of the element: " + out);
        assertTrue(out.contains("float _r0 = x;"), "return this stores the receiver: " + out);
        assertTrue(out.contains("float k = max / (float) Math.sqrt(len2);"), out);
    }

    @Test
    void uniformOperandsAreReadOnce() {
        String out = generate(HEAD + "@GenerateDouble\nrecord M2f(float a, float b) {\n"
                + "    @Bulk(uniform = \"this\", name = \"scale\")\n    M2f scaleBy(M2f o) {\n        return new M2f(a * o.a, b * o.b);\n    }\n}\n");
        int loop = out.indexOf("for (int _i");
        int read = out.indexOf("float a = self[selfOffset];");
        assertTrue(read > 0 && read < loop, "read before the loop: " + out);
        assertTrue(flat(out).contains("public static void scale(float[] self, int selfOffset, float[] o, int oOffset,"), out);
        assertTrue(out.contains("float o_a = o[oOffset + _i * 2];"), out);
    }

    @Test
    void theDoubleTwinOfTheGeneratedClassIsMadeByTheTransformer() {
        String out = generate(HEAD + V2);
        Renames renames = new Renames(java.util.Map.of(), java.util.Map.of("V2f", "V2d"));
        String twin = Transformer.transform(out, "V2fBulk.java", new Options(Mode.DOUBLE, false, renames, null));
        assertTrue(twin.contains("public final class V2dBulk {"), twin);
        assertTrue(flat(twin).contains("public static void add(double[] self, int selfOffset, double[] o, int oOffset, double[] out, int outOffset, int count) {"), twin);
        assertTrue(twin.contains("{@link V2d#add(V2d)}"), twin);
    }

    @Test
    void recordsOfOtherFilesCanBeParametersAndAreImported() {
        BulkGenerator g = new BulkGenerator();
        g.register("package q;\n\nimport vmath.annotations.GenerateDouble;\n\n@GenerateDouble\nrecord W2f(float u, float v) {}\n", "W.java");
        String src = "package p;\n\nimport vmath.annotations.Bulk;\nimport vmath.annotations.GenerateDouble;\nimport q.W2f;\n\n"
                + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    float along(W2f w) {\n        return x * w.u + y * w.v;\n    }\n}\n";
        g.register(src, "T.java");
        String out = g.generate(src, "T.java").get(0).source();
        assertTrue(out.contains("import q.W2f;"), out);
        assertTrue(out.contains("float w_u = w[wOffset + _i * 2];"), out);
    }

    @Test
    void accessorsAndThisAreComponentsToo() {
        String out = generate(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    float mix(V2f o) {\n"
                + "        return this.x * o.x() + y() * o.y;\n    }\n}\n");
        assertTrue(out.contains("out[outOffset + _i] = x * o_x + y * o_y;"), out);
    }

    @Test
    void whatCannotBeALoopIsReportedWithTheLine() {
        TemplateException loop = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    float f() {\n        for (int i = 0; i < 2; i++) {\n        }\n        return x;\n    }\n}\n");
        assertTrue(loop.getMessage().contains("T.java:") && loop.getMessage().contains("loops are not supported"), loop.getMessage());
        TemplateException call = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    float f() {\n        return length();\n    }\n    float length() {\n        return x;\n    }\n}\n");
        assertTrue(call.getMessage().contains("not supported"), call.getMessage());
        TemplateException unknown = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    static final float K = 2f;\n    @Bulk\n    float f() {\n        return x * K;\n    }\n}\n");
        assertTrue(unknown.getMessage().contains("unknown name K"), unknown.getMessage());
        TemplateException other = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    float f(String s) {\n        return x;\n    }\n}\n");
        assertTrue(other.getMessage().contains("neither a primitive nor a record"), other.getMessage());
        TemplateException ret = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    boolean f() {\n        return true;\n    }\n}\n");
        assertTrue(ret.getMessage().contains("return type"), ret.getMessage());
        TemplateException wrongCount = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    V2f f() {\n        return new V2f(x);\n    }\n}\n");
        assertTrue(wrongCount.getMessage().contains("needs 2 components"), wrongCount.getMessage());
        TemplateException local = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    float f() {\n        V2f t = this;\n        return x;\n    }\n}\n");
        assertTrue(local.getMessage().contains("local variables must be primitive"), local.getMessage());
        TemplateException news = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    float f() {\n        float k = new V2f(x, y).x();\n        return k;\n    }\n}\n");
        assertTrue(news.getMessage().contains("not supported"), news.getMessage());
    }

    @Test
    void aRecordWithOtherComponentsCannotBeBulked() {
        TemplateException e = failure(HEAD + "@GenerateDouble\nrecord T(float x, int n) {\n    @Bulk\n    float f() {\n        return x;\n    }\n}\n");
        assertTrue(e.getMessage().contains("all float"), e.getMessage());
    }

    @Test
    void generatedMethodsThatWouldCollideAreReported() {
        TemplateException e = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n"
                + "    @Bulk\n    float f(V2f a) {\n        return x;\n    }\n    @Bulk\n    float f(V2f b) {\n        return y;\n    }\n}\n");
        assertTrue(e.getMessage().contains("same parameter types"), e.getMessage());
        TemplateException names = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk\n    float f(float count) {\n        return x;\n    }\n}\n");
        assertTrue(names.getMessage().contains("reserved"), names.getMessage());
        TemplateException uniform = failure(HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    @Bulk(uniform = \"q\")\n    float f() {\n        return x;\n    }\n}\n");
        assertTrue(uniform.getMessage().contains("does not exist"), uniform.getMessage());
    }

    @Test
    void aFileWithoutMarkedMethodsGeneratesNothing() {
        BulkGenerator g = new BulkGenerator();
        String src = HEAD + "@GenerateDouble\nrecord V2f(float x, float y) {\n    float f() {\n        return x;\n    }\n}\n";
        g.register(src, "T.java");
        assertTrue(g.generate(src, "T.java").isEmpty());
    }
}
