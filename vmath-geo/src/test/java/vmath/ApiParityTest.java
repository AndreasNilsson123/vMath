package vmath;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Keeps the API of sibling types consistent. The tables below are the single source of truth for {@code docs/API.md}: a type that is missing an
 * operation its siblings have fails here, naming the type and the operation, unless the difference is one of the documented exceptions.
 *
 * <p>Two kinds of check:
 * <ol>
 *   <li><b>Family parity.</b> Every type of a family must offer every operation in the family's required list (matched by name, so overloads count
 *       once). What a type has <em>beyond</em> the list is fine, and operations that only make sense for some members are kept out of the list and named in
 *       {@link #EXCEPTIONS}.</li>
 *   <li><b>Float/double parity.</b> Every public method of a {@code ...f} type exists on its {@code ...d} twin with the same name, parameter count and staticness,
 *       and the twin can convert back ({@code toFloat}); the intended differences are listed in {@link #ONLY_FLOAT} and {@link #ONLY_DOUBLE}.</li>
 * </ol>
 */
class ApiParityTest {

    private static final String CORE = "vmath.core.";
    private static final String GEO = "vmath.geo.";

    /** Families: name to (types, required operations). Insertion order is the order of the documentation table. */
    private static final Map<String, Family> FAMILIES = new LinkedHashMap<>();

    private record Family(List<String> types, Set<String> required) {
    }

    private static void family(String name, List<String> types, String... ops) {
        FAMILIES.put(name, new Family(types, new TreeSet<>(List.of(ops))));
    }

    static {
        family("vectors", List.of(CORE + "Vec2f", CORE + "Vec3f", CORE + "Vec4f"),
                "abs", "add", "angle", "approxEquals", "ceil", "clamp", "distance", "distanceSquared", "div", "dot", "equals", "faceForward", "floor",
                "fma", "fract", "get", "hashCode", "isFinite", "length", "lengthSquared", "lerp", "max", "maxComponent", "min", "minComponent", "mul",
                "negate", "normalize", "normalizeOrZero", "project", "reflect", "refract", "reject", "saturate", "sign", "smoothstep", "splat", "step",
                "sub", "toDouble", "toString", "writeTo");
        family("matrices", List.of(CORE + "Mat3f", CORE + "Mat4f", CORE + "Mat4x3f"),
                "approxEquals", "column", "determinant", "equals", "fromArray", "fromColumns", "get", "hashCode", "invert", "isFinite", "mul", "rotation",
                "rotationAxis", "rotationX", "rotationY", "rotationZ", "scaling", "toDouble", "toString", "transform", "writeTo");
        family("square matrices", List.of(CORE + "Mat3f", CORE + "Mat4f"), "row", "transpose");
        family("affine matrices", List.of(CORE + "Mat4f", CORE + "Mat4x3f"),
                "decompose", "getTranslation", "normalMatrix", "transformDirection", "transformPosition", "translation", "upperLeft3x3", "withTranslation");
        family("rotations", List.of(CORE + "Quatf"),
                "angle", "approxEquals", "axis", "conjugate", "dot", "equals", "exp", "fromAxisAngle", "fromEuler", "fromMat3", "fromTo", "hashCode",
                "integrate", "invert", "isFinite", "length", "lengthSquared", "log", "lookRotation", "mul", "nlerp", "normalize", "pow", "slerp", "squad",
                "swing", "toDouble", "toEuler", "toMat3", "toMat4", "toString", "transform", "twist");
        family("shapes", List.of(GEO + "Aabbf", GEO + "Spheref", GEO + "Planef", GEO + "Rayf", GEO + "Trianglef", GEO + "Obbf", GEO + "Frustumf", GEO + "Segmentf", GEO + "Capsulef"),
                "equals", "hashCode", "toDouble", "toString");
        family("shapes with a closest point", List.of(GEO + "Aabbf", GEO + "Spheref", GEO + "Planef", GEO + "Rayf", GEO + "Trianglef", GEO + "Obbf", GEO + "Segmentf", GEO + "Capsulef"),
                "closestPoint");
        family("shapes that transform by a matrix", List.of(GEO + "Aabbf", GEO + "Spheref", GEO + "Planef", GEO + "Rayf", GEO + "Trianglef", GEO + "Segmentf", GEO + "Capsulef"), "transform");
    }

    /**
     * Operations that exist on some members of a family only, on purpose. Documented in {@code docs/API.md}; listed here so that the exceptions are a
     * decision, not an accident. (Not checked: this is the documentation of what the tables above leave out.)
     */
    static final Map<String, String> EXCEPTIONS = Map.ofEntries(
            Map.entry("Vec3f.cross / Vec2f.cross", "no cross product in 4D; in 2D it is the scalar perp-dot product"),
            Map.entry("Vec3f.anyPerpendicular, Vec2f.perpendicular, Vec2f.rotate", "only defined for one dimension"),
            Map.entry("Vec4f.w, xyz, point, direction, divideByW", "homogeneous coordinates"),
            Map.entry("Mat4f.isAffine, lookAt, lookTo, perspective*, ortho, frustum", "projection and view builders are 4x4 only"),
            Map.entry("Mat3f.skew, basisFromNormal, normal", "3x3 conveniences"),
            Map.entry("Mat3f.decompose", "a 3x3 has no translation; use Quatf.fromMat3 for the rotation"),
            Map.entry("Mat4x3f.transpose, row", "not square"),
            Map.entry("Obbf.transform, Frustumf.transform", "an oriented box under a general affine map is not a box; a frustum is rebuilt from its view-projection"));

    /** Methods of a {@code ...f} type that the {@code ...d} twin does not have (conversions to the other precision, float-only buffers). */
    private static final Set<String> ONLY_FLOAT = Set.of("toDouble");

    /** Methods of a {@code ...d} type that the {@code ...f} twin does not have. */
    private static final Set<String> ONLY_DOUBLE = Set.of("toFloat", "relativeTo");

    private static Set<String> operations(Class<?> c) {
        Set<String> names = new TreeSet<>();
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                names.add(m.getName());
            }
        }
        return names;
    }

    private static Set<String> signatures(Class<?> c) {
        Set<String> sigs = new TreeSet<>();
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                // name, parameter count and staticness; buffer writers are float-only and listed separately
                if (m.getName().equals("writeTo") && java.util.Arrays.stream(m.getParameterTypes()).anyMatch(p -> p.getSimpleName().endsWith("Buffer"))) {
                    continue;
                }
                sigs.add((Modifier.isStatic(m.getModifiers()) ? "static " : "") + m.getName() + "/" + m.getParameterCount());
            }
        }
        return sigs;
    }

    @Test
    void everyTypeOffersTheOperationsOfItsFamily() {
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, Family> e : FAMILIES.entrySet()) {
            for (String type : e.getValue().types()) {
                Set<String> have;
                try {
                    have = operations(Class.forName(type));
                } catch (ClassNotFoundException ex) {
                    gaps.add(type + ": class not found");
                    continue;
                }
                for (String op : e.getValue().required()) {
                    if (!have.contains(op)) {
                        gaps.add(type.substring(type.lastIndexOf('.') + 1) + " is missing " + op + " (family: " + e.getKey() + ")");
                    }
                }
            }
        }
        assertTrue(gaps.isEmpty(), "API parity gaps:\n  " + String.join("\n  ", gaps));
    }

    @Test
    void everyDoubleTwinMatchesItsFloatType() {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Family f : FAMILIES.values()) {
            for (String floatType : f.types()) {
                if (!seen.add(floatType)) {
                    continue;
                }
                String doubleType = floatType.substring(0, floatType.length() - 1) + "d";
                Class<?> fc, dc;
                try {
                    fc = Class.forName(floatType);
                    dc = Class.forName(doubleType);
                } catch (ClassNotFoundException ex) {
                    problems.add(floatType + ": no double twin");
                    continue;
                }
                Set<String> fs = signatures(fc), ds = signatures(dc);
                for (String s : fs) {
                    String name = s.substring(s.lastIndexOf(' ') + 1, s.indexOf('/'));
                    if (!ds.contains(s) && !ONLY_FLOAT.contains(name)) {
                        problems.add(doubleType + " is missing " + s + " that " + floatType + " has");
                    }
                }
                for (String s : ds) {
                    String name = s.substring(s.lastIndexOf(' ') + 1, s.indexOf('/'));
                    if (!fs.contains(s) && !ONLY_DOUBLE.contains(name)) {
                        problems.add(doubleType + " has " + s + " that " + floatType + " lacks");
                    }
                }
                if (!operations(dc).contains("toFloat")) {
                    problems.add(doubleType + " cannot convert back: no toFloat");
                }
            }
        }
        assertTrue(problems.isEmpty(), "float/double twin problems:\n  " + String.join("\n  ", problems));
    }

    @Test
    void theDocumentedExceptionsAreRealDifferences() {
        // the exceptions table must not go stale: each named operation must really be absent from the types it says it is absent from
        assertTrue(operations(classOf(CORE + "Vec4f")).contains("w") && !operations(classOf(CORE + "Vec4f")).contains("cross"), "Vec4f has w and no cross");
        assertTrue(operations(classOf(CORE + "Vec3f")).contains("cross") && operations(classOf(CORE + "Vec2f")).contains("cross"));
        assertTrue(!operations(classOf(CORE + "Mat4x3f")).contains("transpose") && !operations(classOf(CORE + "Mat4x3f")).contains("row"));
        assertTrue(!operations(classOf(GEO + "Obbf")).contains("transform") && !operations(classOf(GEO + "Frustumf")).contains("transform"));
        assertTrue(EXCEPTIONS.size() >= 8);
    }

    private static Class<?> classOf(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
