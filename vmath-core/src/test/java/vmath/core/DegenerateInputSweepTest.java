package vmath.core;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * INF-7: every public method of the core value types, called with zeros, negative zeros, NaN, infinities, denormals, huge values and singular or near-singular
 * matrices. It looks for what must never happen (an unexpected exception, a call that does not finish) and for NaN being silently turned into a finite number;
 * everything else it counts and reports, and {@code docs/ROBUSTNESS.md} is written from those measurements.
 */
class DegenerateInputSweepTest {

    private static final Class<?>[] TYPES = {Vec2f.class, Vec3f.class, Vec4f.class, Quatf.class, Mat3f.class, Mat4f.class, Mat4x3f.class,
            Mat2f.class, Mat3x2f.class, Vec2d.class, Vec3d.class, Vec4d.class, Quatd.class, Mat3d.class, Mat4d.class, Mat4x3d.class, Mat2d.class, Mat3x2d.class};

    private static final double[] SPECIAL = {0.0, -0.0, 1.0, -1.0, 0.5, 1e-3, 1e3, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e-44, -1e-44, 3.0e38, -3.0e38, 1e-20, 1e20};

    private final Random rnd = new Random(20261001L);

    // ---------------------------------------------------------------- value pools

    private boolean isDouble(Class<?> t) {
        return t.getSimpleName().endsWith("d");
    }

    private Object make(Class<?> type, double[] v) throws ReflectiveOperationException {
        Constructor<?> c = type.getRecordComponents() == null ? null : type.getDeclaredConstructors()[0];
        Object[] args = new Object[v.length];
        boolean dbl = isDouble(type);
        for (int i = 0; i < v.length; i++) {
            args[i] = dbl ? (Object) v[i] : (Object) (float) v[i];
        }
        return c.newInstance(args);
    }

    private int components(Class<?> type) {
        return type.getRecordComponents().length;
    }

    private List<Object> pool(Class<?> type) throws ReflectiveOperationException {
        int n = components(type);
        List<double[]> raw = new ArrayList<>();
        raw.add(new double[n]); // zero
        double[] ones = new double[n];
        java.util.Arrays.fill(ones, 1.0);
        raw.add(ones);
        double[] nan = new double[n];
        java.util.Arrays.fill(nan, Double.NaN);
        raw.add(nan);
        double[] inf = new double[n];
        java.util.Arrays.fill(inf, Double.POSITIVE_INFINITY);
        raw.add(inf);
        double[] tiny = new double[n];
        java.util.Arrays.fill(tiny, 1e-44);
        raw.add(tiny);
        double[] huge = new double[n];
        java.util.Arrays.fill(huge, 3.0e38);
        raw.add(huge);
        double[] negZero = new double[n];
        java.util.Arrays.fill(negZero, -0.0);
        raw.add(negZero);
        String name = type.getSimpleName();
        if (name.startsWith("Quat")) {
            raw.add(new double[] {0, 0, 0, 1});
            raw.add(new double[] {1, 1, 1, 1});
            raw.add(new double[] {0, 0, 0, -1});
            raw.add(new double[] {1e-30, 0, 0, 1e-30});
        } else if (name.startsWith("Mat")) {
            int dim = n == 4 ? 2 : n == 6 ? 2 : n == 9 ? 3 : 4; // Mat4x3 is stored as 12 and Mat3x2 as 6
            double[] id = new double[n];
            if (n == 6) {
                id[0] = id[3] = 1; // the linear part is the identity, the translation is zero
            } else if (n == 12) {
                id[0] = id[4] = id[8] = 1; // 3 columns of 3 plus translation: rows are not needed for the pool, any consistent record works
            } else {
                for (int i = 0; i < dim; i++) {
                    id[i * dim + i] = 1;
                }
            }
            raw.add(id);
            double[] rank1 = new double[n];
            for (int i = 0; i < n; i++) {
                rank1[i] = (i % 3 + 1) * 0.5;
            }
            raw.add(rank1);
            double[] nearSingular = id.clone();
            nearSingular[0] = 1e-20;
            raw.add(nearSingular);
            double[] denormalScale = new double[n];
            for (int i = 0; i < n; i += dim + 1) {
                if (i < n) {
                    denormalScale[i] = 1e-40;
                }
            }
            raw.add(denormalScale);
            double[] oneNan = id.clone();
            oneNan[n / 2] = Double.NaN;
            raw.add(oneNan);
        } else {
            double[] unit = new double[n];
            unit[0] = 1;
            raw.add(unit);
            double[] oneNan = new double[n];
            oneNan[n - 1] = Double.NaN;
            oneNan[0] = 1;
            raw.add(oneNan);
            double[] mixed = new double[n];
            for (int i = 0; i < n; i++) {
                mixed[i] = i == 0 ? Double.POSITIVE_INFINITY : i == 1 ? -0.0 : 1e-44;
            }
            raw.add(mixed);
        }
        for (int k = 0; k < 40; k++) {
            double[] r = new double[n];
            for (int i = 0; i < n; i++) {
                r[i] = SPECIAL[rnd.nextInt(SPECIAL.length)];
            }
            raw.add(r);
        }
        for (int k = 0; k < 10; k++) { // ordinary values too, so that calls are not all degenerate
            double[] r = new double[n];
            for (int i = 0; i < n; i++) {
                r[i] = rnd.nextDouble() * 4 - 2;
            }
            raw.add(r);
        }
        List<Object> out = new ArrayList<>();
        for (double[] r : raw) {
            out.add(make(type, r));
        }
        return out;
    }

