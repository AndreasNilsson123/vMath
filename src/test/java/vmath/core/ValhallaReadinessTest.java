package vmath.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guard rails that keep every core type a valid future {@code value record}.
 *
 * <p>JEP 401 value classes must be final, have only final fields and must not depend on identity.
 * Records already give us the first two; these checks catch the rest before a Valhalla build does.
 */
class ValhallaReadinessTest {

    static final List<Class<?>> VALUE_TYPES = List.of(
            Vec2f.class, Vec3f.class, Vec4f.class, Quatf.class, Mat3f.class, Mat4f.class,
            Vec2d.class, Vec3d.class, Vec4d.class, Quatd.class, Mat3d.class, Mat4d.class);

    @Test
    void allAreRecordsOfPrimitives() {
        for (Class<?> c : VALUE_TYPES) {
            assertTrue(c.isRecord(), c + " must be a record");
            assertTrue(Modifier.isFinal(c.getModifiers()), c + " must be final");
            assertTrue(c.getInterfaces().length == 0,
                    c + " should not implement interfaces (keeps call sites monomorphic and flattenable)");
            for (RecordComponent rc : c.getRecordComponents()) {
                assertTrue(rc.getType().isPrimitive(),
                        c.getSimpleName() + "." + rc.getName() + " must be primitive so the type stays flattenable");
            }
        }
    }

    @Test
    void noIdentitySensitiveMethods() {
        for (Class<?> c : VALUE_TYPES) {
            for (Method m : c.getDeclaredMethods()) {
                assertTrue(!Modifier.isSynchronized(m.getModifiers()),
                        c.getSimpleName() + "." + m.getName() + " is synchronized; value objects cannot be locked");
                assertTrue(!m.getName().equals("finalize"), c.getSimpleName() + " must not have finalize()");
            }
        }
    }

    @Test
    void sourcesCarryValueMarker() throws IOException {
        Path root = Path.of("src/main/java/vmath/core");
        if (!Files.isDirectory(root)) {
            return; // running outside the project directory
        }
        for (Class<?> c : VALUE_TYPES) {
            String src = Files.readString(root.resolve(c.getSimpleName() + ".java"));
            if (!src.contains("public /*value*/ record " + c.getSimpleName() + "(")) {
                fail(c.getSimpleName() + ".java must declare 'public /*value*/ record' so -Pvalhalla can rewrite it");
            }
        }
    }

    @Test
    void sourcesAvoidReferenceEqualityOnValueTypes() throws IOException {
        Path root = Path.of("src/main/java/vmath");
        if (!Files.isDirectory(root)) {
            return;
        }
        // Heuristic: flag '==' or '!=' next to a static constant of a value type, e.g. 'v == Vec3f.ZERO'.
        var pattern = java.util.regex.Pattern.compile("[!=]=\\s*(Vec[234]|Quat|Mat[34])[fd]\\.[A-Z_]+");
        try (var files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var m = pattern.matcher(Files.readString(p));
                if (m.find()) {
                    fail(p + ": reference comparison '" + m.group() + "'; use equals() or approxEquals()");
                }
            }
        }
    }
}
