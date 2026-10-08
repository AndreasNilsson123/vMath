package vmath.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import vmath.annotations.Experimental;
import vmath.geo.Polygons;

/**
 * The filled areas of a map layer: polygons with holes in projected metres (double precision),
 * each triangulated when it is added and each with a style from a table.
 *
 * <p>The triangulation is done in a frame local to the polygon, so a polygon far from the origin of
 * the projection (a UTM zone is millions of metres wide) is not damaged by single precision; the
 * vertices stay in double precision and are made relative to the origin of the frame by
 * {@link AreaRenderPlan#write}. Polygons are drawn in the order they were added, and consecutive
 * polygons of the same style are one draw. A ring must be simple and the holes inside the outer
 * ring and apart; a polygon the triangulation cannot handle is refused when it is added.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use one batch per thread or lock around it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AreaBatch batch = new AreaBatch();
 * int water = batch.style(AreaStyle.solid(0x4080C0FF));
 * double[] ring = {0, 0, 1000, 0, 1000, 800, 0, 800};
 * batch.addPolygon(ring, 4, water);
 * MapShapes shapes = new MapShapes(view, 0.5);
 * shapes.disc(lat, lon, 5000.0, batch.sink(water));      // a disc as an area
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class AreaBatch {

    private final List<AreaStyle> styles = new ArrayList<>();
    private double[] vertices = new double[64];
    private int vertexCount;
    private int[] polygonFirstVertex = new int[8];
    private int[] polygonFirstIndex = new int[8];
    private int[] polygonStyle = new int[8];
    private int[] indices = new int[96];
    private int indexCount;
    private int polygons;

    /** Makes an empty batch. */
    public AreaBatch() {
    }

    /**
     * Registers a style, or finds it if an equal one is registered.
     *
     * @param style the style; must not be {@code null}
     * @return its index in the style table
     * @throws IllegalArgumentException if the style is {@code null}
     */
    public int style(AreaStyle style) {
        if (style == null) {
            throw new IllegalArgumentException("the style must not be null");
        }
        int i = styles.indexOf(style);
        if (i >= 0) {
            return i;
        }
        styles.add(style);
        return styles.size() - 1;
    }

    /**
     * Gives the number of styles.
     *
     * @return the count
     */
    public int styleCount() {
        return styles.size();
    }

    /**
     * Gives a style.
     *
     * @param index the index from {@link #style}
     * @return the style
     * @throws IndexOutOfBoundsException if out of range
     */
    public AreaStyle styleAt(int index) {
        return styles.get(index);
    }

    /**
     * Adds a polygon of one ring.
     *
     * @param xy the vertices as {@code x, y} pairs in projected metres, either winding
     * @param pointCount the number of vertices, at least 3
     * @param style the style index from {@link #style}
     * @return the index of the polygon
     * @throws IllegalArgumentException if the ring cannot be triangulated (fewer than three vertices, self-intersecting, no area), a value is not finite or the style is unknown
     */
    public int addPolygon(double[] xy, int pointCount, int style) {
        return addPolygon(xy, new int[] {pointCount}, style);
    }

    /**
     * Adds a polygon with holes.
     *
     * @param xy the vertices of all rings one after the other as {@code x, y} pairs
     * @param ringEnds the vertex index at which each ring ends: ring 0, the outer boundary, is vertices {@code 0 .. ringEnds[0] - 1}; at least one entry
     * @param style the style index from {@link #style}
     * @return the index of the polygon
     * @throws IllegalArgumentException if the polygon cannot be triangulated, a value is not finite, the rings are inconsistent with {@code xy} or the style is unknown
     */
    public int addPolygon(double[] xy, int[] ringEnds, int style) {
        if (style < 0 || style >= styles.size()) {
            throw new IllegalArgumentException("unknown style " + style);
        }
        int total = ringEnds.length == 0 ? 0 : ringEnds[ringEnds.length - 1];
        if (total < 3 || 2L * total > xy.length) {
            throw new IllegalArgumentException("need at least 3 vertices in the array: " + total);
        }
        int previous = 0;
        for (int e : ringEnds) {
            if (e - previous < 3) {
                throw new IllegalArgumentException("every ring needs at least 3 vertices: " + Arrays.toString(ringEnds));
            }
            previous = e;
        }
        double ox = xy[0], oy = xy[1];
        float[] local = new float[2 * total];
        for (int i = 0; i < 2 * total; i += 2) {
            if (!Double.isFinite(xy[i]) || !Double.isFinite(xy[i + 1])) {
                throw new IllegalArgumentException("the vertices must be finite");
            }
            local[i] = (float) (xy[i] - ox);
            local[i + 1] = (float) (xy[i + 1] - oy);
        }
        int ringStart = 0;
        for (int e : ringEnds) {
            if (!Polygons.isSimple(Arrays.copyOfRange(local, 2 * ringStart, 2 * e), e - ringStart)) {
                throw new IllegalArgumentException("a ring crosses itself: vertices " + ringStart + " to " + (e - 1));
            }
            ringStart = e;
        }
        int[] tri = new int[3 * (total + 2 * (ringEnds.length - 1))];
        int n = Polygons.triangulate(local, ringEnds, tri);
        if (n < 1) {
            throw new IllegalArgumentException("the polygon cannot be triangulated: it must be simple, with holes inside it and apart");
        }
        if (polygons == polygonStyle.length) {
            polygonFirstVertex = Arrays.copyOf(polygonFirstVertex, polygons * 2);
            polygonFirstIndex = Arrays.copyOf(polygonFirstIndex, polygons * 2);
            polygonStyle = Arrays.copyOf(polygonStyle, polygons * 2);
        }
        if (2 * (vertexCount + total) > vertices.length) {
            vertices = Arrays.copyOf(vertices, Math.max(vertices.length * 2, 2 * (vertexCount + total)));
        }
        if (indexCount + 3 * n > indices.length) {
            indices = Arrays.copyOf(indices, Math.max(indices.length * 2, indexCount + 3 * n));
        }
        int p = polygons++;
        polygonFirstVertex[p] = vertexCount;
        polygonFirstIndex[p] = indexCount;
        polygonStyle[p] = style;
        System.arraycopy(xy, 0, vertices, 2 * vertexCount, 2 * total);
        for (int i = 0; i < 3 * n; i++) {
            indices[indexCount + i] = vertexCount + tri[i];
        }
        vertexCount += total;
        indexCount += 3 * n;
        return p;
    }

    /**
     * Makes a receiver for {@link MapShapes} that adds the filled shapes (circles, sectors, corridors)
     * as polygons of a style and ignores the lines.
     *
     * @param style the style index from {@link #style}
     * @return the sink
     * @throws IllegalArgumentException if the style is unknown
     */
    public MapShapes.Sink sink(int style) {
        if (style < 0 || style >= styles.size()) {
            throw new IllegalArgumentException("unknown style " + style);
        }
        return new MapShapes.Sink() {
            @Override
            public void line(double[] xy, int pointCount, boolean closed) {
                // outlines are lines: draw them with the line layer
            }

            @Override
            public void area(double[] xy, int pointCount) {
                addPolygon(xy, pointCount, style);
            }
        };
    }

    /** Removes all polygons and styles. */
    public void clear() {
        styles.clear();
        vertexCount = 0;
        indexCount = 0;
        polygons = 0;
    }

    /**
     * Gives the number of polygons.
     *
     * @return the count
     */
    public int polygonCount() {
        return polygons;
    }

    /**
     * Gives the number of vertices of all polygons.
     *
     * @return the count
     */
    public int vertexCount() {
        return vertexCount;
    }

    /**
     * Gives the number of triangles of all polygons.
     *
     * @return the count
     */
    public int triangleCount() {
        return indexCount / 3;
    }

    private void check(int polygon) {
        if (polygon < 0 || polygon >= polygons) {
            throw new IndexOutOfBoundsException("polygon " + polygon + " of " + polygons);
        }
    }

    /**
     * Gives the style of a polygon.
     *
     * @param polygon the polygon
     * @return the style index
     * @throws IndexOutOfBoundsException if out of range
     */
    public int styleOf(int polygon) {
        check(polygon);
        return polygonStyle[polygon];
    }

    /**
     * Gives the first vertex of a polygon in the batch's vertex numbering.
     *
     * @param polygon the polygon
     * @return the vertex index
     * @throws IndexOutOfBoundsException if out of range
     */
    public int firstVertex(int polygon) {
        check(polygon);
        return polygonFirstVertex[polygon];
    }

    /**
     * Gives the number of vertices of a polygon.
     *
     * @param polygon the polygon
     * @return the count
     * @throws IndexOutOfBoundsException if out of range
     */
    public int vertexCountOf(int polygon) {
        check(polygon);
        return (polygon + 1 < polygons ? polygonFirstVertex[polygon + 1] : vertexCount) - polygonFirstVertex[polygon];
    }

    /**
     * Gives the first index of a polygon in the batch's index list.
     *
     * @param polygon the polygon
     * @return the position of its first index
     * @throws IndexOutOfBoundsException if out of range
     */
    public int firstIndex(int polygon) {
        check(polygon);
        return polygonFirstIndex[polygon];
    }

    /**
     * Gives the number of indices of a polygon.
     *
     * @param polygon the polygon
     * @return three per triangle
     * @throws IndexOutOfBoundsException if out of range
     */
    public int indexCountOf(int polygon) {
        check(polygon);
        return (polygon + 1 < polygons ? polygonFirstIndex[polygon + 1] : indexCount) - polygonFirstIndex[polygon];
    }

    /**
     * Gives a vertex index of the triangulation.
     *
     * @param position the position in the batch's index list, 0 to {@code 3 * triangleCount() - 1}
     * @return the vertex number
     * @throws IndexOutOfBoundsException if out of range
     */
    public int index(int position) {
        if (position < 0 || position >= indexCount) {
            throw new IndexOutOfBoundsException("index " + position + " of " + indexCount);
        }
        return indices[position];
    }

    /**
     * Gives the x of a vertex.
     *
     * @param vertex the vertex in the batch's numbering
     * @return projected metres
     * @throws IndexOutOfBoundsException if out of range
     */
    public double x(int vertex) {
        if (vertex < 0 || vertex >= vertexCount) {
            throw new IndexOutOfBoundsException("vertex " + vertex + " of " + vertexCount);
        }
        return vertices[2 * vertex];
    }

    /**
     * Gives the y of a vertex.
     *
     * @param vertex the vertex in the batch's numbering
     * @return projected metres
     * @throws IndexOutOfBoundsException if out of range
     */
    public double y(int vertex) {
        if (vertex < 0 || vertex >= vertexCount) {
            throw new IndexOutOfBoundsException("vertex " + vertex + " of " + vertexCount);
        }
        return vertices[2 * vertex + 1];
    }
}