    private Object[] scalarPool(Class<?> t) {
        if (t == float.class) {
            Object[] o = new Object[SPECIAL.length + 4];
            for (int i = 0; i < SPECIAL.length; i++) {
                o[i] = (float) SPECIAL[i];
            }
            o[SPECIAL.length] = 2f;
            o[SPECIAL.length + 1] = 0.25f;
            o[SPECIAL.length + 2] = 3.14159f;
            o[SPECIAL.length + 3] = Float.MIN_VALUE;
            return o;
        }
        if (t == double.class) {
            Object[] o = new Object[SPECIAL.length + 4];
            for (int i = 0; i < SPECIAL.length; i++) {
                o[i] = SPECIAL[i];
            }
            o[SPECIAL.length] = 2.0;
            o[SPECIAL.length + 1] = 0.25;
            o[SPECIAL.length + 2] = 3.14159;
            o[SPECIAL.length + 3] = Double.MIN_VALUE;
            return o;
        }
        if (t == int.class) {
            return new Object[] {0, 1, 2, 3, -1, 7, 1 << 20};
        }
        if (t == boolean.class) {
            return new Object[] {false, true};
        }
        return null;
    }

    // ---------------------------------------------------------------- flattening

    /** All floating point components of a value (primitives, records, arrays of them) as doubles. */
    private static void flatten(Object o, List<Double> out) {
        if (o == null) {
            return;
        }
        if (o instanceof Float f) {
            out.add((double) f);
        } else if (o instanceof Double d) {
            out.add(d);
        } else if (o.getClass().isRecord()) {
            try {
                for (RecordComponent rc : o.getClass().getRecordComponents()) {
                    flatten(rc.getAccessor().invoke(o), out);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        } else if (o instanceof float[] a) {
            for (float v : a) {
                out.add((double) v);
            }
        } else if (o instanceof double[] a) {
            for (double v : a) {
                out.add(v);
            }
        }
    }

    // ---------------------------------------------------------------- the sweep

    /** Per method statistics. */
    private static final class Stat {
        int calls, threw, nanLaundered, nonFiniteFromFinite, finiteCalls;
        final Map<String, Integer> exceptions = new TreeMap<>();
        final List<String> examples = new ArrayList<>();
        long nanos;
    }

    private final Map<String, Stat> stats = new LinkedHashMap<>();
    private final List<String> slow = new ArrayList<>();

    private void sweep(Class<?> type, List<Object> ownPool, Map<Class<?>, List<Object>> pools) throws ReflectiveOperationException {
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() != type || m.isSynthetic() || m.isBridge()) {
                continue;
            }
            boolean supported = true;
            List<Object[]> domains = new ArrayList<>();
            for (Class<?> p : m.getParameterTypes()) {
                Object[] scalars = scalarPool(p);
                if (scalars != null) {
                    domains.add(scalars);
                } else if (pools.containsKey(p)) {
                    domains.add(pools.get(p).toArray());
                } else {
                    supported = false; // arrays, buffers, segments, enums, Object: covered by their own tests
                }
            }
            if (!supported) {
                continue;
            }
            String key = type.getSimpleName() + "." + m.getName() + "(" + m.getParameterCount() + ")";
            Stat st = stats.computeIfAbsent(key, k -> new Stat());
            boolean isStatic = Modifier.isStatic(m.getModifiers());
            int calls = 400;
            for (int c = 0; c < calls; c++) {
                Object receiver = isStatic ? null : ownPool.get(rnd.nextInt(ownPool.size()));
                Object[] args = new Object[domains.size()];
                List<Double> inputs = new ArrayList<>();
                flatten(receiver, inputs);
                for (int i = 0; i < args.length; i++) {
                    args[i] = domains.get(i)[rnd.nextInt(domains.get(i).length)];
                    flatten(args[i], inputs);
                }
                boolean anyNaN = false, allFinite = true;
                for (double d : inputs) {
                    anyNaN |= Double.isNaN(d);
                    allFinite &= Double.isFinite(d);
                }
                long t0 = System.nanoTime();
                Object result = null;
                boolean threw = false;
                try {
                    result = m.invoke(receiver, args);
                } catch (InvocationTargetException e) {
                    threw = true;
                    st.threw++;
                    st.exceptions.merge(e.getCause().getClass().getSimpleName(), 1, Integer::sum);
                }
                st.nanos += System.nanoTime() - t0;
                st.calls++;
                if (threw || result == null) {
                    continue;
                }
                List<Double> out = new ArrayList<>();
                flatten(result, out);
                if (out.isEmpty()) {
                    continue;
                }
                boolean resultFinite = true, resultHasNaN = false;
                for (double d : out) {
                    resultFinite &= Double.isFinite(d);
                    resultHasNaN |= Double.isNaN(d);
                }
                if (anyNaN && resultFinite) {
                    st.nanLaundered++;
                    if (st.examples.size() < 3) {
                        st.examples.add("in " + inputs + " -> " + out);
                    }
                }
                if (allFinite) {
                    st.finiteCalls++;
                    if (!resultFinite) {
                        st.nonFiniteFromFinite++;
                    }
                }
                if (resultHasNaN && !anyNaN && allFinite) {
                    // counted in nonFiniteFromFinite already
                }
            }
            if (st.nanos > 2_000_000_000L) {
                slow.add(key + " took " + st.nanos / 1_000_000 + " ms for " + st.calls + " calls");
            }
        }
    }

