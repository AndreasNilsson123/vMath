package vmath.samples.demos.maps;

import java.util.ArrayList;
import java.util.List;
import vmath.core.ClipSpace;
import vmath.geo.Geodesy;
import vmath.geo.WebMercator;
import vmath.gl.DrawList;
import vmath.lines.LineBatch;
import vmath.lines.LineRenderPlan;
import vmath.map.MapView2d;
import vmath.map.SymbolAtlas;
import vmath.samples.demos.globe.Planet;
import vmath.samples.framework.LineRenderer;
import vmath.samples.framework.LineTier;

/**
 * What the four map demos share: generic symbol sprites, the procedural world (tile images and
 * coastlines of the planet of the globe demo), a follower for a route of waypoints, and a line
 * layer that is written and drawn every frame through the line renderer.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe; OpenGL calls are only valid on the thread that owns the context.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SymbolAtlas atlas = MapKit.sprites();
 * float[] uv = new float[4];
 * atlas.rect(atlas.indexOf("arrow"), uv);
 * }</pre>
 */
final class MapKit {

    private MapKit() {
    }

    /**
     * Makes text that the font of the heads-up display has the characters for: the degree sign
     * becomes the letter d.
     *
     * @param text the text
     * @return the text with only ASCII characters
     */
    static String ascii(String text) {
        return text.replace('°', 'd');
    }

    // ---------------------------------------------------------------- sprites

