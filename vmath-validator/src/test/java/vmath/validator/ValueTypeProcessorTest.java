package vmath.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Each rule of {@link ValueTypeProcessor} on a program that breaks it, programs that must pass, and the options. */
class ValueTypeProcessorTest {

    private static final String ANNOTATION = """
            package vmath.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
            public @interface ValueType { }
            """;

    /** The types the fixtures use: a value record, and an ordinary record that is not marked. */
    private static final String TYPES = """
            package p;
            import vmath.annotations.ValueType;
            @ValueType public record Vec(float x, float y) { }
            """;
    private static final String PLAIN = """
            package p;
            public record Plain(float x) { }
            """;

    private static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    /** Compiles the class {@code p.Fixture} with the body wrapped in it, together with the annotation and the types, and returns the diagnostics of kind error and warning. */
    private static List<Diagnostic<? extends JavaFileObject>> compile(String body, String... options) {
        return compileSources(List.of(source("p.Fixture", "package p;\nimport java.util.*;\nimport java.lang.ref.*;\nclass Fixture {\n" + body + "\n}\n")), options);
    }

    private static List<Diagnostic<? extends JavaFileObject>> compileSources(List<JavaFileObject> fixtures, String... options) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = new ArrayList<>(fixtures);
        units.add(source("vmath.annotations.ValueType", ANNOTATION));
        units.add(source("p.Vec", TYPES));
        units.add(source("p.Plain", PLAIN));
        List<String> flags = new ArrayList<>(List.of("-proc:full", "-d", System.getProperty("java.io.tmpdir")));
        flags.addAll(List.of(options));
        JavaCompiler.CompilationTask task = compiler.getTask(new StringWriter(), null, diagnostics, flags, null, units);
        task.setProcessors(List.of(new ValueTypeProcessor()));
        task.call();
        List<Diagnostic<? extends JavaFileObject>> found = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING) {
                found.add(d);
            }
        }
        return found;
    }

    private static void assertViolations(int count, String body, String... options) {
        List<Diagnostic<? extends JavaFileObject>> found = compile(body, options);
        assertEquals(count, found.size(), found::toString);
    }

    private static String message(List<Diagnostic<? extends JavaFileObject>> found, int i) {
        return found.get(i).getMessage(null);
    }

    @Test
    void referenceComparisonIsRejected() {
        assertViolations(1, "boolean f(Vec a, Vec b) { return a == b; }");
        assertViolations(1, "boolean f(Vec a, Vec b) { return a != b; }");
        assertViolations(1, "boolean f(Vec a, Object o) { return o == a; }");
        assertViolations(2, "boolean f(Vec a, Vec b) { return a == b || b != a; }");
        // what a text scan cannot see: a method result, a field of another class, a lambda parameter, a var, a generic result
        assertViolations(1, "Vec make() { return null; } boolean f(Vec a) { return make() == a; }");
        assertViolations(1, "static class Holder { Vec v; } boolean f(Holder h, Vec a) { return h.v == a; }");
        assertViolations(1, "boolean f(Vec a) { java.util.function.Predicate<Vec> t = v -> v == a; return t.test(a); }");
        assertViolations(1, "boolean f(Vec a) { var b = a; return a == b; }");
        assertViolations(1, "boolean f(List<Vec> list, Vec a) { return list.get(0) == a; }");
    }

    @Test
    void theMessageAndTheLineAreThoseOfTheViolation() {
        List<Diagnostic<? extends JavaFileObject>> found = compile("\nboolean f(Vec a, Vec b) {\n return a == b;\n}");
        assertEquals(1, found.size());
        assertEquals(Diagnostic.Kind.ERROR, found.get(0).getKind());
        assertEquals(7, found.get(0).getLineNumber(), "the fixture has four lines before the body, then a blank one, the method and the return");
        assertTrue(message(found, 0).contains("value type Vec") && message(found, 0).contains("equals()"), message(found, 0));
    }

    @Test
    void lockingAndIdentityApisAreRejected() {
        assertViolations(1, "void f(Vec v) { synchronized (v) { } }");
        assertViolations(1, "int f(Vec v) { return System.identityHashCode(v); }");
        assertViolations(1, "void f(Vec v) throws Exception { v.wait(); }");
        assertViolations(1, "void f(Vec v) { v.notify(); }");
        assertViolations(1, "void f(Vec v) { v.notifyAll(); }");
        assertViolations(1, "Map<Vec, String> f() { return new IdentityHashMap<Vec, String>(); }");
        assertViolations(1, "Map<Vec, String> f() { return new IdentityHashMap<>(); }");
        assertViolations(1, "Map<Vec, String> f() { return new WeakHashMap<>(); }");
        assertViolations(1, "Object f(Vec v) { return new WeakReference<Vec>(v); }");
        assertViolations(1, "Object f(Vec v) { return new SoftReference<>(v); }");
        assertViolations(1, "Object f(Vec v) { return new PhantomReference<>(v, new ReferenceQueue<>()); }");
    }

    @Test
    void declarationsOfValueTypesAreChecked() {
        List<Diagnostic<? extends JavaFileObject>> notRecord = compileSources(List.of(source("p.Bad", "package p;\nimport vmath.annotations.ValueType;\n@ValueType class Bad { }\n")));
        assertEquals(1, notRecord.size(), notRecord.toString());
        assertTrue(message(notRecord, 0).contains("@ValueType is for records"), message(notRecord, 0));
        List<Diagnostic<? extends JavaFileObject>> locked = compileSources(List.of(source("p.Locked", """
                package p;
                import vmath.annotations.ValueType;
                @ValueType record Locked(int a) {
                    synchronized void f() { }
                    @Override protected void finalize() { }
                }
                """)), "-Xlint:-removal");
        long errors = locked.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
        assertEquals(2, errors, locked.toString());
    }

    @Test
    void legitimateCodePasses() {
        assertViolations(0, "boolean f(Vec a) { return a == null; }");
        assertViolations(0, "boolean f(Vec a) { return null != a; }");
        assertViolations(0, "boolean f(Vec a, Vec b) { return a.equals(b); }");
        assertViolations(0, "boolean f(int a, int b) { return a == b; }");
        assertViolations(0, "boolean f(float a, double b) { return a == b; }");
        assertViolations(0, "boolean f(Object a, Object b) { return a == b; }");
        assertViolations(0, "boolean f(Plain a, Plain b) { return a == b; }"); // not marked: not a value type
        assertViolations(0, "boolean f(Vec[] a, Vec[] b) { return a == b; }"); // arrays have identity
        assertViolations(0, "void f(Object o) { synchronized (o) { o.notify(); } }");
        assertViolations(0, "int f(Object o) { return System.identityHashCode(o); }");
        assertViolations(0, "Map<String, Vec> f() { return new HashMap<>(); }");
        assertViolations(0, "Map<Plain, String> f() { return new IdentityHashMap<>(); }");
        assertViolations(0, "Object f(Plain p) { return new WeakReference<>(p); }");
        assertViolations(0, "<T> boolean f(T a, T b) { return a == b; }"); // a type variable is not known to be a value type
        assertViolations(0, "boolean f(Vec a, Vec b) { return a.x() == b.x(); }"); // the components are floats
    }

    @Test
    void theOptionsTurnTheCheckIntoWarningsOrOff() {
        String body = "boolean f(Vec a, Vec b) { return a == b; }";
        List<Diagnostic<? extends JavaFileObject>> warn = compile(body, "-Avmath.validator=warn");
        assertEquals(1, warn.size());
        assertEquals(Diagnostic.Kind.WARNING, warn.get(0).getKind());
        assertViolations(0, body, "-Avmath.validator=off");
        assertFalse(compile(body).isEmpty());
    }

    @Test
    void theServiceFileNamesTheProcessor() throws Exception {
        try (var in = ValueTypeProcessor.class.getResourceAsStream("/META-INF/services/javax.annotation.processing.Processor")) {
            assertEquals("vmath.validator.ValueTypeProcessor", new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim());
        }
        ValueTypeProcessor p = new ValueTypeProcessor();
        assertFalse(p.process(java.util.Set.of(), null), "the processor claims nothing");
        assertTrue(p.getSupportedSourceVersion().compareTo(javax.lang.model.SourceVersion.RELEASE_21) >= 0);
    }
}
