package vmath.core;

import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every generated method of every {@code *Bulk} class against the method it was made from, found
 * by reflection: the same arguments, element by element, in both layouts. This covers the whole
 * generated surface, whatever the templates mark next.
 */
@GenerateDouble
class BulkGeneratedAllfTest {

    static final String[] RECORDS = {"Vec2f", "Vec3f", "Vec4f", "Quatf", "Mat3f", "Mat4f", "Mat4x3f"};

    /** The marked methods whose receiver is one value for the whole batch (the attribute is not kept in the class files). */
    static final Set<String> UNIFORM_RECEIVER = Set.of("Quatf.transform", "Mat3f.transform", "Mat4f.transform", "Mat4f.transformPosition",
            "Mat4f.transformDirection", "Mat4x3f.transform", "Mat4x3f.transformPosition");

    final Rnd rnd = Rnd.create();

    /** One operand of the method: its record type (or null for a scalar) and the values of every element. */
    private static final class Operand {
        final Class<?> type;
        final boolean uniform;
        float[][] values;
        float scalar;

        Operand(Class<?> type, boolean uniform) {
            this.type = type;
            this.uniform = uniform;
        }

        int stride() {
            return type.getRecordComponents().length;
        }
    }

    private float random() {
        return (float) rnd.range(-10, 10);
    }

    private static boolean isRecordOfFloats(Class<?> c) {
        if (!c.isRecord()) {
            return false;
        }
        for (RecordComponent rc : c.getRecordComponents()) {
            if (rc.getType() != float.class) {
                return false;
            }
        }
        return true;
    }