    /**
     * Makes the generic sprites: an arrow, a dot, a square and a diamond, white with soft edges so
     * that the colour of a symbol tints them.
     *
     * @return the atlas with the sprites {@code arrow}, {@code dot}, {@code square} and {@code diamond}
     */
    static SymbolAtlas sprites() {
        int n = 32;
        SymbolAtlas.Builder b = SymbolAtlas.builder();
        String[] names = {"arrow", "dot", "square", "diamond"};
        for (int s = 0; s < names.length; s++) {
            byte[] px = new byte[n * n * 4];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int hit = 0;
                    for (int sy = 0; sy < 4; sy++) {
                        for (int sx = 0; sx < 4; sx++) {
                            double u = (x + (sx + 0.5) / 4) / n * 2 - 1, v = 1 - (y + (sy + 0.5) / 4) / n * 2;     // -1..1, v up
                            hit += inside(s, u, v) ? 1 : 0;
                        }
                    }
                    int o = 4 * (y * n + x);
                    px[o] = (byte) 255;
                    px[o + 1] = (byte) 255;
                    px[o + 2] = (byte) 255;
                    px[o + 3] = (byte) Math.round(hit * 255 / 16.0);
                }
            }
            b.add(names[s], n, n, px);
        }
        return b.build(256);
    }

    private static boolean inside(int shape, double u, double v) {
        switch (shape) {
            case 0: {
                // an arrow pointing up: a head and a tail, with a notch
                if (v > -0.9 && v < 0.95) {
                    double w = 0.8 * (0.95 - v) / 1.85;               // the half width grows from the nose to the tail
                    boolean body = Math.abs(u) < w + 0.05 && !(v < -0.3 && Math.abs(u) < 0.25 * (-0.3 - v) / 0.6);
                    return body;
                }
                return false;
            }
            case 1:
                return u * u + v * v < 0.7;
            case 2:
                return Math.abs(u) < 0.8 && Math.abs(v) < 0.8;
            default:
                return Math.abs(u) + Math.abs(v) < 0.95;
        }
    }

    // ---------------------------------------------------------------- the world

    /**
     * Makes the image of a Web Mercator tile of the procedural planet.
     *
     * @param zoom the zoom
     * @param x the column
     * @param y the row (XYZ)
     * @param size the width and height in pixels
     * @return RGBA bytes, row 0 the north row
     */
    static byte[] tileImage(int zoom, long x, long y, int size) {
        byte[] out = new byte[size * size * 4];
        double[] rgb = new double[3];
        double n = 1L << zoom;
        for (int j = 0; j < size; j++) {
            double lat = WebMercator.latitudeOfV((y + (j + 0.5) / size) / n);
            for (int i = 0; i < size; i++) {
                double lon = WebMercator.longitudeOfU((x + (i + 0.5) / size) / n);
                double h = Planet.height(lon, lat);
                Planet.albedo(lon, lat, h, 0.0, rgb);
                int o = 4 * (j * size + i);
                out[o] = (byte) Math.round(255 * Math.max(0, Math.min(1, rgb[0])));
                out[o + 1] = (byte) Math.round(255 * Math.max(0, Math.min(1, rgb[1])));
                out[o + 2] = (byte) Math.round(255 * Math.max(0, Math.min(1, rgb[2])));
                out[o + 3] = (byte) 255;
            }
        }
        return out;
    }

    /**
     * Finds the coast of the procedural planet by marching squares over a grid of longitude and
     * latitude: the line where the height is zero, as separate segments.
     *
     * @param cellsLon the number of cells in longitude, at least 4
     * @param cellsLat the number of cells in latitude, at least 2
     * @param maxLatitude the highest absolute latitude in radians that is covered
     * @return the segments, each {@code {lat1, lon1, lat2, lon2}} in radians
     */
    static List<double[]> coastlines(int cellsLon, int cellsLat, double maxLatitude) {
        double[][] h = new double[cellsLat + 1][cellsLon + 1];
        double[] lons = new double[cellsLon + 1], lats = new double[cellsLat + 1];
        for (int i = 0; i <= cellsLon; i++) {
            lons[i] = -Math.PI + 2 * Math.PI * i / cellsLon;
        }
        for (int j = 0; j <= cellsLat; j++) {
            lats[j] = maxLatitude - 2 * maxLatitude * j / cellsLat;
        }
        for (int j = 0; j <= cellsLat; j++) {
            for (int i = 0; i <= cellsLon; i++) {
                h[j][i] = Planet.height(lons[i], lats[j]);
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int j = 0; j < cellsLat; j++) {
            for (int i = 0; i < cellsLon; i++) {
                double a = h[j][i], b = h[j][i + 1], c = h[j + 1][i + 1], d = h[j + 1][i];      // corners: nw, ne, se, sw
                double[][] cross = new double[4][];                                              // top, right, bottom, left
                if ((a > 0) != (b > 0)) {
                    cross[0] = new double[] {lats[j], lons[i] + (lons[i + 1] - lons[i]) * a / (a - b)};
                }
                if ((b > 0) != (c > 0)) {
                    cross[1] = new double[] {lats[j] + (lats[j + 1] - lats[j]) * b / (b - c), lons[i + 1]};
                }
                if ((d > 0) != (c > 0)) {
                    cross[2] = new double[] {lats[j + 1], lons[i] + (lons[i + 1] - lons[i]) * d / (d - c)};
                }
                if ((a > 0) != (d > 0)) {
                    cross[3] = new double[] {lats[j] + (lats[j + 1] - lats[j]) * a / (a - d), lons[i]};
                }
                List<double[]> pts = new ArrayList<>(4);
                for (double[] p : cross) {
                    if (p != null) {
                        pts.add(p);
                    }
                }
                for (int k = 0; k + 1 < pts.size(); k += 2) {
                    out.add(new double[] {pts.get(k)[0], pts.get(k)[1], pts.get(k + 1)[0], pts.get(k + 1)[1]});
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- a route

    /** A closed route of waypoints, followed at a distance along the geodesic legs. */
    static final class Route {
        private final double[] latLon;
        private final int count;
        private final double[] legLength;
        private final double[] legStart;
        private final double total;

        /**
         * Makes the route; the last waypoint leads back to the first.
         *
         * @param latLon waypoints as latitude and longitude pairs in radians
         */
        Route(double[] latLon) {
            this.latLon = latLon.clone();
            this.count = latLon.length / 2;
            legLength = new double[count];
            legStart = new double[count];
            double[] r = new double[3];
            double sum = 0;
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                Geodesy.inverse(latLon[2 * i], latLon[2 * i + 1], latLon[2 * j], latLon[2 * j + 1], r);
                legLength[i] = r[0];
                legStart[i] = sum;
                sum += r[0];
            }
            total = sum;
        }

        /**
         * Gives the waypoints, as the route sees them: the last one is joined to the first.
         *
         * @return a copy of the latitude and longitude pairs
         */
        double[] waypoints() {
            return latLon.clone();
        }

        /**
         * Gives the number of waypoints.
         *
         * @return the count
         */
        int count() {
            return count;
        }

        /**
         * Gives the length of the closed route.
         *
         * @return metres
         */
        double length() {
            return total;
        }

        /**
         * Finds the position and the course at a distance along the route.
         *
         * @param distance metres from the first waypoint, any value (the route repeats)
         * @param out receives latitude, longitude and course (true bearing) in radians
         */
        void at(double distance, double[] out) {
            double d = distance - total * Math.floor(distance / total);
            int leg = count - 1;
            for (int i = 0; i < count; i++) {
                if (d < legStart[i] + legLength[i]) {
                    leg = i;
                    break;
                }
            }
            int j = (leg + 1) % count;
            double[] r = new double[3];
            Geodesy.inverse(latLon[2 * leg], latLon[2 * leg + 1], latLon[2 * j], latLon[2 * j + 1], r);
            double[] p = new double[3];
            Geodesy.direct(latLon[2 * leg], latLon[2 * leg + 1], r[1], d - legStart[leg], p);
            out[0] = p[0];
            out[1] = p[1];
            out[2] = Geodesy.normalizeBearing(p[2]);
        }
    }

    // ---------------------------------------------------------------- the line layer

    /** A batch of polylines that is rewritten and drawn every frame through the line renderer of one tier. */
    static final class LineLayer implements AutoCloseable {
        private final LineBatch batch = new LineBatch();
        private final LineRenderPlan plan;
        private final LineRenderer renderer;
        private final DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 256);
        private final long dataCapacity;
        private final long styleCapacity;
        private final LineTier tier;
        private long dataBytes;
        private int calls;
        private boolean overflow;

        /**
         * Creates the layer.
         *
         * @param tier the tier whose capabilities, shaders and draws are used
         * @param dataCapacity the size of the data buffer in bytes
         */
        LineLayer(LineTier tier, long dataCapacity) {
            this.tier = tier;
            this.plan = tier.plan();
            this.dataCapacity = dataCapacity;
            this.styleCapacity = 1 << 16;
            this.renderer = new LineRenderer(plan, tier.caps(), dataCapacity, styleCapacity);
        }

        /**
         * Gives the batch to fill after {@link #begin}.
         *
         * @return the batch
         */
        LineBatch batch() {
            return batch;
        }

        /**
         * Gives the tier.
         *
         * @return the tier
         */
        LineTier tier() {
            return tier;
        }

        /**
         * Clears the batch and sets its origin.
         *
         * @param x the projected x of the origin
         * @param y the projected y of the origin
         */
        void begin(double x, double y) {
            batch.clear();
            batch.setOrigin(x, y, 0.0);
        }

        /**
         * Writes and uploads the batch.
         *
         * @return the bytes uploaded
         */
        long end() {
            overflow = plan.dataBytes(batch) > dataCapacity || plan.styleBytes(batch) > styleCapacity;
            if (overflow) {
                dataBytes = 0;
                calls = 0;
                return 0;
            }
            plan.write(batch, renderer.dataMirror(), renderer.styleMirror(), draws);
            dataBytes = plan.dataBytes(batch);
            renderer.uploadData(0, dataBytes);
            renderer.uploadStyles(plan.styleBytes(batch));
            calls = renderer.setDraws(draws);
            return dataBytes;
        }

        /**
         * Tells whether the last batch did not fit the buffers and was not drawn.
         *
         * @return {@code true} on overflow
         */
        boolean overflow() {
            return overflow;
        }

        /**
         * Gives the draw calls of a frame.
         *
         * @return the count
         */
        int calls() {
            return calls;
        }

        /**
         * Draws the written lines.
         *
         * @param view the view whose matrix is used; the origin must be the one given to {@link #begin}
         * @param width the viewport width
         * @param height the viewport height
         * @param ox the origin x given to {@link #begin}
         * @param oy the origin y given to {@link #begin}
         */
        void draw(MapView2d view, int width, int height, double ox, double oy) {
            if (overflow || batch.polylineCount() == 0) {
                return;
            }
            float[] vp = new float[16];
            view.viewProjection(ClipSpace.OPENGL, ox, oy, vp);
            org.lwjgl.opengl.GL46.glEnable(org.lwjgl.opengl.GL46.GL_BLEND);
            org.lwjgl.opengl.GL46.glBlendFunc(org.lwjgl.opengl.GL46.GL_SRC_ALPHA, org.lwjgl.opengl.GL46.GL_ONE_MINUS_SRC_ALPHA);
            renderer.draw(vp, (float) view.pixelsPerMapUnit(), width, height);
            org.lwjgl.opengl.GL46.glDisable(org.lwjgl.opengl.GL46.GL_BLEND);
        }

        @Override
        public void close() {
            renderer.close();
        }
    }
}
