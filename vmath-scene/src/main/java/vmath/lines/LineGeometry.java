package vmath.lines;

import vmath.annotations.Experimental;

/**
 * The geometry of one segment of a thick line, in screen pixels: the definition that every line
 * strategy follows and that the shaders of {@link LineRenderPlan} repeat.
 *
 * <p>A segment is drawn as {@value #VERTICES_PER_SEGMENT} vertices, which form triangles three at
 * a time and are numbered as follows:
 *
 * <ul>
 *   <li>0 to 5: the body, a rectangle of the width of the line from the start to the end of the
 *       segment, as two triangles;
 *   <li>6 to {@code 6 + 3 * ROUND_STEPS - 1}: the piece at the start: the <b>join</b> with the
 *       previous segment if there is one, else the <b>cap</b> if the polyline starts here, else
 *       nothing;
 *   <li>the next {@code 3 * ROUND_STEPS}: the <b>cap</b> at the end if the polyline ends here,
 *       else nothing.
 * </ul>
 *
 * <p>A piece is a fan of triangles around a centre (the point of the corner or of the end): up to
 * {@value #ROUND_STEPS} triangles, each from the centre to two neighbouring points of an outline.
 * The outline of a round join or cap is an arc, of a bevel one chord, of a miter two (through the
 * tip of the miter) and of a square cap three. Triangles that a piece does not use collapse to
 * the centre and cover nothing. A piece only fills the outside of a corner; the bodies overlap on
 * the inside, so a line drawn with transparency shows the overlap.
 *
 * <p>Every vertex also has a distance along the polyline in world units ({@code along}, from the
 * start of the segment for the body and the start piece, from its end for the end cap), which the
 * fragment stage compares with the dash pattern. A segment shorter than a millionth of a pixel
 * has no geometry.
 *
 * <p>This class holds the arithmetic only; {@link LineShaderModel} and {@link LineExpander} apply it
 * to data. The shaders do the same arithmetic in {@code float}, with {@code atan(y, x)} for the
 * angle of a round join.
 *
 * <p><b>Thread safety.</b> Stateless apart from the scratch of an {@link Emitter}: use one emitter
 * per thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] out = new float[3];
 * LineGeometry.vertex(0, 0f, 0f, 100f, 0f, 0f, 0f, 0, 3f, 0, 0, 4f, 0f, 100f, out);   // (0, 3, 0): the upper corner at the start
 * }</pre>
 */
@Experimental("the geometry may change")
public final class LineGeometry {

    /** The triangles of a round piece, and the most that any piece uses. */
    public static final int ROUND_STEPS = 8;
    /** The vertices of the body of a segment. */
    public static final int BODY_VERTICES = 6;
    /** The vertices of a piece at one end. */
    public static final int PIECE_VERTICES = 3 * ROUND_STEPS;
    /** The vertices of a segment. */
    public static final int VERTICES_PER_SEGMENT = BODY_VERTICES + 2 * PIECE_VERTICES;

    /** The segment has a previous segment: its start piece is a join. */
    public static final int FLAG_HAS_PREV = 1;
    /** The polyline is open and starts here: its start piece is a cap. */
    public static final int FLAG_START_CAP = 2;
    /** The polyline is open and ends here: its end piece is a cap. */
    public static final int FLAG_END_CAP = 4;

    /** The code of {@link LineStyle.Cap#BUTT}. */
    public static final int CAP_BUTT = 0;
    /** The code of {@link LineStyle.Cap#SQUARE}. */
    public static final int CAP_SQUARE = 1;
    /** The code of {@link LineStyle.Cap#ROUND}. */
    public static final int CAP_ROUND = 2;
    /** The code of {@link LineStyle.Join#MITER}. */
    public static final int JOIN_MITER = 0;
    /** The code of {@link LineStyle.Join#BEVEL}. */
    public static final int JOIN_BEVEL = 1;
    /** The code of {@link LineStyle.Join#ROUND}. */
    public static final int JOIN_ROUND = 2;

    private static final float EPS = 1e-6f;
    private static final float STRAIGHT = 1e-4f;

    private LineGeometry() {
    }

    /**
     * Gives the code of a cap.
     *
     * @param cap the cap; must not be {@code null}
     * @return {@link #CAP_BUTT}, {@link #CAP_SQUARE} or {@link #CAP_ROUND}
     */
    public static int capCode(LineStyle.Cap cap) {
        return switch (cap) {
            case BUTT -> CAP_BUTT;
            case SQUARE -> CAP_SQUARE;
            case ROUND -> CAP_ROUND;
        };
    }

