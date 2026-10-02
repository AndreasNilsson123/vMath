package vmath.geo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import vmath.annotations.Experimental;
import vmath.core.Predicates;
import vmath.core.Quatf;
import vmath.core.Vec3f;

/**
 * A solid convex polytope: the extreme points of a point set (from {@link ConvexHull}), its triangular faces with outward normals, and the list of distinct face directions and
 * edge directions that the separating axis test ({@link Sat}) needs. It is a {@link ConvexShape} (the support function scans the vertices, so it suits polytopes of up to a
 * few hundred vertices), and offers an exact point-containment test.
 *
 * <p>Faces that are coplanar (the triangles of one planar facet) share one entry in the list of face normals, and edges between coplanar triangles are left out of the list of edges,
 * so a box has 3 face directions and 3 edge directions however it was triangulated.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads. The arrays returned by the accessors are copies.
 */
@Experimental("the representation follows what the collision queries need; a half-edge structure may replace the triangle list")
public final class ConvexPolytope implements ConvexShape {

    private final float[] vertices;
    private final int[] triangles;
    private final double[] faceNormals; // distinct unit normals, xyz triples
    private final double[] edgeDirections; // distinct unit edge directions, xyz triples
    private final double volume;

    private ConvexPolytope(float[] vertices, int[] triangles) {
        this.vertices = vertices;
        this.triangles = triangles;
        List<double[]> normals = new ArrayList<>();
        double vol = 0;
        int nf = triangles.length / 3;
        double[][] faceNormal = new double[nf][];
        for (int f = 0; f < nf; f++) {
            int a = triangles[3 * f], b = triangles[3 * f + 1], c = triangles[3 * f + 2];
            double[] n = cross(a, b, c);
            double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            faceNormal[f] = new double[] {n[0] / len, n[1] / len, n[2] / len};
            vol += (n[0] * vertices[3 * a] + n[1] * vertices[3 * a + 1] + n[2] * vertices[3 * a + 2]) / 6.0;
            boolean known = false;
            for (double[] m : normals) {
                known |= Math.abs(m[0] * faceNormal[f][0] + m[1] * faceNormal[f][1] + m[2] * faceNormal[f][2]) > 1 - 1e-12; // an axis: up to sign
            }
            if (!known) {
                normals.add(faceNormal[f]);
            }
        }
        this.volume = vol;
        this.faceNormals = flatten(normals);
        // an edge is listed once, unless the two triangles on it are coplanar; its direction once among parallel edges
        Set<Long> seen = new HashSet<>();
        java.util.Map<Long, Integer> faceOf = new java.util.HashMap<>();
        for (int f = 0; f < nf; f++) {
            for (int k = 0; k < 3; k++) {
                faceOf.put(((long) triangles[3 * f + k] << 32) | triangles[3 * f + (k + 1) % 3], f);
            }
        }
        List<double[]> dirs = new ArrayList<>();
        for (int f = 0; f < nf; f++) {
            for (int k = 0; k < 3; k++) {
                int u = triangles[3 * f + k], v = triangles[3 * f + (k + 1) % 3];
                long key = ((long) Math.min(u, v) << 32) | Math.max(u, v);
                if (!seen.add(key)) {
                    continue;
                }
                Integer g = faceOf.get(((long) v << 32) | u);
                if (g != null && faceNormal[g][0] * faceNormal[f][0] + faceNormal[g][1] * faceNormal[f][1] + faceNormal[g][2] * faceNormal[f][2] > 1 - 1e-12) {
                    continue; // inside a planar facet
                }
                double ex = vertices[3 * v] - vertices[3 * u], ey = vertices[3 * v + 1] - vertices[3 * u + 1], ez = vertices[3 * v + 2] - vertices[3 * u + 2];
                double len = Math.sqrt(ex * ex + ey * ey + ez * ez);
                double[] d = {ex / len, ey / len, ez / len};
                boolean known = false;
                for (double[] m : dirs) {
                    known |= Math.abs(m[0] * d[0] + m[1] * d[1] + m[2] * d[2]) > 1 - 1e-12;
                }
                if (!known) {
                    dirs.add(d);
                }
            }
        }
        this.edgeDirections = flatten(dirs);
    }

