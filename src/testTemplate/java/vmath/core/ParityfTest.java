package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

/** The operations added to make sibling types consistent (see {@code ApiParityTest}): every one is checked for what it does, not just that it exists. */
@GenerateDouble
class ParityfTest {
    @Eps(d = 1e-12)
    static final float EPS = 1e-4f;

    /** The reference angle goes through acos of a rounded cosine, so it is only that accurate near 0 and PI. */
    @Eps(d = 1e-6)
    static final float ANGLE_EPS = 3e-3f;

    final Rnd rnd = Rnd.create();

    // ------------------------------------------------------------ Vec4

    @Test
    void vec4Splat() {
        for (int i = 0; i < N; i++) {
            float s = (float) rnd.range(-10, 10);
            check(Vec4f.splat(s).equals(new Vec4f(s, s, s, s)), i, "splat");
        }
    }

    @Test
    void vec4AngleMatchesTheDefinitionAndStaysAccurateAtTheExtremes() {
        for (int i = 0; i < N; i++) {
            Vec4f a = rnd.nextVec4f();
            Vec4f b = rnd.nextVec4f();
            double cos = a.dot(b) / (a.length() * b.length());
            double expected = Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
            close(a.angle(b), expected, ANGLE_EPS, i);
            close(a.angle(b), b.angle(a), EPS, i);
            check(a.angle(b) >= 0f && a.angle(b) <= (float) Math.PI + EPS, i, "angle in [0, PI]");
        }
        Vec4f x = new Vec4f(1f, 0f, 0f, 0f);
        close(x.angle(x.mul(5f)), 0.0, EPS, 0);
        close(x.angle(x.negate()), Math.PI, EPS, 1);
        close(x.angle(new Vec4f(0f, 0f, 0f, 2f)), Math.PI / 2, EPS, 2);
        close(x.angle(Vec4f.ZERO), 0.0, EPS, 3);
        close(Vec4f.ZERO.angle(Vec4f.ZERO), 0.0, EPS, 4);
        close(x.angle(new Vec4f(1f, 1e-3f, 0f, 0f)), 1e-3, 1e-6, 5); // nearly parallel: acos(dot) would give 0 or garbage in float
        close(x.angle(new Vec4f(-1f, 1e-3f, 0f, 0f)), Math.PI - 1e-3, 1e-5, 6); // nearly opposite
    }

    @Test
    void vec4ReflectAndRefractFollowTheGlslDefinitions() {
        for (int i = 0; i < N; i++) {
            Vec4f a = rnd.nextVec4f();
            Vec4f n = rnd.nextVec4f().normalize();
            Vec4f r = a.reflect(n);
            close(r.length(), a.length(), EPS * 10, i);
            close(r.dot(n), -a.dot(n), EPS * 10, i);
            close(r.reflect(n), a, EPS * 10, i);

            Vec4f incident = a.normalize();
            // GLSL refract wants the normal to oppose the incident direction (incident . n < 0)
            Vec4f facing = incident.dot(n) > 0f ? n.negate() : n;
            close(incident.refract(facing, 1f), incident, EPS * 10, i); // equal indices: straight through
            // with w = 0 everywhere the 4D version is the 3D one
            Vec3f a3 = rnd.nextVec3f().normalize();
            Vec3f n3 = rnd.nextVec3f().normalize();
            float eta = (float) rnd.range(0.5, 1.5);
            Vec4f r4 = Vec4f.direction(a3).refract(Vec4f.direction(n3), eta);
            Vec3f r3 = a3.refract(n3, eta);
            close(r4.xyz(), r3, EPS * 10, i);
            close(r4.w(), 0.0, EPS, i);
        }
        // total internal reflection: a grazing ray leaving a dense medium
        Vec4f grazing = new Vec4f(1f, 0.05f, 0f, 0f).normalize();
        check(grazing.refract(new Vec4f(0f, 1f, 0f, 0f), 1.5f).equals(Vec4f.ZERO), 0, "total internal reflection gives zero");
    }

    // ------------------------------------------------------------ Mat3

    @Test
    void mat3FromArrayRoundTripsAndRowIsTheTransposedColumn() {
        for (int i = 0; i < N; i++) {
            Mat3f m = rnd.nextDenseMat3f();
            float[] buf = new float[12];
            m.writeTo(buf, 2);
            check(Mat3f.fromArray(buf, 2).equals(m), i, "writeTo then fromArray must give the same matrix");
            Mat3f t = m.transpose();
            for (int r = 0; r < 3; r++) {
                close(m.row(r), t.column(r), EPS, i);
                for (int c = 0; c < 3; c++) {
                    close(m.row(r).get(c), m.get(c, r), EPS, i);
                }
            }
        }
    }

    // ------------------------------------------------------------ Mat4x3

    @Test
    void mat4x3RotationsMatchTheFullMatrices() {
        for (int i = 0; i < N; i++) {
            float angle = (float) rnd.range(-7, 7);
            Vec3f axis = rnd.nextVec3f().normalize();
            close(Mat4x3f.rotationX(angle).toMat4(), Mat4f.rotationX(angle), EPS, i);
            close(Mat4x3f.rotationY(angle).toMat4(), Mat4f.rotationY(angle), EPS, i);
            close(Mat4x3f.rotationZ(angle).toMat4(), Mat4f.rotationZ(angle), EPS, i);
            close(Mat4x3f.rotationAxis(angle, axis).toMat4(), Mat4f.rotationAxis(angle, axis), EPS, i);
        }
    }

    @Test
    void mat4x3TransformOfAHomogeneousVectorMatchesTheFullMatrix() {
        for (int i = 0; i < N; i++) {
            Mat4f full = rnd.nextTrsMat4f();
            Mat4x3f m = Mat4x3f.fromMat4(full);
            Vec4f v = rnd.nextVec4f();
            close(m.transform(v), full.transform(v), EPS * 10, i);
            Vec3f p = rnd.nextVec3f();
            close(m.transform(Vec4f.point(p)).xyz(), m.transformPosition(p), EPS, i);
            close(m.transform(Vec4f.direction(p)).xyz(), m.transformDirection(p), EPS, i);
            check(m.transform(v).w() == v.w(), i, "w is unchanged by an affine matrix");
        }
    }
}
