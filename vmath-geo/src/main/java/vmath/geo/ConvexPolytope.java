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
 * A solid convex polytope: the extreme points of a point set (from {@link ConvexHull}), its
 * triangular faces with outward normals, and the list of distinct face directions and edge
 * directions that the separating axis test ({@link Sat}) needs.
 *
 * <p>It is a {@link ConvexShape} (the support function scans the vertices, so it suits polytopes of
 * up to a few hundred vertices), and offers an exact point-containment test.
 *
 * <p>Faces that are coplanar (the triangles of one planar facet) share one entry in the list of
 * face normals, and edges between coplanar triangles are left out of the list of edges, so a box
 * has 3 face directions and 3 edge directions however it was triangulated.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads. The arrays returned by the
 * accessors are copies.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ConvexPolytope box = ConvexPolytope.of(Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f)));
 * ConvexPolytope moved = box.transformed(Quatf.rotationZ(0.3f), new Vec3f(1.5f, 0f, 0f));
 * boolean overlapping = Sat.intersects(box, moved);
 * double[] plane = new double[4];
 * box.facetPlane(0, plane);                                           // unit outward normal and offset of the first facet
 * }</pre>
 */
@Experimental("the representation follows what the collision queries need; a half-edge structure may replace the triangle list")
public final class ConvexPolytope implements ConvexShape {

    private final float[] vertices;
    private final int[] triangles;
    private final double[] faceNormals; // distinct unit normals, xyz triples
    private final double[] edgeDirections; // distinct unit edge directions, xyz triples
    private final double volume;
    private final int[] facetStart; // facet f owns the vertices facetVertex[facetStart[f] .. facetStart[f + 1] - 1], counter-clockwise seen from outside
    private final int[] facetVertex;
    private final double[] facetPlane; // 4 doubles per facet: the unit outward normal and d, so that n . x = d on the facet
    private final int[] edgeVertex; // 2 vertex indices per edge, the edges between facets (not the diagonals inside a planar facet)

    /**
     * A copy of {@code source} with its own geometry arrays and the topology (triangles, facets,
     * edges) shared; {@link #place} fills the geometry.
     */
    private ConvexPolytope(ConvexPolytope source) {
        this.vertices = new float[source.vertices.length];
        this.triangles = source.triangles;
        this.faceNormals = new double[source.faceNormals.length];
        this.edgeDirections = new double[source.edgeDirections.length];
        this.volume = source.volume;
        this.facetStart = source.facetStart;
        this.facetVertex = source.facetVertex;
        this.facetPlane = new double[source.facetPlane.length];
        this.edgeVertex = source.edgeVertex;
    }

    private ConvexPolytope(float[] vertices, int[] triangles) {
        this.vertices = vertices;
        this.triangles = triangles;
        Derived d = new Derived(vertices, triangles);
        this.volume = d.volume;
        this.faceNormals = d.faceNormals;
        this.edgeDirections = d.edgeDirections;
        this.facetStart = d.facetStart;
        this.facetVertex = d.facetVertex;
        this.facetPlane = d.facetPlane;
        this.edgeVertex = d.edgeVertex;
    }

    /**
     * Everything that is worked out from the vertices and the triangles when a polytope is made, step by step: the normals and the volume, the
     * distinct edge directions, the facets (the triangles of one plane form one polygon), the order of the vertices around each facet, and the edges
     * between facets.
     */
    private static final class Derived {
        private final float[] vertices;
        private final int[] triangles;
        private final int nf;
        private double[][] faceNormal;
        private final java.util.Map<Long, Integer> faceOf = new java.util.HashMap<>();
        private int[] facetOf;
        private final List<double[]> planes = new ArrayList<>();
        private final List<List<Integer>> facetTriangles = new ArrayList<>();
        double volume;
        double[] faceNormals;
        double[] edgeDirections;
        int[] facetStart;
        int[] facetVertex;
        double[] facetPlane;
        int[] edgeVertex;

        Derived(float[] vertices, int[] triangles) {
            this.vertices = vertices;
            this.triangles = triangles;
            this.nf = triangles.length / 3;
            normalsAndVolume();
            indexDirectedEdges();
            collectEdgeDirections();
            groupFacets();
            orderFacetVertices();
            collectEdges();
        }