    private double[] cross(int a, int b, int c) {
        double ux = (double) vertices[3 * b] - vertices[3 * a], uy = (double) vertices[3 * b + 1] - vertices[3 * a + 1], uz = (double) vertices[3 * b + 2] - vertices[3 * a + 2];
        double vx = (double) vertices[3 * c] - vertices[3 * a], vy = (double) vertices[3 * c + 1] - vertices[3 * a + 1], vz = (double) vertices[3 * c + 2] - vertices[3 * a + 2];
        return new double[] {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
    }

    private static double[] flatten(List<double[]> list) {
        double[] a = new double[3 * list.size()];
        for (int i = 0; i < list.size(); i++) {
            System.arraycopy(list.get(i), 0, a, 3 * i, 3);
        }
        return a;
    }

    /**
     * The polytope that is the convex hull of {@code count} points ({@code x, y, z} triples). The vertices are the extreme points only; the indices of the faces refer to
     * {@link #vertices()}. {@link IllegalArgumentException} when the points do not span space (all coplanar, collinear or equal): such a set has no volume.
     */
    public static ConvexPolytope of(float[] xyz, int count) {
        ConvexHull hull = ConvexHull.of(xyz, count);
        if (hull.dimension() != 3) {
            throw new IllegalArgumentException("the points span only " + hull.dimension() + " dimensions; a polytope needs 3");
        }
        int[] verts = hull.vertices();
        int[] remap = new int[count];
        java.util.Arrays.fill(remap, -1);
        float[] v = new float[3 * verts.length];
        for (int i = 0; i < verts.length; i++) {
            remap[verts[i]] = i;
            System.arraycopy(xyz, 3 * verts[i], v, 3 * i, 3);
        }
        int[] t = hull.triangles();
        for (int i = 0; i < t.length; i++) {
            t[i] = remap[t[i]];
        }
        return new ConvexPolytope(v, t);
    }

    /** The box as a polytope: 8 vertices, 12 triangles. */
    public static ConvexPolytope of(Aabbf box) {
        float[] c = new float[24];
        for (int i = 0; i < 8; i++) {
            Vec3f p = box.corner(i);
            c[3 * i] = p.x();
            c[3 * i + 1] = p.y();
            c[3 * i + 2] = p.z();
        }
        return of(c, 8);
    }

    /** The polytope rotated by {@code rotation} (a unit quaternion) about the origin and then moved by {@code translation}; the faces keep their indices. */
    public ConvexPolytope transformed(Quatf rotation, Vec3f translation) {
        float[] v = new float[vertices.length];
        for (int i = 0; i < vertices.length / 3; i++) {
            Vec3f p = rotation.transform(new Vec3f(vertices[3 * i], vertices[3 * i + 1], vertices[3 * i + 2])).add(translation);
            v[3 * i] = p.x();
            v[3 * i + 1] = p.y();
            v[3 * i + 2] = p.z();
        }
        return new ConvexPolytope(v, triangles.clone());
    }

    /** The number of vertices. */
    public int vertexCount() {
        return vertices.length / 3;
    }

    /** The vertices as {@code x, y, z} triples. */
    public float[] vertices() {
        return vertices.clone();
    }

    /** The faces as vertex index triples, counter-clockwise seen from outside. */
    public int[] triangles() {
        return triangles.clone();
    }

    /** The volume. */
    public double volume() {
        return volume;
    }

    /** The number of distinct face directions, counting a direction and its opposite once (the axes of the separating axis test): 3 for a box, however its facets are triangulated. */
    public int faceDirectionCount() {
        return faceNormals.length / 3;
    }

    /** The distinct unit face normals, up to sign (one of each pair of opposite directions), as {@code x, y, z} triples. */
    public double[] faceDirections() {
        return faceNormals.clone();
    }

    /** The distinct unit edge directions that are not inside a planar facet, as {@code x, y, z} triples (the edge axes of the separating axis test). */
    public double[] edgeDirections() {
        return edgeDirections.clone();
    }

    /** Whether the point is inside the polytope or on its surface. Exact: no face of the polytope may have the point above it ({@link Predicates#orient3d}). */
    public boolean contains(float x, float y, float z) {
        for (int f = 0; f < triangles.length / 3; f++) {
            int a = triangles[3 * f], b = triangles[3 * f + 1], c = triangles[3 * f + 2];
            double o = Predicates.orient3d(vertices[3 * a], vertices[3 * a + 1], vertices[3 * a + 2], vertices[3 * b], vertices[3 * b + 1], vertices[3 * b + 2],
                    vertices[3 * c], vertices[3 * c + 1], vertices[3 * c + 2], x, y, z);
            if (o < 0.0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void support(double dx, double dy, double dz, double[] out) {
        double best = Double.NEGATIVE_INFINITY;
        int at = 0;
        for (int i = 0; i < vertices.length; i += 3) {
            double d = vertices[i] * dx + vertices[i + 1] * dy + vertices[i + 2] * dz;
            if (d > best) {
                best = d;
                at = i;
            }
        }
        out[0] = vertices[at];
        out[1] = vertices[at + 1];
        out[2] = vertices[at + 2];
    }
}
