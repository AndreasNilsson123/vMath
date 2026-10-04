package vmath.geo;

import vmath.core.Quatd;
import vmath.core.Vec3d;
import vmath.core.Wgs84;

/**
 * Bounding volumes of a map tile in Earth-centred Earth-fixed (ECEF) coordinates: the oriented box
 * that fits the curved patch of the ellipsoid that the tile covers between two heights, and the box
 * and sphere that follow from it.
 *
 * <p><b>The patch.</b> A tile is a rectangle in longitude and (Mercator) latitude, and its volume
 * is everything between the heights {@code minHeight} and {@code maxHeight} above the ellipsoid
 * along the ellipsoid normal. The surface curves away from the tangent plane at the middle of the
 * tile, so the box is oriented like the local East-North-Up frame there ({@link
 * Wgs84#enuToEcefRotation}); its half height covers the height range and the corners' drop, its
 * half width and depth the extent of the patch.
 *
 * <p><b>Conservative.</b> The box is built from a grid of samples of the patch at both heights,
 * grown by the largest distance that the surface can leave the chord between two neighbouring
 * samples. It never excludes a point of the volume (the tests check this on dense grids, on tiles
 * from the whole map down to street size and at the poles of the map), which is what culling needs;
 * it is not the smallest box, and a tile that spans a large part of the globe gets a box that is
 * visibly larger than the patch.
 *
 * <p><b>Layout of the compact form.</b> {@link #compute} fills a {@code double[16]} so that a
 * selector that tests thousands of tiles per frame does not allocate: the centre (3 values), the
 * half extents along the east, north and up axes (3), the three axes as unit vectors in ECEF (9)
 * and the radius of the bounding sphere around the centre (1).
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time, each with its own output array.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * TileId id = TileId.containing(8, Math.toRadians(18.07), Math.toRadians(59.33));
 * Obbd box = TileBounds.obb(id, -10.0, 300.0);
 * Sphered ball = TileBounds.sphere(id, -10.0, 300.0);
 * boolean seen = frustum.intersects(box);
 * }</pre>
 */
public final class TileBounds {

    /**
     * The number of values that {@link #compute} writes.
     */
    public static final int SIZE = 16;

    /**
     * Offset of the centre (x, y, z) in the compact form.
     */
    public static final int CENTER = 0;

    /**
     * Offset of the half extents along the east, north and up axes in the compact form.
     */
    public static final int HALF = 3;

    /**
     * Offset of the east axis, then the north axis and the up axis, three values each, in the
     * compact form.
     */
    public static final int AXES = 6;

    /**
     * Offset of the radius of the bounding sphere in the compact form.
     */
    public static final int RADIUS = 15;

    private static final int SAMPLES = 5;

    private TileBounds() {
    }