        // the unit normal of every triangle, the enclosed volume, and the distinct normals up to sign
        private void normalsAndVolume() {
            List<double[]> normals = new ArrayList<>();
            double vol = 0;
            faceNormal = new double[nf][];
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
            volume = vol;
            faceNormals = flatten(normals);
        }

        // which triangle runs along each directed edge
        private void indexDirectedEdges() {
            for (int f = 0; f < nf; f++) {
                for (int k = 0; k < 3; k++) {
                    faceOf.put(((long) triangles[3 * f + k] << 32) | triangles[3 * f + (k + 1) % 3], f);
                }
            }
        }

        // an edge is listed once, unless the two triangles on it are coplanar; its direction once among parallel edges
        private void collectEdgeDirections() {
            Set<Long> seen = new HashSet<>();
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
            edgeDirections = flatten(dirs);
        }

        // facets: the triangles of one plane form one polygon
        private void groupFacets() {
            facetOf = new int[nf];
            java.util.Arrays.fill(facetOf, -1);
            for (int f = 0; f < nf; f++) {
                if (facetOf[f] >= 0) {
                    continue;
                }
                double d = faceNormal[f][0] * vertices[3 * triangles[3 * f]] + faceNormal[f][1] * vertices[3 * triangles[3 * f] + 1] + faceNormal[f][2] * vertices[3 * triangles[3 * f] + 2];
                int id = planes.size();
                planes.add(new double[] {faceNormal[f][0], faceNormal[f][1], faceNormal[f][2], d});
                List<Integer> members = new ArrayList<>();
                for (int g = f; g < nf; g++) {
                    if (facetOf[g] >= 0 || faceNormal[g][0] * faceNormal[f][0] + faceNormal[g][1] * faceNormal[f][1] + faceNormal[g][2] * faceNormal[f][2] < 1 - 1e-10) {
                        continue;
                    }
                    double dg = faceNormal[f][0] * vertices[3 * triangles[3 * g]] + faceNormal[f][1] * vertices[3 * triangles[3 * g] + 1] + faceNormal[f][2] * vertices[3 * triangles[3 * g] + 2];
                    if (Math.abs(dg - d) <= 1e-5 * (1 + Math.abs(d))) {
                        facetOf[g] = id;
                        members.add(g);
                    }
                }
                facetTriangles.add(members);
            }
        }

        // the vertices of each facet, ordered counter-clockwise about the normal: by the angle in a basis of the plane around the centroid
        private void orderFacetVertices() {
            int facets = planes.size();
            facetStart = new int[facets + 1];
            facetPlane = new double[4 * facets];
            List<Integer> ordered = new ArrayList<>();
            for (int f = 0; f < facets; f++) {
                double[] pl = planes.get(f);
                System.arraycopy(pl, 0, facetPlane, 4 * f, 4);
                Set<Integer> verts = new java.util.LinkedHashSet<>();
                for (int g : facetTriangles.get(f)) {
                    verts.add(triangles[3 * g]);
                    verts.add(triangles[3 * g + 1]);
                    verts.add(triangles[3 * g + 2]);
                }
                double cx = 0, cy = 0, cz = 0;
                for (int v : verts) {
                    cx += vertices[3 * v];
                    cy += vertices[3 * v + 1];
                    cz += vertices[3 * v + 2];
                }
                cx /= verts.size();
                cy /= verts.size();
                cz /= verts.size();
                double ax = Math.abs(pl[0]) < 0.9 ? 1 : 0, ay = Math.abs(pl[0]) < 0.9 ? 0 : 1;
                double ux = pl[1] * 0 - pl[2] * ay, uy = pl[2] * ax - pl[0] * 0, uz = pl[0] * ay - pl[1] * ax; // a x n ... any vector in the plane
                double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
                ux /= ul;
                uy /= ul;
                uz /= ul;
                double vx = pl[1] * uz - pl[2] * uy, vy = pl[2] * ux - pl[0] * uz, vz = pl[0] * uy - pl[1] * ux; // n x u
                List<Integer> list = new ArrayList<>(verts);
                final double fcx = cx, fcy = cy, fcz = cz, fux = ux, fuy = uy, fuz = uz, fvx = vx, fvy = vy, fvz = vz;
                list.sort(java.util.Comparator.comparingDouble(v -> {
                    double dx = vertices[3 * v] - fcx, dy = vertices[3 * v + 1] - fcy, dz = vertices[3 * v + 2] - fcz;
                    return Math.atan2(dx * fvx + dy * fvy + dz * fvz, dx * fux + dy * fuy + dz * fuz);
                }));
                ordered.addAll(list);
                facetStart[f + 1] = ordered.size();
            }
            facetVertex = new int[ordered.size()];
            for (int i = 0; i < facetVertex.length; i++) {
                facetVertex[i] = ordered.get(i);
            }
        }

