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
import vmath.geo.Aabbd;
import vmath.geo.Aabbf;
import vmath.geo.Frustumd;
import vmath.geo.Frustumf;
import vmath.geo.Obbd;
import vmath.geo.Obbf;
import vmath.geo.Planed;
import vmath.geo.Planef;
import vmath.geo.Rayd;
import vmath.geo.Rayf;
import vmath.geo.Sphered;
import vmath.geo.Spheref;
import vmath.geo.Triangled;
import vmath.geo.Trianglef;

/**
 * Guard rails that keep every core type a valid future {@code value record}.
 *
 * <p>JEP 401 value classes must be final, have only final fields and must not depend on identity.
 * Records already give us the first two; these checks catch the rest before a Valhalla build does. The identity rules on the sources (no {@code ==}, {@code synchronized} or
 * {@code identityHashCode} on a value type) are checked by the compiler plugin of {@code vmath-validator} while the library compiles.
 */
class ValhallaReadinessTest {

    static final List<Class<?>> VALUE_TYPES = List.of(
            Vec2f.class, Vec3f.class, Vec4f.class, Quatf.class, Mat3f.class, Mat4f.class,
            Vec2d.class, Vec3d.class, Vec4d.class, Quatd.class, Mat3d.class, Mat4d.class,
            Aabbf.class, Spheref.class, Planef.class, Rayf.class, Trianglef.class, Obbf.class, Frustumf.class,
            Aabbd.class, Sphered.class, Planed.class, Rayd.class, Triangled.class, Obbd.class, Frustumd.class);

    @Test
    void allAreRecordsOfPrimitives() {
        for (Class<?> c : VALUE_TYPES) {
            assertTrue(c.isRecord(), c + " must be a record");
            assertTrue(Modifier.isFinal(c.getModifiers()), c + " must be final");
            assertTrue(c.getInterfaces().length == 0,
                    c + " should not implement interfaces (keeps call sites monomorphic and flattenable)");
            for (RecordComponent rc : c.getRecordComponents()) {
                assertTrue(rc.getType().isPrimitive() || VALUE_TYPES.contains(rc.getType()),
                        c.getSimpleName() + "." + rc.getName()
                                + " must be a primitive or another value record so the type stays flattenable");
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
    void templatesCarryValueAnnotations() throws IOException {
        List<Path> roots = List.of(Path.of("../vmath-core/src/template/java"), Path.of("../vmath-geo/src/template/java"));
        if (!Files.isDirectory(roots.get(0))) {
            return; // running outside the project directory
        }
        for (Class<?> c : VALUE_TYPES) {
            if (!c.getSimpleName().endsWith("f")) {
                continue; // double twins are generated from the float template
            }
            Path file = roots.stream().map(r -> r.resolve(c.getPackageName().replace('.', '/')).resolve(c.getSimpleName() + ".java")).filter(Files::exists).findFirst()
                    .orElseThrow(() -> new AssertionError("no template for " + c));
            String src = Files.readString(file);
            if (!src.contains("@ValueType") || !src.contains("@GenerateDouble")
                    || !src.contains("public record " + c.getSimpleName() + "(")) {
                fail(c.getSimpleName() + ".java must be a '@GenerateDouble @ValueType public record' template so"
                        + " the generator can emit both precisions and -Pvalhalla can make it a value record");
            }
        }
    }
}
