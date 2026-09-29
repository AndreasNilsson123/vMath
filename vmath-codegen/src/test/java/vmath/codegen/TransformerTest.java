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

class TransformerTest {

    static final Renames RENAMES = new Renames(
            Map.of("Vector3f", "Vector3d"),
            Map.of("Vec3f", "Vec3d", "Vec3fTest", "Vec3dTest"));

    static String gen(Mode mode, String src) {
        return Transformer.transform(src, "T.java", new Options(mode, false, RENAMES, null));
    }

    static String dbl(String src) {
        return gen(Mode.DOUBLE, src);
    }

    static String flt(String src) {
        return gen(Mode.FLOAT, src);
    }

    static String template(String body) {
        return """
                package p;

                import vmath.annotations.GenerateDouble;
                import vmath.annotations.FloatOnly;
                import vmath.annotations.DoubleOnly;
                import vmath.annotations.Eps;
                import vmath.annotations.ValueType;

                @GenerateDouble
                @ValueType
                public record Vec3f(float x, float y, float z) {
                %s
                }
                """.formatted(body);
    }

    // ------------------------------------------------------------ literals, casts, types

    @Test
    void retypesFloatsAndLiterals() {
        String out = dbl(template("""
                    static final float A = 1f, B = 1e-4f, C = 0.5F, D = 2.f;
                    static final float[] ARR = new float[3];
                """));
        assertTrue(out.contains("record Vec3d(double x, double y, double z)"), out);
        assertTrue(out.contains("double A = 1.0, B = 1e-4, C = 0.5, D = 2."), out);
        assertTrue(out.contains("double[] ARR = new double[3]"), out);
        assertFalse(out.contains("float "), out);
    }

    @Test
    void floatOutputKeepsFloatsButStripsAnnotations() {
        String out = flt(template("    static final float A = 1f;\n"));
        assertTrue(out.contains("record Vec3f(float x"), out);
        assertTrue(out.contains("float A = 1f;"), out);
        assertFalse(out.contains("@GenerateDouble"), out);
        assertFalse(out.contains("@ValueType"), out);
        assertFalse(out.contains("vmath.annotations"), out);
    }

    @Test
    void narrowingCastsDisappear() {
        String out = dbl(template("""
                    float len() { return (float) Math.sqrt(x * x); }
                    float mix(double a) { return (float) (a * 0.5) + 1f; }
                """));
        assertTrue(out.contains("return Math.sqrt(x * x);"), out);
        assertTrue(out.contains("return (a * 0.5) + 1.0;"), out);
    }

    @Test
    void castsToOtherTypesAreKept() {
        String out = dbl(template("    int i(float v) { return (int) v; }\n"));
        assertTrue(out.contains("int i(double v) { return (int) v; }"), out);
    }

    // ------------------------------------------------------------ names

    @Test
    void renamesFamilyJdkAndOracleNames() {
        String out = dbl(template("""
                    Vec3f self() { return new Vec3f(x, y, z); }
                    boolean nan() { return Float.isNaN(x); }
                    void put(java.nio.FloatBuffer b) { b.put(0, x); }
                    Vector3f oracle(Vec3f v) { return null; }
                    static Vec3f nextVec3f() { return null; }
                """));
        assertTrue(out.contains("Vec3d self() { return new Vec3d(x, y, z); }"), out);
        assertTrue(out.contains("Double.isNaN(x)"), out);
        assertTrue(out.contains("java.nio.DoubleBuffer b"), out);
        assertTrue(out.contains("Vector3d oracle(Vec3d v)"), out);
        assertTrue(out.contains("static Vec3d nextVec3d()"), out);
    }

    @Test
    void embeddedFamilyNamesOnlyMatchAtWordBoundaries() {
        String out = dbl(template("    static int Vec3fish, nextVec3fArray, Vec3fTest;\n"));
        assertTrue(out.contains("Vec3fish"), out); // 'i' continues the word: not a family reference
        assertTrue(out.contains("nextVec3dArray"), out);
        assertTrue(out.contains("Vec3dTest"), out);
    }

    @Test
    void commentsAndStringsAreRewrittenButCodeIsNotConfusedByThem() {
        String out = dbl(template("""
                    /** A float Vec3f. */
                    static String s = "float Vec3f";
                    // float note
                    static float f = 1f; // trailing float
                """));
        assertTrue(out.contains("/** A double Vec3d. */"), out);
        assertTrue(out.contains("\"double Vec3d\""), out);
        assertTrue(out.contains("// double note"), out);
        assertTrue(out.contains("static double f = 1.0; // trailing double"), out);
    }

    @Test
    void floatCommentsAreLeftAloneInFloatOutput() {
        String out = flt(template("    /** A float Vec3f. */\n    static float f = 1f;\n"));
        assertTrue(out.contains("/** A float Vec3f. */"), out);
    }

    @Test
    void compactConstructorIsRenamedWithTheRecord() {
        String out = dbl(template("""
                    public Vec3f {
                        if (x != x) {
                            throw new IllegalArgumentException("NaN");
                        }
                    }
                """));
        assertTrue(out.contains("public Vec3d {"), out);
        assertFalse(out.contains("Vec3f"), out);
    }

    // ------------------------------------------------------------ precision-only members