    /** Methods that select or read one part of a value by an index or name: they throw for an index outside the value, by design, and nothing else. */
    private static final java.util.Set<String> INDEXED_ACCESSORS = java.util.Set.of("get", "column", "row");

    /** Methods with a count of decimals that must be from 0 to 17 (CORE-12): they refuse any other number with an IllegalArgumentException, by design. */
    private static final java.util.Set<String> CHECKED_DECIMALS = java.util.Set.of("format", "toMatrixString");

    /**
     * Methods that may return a finite value although an input held NaN, each because the NaN was in a part the method does not read or because the result is
     * defined by a comparison. A new method that turns NaN into a finite number must be added here with its reason, or fixed.
     */
    private static final java.util.Set<String> NAN_MAY_BE_IGNORED = java.util.Set.of(
            // reading one component or a part of the value
            "x", "y", "z", "w", "xyz", "get", "column", "row", "upperLeft3x3", "getTranslation", "linear" /* Mat3x2: the 2x2 part */, "trace" /* the diagonal */,
            "rotationAngle" /* the first column */, "fromMat3" /* Mat3x2: the last row of the 3x3 is ignored */, "m00", "m01", "m02", "m03", "m10", "m11", "m12", "m13", "m20", "m21", "m22",
            "m23", "m30", "m31", "m32", "m33", "withTranslation", "determinant" /* Mat4x3: the 3x3 part only */,
            // affine matrices are assumed to have the bottom row 0, 0, 0, 1 and never read it
            "transformPosition", "transformDirection", "normalMatrix", "invertAffine", "decompose", "decomposeWithShear", "fromMat4",
            // a projection matrix is assumed to have the structure of the matrices the library builds: the six entries that must be zero are never read
            "invertProjection",
            // defined by a comparison or a guard for a zero or unit input, see docs/ROBUSTNESS.md
            "step", "faceForward", "project", "angle", "axis", "pow", "integrate");

