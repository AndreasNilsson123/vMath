package vmath.geo;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * A triangle by its three vertices; counter-clockwise winding faces {@link #normal()}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Trianglef t = Trianglef.of(new Vec3f(0f, 0f, 0f), new Vec3f(1f, 0f, 0f), new Vec3f(0f, 1f, 0f));
 * float area = t.area();                                        // 0.5
 * Vec3f normal = t.normal();                                    // (0, 0, 1), counter-clockwise seen from +z
 * Vec3f weights = t.barycentric(new Vec3f(0.25f, 0.25f, 0f));
 * }</pre>
 *
 * @param ax the x coordinate of the first vertex
 * @param ay the y coordinate of the first vertex
 * @param az the z coordinate of the first vertex
 * @param bx the x coordinate of the second vertex
 * @param by the y coordinate of the second vertex
 * @param bz the z coordinate of the second vertex
 * @param cx the x coordinate of the third vertex
 * @param cy the y coordinate of the third vertex
 * @param cz the z coordinate of the third vertex
 */
@GenerateDouble
@ValueType
public record Trianglef(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz) {

    /**
     * Builds a triangle from its three vertices.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param c the vector; must not be {@code null}
     * @return the triangle with the given vertices
     */
    public static Trianglef of(Vec3f a, Vec3f b, Vec3f c) {
        return new Trianglef(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), c.x(), c.y(), c.z());
    }

    /**
     * Exposes the first vertex.
     *
     * @return the first vertex
     */
    public Vec3f a() {
        return new Vec3f(ax, ay, az);
    }

    /**
     * Exposes the second vertex.
     *
     * @return the second vertex
     */
    public Vec3f b() {
        return new Vec3f(bx, by, bz);
    }

    /**
     * Exposes the third vertex.
     *
     * @return the third vertex
     */
    public Vec3f c() {
        return new Vec3f(cx, cy, cz);
    }

    /**
     * Transforms the three vertices; a matrix that mirrors flips the winding, and with it the
     * direction of the normal.
     *
     * @param m the matrix; must not be {@code null}
     * @return the triangle after transforming space by the affine matrix {@code m} (the vertices
     *     are transformed; a mirroring {@code m} flips the winding)
     */
    public Trianglef transform(Mat4f m) {
        return of(m.transformPosition(a()), m.transformPosition(b()), m.transformPosition(c()));
    }

    /**
     * Computes the face normal from the cross product of two edges, without normalising it, so that
     * its length carries the area; a degenerate triangle gives the zero vector.
     *
     * @return unnormalized normal {@code (b - a) x (c - a)}; its length is twice the area
     */
    public Vec3f normal() {
        return b().sub(a()).cross(c().sub(a()));
    }

    /**
     * Computes the area from the length of the cross product of two edges.
     *
     * @return the area: half the length of {@link #normal()}
     */
    public float area() {
        return normal().length() * 0.5f;
    }

    /**
     * Computes the centroid as the mean of the vertices.
     *
     * @return the centroid: the mean of the three vertices
     */
    public Vec3f centroid() {
        return new Vec3f((ax + bx + cx) / 3f, (ay + by + cy) / 3f, (az + bz + cz) / 3f);
    }

    /**
     * Computes the axis-aligned box around the triangle.
     *
     * @return the smallest axis-aligned box that contains the triangle
     */
    public Aabbf aabb() {
        return new Aabbf(
                Math.min(ax, Math.min(bx, cx)), Math.min(ay, Math.min(by, cy)), Math.min(az, Math.min(bz, cz)),
                Math.max(ax, Math.max(bx, cx)), Math.max(ay, Math.max(by, cy)), Math.max(az, Math.max(bz, cz)));
    }

    /**
     * Computes the barycentric coordinates of a point's projection onto the triangle's plane, by
     * the method of dot products from Ericson; a degenerate triangle gives non-finite values.
     *
     * <p>Degenerate triangles yield NaN.
     *
     * @param p the vector; must not be {@code null}
     * @return barycentric weights {@code (u, v, w)} of the projection of {@code p} onto the
     *     triangle's plane, so that {@code p ~ u a + v b + w c} and {@code u + v + w = 1}
     */
    public Vec3f barycentric(Vec3f p) {
        Vec3f v0 = b().sub(a());
        Vec3f v1 = c().sub(a());
        Vec3f v2 = p.sub(a());
        float d00 = v0.dot(v0), d01 = v0.dot(v1), d11 = v1.dot(v1);
        float d20 = v2.dot(v0), d21 = v2.dot(v1);
        float inv = 1f / (d00 * d11 - d01 * d01);
        float v = (d11 * d20 - d01 * d21) * inv;
        float w = (d00 * d21 - d01 * d20) * inv;
        return new Vec3f(1f - v - w, v, w);
    }

    /**
     * Finds the nearest point of the triangle by classifying the point against the vertex, edge and
     * face regions, after Ericson.
     *
     * @param p the vector; must not be {@code null}
     * @return the point of the triangle nearest to {@code p} (Ericson, Real-Time Collision
     *     Detection 5.1.5)
     */
    public Vec3f closestPoint(Vec3f p) {
        Vec3f a = a();
        Vec3f ab = b().sub(a);
        Vec3f ac = c().sub(a);
        Vec3f ap = p.sub(a);
        float d1 = ab.dot(ap), d2 = ac.dot(ap);
        if (d1 <= 0f && d2 <= 0f) {
            return a;
        }
        Vec3f bp = p.sub(b());
        float d3 = ab.dot(bp), d4 = ac.dot(bp);
        if (d3 >= 0f && d4 <= d3) {
            return b();
        }
        float vc = d1 * d4 - d3 * d2;
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
            return a.fma(ab, d1 / (d1 - d3));
        }
        Vec3f cp = p.sub(c());
        float d5 = ab.dot(cp), d6 = ac.dot(cp);
        if (d6 >= 0f && d5 <= d6) {
            return c();
        }
        float vb = d5 * d2 - d1 * d6;
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
            return a.fma(ac, d2 / (d2 - d6));
        }
        float va = d3 * d6 - d5 * d4;
        if (va <= 0f && d4 - d3 >= 0f && d5 - d6 >= 0f) {
            float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return b().fma(c().sub(b()), w);
        }
        float denom = 1f / (va + vb + vc);
        return a.fma(ab, vb * denom).fma(ac, vc * denom);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same triangle with double-precision components
     */
    @FloatOnly
    public Triangled toDouble() {
        return new Triangled(ax, ay, az, bx, by, bz, cx, cy, cz);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same triangle with float components, each rounded to the nearest float
     */
    @DoubleOnly
    public Trianglef toFloat() {
        return new Trianglef((float) ax, (float) ay, (float) az, (float) bx, (float) by, (float) bz, (float) cx, (float) cy, (float) cz);
    }
}