    /**
     * Computes the box of a tile into an array.
     *
     * @param zoom the zoom level
     * @param x the column
     * @param y the row
     * @param minHeight the lowest height of the ground in the tile, in metres above the ellipsoid
     * @param maxHeight the highest height in the tile; at least {@code minHeight}
     * @param out receives {@link #SIZE} values in the layout described in the class comment; must
     *     not be {@code null}
     * @throws IllegalArgumentException if the tile address is invalid
     */
    public static void compute(int zoom, int x, int y, double minHeight, double maxHeight, double[] out) {
        TileId id = new TileId(zoom, x, y);
        double west = id.west(), east = id.east(), north = id.north(), south = id.south();
        double lon0 = id.centerLongitude(), lat0 = id.centerLatitude();
        Vec3d o = Wgs84.toEcef(lat0, lon0, 0.0);
        Vec3d e = Wgs84.east(lon0), n = Wgs84.north(lat0, lon0), u = Wgs84.up(lat0, lon0);
        double minE = Double.POSITIVE_INFINITY, maxE = Double.NEGATIVE_INFINITY;
        double minN = minE, maxN = maxE, minU = minE, maxU = maxE;
        double n0 = 1.0 / (1 << zoom);
        double v0 = (double) y * n0;
        for (int j = 0; j < SAMPLES; j++) {
            double lat = j == 0 ? north : j == SAMPLES - 1 ? south : WebMercator.latitudeOfV(v0 + n0 * j / (SAMPLES - 1));
            for (int i = 0; i < SAMPLES; i++) {
                double lon = west + (east - west) * i / (SAMPLES - 1);
                for (int k = 0; k < 2; k++) {
                    Vec3d p = Wgs84.toEcef(lat, lon, k == 0 ? minHeight : maxHeight);
                    double dx = p.x() - o.x(), dy = p.y() - o.y(), dz = p.z() - o.z();
                    double pe = dx * e.x() + dy * e.y() + dz * e.z();
                    double pn = dx * n.x() + dy * n.y() + dz * n.z();
                    double pu = dx * u.x() + dy * u.y() + dz * u.z();
                    minE = Math.min(minE, pe);
                    maxE = Math.max(maxE, pe);
                    minN = Math.min(minN, pn);
                    maxN = Math.max(maxN, pn);
                    minU = Math.min(minU, pu);
                    maxU = Math.max(maxU, pu);
                }
            }
        }
        // the surface leaves the chord between two neighbouring samples by at most s^2 / (8 r); the
        // radius of a parallel shrinks with the cosine of the latitude, which the longitude term uses
        double cellLat = (north - south) / (SAMPLES - 1) * Wgs84.A;
        double cosMax = Math.max(1e-3, Math.cos(Math.max(Math.abs(north), Math.abs(south))));
        double cosEq = Math.cos(Math.min(Math.abs(north), Math.abs(south)) * (north * south > 0 ? 1.0 : 0.0));
        double cellLon = (east - west) / (SAMPLES - 1) * Wgs84.A * cosEq;
        double margin = cellLat * cellLat / (8.0 * Wgs84.B) + cellLon * cellLon / (8.0 * Wgs84.B * cosMax) + 1e-3;
        minE -= margin;
        maxE += margin;
        minN -= margin;
        maxN += margin;
        minU -= margin;
        maxU += margin;
        double ce = 0.5 * (minE + maxE), cn = 0.5 * (minN + maxN), cu = 0.5 * (minU + maxU);
        double he = 0.5 * (maxE - minE), hn = 0.5 * (maxN - minN), hu = 0.5 * (maxU - minU);
        out[CENTER] = o.x() + e.x() * ce + n.x() * cn + u.x() * cu;
        out[CENTER + 1] = o.y() + e.y() * ce + n.y() * cn + u.y() * cu;
        out[CENTER + 2] = o.z() + e.z() * ce + n.z() * cn + u.z() * cu;
        out[HALF] = he;
        out[HALF + 1] = hn;
        out[HALF + 2] = hu;
        out[AXES] = e.x();
        out[AXES + 1] = e.y();
        out[AXES + 2] = e.z();
        out[AXES + 3] = n.x();
        out[AXES + 4] = n.y();
        out[AXES + 5] = n.z();
        out[AXES + 6] = u.x();
        out[AXES + 7] = u.y();
        out[AXES + 8] = u.z();
        out[RADIUS] = Math.sqrt(he * he + hn * hn + hu * hu);
    }

    /**
     * Gives the oriented box of a tile.
     *
     * @param id the tile; must not be {@code null}
     * @param minHeight the lowest height of the ground in the tile, in metres above the ellipsoid
     * @param maxHeight the highest height in the tile; at least {@code minHeight}
     * @return the box in ECEF, oriented like the local East-North-Up frame at the tile's middle
     */
    public static Obbd obb(TileId id, double minHeight, double maxHeight) {
        double[] b = new double[SIZE];
        compute(id.zoom(), id.x(), id.y(), minHeight, maxHeight, b);
        Quatd rotation = Wgs84.enuToEcefRotation(id.centerLatitude(), id.centerLongitude());
        return Obbd.of(new Vec3d(b[CENTER], b[CENTER + 1], b[CENTER + 2]), new Vec3d(b[HALF], b[HALF + 1], b[HALF + 2]), rotation);
    }

    /**
     * Gives the bounding sphere of a tile, centred on the centre of its box.
     *
     * @param id the tile; must not be {@code null}
     * @param minHeight the lowest height of the ground in the tile, in metres above the ellipsoid
     * @param maxHeight the highest height in the tile; at least {@code minHeight}
     * @return the sphere in ECEF
     */
    public static Sphered sphere(TileId id, double minHeight, double maxHeight) {
        double[] b = new double[SIZE];
        compute(id.zoom(), id.x(), id.y(), minHeight, maxHeight, b);
        return new Sphered(b[CENTER], b[CENTER + 1], b[CENTER + 2], b[RADIUS]);
    }

    /**
     * Gives the axis-aligned box of a tile in ECEF, the box around its oriented box.
     *
     * @param id the tile; must not be {@code null}
     * @param minHeight the lowest height of the ground in the tile, in metres above the ellipsoid
     * @param maxHeight the highest height in the tile; at least {@code minHeight}
     * @return the box in ECEF
     */
    public static Aabbd aabb(TileId id, double minHeight, double maxHeight) {
        return obb(id, minHeight, maxHeight).aabb();
    }
}