    private static Object make(Class<?> type, float[] comps) throws Exception {
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = float.class;
            args[i] = comps[i];
        }
        Constructor<?> ctor = type.getDeclaredConstructor(types);
        return ctor.newInstance(args);
    }

    private static float[] components(Object record) throws Exception {
        RecordComponent[] rcs = record.getClass().getRecordComponents();
        float[] out = new float[rcs.length];
        for (int i = 0; i < rcs.length; i++) {
            out[i] = (Float) rcs[i].getAccessor().invoke(record);
        }
        return out;
    }

    /** The operands of a method, the receiver first unless it is static. */
    private static List<Operand> operands(Class<?> rec, Method m) {
        List<Operand> ops = new ArrayList<>();
        if (!Modifier.isStatic(m.getModifiers())) {
            ops.add(new Operand(rec, UNIFORM_RECEIVER.contains(rec.getSimpleName() + "." + m.getName())));
        }
        for (Class<?> p : m.getParameterTypes()) {
            ops.add(p == float.class ? new Operand(null, true) : new Operand(p, false));
        }
        return ops;
    }

    /** The parameter types that the generator gives the interleaved or planar method for these operands and this result. */
    private static List<Class<?>> signature(List<Operand> ops, Class<?> ret, boolean planar) {
        List<Class<?>> types = new ArrayList<>();
        for (Operand op : ops) {
            if (op.type == null) {
                types.add(float.class);
            } else if (planar && !op.uniform) {
                for (int c = 0; c < op.stride(); c++) {
                    types.add(float[].class);
                }
                types.add(int.class);
            } else {
                types.add(float[].class);
                types.add(int.class);
            }
        }
        if (planar && ret != float.class) {
            for (int c = 0; c < ret.getRecordComponents().length; c++) {
                types.add(float[].class);
            }
        } else {
            types.add(float[].class);
        }
        types.add(int.class);
        types.add(int.class);
        return types;
    }

    private static Method original(Class<?> rec, String base, Method generated, boolean planar) {
        String alias = base.equals("transformPositions") ? "transformPosition" : base.equals("transformDirections") ? "transformDirection" : base;
        for (Method m : rec.getDeclaredMethods()) {
            if (!m.getName().equals(alias) || !Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            if (ret != float.class && !isRecordOfFloats(ret)) {
                continue;
            }
            List<Operand> ops = operands(rec, m);
            boolean fits = true;
            for (Operand op : ops) {
                fits &= op.type == null || isRecordOfFloats(op.type);
            }
            if (fits && signature(ops, ret, planar).equals(List.of(generated.getParameterTypes()))) {
                return m;
            }
        }
        return null;
    }

    @Test
    void everyGeneratedMethodMatchesTheMethodItWasMadeFrom() throws Exception {
        int checked = 0;
        for (String name : RECORDS) {
            Class<?> rec = Class.forName("vmath.core." + name);
            Class<?> bulk = Class.forName("vmath.core." + name + "Bulk");
            for (Method g : bulk.getDeclaredMethods()) {
                if (!Modifier.isPublic(g.getModifiers()) || !Modifier.isStatic(g.getModifiers())) {
                    continue;
                }
                boolean planar = g.getName().endsWith("Planar");
                String base = planar ? g.getName().substring(0, g.getName().length() - "Planar".length()) : g.getName();
                Method orig = original(rec, base, g, planar);
                check(orig != null, 0, "no method of " + name + " matches " + g);
                for (int count : new int[] {0, 1, 5}) {
                    run(rec, g, orig, planar, count);
                    checked++;
                }
            }
        }
        check(checked > 100, 0, "the test must cover the generated surface, checked " + checked);
    }

    private void run(Class<?> rec, Method g, Method orig, boolean planar, int count) throws Exception {
        List<Operand> ops = operands(rec, orig);
        Class<?> ret = orig.getReturnType();
        int retStride = ret == float.class ? 1 : ret.getRecordComponents().length;
        List<Object> args = new ArrayList<>();
        int offset = 2, planeOffset = 1;
        for (Operand op : ops) {
            if (op.type == null) {
                op.scalar = random();
                args.add(op.scalar);
                continue;
            }
            int n = op.uniform ? 1 : count;
            op.values = new float[Math.max(n, 1)][op.stride()];
            for (float[] v : op.values) {
                for (int c = 0; c < v.length; c++) {
                    v[c] = random();
                }
            }
            if (planar && !op.uniform) {
                float[][] planes = new float[op.stride()][planeOffset + n + 2];
                for (int c = 0; c < op.stride(); c++) {
                    for (int i = 0; i < n; i++) {
                        planes[c][planeOffset + i] = op.values[i][c];
                    }
                    args.add(planes[c]);
                }
                args.add(planeOffset);
            } else {
                float[] flat = new float[offset + op.stride() * Math.max(n, 1) + 3];
                for (int i = 0; i < n; i++) {
                    System.arraycopy(op.values[i], 0, flat, offset + i * op.stride(), op.stride());
                }
                args.add(flat);
                args.add(offset);
            }
        }
        float[][] outPlanes = new float[retStride][planeOffset + count + 2];
        float[] outFlat = new float[3 + retStride * count + 4];
        if (planar && ret != float.class) {
            for (float[] plane : outPlanes) {
                args.add(plane);
            }
            args.add(planeOffset);
        } else {
            args.add(planar ? outPlanes[0] : outFlat);
            args.add(planar ? planeOffset : 3);
        }
        args.add(count);
        g.invoke(null, args.toArray());
        for (int i = 0; i < count; i++) {
            Object receiver = null;
            Object[] params = new Object[orig.getParameterCount()];
            int p = 0;
            for (Operand op : ops) {
                Object value = op.type == null ? (Object) op.scalar : make(op.type, op.values[op.uniform ? 0 : i]);
                if (receiver == null && !Modifier.isStatic(orig.getModifiers()) && op == ops.get(0)) {
                    receiver = value;
                } else {
                    params[p++] = value;
                }
            }
            Object result = orig.invoke(receiver, params);
            float[] expected = ret == float.class ? new float[] {(Float) result} : components(result);
            for (int c = 0; c < retStride; c++) {
                float got = planar && ret != float.class ? outPlanes[c][planeOffset + i]
                        : planar ? outPlanes[0][planeOffset + i] : outFlat[3 + i * retStride + c];
                check(Float.compare(got, expected[c]) == 0, i, g + " element " + i + " component " + c + ": " + got + " but " + expected[c]);
            }
        }
    }
}
