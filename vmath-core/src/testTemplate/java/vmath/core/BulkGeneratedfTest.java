package vmath.core;

import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

/**
 * The classes that the generator writes for the {@code @Bulk} methods, against the methods they
 * were made from: the results must be bit-identical, in both layouts, with offsets, over an
 * operand, and without writing outside the result.
 */
@GenerateDouble
class BulkGeneratedfTest {

    final Rnd rnd = Rnd.create();

    /** The interleaved form of one operation: two operand arrays (the second may be unused), the result, and the count. */
    interface Interleaved {
        void run(float[] a, int ao, float[] b, int bo, float[] out, int oo, int count);
    }

    /** The planar form: the component arrays of the operands and of the result. */
    interface Planar {
        void run(float[][] a, int ao, float[][] b, int bo, float[][] out, int oo, int count);
    }

    /** The scalar method that the loops were made from, on the components of one element. */
    interface Reference {
        float[] apply(float[] a, float[] b);
    }

    private float[] random(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = (float) rnd.range(-10, 10);
        }
        return a;
    }

    private static float[] sub(float[] a, int off, int stride, int i) {
        return java.util.Arrays.copyOfRange(a, off + i * stride, off + (i + 1) * stride);
    }

    private static boolean same(float a, float b) {
        return Float.compare(a, b) == 0;
    }

    /** Runs one operation in both layouts and compares every result with the reference. */
    private void operation(String name, int sa, int sb, int so, Interleaved interleaved, Planar planar, Reference reference) {
        for (int trial = 0; trial < 20; trial++) {
            int count = trial == 0 ? 0 : trial == 1 ? 1 : 3 + (int) rnd.range(0, 12);
            int ao = 2, bo = 5, oo = 3;
            float[] a = random(ao + sa * count + 3);
            float[] b = random(bo + Math.max(sb, 1) * count + 3);
            float[] expected = new float[so * Math.max(count, 1)];
            for (int i = 0; i < count; i++) {
                float[] r = reference.apply(sub(a, ao, sa, i), sb == 0 ? null : sub(b, bo, sb, i));
                System.arraycopy(r, 0, expected, i * so, so);
            }
            // interleaved, with guard values around the result
            float[] out = new float[oo + so * count + 4];
            java.util.Arrays.fill(out, 42f);
            interleaved.run(a, ao, b, bo, out, oo, count);
            for (int k = 0; k < out.length; k++) {
                boolean inside = k >= oo && k < oo + so * count;
                float want = inside ? expected[k - oo] : 42f;
                check(same(out[k], want), trial, name + " interleaved, element float " + k + ": " + out[k] + " but " + want);
            }
            // over the first operand, element for element
            if (sa == so) {
                float[] over = a.clone();
                interleaved.run(over, ao, b, bo, over, ao, count);
                for (int k = 0; k < so * count; k++) {
                    check(same(over[ao + k], expected[k]), trial, name + " over the operand, float " + k);
                }
            }
            // planar: the same values, one array per component, with an offset
            int po = 1;
            float[][] pa = planes(a, ao, sa, count, po), pb = planes(b, bo, sb, count, po);
            float[][] pout = new float[so][count + po + 2];
            for (float[] plane : pout) {
                java.util.Arrays.fill(plane, 42f);
            }
            planar.run(pa, po, pb, po, pout, po, count);
            for (int c = 0; c < so; c++) {
                for (int k = 0; k < pout[c].length; k++) {
                    boolean inside = k >= po && k < po + count;
                    float want = inside ? expected[(k - po) * so + c] : 42f;
                    check(same(pout[c][k], want), trial, name + " planar, component " + c + " float " + k + ": " + pout[c][k] + " but " + want);
                }
            }
        }
    }

    private static float[][] planes(float[] a, int off, int stride, int count, int po) {
        float[][] p = new float[stride][count + po + 2];
        for (int c = 0; c < stride; c++) {
            for (int i = 0; i < count; i++) {
                p[c][po + i] = a[off + i * stride + c];
            }
        }
        return p;
    }

    private static Vec3f v3(float[] e) {
        return new Vec3f(e[0], e[1], e[2]);
    }

    private static float[] c3(Vec3f v) {
        return new float[] {v.x(), v.y(), v.z()};
    }

    @Test
    void vec3OperationsOnPairs() {
        operation("add", 3, 3, 3, Vec3fBulk::add, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.addPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, o[0], o[1], o[2], oo, n),
                (a, b) -> c3(v3(a).add(v3(b))));
        operation("sub", 3, 3, 3, Vec3fBulk::sub, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.subPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, o[0], o[1], o[2], oo, n),
                (a, b) -> c3(v3(a).sub(v3(b))));
        operation("mul", 3, 3, 3, Vec3fBulk::mul, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.mulPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, o[0], o[1], o[2], oo, n),
                (a, b) -> c3(v3(a).mul(v3(b))));
        operation("min", 3, 3, 3, Vec3fBulk::min, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.minPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, o[0], o[1], o[2], oo, n),
                (a, b) -> c3(v3(a).min(v3(b))));
        operation("max", 3, 3, 3, Vec3fBulk::max, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.maxPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, o[0], o[1], o[2], oo, n),
                (a, b) -> c3(v3(a).max(v3(b))));
        operation("cross", 3, 3, 3, Vec3fBulk::cross, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.crossPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, o[0], o[1], o[2], oo, n),
                (a, b) -> c3(v3(a).cross(v3(b))));
    }

    @Test
    void vec3OperationsThatGiveAScalar() {
        operation("dot", 3, 3, 1, Vec3fBulk::dot, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.dotPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, o[0], oo, n),
                (a, b) -> new float[] {v3(a).dot(v3(b))});
        operation("distanceSquared", 3, 3, 1, Vec3fBulk::distanceSquared,
                (a, ao, b, bo, o, oo, n) -> Vec3fBulk.distanceSquaredPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, o[0], oo, n),
                (a, b) -> new float[] {v3(a).distanceSquared(v3(b))});
        operation("lengthSquared", 3, 0, 1, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.lengthSquared(a, ao, o, oo, n),
                (a, ao, b, bo, o, oo, n) -> Vec3fBulk.lengthSquaredPlanar(a[0], a[1], a[2], ao, o[0], oo, n), (a, b) -> new float[] {v3(a).lengthSquared()});
    }

    @Test
    void vec3OperationsWithAScalarParameter() {
        for (int k = 0; k < 4; k++) {
            float s = (float) rnd.range(-3, 3);
            operation("mul(float)", 3, 0, 3, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.mul(a, ao, s, o, oo, n),
                    (a, ao, b, bo, o, oo, n) -> Vec3fBulk.mulPlanar(a[0], a[1], a[2], ao, s, o[0], o[1], o[2], oo, n), (a, b) -> c3(v3(a).mul(s)));
            operation("div(float)", 3, 0, 3, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.div(a, ao, s, o, oo, n),
                    (a, ao, b, bo, o, oo, n) -> Vec3fBulk.divPlanar(a[0], a[1], a[2], ao, s, o[0], o[1], o[2], oo, n), (a, b) -> c3(v3(a).div(s)));
            operation("lerp", 3, 3, 3, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.lerp(a, ao, b, bo, s, o, oo, n),
                    (a, ao, b, bo, o, oo, n) -> Vec3fBulk.lerpPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, s, o[0], o[1], o[2], oo, n),
                    (a, b) -> c3(v3(a).lerp(v3(b), s)));
            operation("fma", 3, 3, 3, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.fma(a, ao, b, bo, s, o, oo, n),
                    (a, ao, b, bo, o, oo, n) -> Vec3fBulk.fmaPlanar(a[0], a[1], a[2], ao, b[0], b[1], b[2], bo, s, o[0], o[1], o[2], oo, n),
                    (a, b) -> c3(v3(a).fma(v3(b), s)));
        }
    }

    @Test
    void unaryVec3Operations() {
        operation("negate", 3, 0, 3, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.negate(a, ao, o, oo, n),
                (a, ao, b, bo, o, oo, n) -> Vec3fBulk.negatePlanar(a[0], a[1], a[2], ao, o[0], o[1], o[2], oo, n), (a, b) -> c3(v3(a).negate()));
        operation("abs", 3, 0, 3, (a, ao, b, bo, o, oo, n) -> Vec3fBulk.abs(a, ao, o, oo, n),
                (a, ao, b, bo, o, oo, n) -> Vec3fBulk.absPlanar(a[0], a[1], a[2], ao, o[0], o[1], o[2], oo, n), (a, b) -> c3(v3(a).abs()));
    }

    @Test
    void vec2AndVec4() {
        operation("Vec2 add", 2, 2, 2, Vec2fBulk::add, (a, ao, b, bo, o, oo, n) -> Vec2fBulk.addPlanar(a[0], a[1], ao, b[0], b[1], bo, o[0], o[1], oo, n),
                (a, b) -> {
                    Vec2f r = new Vec2f(a[0], a[1]).add(new Vec2f(b[0], b[1]));
                    return new float[] {r.x(), r.y()};
                });
        operation("Vec2 dot", 2, 2, 1, Vec2fBulk::dot, (a, ao, b, bo, o, oo, n) -> Vec2fBulk.dotPlanar(a[0], a[1], ao, b[0], b[1], bo, o[0], oo, n),
                (a, b) -> new float[] {new Vec2f(a[0], a[1]).dot(new Vec2f(b[0], b[1]))});
        operation("Vec4 add", 4, 4, 4, Vec4fBulk::add,
                (a, ao, b, bo, o, oo, n) -> Vec4fBulk.addPlanar(a[0], a[1], a[2], a[3], ao, b[0], b[1], b[2], b[3], bo, o[0], o[1], o[2], o[3], oo, n), (a, b) -> {
                    Vec4f r = new Vec4f(a[0], a[1], a[2], a[3]).add(new Vec4f(b[0], b[1], b[2], b[3]));
                    return new float[] {r.x(), r.y(), r.z(), r.w()};
                });
        operation("Vec4 dot", 4, 4, 1, Vec4fBulk::dot, (a, ao, b, bo, o, oo, n) -> Vec4fBulk.dotPlanar(a[0], a[1], a[2], a[3], ao, b[0], b[1], b[2], b[3], bo, o[0], oo, n),
                (a, b) -> new float[] {new Vec4f(a[0], a[1], a[2], a[3]).dot(new Vec4f(b[0], b[1], b[2], b[3]))});
    }

    @Test
    void quaternionProductAndConjugate() {
        operation("Quat mul", 4, 4, 4, QuatfBulk::mul,
                (a, ao, b, bo, o, oo, n) -> QuatfBulk.mulPlanar(a[0], a[1], a[2], a[3], ao, b[0], b[1], b[2], b[3], bo, o[0], o[1], o[2], o[3], oo, n), (a, b) -> {
                    Quatf r = new Quatf(a[0], a[1], a[2], a[3]).mul(new Quatf(b[0], b[1], b[2], b[3]));
                    return new float[] {r.x(), r.y(), r.z(), r.w()};
                });
        operation("Quat conjugate", 4, 0, 4, (a, ao, b, bo, o, oo, n) -> QuatfBulk.conjugate(a, ao, o, oo, n),
                (a, ao, b, bo, o, oo, n) -> QuatfBulk.conjugatePlanar(a[0], a[1], a[2], a[3], ao, o[0], o[1], o[2], o[3], oo, n), (a, b) -> {
                    Quatf r = new Quatf(a[0], a[1], a[2], a[3]).conjugate();
                    return new float[] {r.x(), r.y(), r.z(), r.w()};
                });
    }

    /** A uniform operand: one matrix or quaternion for every element. */
    @Test
    void uniformOperands() {
        for (int trial = 0; trial < N / 20; trial++) {
            int count = 1 + (int) rnd.range(0, 20);
            Mat4f m = rnd.nextDenseMat4f();
            Quatf q = rnd.nextUnitQuatf();
            float[] mf = new float[17];
            m.writeTo(mf, 1);
            float[] qf = {7f, q.x(), q.y(), q.z(), q.w()};
            float[] pts = random(3 * count + 2);
            float[] hom = random(4 * count + 2);
            float[] out3 = new float[3 * count + 2], out4 = new float[4 * count + 2];
            Mat4fBulk.transformPositions(mf, 1, pts, 2, out3, 1, count);
            for (int i = 0; i < count; i++) {
                Vec3f e = m.transformPosition(new Vec3f(pts[2 + 3 * i], pts[3 + 3 * i], pts[4 + 3 * i]));
                check(same(out3[1 + 3 * i], e.x()) && same(out3[2 + 3 * i], e.y()) && same(out3[3 + 3 * i], e.z()), trial, "transformPositions " + i);
            }
            Mat4fBulk.transformDirections(mf, 1, pts, 2, out3, 1, count);
            for (int i = 0; i < count; i++) {
                Vec3f e = m.transformDirection(new Vec3f(pts[2 + 3 * i], pts[3 + 3 * i], pts[4 + 3 * i]));
                check(same(out3[1 + 3 * i], e.x()) && same(out3[2 + 3 * i], e.y()) && same(out3[3 + 3 * i], e.z()), trial, "transformDirections " + i);
            }
            Mat4fBulk.transform(mf, 1, hom, 2, out4, 1, count);
            for (int i = 0; i < count; i++) {
                Vec4f e = m.transform(new Vec4f(hom[2 + 4 * i], hom[3 + 4 * i], hom[4 + 4 * i], hom[5 + 4 * i]));
                check(same(out4[1 + 4 * i], e.x()) && same(out4[2 + 4 * i], e.y()) && same(out4[3 + 4 * i], e.z()) && same(out4[4 + 4 * i], e.w()), trial,
                        "transform " + i);
            }
            QuatfBulk.transform(qf, 1, pts, 2, out3, 1, count);
            for (int i = 0; i < count; i++) {
                Vec3f e = q.transform(new Vec3f(pts[2 + 3 * i], pts[3 + 3 * i], pts[4 + 3 * i]));
                check(same(out3[1 + 3 * i], e.x()) && same(out3[2 + 3 * i], e.y()) && same(out3[3 + 3 * i], e.z()), trial, "rotate " + i);
            }
            // in place
            float[] inPlace = pts.clone();
            Mat4fBulk.transformPositions(mf, 1, inPlace, 2, inPlace, 2, count);
            Mat4fBulk.transformPositions(mf, 1, pts, 2, out3, 2, count);
            for (int i = 0; i < 3 * count; i++) {
                check(same(inPlace[2 + i], out3[2 + i]), trial, "in place " + i);
            }
        }
    }

    @Test
    void anEmptyBatchTouchesNothingAndNeedsNoArrays() {
        Vec3fBulk.add(null, 0, null, 0, null, 0, 0);
        Vec3fBulk.addPlanar(null, null, null, 0, null, null, null, 0, null, null, null, 0, -3);
        boolean threw = false;
        try {
            Vec3fBulk.add(new float[3], 0, new float[3], 0, new float[2], 0, 1);
        } catch (ArrayIndexOutOfBoundsException e) {
            threw = true;
        }
        check(threw, 0, "a result array that is too short throws");
        threw = false;
        try {
            Vec3fBulk.add(null, 0, new float[3], 0, new float[3], 0, 1);
        } catch (NullPointerException e) {
            threw = true;
        }
        check(threw, 0, "a null array throws");
    }
}