        // edges between facets: an edge of a triangle whose neighbour triangle belongs to another facet
        private void collectEdges() {
            List<int[]> edgeList = new ArrayList<>();
            Set<Long> edgesSeen = new HashSet<>();
            for (int f = 0; f < nf; f++) {
                for (int k = 0; k < 3; k++) {
                    int u = triangles[3 * f + k], v = triangles[3 * f + (k + 1) % 3];
                    Integer g = faceOf.get(((long) v << 32) | u);
                    long key = ((long) Math.min(u, v) << 32) | Math.max(u, v);
                    if (g != null && facetOf[g] != facetOf[f] && edgesSeen.add(key)) {
                        edgeList.add(new int[] {u, v});
                    }
                }
            }
            edgeVertex = new int[2 * edgeList.size()];
            for (int i = 0; i < edgeList.size(); i++) {
                edgeVertex[2 * i] = edgeList.get(i)[0];
                edgeVertex[2 * i + 1] = edgeList.get(i)[1];
            }
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
    }

    /**
     * Builds a polytope from a point cloud by taking its convex hull and extracting the facets and
     * edges that collision queries need; an offline step, not meant for every frame.
     *
     * <p>The vertices are the extreme points only; the indices of the faces refer to
     * {@link #vertices()}. {@link IllegalArgumentException} when the points do not span space (all
     * coplanar, collinear or equal): such a set has no volume.
     *
     * @param xyz the three components
     * @param count the number of elements
     * @return the polytope that is the convex hull of {@code count} points ({@code x, y, z}
     *     triples)
     * @throws IllegalArgumentException if the points do not span three dimensions
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

    /**
     * Builds the polytope of an axis-aligned box.
     *
     * @param box the box; must not be {@code null}
     * @return the box as a polytope: 8 vertices, 12 triangles
     */
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

    /**
     * Applies a rigid transform to the polytope without changing its topology, so face and vertex
     * indices stay valid; the rotation must be a unit quaternion.
     *
     * @param rotation the rotation; must not be {@code null}
     * @param translation the translation; must not be {@code null}
     * @return the polytope rotated by {@code rotation} (a unit quaternion) about the origin and
     *     then moved by {@code translation}; the faces keep their indices
     */
    public ConvexPolytope transformed(Quatf rotation, Vec3f translation) {
        ConvexPolytope moved = new ConvexPolytope(this);
        place(moved, rotation.x(), rotation.y(), rotation.z(), rotation.w(), translation.x(), translation.y(), translation.z());
        return moved;
    }

    /**
     * Applies a rigid transform to the polytope like {@link #transformed(Quatf, Vec3f)}, but into
     * the arrays of {@code target}, which an earlier {@code transformed} of this polytope made, so
     * that a body that moves every step allocates nothing.
     *
     * <p>The quaternion is normalised here (a rotation that is a few parts in 10^8 off unit length,
     * as one of {@code float} components is, still gives unit normals); the numbers are those of
     * the polytope made by {@code transformed} from the same rotation and translation (the same
     * arithmetic in {@code double}, rounded to {@code float} for the vertices). The
     * topology is shared, so the facets, edges and triangles keep their indices.
     *
     * @param qx the x component of the quaternion of the rotation
     * @param qy the y component
     * @param qz the z component
     * @param qw the w component
     * @param tx the x component of the translation
     * @param ty the y component of the translation
     * @param tz the z component of the translation
     * @param target the polytope that receives the result; must have been made by
     *     {@link #transformed(Quatf, Vec3f)} or {@link #copyTopology()} of this polytope, and is
     *     changed
     * @return {@code target}
     * @throws IllegalArgumentException if {@code target} has not the topology of this polytope
     */
    public ConvexPolytope transformInto(double qx, double qy, double qz, double qw, double tx, double ty, double tz, ConvexPolytope target) {
        if (target.triangles != triangles || target.vertices.length != vertices.length) {
            throw new IllegalArgumentException("the target was not made from this polytope");
        }
        place(target, qx, qy, qz, qw, tx, ty, tz);
        return target;
    }