    /**
     * Gives the code of a join.
     *
     * @param join the join; must not be {@code null}
     * @return {@link #JOIN_MITER}, {@link #JOIN_BEVEL} or {@link #JOIN_ROUND}
     */
    public static int joinCode(LineStyle.Join join) {
        return switch (join) {
            case MITER -> JOIN_MITER;
            case BEVEL -> JOIN_BEVEL;
            case ROUND -> JOIN_ROUND;
        };
    }

    private static float rotX(float x, float y, float a) {
        return (float) (x * Math.cos(a) - y * Math.sin(a));
    }

    private static float rotY(float x, float y, float a) {
        return (float) (x * Math.sin(a) + y * Math.cos(a));
    }

    /**
     * Computes one vertex of a segment.
     *
     * @param vid the number of the vertex, 0 to {@link #VERTICES_PER_SEGMENT} minus one
     * @param s0x the x coordinate of the start of the segment on the screen, in pixels
     * @param s0y the y coordinate of the start
     * @param s1x the x coordinate of the end
     * @param s1y the y coordinate of the end
     * @param px the x coordinate of the previous point of the polyline, used when the flags have
     *     {@link #FLAG_HAS_PREV}
     * @param py the y coordinate of the previous point
     * @param flags {@link #FLAG_HAS_PREV}, {@link #FLAG_START_CAP} and {@link #FLAG_END_CAP}
     * @param halfWidth half the width of the line in pixels
     * @param cap the cap code
     * @param join the join code
     * @param miterLimit the miter limit
     * @param along0 the distance along the polyline at the start, in world units
     * @param along1 the distance at the end
     * @param out receives {@code x, y} in pixels and {@code along}; must have room for 3 values
     */
    public static void vertex(int vid, float s0x, float s0y, float s1x, float s1y, float px, float py, int flags, float halfWidth, int cap, int join, float miterLimit,
                              float along0, float along1, float[] out) {
        float dx = s1x - s0x, dy = s1y - s0y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(len > EPS)) {
            out[0] = s0x;
            out[1] = s0y;
            out[2] = along0;
            return;
        }
        float tx = dx / len, ty = dy / len;
        float nx = -ty, ny = tx;
        float h = halfWidth;
        if (vid < BODY_VERTICES) {
            // corners 0 a0, 1 b0, 2 a1, 3 b0, 4 b1, 5 a1 with a = left (+n) and b = right (-n), 0 at the start and 1 at the end
            boolean atEnd = vid == 2 || vid == 4 || vid == 5;
            float side = vid == 0 || vid == 2 || vid == 5 ? 1f : -1f;
            out[0] = (atEnd ? s1x : s0x) + side * nx * h;
            out[1] = (atEnd ? s1y : s0y) + side * ny * h;
            out[2] = atEnd ? along1 : along0;
            return;
        }
        int local = vid - BODY_VERTICES;
        boolean endPiece = local >= PIECE_VERTICES;
        if (endPiece) {
            local -= PIECE_VERTICES;
        }
        int tri = local / 3, corner = local % 3;
        float cx = endPiece ? s1x : s0x, cy = endPiece ? s1y : s0y;
        out[0] = cx;
        out[1] = cy;
        out[2] = endPiece ? along1 : along0;
        // the outline of the piece: kind 0 none, 1 round arc, 2 bevel, 3 miter, 4 square cap; o1 is its first vector, phi its sweep, ux uy and dirx diry for a square cap
        int kind = 0;
        float o1x = 0f, o1y = 0f, phi = 0f, scale = 1f;
        float dirx = 0f, diry = 0f;
        if (!endPiece && (flags & FLAG_HAS_PREV) != 0) {
            float qx = s0x - px, qy = s0y - py;
            float plen = (float) Math.sqrt(qx * qx + qy * qy);
            if (plen > EPS) {
                float tpx = qx / plen, tpy = qy / plen;
                float cross = tpx * ty - tpy * tx;
                float dot = tpx * tx + tpy * ty;
                float outer = cross > 0f ? -1f : 1f;
                o1x = outer * h * -tpy;
                o1y = outer * h * tpx;
                float o2x = outer * h * nx, o2y = outer * h * ny;
                phi = (float) Math.atan2(o1x * o2y - o1y * o2x, o1x * o2x + o1y * o2y);
                if (Math.abs(cross) < EPS && dot < 0f) {
                    // a reversal: the arc goes round the front of the corner, the way the line was heading
                    phi = (-o1y * tpx + o1x * tpy) > 0f ? (float) Math.PI : (float) -Math.PI;
                }
                if (Math.abs(phi) > STRAIGHT) {
                    if (join == JOIN_ROUND) {
                        kind = 1;
                    } else if (join == JOIN_MITER) {
                        float cosHalf = (float) Math.cos(phi * 0.5f);
                        if (cosHalf > STRAIGHT && 1f / cosHalf <= miterLimit) {
                            kind = 3;
                            scale = 1f / cosHalf;
                        } else {
                            kind = 2;
                        }
                    } else {
                        kind = 2;
                    }
                }
            }
        } else if (!endPiece && (flags & FLAG_START_CAP) != 0 && cap != CAP_BUTT) {
            o1x = nx * h;
            o1y = ny * h;
            dirx = -tx;
            diry = -ty;
            kind = cap == CAP_ROUND ? 1 : 4;
            phi = (float) Math.PI;
        } else if (endPiece && (flags & FLAG_END_CAP) != 0 && cap != CAP_BUTT) {
            o1x = -nx * h;
            o1y = -ny * h;
            dirx = tx;
            diry = ty;
            kind = cap == CAP_ROUND ? 1 : 4;
            phi = (float) Math.PI;
        }
        int steps = kind == 1 ? ROUND_STEPS : kind == 2 ? 1 : kind == 3 ? 2 : kind == 4 ? 3 : 0;
        if (tri >= steps || corner == 0) {
            return;
        }
        int k = tri + (corner == 2 ? 1 : 0);
        float vx, vy;
        if (kind == 1) {
            float a = phi * k / ROUND_STEPS;
            vx = rotX(o1x, o1y, a);
            vy = rotY(o1x, o1y, a);
        } else if (kind == 2) {
            float a = phi * k;
            vx = rotX(o1x, o1y, a);
            vy = rotY(o1x, o1y, a);
        } else if (kind == 3) {
            float a = phi * k * 0.5f;
            float s = k == 1 ? scale : 1f;
            vx = rotX(o1x, o1y, a) * s;
            vy = rotY(o1x, o1y, a) * s;
        } else {
            // square cap: u, u + dir h, -u + dir h, -u
            float sign = k <= 1 ? 1f : -1f;
            float push = k == 1 || k == 2 ? h : 0f;
            vx = sign * o1x + dirx * push;
            vy = sign * o1y + diry * push;
        }
        out[0] = cx + vx;
        out[1] = cy + vy;
    }

    /**
     * Receives the triangles of a segment.
     */
    @FunctionalInterface
    public interface Sink {
        /**
         * Receives one triangle that covers some area.
         *
         * @param x0 the x coordinate of the first corner, in pixels
         * @param y0 the y coordinate of the first corner
         * @param a0 the distance along the polyline at the first corner, in world units
         * @param x1 the x coordinate of the second corner
         * @param y1 the y coordinate of the second corner
         * @param a1 the distance at the second corner
         * @param x2 the x coordinate of the third corner
         * @param y2 the y coordinate of the third corner
         * @param a2 the distance at the third corner
         * @param dash the dash pattern in world units, or {@code null} for a solid line; shared,
         *     must not be changed
         * @param dashCount the numbers of the pattern
         * @param color the colour as {@code 0xRRGGBBAA}
         */
        void triangle(float x0, float y0, float a0, float x1, float y1, float a1, float x2, float y2, float a2, float[] dash, int dashCount, int color);
    }

    /**
     * Turns segments into triangles with {@link #vertex}, reusing its scratch.
     *
     * <p><b>Thread safety.</b> Not thread-safe: one emitter per thread.
     */
    public static final class Emitter {
        private final float[] v = new float[3 * VERTICES_PER_SEGMENT];
        private final float[] tmp = new float[3];

        /**
         * Creates an emitter.
         */
        public Emitter() {
        }

        /**
         * Projects a point to the screen.
         *
         * @param viewProjection the matrix, column-major, 16 values
         * @param x the x coordinate of the point
         * @param y the y coordinate of the point
         * @param z the z coordinate of the point
         * @param width the width of the viewport in pixels
         * @param height the height of the viewport in pixels
         * @param out receives the screen {@code x, y} in pixels at {@code 0} and {@code 1}
         * @return the clip-space {@code w}; a point at or behind the camera has a {@code w} that
         *     is not above zero
         */
        public static float project(float[] viewProjection, float x, float y, float z, float width, float height, float[] out) {
            float cx = viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12];
            float cy = viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13];
            float cw = viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15];
            out[0] = (cx / cw * 0.5f + 0.5f) * width;
            out[1] = (cy / cw * 0.5f + 0.5f) * height;
            return cw;
        }

        private final float[] s0 = new float[2];
        private final float[] s1 = new float[2];
        private final float[] sp = new float[2];

        /**
         * Projects a segment to the screen and emits its triangles: the whole of what the vertex
         * stage of a line shader does, from positions relative to the origin to pixels.
         *
         * <p>A segment with an end at or behind the camera ({@code w} not above zero) has no
         * geometry; a previous point behind the camera is treated as no previous point. The width
         * of a world-unit line is {@code width * worldToPixel / w} at the start of the segment.
         *
         * @param sink receives the triangles; must not be {@code null}
         * @param viewProjection the matrix, column-major, relative to the origin of the batch
         * @param viewWidth the width of the viewport in pixels
         * @param viewHeight the height of the viewport in pixels
         * @param worldToPixel pixels per world unit at {@code w = 1}: half the viewport height
         *     times the element {@code [1][1]} of the projection matrix
         * @param p0 the start position, 3 values
         * @param p1 the end position, 3 values
         * @param prev the previous position, 3 values
         * @param flags the flags of {@link LineGeometry}
         * @param along0 the distance along the polyline at the start
         * @param along1 the distance at the end
         * @param width the width of the line, in pixels or world units
         * @param worldWidth whether the width is in world units
         * @param cap the cap code
         * @param join the join code
         * @param miterLimit the miter limit
         * @param dash the dash pattern, or {@code null}
         * @param dashCount the numbers of the pattern
         * @param color the colour as {@code 0xRRGGBBAA}
         */
        public void emitProjected(Sink sink, float[] viewProjection, float viewWidth, float viewHeight, float worldToPixel, float[] p0, float[] p1, float[] prev, int flags,
                                  float along0, float along1, float width, boolean worldWidth, int cap, int join, float miterLimit, float[] dash, int dashCount, int color) {
            float w0 = project(viewProjection, p0[0], p0[1], p0[2], viewWidth, viewHeight, s0);
            float w1 = project(viewProjection, p1[0], p1[1], p1[2], viewWidth, viewHeight, s1);
            if (!(w0 > 0f) || !(w1 > 0f)) {
                return;
            }
            int f = flags;
            if ((f & FLAG_HAS_PREV) != 0) {
                float wp = project(viewProjection, prev[0], prev[1], prev[2], viewWidth, viewHeight, sp);
                if (!(wp > 0f)) {
                    f &= ~FLAG_HAS_PREV;
                }
            }
            float widthPx = worldWidth ? width * worldToPixel / w0 : width;
            emit(sink, s0[0], s0[1], s1[0], s1[1], sp[0], sp[1], f, 0.5f * widthPx, cap, join, miterLimit, along0, along1, dash, dashCount, color);
        }

        /**
         * Emits the triangles of one segment given in screen space.
         *
         * @param sink receives the triangles; must not be {@code null}
         * @param s0x the x coordinate of the start in pixels
         * @param s0y the y coordinate of the start
         * @param s1x the x coordinate of the end
         * @param s1y the y coordinate of the end
         * @param px the x coordinate of the previous point
         * @param py the y coordinate of the previous point
         * @param flags the flags of {@link LineGeometry}
         * @param halfWidth half the width in pixels
         * @param cap the cap code
         * @param join the join code
         * @param miterLimit the miter limit
         * @param along0 the distance along the polyline at the start
         * @param along1 the distance at the end
         * @param dash the dash pattern or {@code null}
         * @param dashCount the numbers of the pattern
         * @param color the colour
         */
        public void emit(Sink sink, float s0x, float s0y, float s1x, float s1y, float px, float py, int flags, float halfWidth, int cap, int join, float miterLimit,
                         float along0, float along1, float[] dash, int dashCount, int color) {
            for (int i = 0; i < VERTICES_PER_SEGMENT; i++) {
                vertex(i, s0x, s0y, s1x, s1y, px, py, flags, halfWidth, cap, join, miterLimit, along0, along1, tmp);
                v[3 * i] = tmp[0];
                v[3 * i + 1] = tmp[1];
                v[3 * i + 2] = tmp[2];
            }
            for (int i = 0; i < VERTICES_PER_SEGMENT; i += 3) {
                float ax = v[3 * i], ay = v[3 * i + 1], bx = v[3 * i + 3], by = v[3 * i + 4], cx = v[3 * i + 6], cy = v[3 * i + 7];
                float area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
                if (area != 0f) {
                    sink.triangle(ax, ay, v[3 * i + 2], bx, by, v[3 * i + 5], cx, cy, v[3 * i + 8], dash, dashCount, color);
                }
            }
        }
    }
}