    private static String baseName(String key) {
        String name = key.substring(key.indexOf('.') + 1);
        return name.substring(0, name.indexOf('('));
    }

    @Test
    void sweepAllPublicMethods() throws ReflectiveOperationException {
        Map<Class<?>, List<Object>> pools = new LinkedHashMap<>();
        for (Class<?> t : TYPES) {
            pools.put(t, pool(t));
        }
        // the geo shapes that core methods take are out of scope here; ClipSpace and the like are enums and are skipped by the parameter filter
        for (Class<?> t : TYPES) {
            sweep(t, pools.get(t), pools);
        }
        int totalCalls = 0, throwing = 0, laundering = 0;
        StringBuilder report = new StringBuilder();
        report.append("SWEEP methods=").append(stats.size()).append('\n');
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            Stat s = e.getValue();
            totalCalls += s.calls;
            if (s.threw > 0) {
                throwing++;
                report.append("SWEEP-THROWS ").append(e.getKey()).append(' ').append(s.exceptions).append('\n');
            }
            if (s.nanLaundered > 0) {
                laundering++;
                report.append("SWEEP-NAN-TO-FINITE ").append(e.getKey()).append(" ").append(s.nanLaundered).append(" of ").append(s.calls).append('\n');
                for (String ex : s.examples) {
                    report.append("SWEEP-EXAMPLE ").append(e.getKey()).append("  ").append(ex).append('\n');
                }
            }
            if (s.finiteCalls > 0 && s.nonFiniteFromFinite > 0) {
                report.append("SWEEP-FINITE-TO-NONFINITE ").append(e.getKey()).append(" ").append(s.nonFiniteFromFinite).append(" of ").append(s.finiteCalls).append('\n');
            }
        }
        report.append("SWEEP calls=").append(totalCalls).append(" methodsThatThrow=").append(throwing).append(" methodsThatLaunderNaN=").append(laundering).append('\n');
        Report.print(report.toString());
        List<String> unexpected = new ArrayList<>();
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            Stat st = e.getValue();
            for (String exception : st.exceptions.keySet()) {
                boolean byDesign = INDEXED_ACCESSORS.contains(baseName(e.getKey())) && exception.equals("IndexOutOfBoundsException")
                        || CHECKED_DECIMALS.contains(baseName(e.getKey())) && exception.equals("IllegalArgumentException");
                if (!byDesign) {
                    unexpected.add(e.getKey() + " threw " + exception);
                }
            }
            if (st.nanLaundered > 0 && !NAN_MAY_BE_IGNORED.contains(baseName(e.getKey()))) {
                unexpected.add(e.getKey() + " returned a finite value for input with NaN in " + st.nanLaundered + " calls, e.g. " + st.examples.get(0));
            }
        }
        assertTrue(unexpected.isEmpty(), "unexpected behaviour on degenerate input:\n" + String.join("\n", unexpected));
        assertTrue(slow.isEmpty(), "calls that are far too slow: " + slow);
        assertTrue(totalCalls > 100_000, "the sweep must be broad: " + totalCalls);
    }
}