    /**
     * Makes a polytope with the topology of this one and the same vertices, to be moved with
     * {@link #transformInto} and read afterwards.
     *
     * @return a new polytope that shares the topology of this one; its geometry is a copy of this one's
     */
    public ConvexPolytope copyTopology() {
        ConvexPolytope copy = new ConvexPolytope(this);
        place(copy, 0, 0, 0, 1, 0, 0, 0);
        return copy;
    }

    /**
     * Writes the geometry of this polytope rotated by the unit quaternion and moved by the
     * translation into {@code to}: the vertices, the facet planes, the face and edge directions.
     * The plane offsets are taken from the rounded vertices, as the constructor does.
     */
    private void place(ConvexPolytope to, double qx, double qy, double qz, double qw, double tx, double ty, double tz) {
        double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw); // a quaternion of float components is a few 1e-8 off unit length, which would show in the unit normals
        qx /= norm;
        qy /= norm;
        qz /= norm;
        qw /= norm;
        double r00 = 1 - 2 * (qy * qy + qz * qz), r01 = 2 * (qx * qy - qz * qw), r02 = 2 * (qx * qz + qy * qw);
        double r10 = 2 * (qx * qy + qz * qw), r11 = 1 - 2 * (qx * qx + qz * qz), r12 = 2 * (qy * qz - qx * qw);
        double r20 = 2 * (qx * qz - qy * qw), r21 = 2 * (qy * qz + qx * qw), r22 = 1 - 2 * (qx * qx + qy * qy);
        float[] v = to.vertices;
        for (int i = 0; i < vertices.length; i += 3) {
            double x = vertices[i], y = vertices[i + 1], z = vertices[i + 2];
            v[i] = (float) (r00 * x + r01 * y + r02 * z + tx);
            v[i + 1] = (float) (r10 * x + r11 * y + r12 * z + ty);
            v[i + 2] = (float) (r20 * x + r21 * y + r22 * z + tz);
        }
        for (int i = 0; i < faceNormals.length; i += 3) {
            rotate(faceNormals, i, to.faceNormals, r00, r01, r02, r10, r11, r12, r20, r21, r22);
        }
        for (int i = 0; i < edgeDirections.length; i += 3) {
            rotate(edgeDirections, i, to.edgeDirections, r00, r01, r02, r10, r11, r12, r20, r21, r22);
        }
        for (int f = 0; f < facetPlane.length / 4; f++) {
            rotate(facetPlane, 4 * f, to.facetPlane, r00, r01, r02, r10, r11, r12, r20, r21, r22);
            int first = 3 * facetVertex[facetStart[f]];
            to.facetPlane[4 * f + 3] = to.facetPlane[4 * f] * v[first] + to.facetPlane[4 * f + 1] * v[first + 1] + to.facetPlane[4 * f + 2] * v[first + 2];
        }
    }

    private static void rotate(double[] from, int at, double[] to, double r00, double r01, double r02, double r10, double r11, double r12, double r20, double r21, double r22) {
        double x = from[at], y = from[at + 1], z = from[at + 2];
        to[at] = r00 * x + r01 * y + r02 * z;
        to[at + 1] = r10 * x + r11 * y + r12 * z;
        to[at + 2] = r20 * x + r21 * y + r22 * z;
    }

    /**
     * Counts the vertices of the polytope.
     *
     * @return the number of vertices
     */
    public int vertexCount() {
        return vertices.length / 3;
    }

    /**
     * Reads one coordinate of a vertex directly from the stored array, which avoids the copy that
     * {@link #vertices()} makes.
     *
     * @param i the index
     * @param axis the axis
     * @return the coordinate {@code axis} (0 is x, 1 is y, 2 is z) of vertex {@code i}, without
     *     copying the vertex array as {@link #vertices()} does
     */
    public float vertex(int i, int axis) {
        return vertices[3 * i + axis];
    }