    @Test
    void floatOnlyIsDroppedFromDoubleWithItsJavadoc() {
        String src = template("""
                    float keep() { return x; }

                    /** Only in float. */
                    @FloatOnly
                    public Vec3d toDouble() { return new Vec3d(x, y, z); }
                """);
        String d = dbl(src);
        assertFalse(d.contains("toDouble"), d);
        assertFalse(d.contains("Only in float"), d);
        assertFalse(d.contains("\n\n\n"), "no double blank lines: " + d);
        assertTrue(d.contains("double keep()"), d);
        String f = flt(src);
        assertTrue(f.contains("Only in float"), f);
        assertTrue(f.contains("public Vec3d toDouble()"), f);
        assertFalse(f.contains("@FloatOnly"), f);
    }

    @Test
    void doubleOnlyIsVerbatimInDoubleAndDroppedFromFloat() {
        String src = template("""
                    float keep() { return x; }

                    /** Narrows to float precision. */
                    @DoubleOnly
                    public Vec3f toFloat() { return new Vec3f((float) x, (float) y, 1f); }
                """);
        String d = dbl(src);
        assertTrue(d.contains("/** Narrows to float precision. */"), d);
        assertTrue(d.contains("public Vec3f toFloat() { return new Vec3f((float) x, (float) y, 1f); }"), d);
        assertFalse(d.contains("@DoubleOnly"), d);
        String f = flt(src);
        assertFalse(f.contains("toFloat"), f);
        assertFalse(f.contains("Narrows"), f);
    }

    @Test
    void epsReplacesTheInitializerOnlyInDouble() {
        String src = template("""
                    @Eps(d = 1e-11)
                    static final float EPS = 1e-4f;
                """);
        assertTrue(dbl(src).contains("static final double EPS = 1e-11;"), dbl(src));
        assertTrue(flt(src).contains("static final float EPS = 1e-4f;"), flt(src));
        assertFalse(flt(src).contains("@Eps"), flt(src));
    }

    // ------------------------------------------------------------ Valhalla profile

    @Test
    void valueTypeBecomesValueRecordOnlyInValhallaProfile() {
        String src = template("");
        String plain = Transformer.transform(src, "T.java", new Options(Mode.DOUBLE, false, RENAMES, null));
        String value = Transformer.transform(src, "T.java", new Options(Mode.DOUBLE, true, RENAMES, null));
        assertTrue(plain.contains("public record Vec3d"), plain);
        assertTrue(value.contains("public value record Vec3d"), value);
    }

    @Test
    void plainModeStripsAnnotationsOfHandWrittenSources() {
        String src = """
                package p;

                import vmath.annotations.ValueType;

                @ValueType
                public record Half(short bits) {
                    float toFloat() { return 1f; }
                }
                """;
        String out = Transformer.transform(src, "Half.java", new Options(Mode.PLAIN, true, RENAMES, null));
        assertTrue(out.contains("public value record Half(short bits)"), out);
        assertTrue(out.contains("float toFloat() { return 1f; }"), out);
        assertFalse(out.contains("vmath.annotations"), out);
    }

    // ------------------------------------------------------------ header and discovery

    @Test
    void headerGoesBelowThePackageLine() {
        String out = Transformer.transform(template(""), "T.java",
                new Options(Mode.FLOAT, false, RENAMES, "// GENERATED test"));
        assertTrue(out.startsWith("package p;\n\n// GENERATED test\n"), out);
    }

    @Test
    void findsFamiliesAndTwinNames() {
        List<Transformer.Family> fams = Transformer.families(template(""), "T.java");
        assertEquals(List.of(new Transformer.Family("Vec3f", "Vec3d")), fams);
        assertEquals("Vec3dTest", Transformer.twinName("Vec3fTest", null));
        assertEquals("Custom", Transformer.twinName("Anything", "Custom"));
    }

    // ------------------------------------------------------------ diagnostics

    @Test
    void reportsBothPrecisionAnnotationsOnOneMember() {
        var e = assertThrows(TemplateException.class,
                () -> dbl(template("    @FloatOnly @DoubleOnly void m() {}\n")));
        assertTrue(e.getMessage().contains("both"), e.getMessage());
        assertTrue(e.getMessage().startsWith("T.java:"), e.getMessage());
    }

    @Test
    void reportsEpsWithoutInitializer() {
        var e = assertThrows(TemplateException.class,
                () -> dbl(template("    @Eps(d = 1e-9) static float EPS;\n")));
        assertTrue(e.getMessage().contains("initializer"), e.getMessage());
    }

    @Test
    void reportsUseOfADroppedMember() {
        String src = template("""
                    @FloatOnly
                    Vec3d toDouble() { return null; }
                    Vec3d viaKept() { return toDouble(); }
                """);
        var e = assertThrows(TemplateException.class, () -> dbl(src));
        assertTrue(e.getMessage().contains("toDouble"), e.getMessage());
        assertTrue(e.getMessage().contains("@FloatOnly"), e.getMessage());
    }

    @Test
    void reportsUnderivableTwinName() {
        assertThrows(TemplateException.class, () -> Transformer.twinName("Widget", null));
    }

    @Test
    void reportsSyntaxErrorsWithLocation() {
        var e = assertThrows(TemplateException.class, () -> dbl("package p; class {"));
        assertTrue(e.getMessage().startsWith("T.java:"), e.getMessage());
    }
}
