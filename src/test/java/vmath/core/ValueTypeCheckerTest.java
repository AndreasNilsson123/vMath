package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each rule of {@link ValueTypeChecker} on a source that breaks it, and sources that must pass. */
class ValueTypeCheckerTest {

    private static List<ValueTypeChecker.Violation> check(String body) throws IOException {
        return ValueTypeChecker.check("Fixture.java", "package p;\nclass Fixture {\n" + body + "\n}\n");
    }

    @Test
    void referenceComparisonIsRejected() throws IOException {
        assertEquals(1, check("boolean f(Vec3f a, Vec3f b) { return a == b; }").size());
        assertEquals(1, check("boolean f(Vec3d a) { return a != Vec3d.ZERO; }").size());
        assertEquals(1, check("Quatf q; boolean f(Quatf o) { return this.q == o; }").size());
        assertEquals(1, check("boolean f(Mat4f a, Mat4f b) { return (a) == b; }").size());
        assertEquals(2, check("boolean f(Aabbf a, Aabbf b) { return a == b || b != a; }").size());
    }

    @Test
    void lockingAndIdentityHashAreRejected() throws IOException {
        assertEquals(1, check("void f(Vec2f v) { synchronized (v) { } }").size());
        assertEquals(1, check("int f(Vec2f v) { return System.identityHashCode(v); }").size());
    }

    @Test
    void theLineNumberPointsAtTheViolation() throws IOException {
        List<ValueTypeChecker.Violation> v = check("\nboolean f(Vec3f a, Vec3f b) {\n return a == b;\n}");
        assertEquals(1, v.size());
        assertEquals(5, v.get(0).line());
    }

    @Test
    void legitimateCodePasses() throws IOException {
        assertTrue(check("boolean f(Vec3f a) { return a == null; }").isEmpty(), "null checks are fine");
        assertTrue(check("boolean f(Vec3f a, Vec3f b) { return a.equals(b); }").isEmpty());
        assertTrue(check("boolean f(int a, int b) { return a == b; }").isEmpty(), "primitives are fine");
        assertTrue(check("boolean f(Object a, Object b) { return a == b; }").isEmpty(), "other reference types are fine");
        assertTrue(check("void f(Object o) { synchronized (o) { } }").isEmpty());
        assertTrue(check("boolean f(Vec3f[] a, Vec3f[] b) { return a == b; }").isEmpty(), "arrays of value types have identity");
    }

    @Test
    void theInnermostDeclarationDecides() throws IOException {
        assertTrue(check("Vec3f a; boolean f(float a, float b) { return a == b; }").isEmpty(), "a parameter shadows the field");
        assertTrue(check("boolean f(Vec3f a, float b) { { float a2 = 1; } return b == 2; }").isEmpty());
        assertEquals(1, check("float a; boolean f(Vec3f a, Vec3f b) { return a == b; }").size(), "the parameter is the value type here");
        assertEquals(1, check("void g(Vec3f a, Vec3f b) { Runnable r = () -> { boolean c = a == b; }; }").size(), "lambda bodies are checked");
    }
}