    /**
     * Exposes the vertex coordinates; this is a copy, so use {@link #vertex(int, int)} in loops.
     *
     * @return the vertices as {@code x, y, z} triples
     */
    public float[] vertices() {
        return vertices.clone();
    }

    /**
     * Lists the surface triangles as indices into the vertices, wound counter-clockwise seen from
     * outside.
     *
     * @return the faces as vertex index triples, counter-clockwise seen from outside
     */
    public int[] triangles() {
        return triangles.clone();
    }

    /**
     * Exposes the enclosed volume, computed once at construction.
     *
     * @return the volume
     */
    public double volume() {
        return volume;
    }

    /**
     * Counts the distinct face directions, which are the candidate axes of the separating axis
     * test; coplanar triangles and opposite faces are merged, so this is smaller than the triangle
     * count.
     *
     * @return the number of distinct face directions, counting a direction and its opposite once
     *     (the axes of the separating axis test): 3 for a box, however its facets are triangulated
     */
    public int faceDirectionCount() {
        return faceNormals.length / 3;
    }

    /**
     * Lists the distinct face normals, one per pair of opposite directions, as the face axes for a
     * separating axis test.
     *
     * @return the distinct unit face normals, up to sign (one of each pair of opposite directions),
     *     as {@code x, y, z} triples
     */
    public double[] faceDirections() {
        return faceNormals.clone();
    }

    /**
     * Lists the distinct edge directions that are not inside a planar facet, as the edge axes for a
     * separating axis test; this list can be long for round shapes.
     *
     * @return the distinct unit edge directions that are not inside a planar facet, as
     *     {@code x, y, z} triples (the edge axes of the separating axis test)
     */
    public double[] edgeDirections() {
        return edgeDirections.clone();
    }

    /**
     * Counts the planar facets of the surface, where coplanar triangles form one facet.
     *
     * @return the number of facets: the planar polygons of the surface (the triangles in one plane
     *     form one facet, so a box has 6)
     */
    public int facetCount() {
        return facetStart.length - 1;
    }

    /**
     * Writes the plane of facet {@code f} to {@code out[0 .. 4)}: the unit outward normal and
     * {@code d}, with {@code n . x = d} on the facet and {@code n . x < d} inside the polytope.
     *
     * @param f the facet index
     * @param out receives the result
     */
    public void facetPlane(int f, double[] out) {
        System.arraycopy(facetPlane, 4 * f, out, 0, 4);
    }

    /**
     * Counts the vertices of one facet.
     *
     * @param f the facet index
     * @return the number of vertices of facet {@code f}
     */
    public int facetVertexCount(int f) {
        return facetStart[f + 1] - facetStart[f];
    }

    /**
     * Looks up a vertex of a facet by position in the facet's boundary loop, which runs
     * counter-clockwise seen from outside.
     *
     * @param f the facet index
     * @param k the position of the vertex within the facet
     * @return the index (into {@link #vertices()}) of the {@code k}-th vertex of facet {@code f};
     *     the vertices run counter-clockwise seen from outside
     */
    public int facetVertex(int f, int k) {
        return facetVertex[facetStart[f] + k];
    }

    /**
     * Counts the edges between facets, which excludes the diagonals of triangulated facets.
     *
     * @return the number of edges between facets (the edges of the surface; not the diagonals of a
     *     triangulated facet)
     */
    public int edgeCount() {
        return edgeVertex.length / 2;
    }

    /**
     * Looks up the first end point of an edge.
     *
     * @param e the edge index
     * @return the index of the first vertex of edge {@code e}
     */
    public int edgeStart(int e) {
        return edgeVertex[2 * e];
    }

    /**
     * Looks up the second end point of an edge.
     *
     * @param e the edge index
     * @return the index of the second vertex of edge {@code e}
     */
    public int edgeEnd(int e) {
        return edgeVertex[2 * e + 1];
    }

    /**
     * Returns whether the point is inside the polytope or on its surface.
     *
     * <p>Exact: no face of the polytope may have the point above it ({@link Predicates#orient3d}).
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return {@code true} if the point is inside the polytope or on its surface
     */
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
