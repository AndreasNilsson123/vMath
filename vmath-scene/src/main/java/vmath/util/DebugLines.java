package vmath.util;

import vmath.anim.Skeleton;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;
import vmath.geo.Capsulef;
import vmath.geo.DepthRange;
import vmath.geo.Obbf;
import vmath.geo.Spheref;

/**
 * A buffer of coloured line segments for drawing debug geometry: the outlines of boxes, spheres,
 * capsules, frusta and cones, coordinate axes, grids, arrows and skeletons, generated into plain
 * arrays that can be uploaded as a line list ({@code GL_LINES}) with a position attribute of three
 * floats and a colour attribute of four normalised unsigned bytes.
 *
 * <p>Every method appends lines in the colour that is current at the time of the call
 * ({@link #setColor}); {@link #clear()} empties the buffer for the next frame while keeping the
 * memory, so a steady-state frame allocates nothing: the generators use scratch arrays of the
 * buffer, and the arrays double in size when they run out (the exception is {@link #frustum}, which
 * creates the inverse of the matrix and a few small vectors). {@link #positions()} and
 * {@link #colors()} are the live arrays: line {@code i} runs from {@code positions[6 i .. 6 i + 3)}
 * to {@code positions[6 i + 3 .. 6 i + 6)} and has the colour {@code colors[i]}, one packed
 * {@code int} per line whose memory bytes are red, green, blue, alpha ({@link #pack}); only the
 * first {@link #lineCount()} lines are valid.
 *
 * <p>Round shapes are polylines: the {@code segments} argument is the number of straight pieces of
 * a full circle (at least 3; 24 to 48 looks round at debug scale).
 *
 * <p><b>Thread safety.</b> Mutable and not thread-safe: use one per thread and merge, or guard it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DebugLines lines = new DebugLines();
 * lines.setColor(1f, 0f, 0f, 1f).box(Aabbf.of(new Vec3f(-1f, 0f, -1f), new Vec3f(1f, 2f, 1f)));
 * lines.setColor(0f, 1f, 0f, 1f).arrow(Vec3f.ZERO, new Vec3f(0f, 3f, 0f), 0.3f);
 * float[] positions = lines.positions();                                       // 6 floats per line, for one draw call
 * int count = lines.lineCount();
 * lines.clear();
 * }</pre>
 */
public final class DebugLines {

    /**
     * Opaque red, packed by {@link #pack}.
     */
    public static final int RED = pack(255, 0, 0, 255);
    /**
     * Opaque green.
     */
    public static final int GREEN = pack(0, 255, 0, 255);
    /**
     * Opaque blue.
     */
    public static final int BLUE = pack(0, 0, 255, 255);
    /**
     * Opaque white.
     */
    public static final int WHITE = pack(255, 255, 255, 255);
    /**
     * Opaque yellow.
     */
    public static final int YELLOW = pack(255, 255, 0, 255);
    /**
     * Opaque cyan.
     */
    public static final int CYAN = pack(0, 255, 255, 255);
    /**
     * Opaque magenta.
     */
    public static final int MAGENTA = pack(255, 0, 255, 255);

    private float[] positions;
    private int[] colors;
    private int count;
    private int color = WHITE;
    // scratch for the shape generators, so that generating a shape allocates nothing
    private final double[] bt = new double[3], bb = new double[3], cv = new double[3], cw = new double[3];
    private final float[] corners = new float[24];

    /**
     * Creates an empty buffer with room for {@code initialLines} lines (it grows as needed).
     *
     * @param initialLines the initial lines
     * @throws IllegalArgumentException if {@code initialLines} is not positive
     */
    public DebugLines(int initialLines) {
        if (initialLines < 1) {
            throw new IllegalArgumentException("the initial capacity must be positive: " + initialLines);
        }
        positions = new float[6 * initialLines];
        colors = new int[initialLines];
    }

    /**
     * Creates an empty buffer with room for 1 024 lines.
     */
    public DebugLines() {
        this(1024);
    }

    // ------------------------------------------------------------ state

    /**
     * Packs a colour of four 0 to 255 components into an {@code int} whose bytes in memory
     * (little-endian) are red, green, blue, alpha.
     *
     * @param r the red component, 0 to 255
     * @param g the green component, 0 to 255
     * @param b the blue component, 0 to 255
     * @param a the alpha component, 0 to 255
     * @return the packed colour
     */
    public static int pack(int r, int g, int b, int a) {
        return (r & 0xFF) | (g & 0xFF) << 8 | (b & 0xFF) << 16 | (a & 0xFF) << 24;
    }

    /**
     * Sets the colour of the lines added from now on, as a packed value ({@link #pack}).
     *
     * @param packed the packed value
     * @return this instance, for chaining
     */
    public DebugLines setColor(int packed) {
        color = packed;
        return this;
    }

    /**
     * Sets the colour of the lines added from now on from components in {@code [0, 1]} (clamped,
     * rounded to 8 bits).
     *
     * @param r the red component, in {@code [0, 1]}
     * @param g the green component, in {@code [0, 1]}
     * @param b the blue component, in {@code [0, 1]}
     * @param a the alpha component, in {@code [0, 1]}
     * @return this instance, for chaining
     */
    public DebugLines setColor(float r, float g, float b, float a) {
        color = pack(to8(r), to8(g), to8(b), to8(a));
        return this;
    }

    private static int to8(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    /**
     * Counts the lines in the buffer.
     *
     * @return the number of lines in the buffer
     */
    public int lineCount() {
        return count;
    }

    /**
     * Exposes the end points as a live array, six floats per line.
     *
     * <p>It is replaced when the buffer grows, so fetch it after adding.
     *
     * @return the live array of end points, six floats per line; valid up to
     *     {@code 6 * lineCount()}
     */
    public float[] positions() {
        return positions;
    }

    /**
     * Exposes the packed colours as a live array, one per line.
     *
     * <p>It is replaced when the buffer grows.
     *
     * @return the live array of packed colours, one per line; valid up to {@link #lineCount()}
     */
    public int[] colors() {
        return colors;
    }

    /**
     * Removes all lines but keeps the memory and the current colour.
     */
    public void clear() {
        count = 0;
    }

    // ------------------------------------------------------------ primitives

    /**
     * Adds one line from {@code (x0, y0, z0)} to {@code (x1, y1, z1)}.
     *
     * @param x0 the x coordinate of the start
     * @param y0 the y coordinate of the start
     * @param z0 the z coordinate of the start
     * @param x1 the x coordinate of the end
     * @param y1 the y coordinate of the end
     * @param z1 the z coordinate of the end
     * @return this instance, for chaining
     */
    public DebugLines line(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (count == colors.length) {
            positions = java.util.Arrays.copyOf(positions, positions.length * 2);
            colors = java.util.Arrays.copyOf(colors, colors.length * 2);
        }
        int o = 6 * count;
        positions[o] = x0;
        positions[o + 1] = y0;
        positions[o + 2] = z0;
        positions[o + 3] = x1;
        positions[o + 4] = y1;
        positions[o + 5] = z1;
        colors[count++] = color;
        return this;
    }

    /**
     * Adds one line between two points.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @return this instance, for chaining
     */
    public DebugLines line(Vec3f a, Vec3f b) {
        return line(a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }

    /**
     * Adds a polyline through the {@code count} points ({@code x, y, z} triples) of {@code xyz}
     * from float index {@code offset}: {@code count - 1} lines, plus the closing one if
     * {@code closed}.
     *
     * @param xyz the three components
     * @param offset the index of the first element to read or write
     * @param count the number of elements
     * @param closed whether the shape is closed
     * @return this instance, for chaining
     * @throws IllegalArgumentException if {@code count} points at {@code offset} do not fit in
     *     {@code xyz}
     */
    public DebugLines polyline(float[] xyz, int offset, int count, boolean closed) {
        if (count < 0 || offset < 0 || (long) offset + 3L * count > xyz.length) {
            throw new IllegalArgumentException(count + " points at offset " + offset + " do not fit in an array of " + xyz.length);
        }
        for (int i = 0; i + 1 < count; i++) {
            line(xyz[offset + 3 * i], xyz[offset + 3 * i + 1], xyz[offset + 3 * i + 2], xyz[offset + 3 * i + 3], xyz[offset + 3 * i + 4], xyz[offset + 3 * i + 5]);
        }
        if (closed && count > 2) {
            int l = offset + 3 * (count - 1);
            line(xyz[l], xyz[l + 1], xyz[l + 2], xyz[offset], xyz[offset + 1], xyz[offset + 2]);
        }
        return this;
    }

    /**
     * Adds the three edges of the triangle with the corners {@code a}, {@code b}, {@code c}.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param c the vector; must not be {@code null}
     * @return this instance, for chaining
     */
    public DebugLines triangle(Vec3f a, Vec3f b, Vec3f c) {
        return line(a, b).line(b, c).line(c, a);
    }

    /**
     * Adds the edges of {@code triangleCount} triangles of an indexed mesh: {@code positions} holds
     * {@code x, y, z} triples and {@code indices} three vertex indices per triangle.
     *
     * <p>An edge shared by two triangles is drawn twice.
     *
     * @param positions the positions (at least 3 elements)
     * @param indices the indices
     * @param triangleCount the triangle count
     * @return this instance, for chaining
     */
    public DebugLines wireTriangles(float[] positions, int[] indices, int triangleCount) {
        for (int t = 0; t < triangleCount; t++) {
            for (int k = 0; k < 3; k++) {
                int a = 3 * indices[3 * t + k], b = 3 * indices[3 * t + (k + 1) % 3];
                line(positions[a], positions[a + 1], positions[a + 2], positions[b], positions[b + 1], positions[b + 2]);
            }
        }
        return this;
    }

    /**
     * Adds a cross of three axis-aligned lines of half length {@code size} through the point: a
     * marker for a position.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param size the size
     * @return this instance, for chaining
     */
    public DebugLines cross(float x, float y, float z, float size) {
        return line(x - size, y, z, x + size, y, z).line(x, y - size, z, x, y + size, z).line(x, y, z - size, x, y, z + size);
    }

    // ------------------------------------------------------------ boxes

    /**
     * Adds the 12 edges of the axis-aligned box.
     *
     * <p>An empty box adds nothing.
     *
     * @param b the second box; must not be {@code null}
     * @return this instance, for chaining
     */
    public DebugLines box(Aabbf b) {
        if (b.isEmpty()) {
            return this;
        }
        return box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    /**
     * Adds the 12 edges of the axis-aligned box with the given bounds.
     *
     * @param minX the smallest x coordinate
     * @param minY the smallest y coordinate
     * @param minZ the smallest z coordinate
     * @param maxX the largest x coordinate
     * @param maxY the largest y coordinate
     * @param maxZ the largest z coordinate
     * @return this instance, for chaining
     */
    public DebugLines box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        line(minX, minY, minZ, maxX, minY, minZ).line(minX, maxY, minZ, maxX, maxY, minZ).line(minX, minY, maxZ, maxX, minY, maxZ).line(minX, maxY, maxZ, maxX, maxY, maxZ);
        line(minX, minY, minZ, minX, maxY, minZ).line(maxX, minY, minZ, maxX, maxY, minZ).line(minX, minY, maxZ, minX, maxY, maxZ).line(maxX, minY, maxZ, maxX, maxY, maxZ);
        return line(minX, minY, minZ, minX, minY, maxZ).line(maxX, minY, minZ, maxX, minY, maxZ).line(minX, maxY, minZ, minX, maxY, maxZ).line(maxX, maxY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Adds the 12 edges of the oriented box.
     *
     * @param b the second oriented box; must not be {@code null}
     * @return this instance, for chaining
     */
    public DebugLines obb(Obbf b) {
        double qx = b.qx(), qy = b.qy(), qz = b.qz(), qw = b.qw();
        double n = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        qx /= n;
        qy /= n;
        qz /= n;
        qw /= n;
        // the eight corners, written as floats into the scratch array: corner i has the sign of bit 0 for x, bit 1 for y and bit 2 for z
        for (int i = 0; i < 8; i++) {
            double vx = (i & 1) == 0 ? -b.hx() : b.hx(), vy = (i & 2) == 0 ? -b.hy() : b.hy(), vz = (i & 4) == 0 ? -b.hz() : b.hz();
            double tx = 2 * (qy * vz - qz * vy), ty = 2 * (qz * vx - qx * vz), tz = 2 * (qx * vy - qy * vx);
            corners[3 * i] = (float) (b.cx() + vx + qw * tx + (qy * tz - qz * ty));
            corners[3 * i + 1] = (float) (b.cy() + vy + qw * ty + (qz * tx - qx * tz));
            corners[3 * i + 2] = (float) (b.cz() + vz + qw * tz + (qx * ty - qy * tx));
        }
        // the corners differ in one bit of the index along each edge
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) == 0) {
                    int j = i | bit;
                    line(corners[3 * i], corners[3 * i + 1], corners[3 * i + 2], corners[3 * j], corners[3 * j + 1], corners[3 * j + 2]);
                }
            }
        }
        return this;
    }

    // ------------------------------------------------------------ circles, spheres, capsules, cones

    /**
     * Adds a circle of the given radius around {@code (cx, cy, cz)} in the plane perpendicular to
     * the unit normal {@code (nx, ny, nz)}, with {@code segments} straight pieces.
     *
     * <p>The first point is in a direction chosen from the normal.
     *
     * @param cx the x coordinate of the center
     * @param cy the y coordinate of the center
     * @param cz the z coordinate of the center
     * @param nx the x component of the normal
     * @param ny the y component of the normal
     * @param nz the z component of the normal
     * @param radius the radius
     * @param segments the number of segments
     * @return this instance, for chaining
     */
    public DebugLines circle(float cx, float cy, float cz, float nx, float ny, float nz, float radius, int segments) {
        checkSegments(segments);
        double[] t = bt, b = bb;
        basis(nx, ny, nz, t, b);
        float px = 0, py = 0, pz = 0, fx = 0, fy = 0, fz = 0;
        for (int i = 0; i <= segments; i++) {
            double a = 2 * Math.PI * (i % segments) / segments, c = Math.cos(a) * radius, s = Math.sin(a) * radius;
            float x = (float) (cx + c * t[0] + s * b[0]), y = (float) (cy + c * t[1] + s * b[1]), z = (float) (cz + c * t[2] + s * b[2]);
            if (i == 0) {
                fx = x;
                fy = y;
                fz = z;
            } else {
                line(px, py, pz, i == segments ? fx : x, i == segments ? fy : y, i == segments ? fz : z);
            }
            px = x;
            py = y;
            pz = z;
        }
        return this;
    }

    /**
     * Adds three perpendicular great circles (in the planes of the axes) of the sphere:
     * {@code 3 * segments} lines.
     *
     * @param cx the x coordinate of the center
     * @param cy the y coordinate of the center
     * @param cz the z coordinate of the center
     * @param radius the radius
     * @param segments the number of segments
     * @return this instance, for chaining
     */
    public DebugLines sphere(float cx, float cy, float cz, float radius, int segments) {
        return circle(cx, cy, cz, 1, 0, 0, radius, segments).circle(cx, cy, cz, 0, 1, 0, radius, segments).circle(cx, cy, cz, 0, 0, 1, radius, segments);
    }

    /**
     * Adds the great circles of a sphere.
     *
     * @param s the sphere; must not be {@code null}
     * @param segments the number of segments
     * @return this instance, for chaining
     */
    public DebugLines sphere(Spheref s, int segments) {
        return sphere(s.cx(), s.cy(), s.cz(), s.radius(), segments);
    }

    /**
     * Adds the outline of a capsule: a circle around each end of the axis, four lines along it, and
     * in two perpendicular planes through the axis a half circle over each end ({@code segments}
     * must be even).
     *
     * <p>A capsule with a zero-length axis is drawn as a sphere.
     *
     * @param c the capsule; must not be {@code null}
     * @param segments the number of segments
     * @return this instance, for chaining
     * @throws IllegalArgumentException if {@code segments} is odd
     */
    public DebugLines capsule(Capsulef c, int segments) {
        checkSegments(segments);
        if (segments % 2 != 0) {
            throw new IllegalArgumentException("the number of segments of a capsule must be even: " + segments);
        }
        double ux = c.bx() - c.ax(), uy = c.by() - c.ay(), uz = c.bz() - c.az();
        double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (len < 1e-12) {
            return sphere(c.ax(), c.ay(), c.az(), c.radius(), segments);
        }
        ux /= len;
        uy /= len;
        uz /= len;
        double[] v = cv, w = cw;
        basis((float) ux, (float) uy, (float) uz, v, w);
        float r = c.radius();
        circle(c.ax(), c.ay(), c.az(), (float) ux, (float) uy, (float) uz, r, segments);
        circle(c.bx(), c.by(), c.bz(), (float) ux, (float) uy, (float) uz, r, segments);
        for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
            double[] s = sideIndex == 0 ? v : w;
            for (int sign = -1; sign <= 1; sign += 2) {
                float ox = (float) (sign * r * s[0]), oy = (float) (sign * r * s[1]), oz = (float) (sign * r * s[2]);
                line(c.ax() + ox, c.ay() + oy, c.az() + oz, c.bx() + ox, c.by() + oy, c.bz() + oz);
            }
            // half circles over the two ends, in the plane of the axis and this side direction
            int half = segments / 2;
            for (int end = 0; end < 2; end++) {
                double ex = end == 0 ? c.ax() : c.bx(), ey = end == 0 ? c.ay() : c.by(), ez = end == 0 ? c.az() : c.bz();
                double dir = end == 0 ? -1 : 1; // the end cap points away from the axis
                float px = 0, py = 0, pz = 0;
                for (int i = 0; i <= half; i++) {
                    double a = Math.PI * i / half, cs = Math.cos(a), sn = Math.sin(a);
                    // from one side of the end circle over the pole to the other: the side direction goes from +s to -s
                    float x = (float) (ex + r * (sn * dir * ux + cs * s[0]));
                    float y = (float) (ey + r * (sn * dir * uy + cs * s[1]));
                    float z = (float) (ez + r * (sn * dir * uz + cs * s[2]));
                    if (i > 0) {
                        line(px, py, pz, x, y, z);
                    }
                    px = x;
                    py = y;
                    pz = z;
                }
            }
        }
        return this;
    }

    /**
     * Adds the outline of a cone, the gizmo of a spot light: a circle of the radius
     * {@code length * tan(halfAngle)} at the distance {@code length} along the unit direction
     * {@code (dx, dy, dz)} from the apex, and four lines from the apex to it (and with
     * {@code segments} divisible by 4, lines to every quarter of the circle).
     *
     * @param ax the x coordinate of the apex
     * @param ay the y coordinate of the apex
     * @param az the z coordinate of the apex
     * @param dx the x component of the direction
     * @param dy the y component of the direction
     * @param dz the z component of the direction
     * @param length the length
     * @param halfAngle the half angle
     * @param segments the number of segments
     * @return this instance, for chaining
     */
    public DebugLines cone(float ax, float ay, float az, float dx, float dy, float dz, float length, float halfAngle, int segments) {
        checkSegments(segments);
        float radius = (float) (length * Math.tan(halfAngle));
        float cx = ax + dx * length, cy = ay + dy * length, cz = az + dz * length;
        circle(cx, cy, cz, dx, dy, dz, radius, segments);
        double[] t = bt, b = bb;
        basis(dx, dy, dz, t, b);
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 2 * k, c = Math.cos(a) * radius, s = Math.sin(a) * radius;
            line(ax, ay, az, (float) (cx + c * t[0] + s * b[0]), (float) (cy + c * t[1] + s * b[1]), (float) (cz + c * t[2] + s * b[2]));
        }
        return this;
    }

    // ------------------------------------------------------------ frames, grids, arrows

    /**
     * Adds the three axes of a frame as lines of length {@code size} from the origin
     * {@code (ox, oy, oz)}: the x axis in red, y in green and z in blue, whatever the current
     * colour, which is restored afterwards.
     *
     * <p>{@code (xx, xy, xz)} and the following two triples are the directions of the axes (the
     * columns of a rotation matrix).
     *
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @param oz the z coordinate of the origin
     * @param xx the x component of the x axis
     * @param xy the y component of the x axis
     * @param xz the z component of the x axis
     * @param yx the x component of the y axis
     * @param yy the y component of the y axis
     * @param yz the z component of the y axis
     * @param zx the x component of the z axis
     * @param zy the y component of the z axis
     * @param zz the z component of the z axis
     * @param size the size
     * @return this instance, for chaining
     */
    public DebugLines axes(float ox, float oy, float oz, float xx, float xy, float xz, float yx, float yy, float yz, float zx, float zy, float zz, float size) {
        int saved = color;
        color = RED;
        line(ox, oy, oz, ox + xx * size, oy + xy * size, oz + xz * size);
        color = GREEN;
        line(ox, oy, oz, ox + yx * size, oy + yy * size, oz + yz * size);
        color = BLUE;
        line(ox, oy, oz, ox + zx * size, oy + zy * size, oz + zz * size);
        color = saved;
        return this;
    }

    /**
     * Adds the axes of a transform given as a column-major matrix: the origin is its translation
     * and the axes are its columns (normalised, then scaled by {@code size}).
     *
     * @param m the matrix; must not be {@code null}
     * @param size the size
     * @return this instance, for chaining
     */
    public DebugLines axes(Mat4f m, float size) {
        float lx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        float ly = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12());
        float lz = (float) Math.sqrt(m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22());
        return axes(m.m30(), m.m31(), m.m32(), m.m00() / lx, m.m01() / lx, m.m02() / lx, m.m10() / ly, m.m11() / ly, m.m12() / ly, m.m20() / lz, m.m21() / lz, m.m22() / lz, size);
    }

    /**
     * Adds a grid in the plane {@code y = height}, centred at {@code (cx, cz)}, with
     * {@code divisions} cells along each side of length {@code size}: {@code 2 (divisions + 1)}
     * lines.
     *
     * @param cx the x coordinate of the center
     * @param height the height
     * @param cz the z coordinate of the center
     * @param size the size
     * @param divisions the divisions
     * @return this instance, for chaining
     * @throws IllegalArgumentException if {@code divisions} is below 1
     */
    public DebugLines grid(float cx, float height, float cz, float size, int divisions) {
        if (divisions < 1) {
            throw new IllegalArgumentException("the grid needs at least one division: " + divisions);
        }
        float half = size / 2;
        for (int i = 0; i <= divisions; i++) {
            float t = -half + size * i / divisions;
            line(cx - half, height, cz + t, cx + half, height, cz + t);
            line(cx + t, height, cz - half, cx + t, height, cz + half);
        }
        return this;
    }

    /**
     * Adds an arrow from {@code from} to {@code to}: the shaft and four lines forming the head,
     * whose length is {@code headLength} (the same for a short arrow) and which opens to a quarter
     * of it.
     *
     * @param from the from; must not be {@code null}
     * @param to the vector; must not be {@code null}
     * @param headLength the head length
     * @return this instance, for chaining
     */
    public DebugLines arrow(Vec3f from, Vec3f to, float headLength) {
        line(from, to);
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-12) {
            return this;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        double head = Math.min(headLength, len);
        double[] t = bt, b = bb;
        basis((float) dx, (float) dy, (float) dz, t, b);
        double bx = to.x() - dx * head, by = to.y() - dy * head, bz = to.z() - dz * head;
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 2 * k, r = head * 0.25;
            line((float) bx + (float) (r * (Math.cos(a) * t[0] + Math.sin(a) * b[0])), (float) by + (float) (r * (Math.cos(a) * t[1] + Math.sin(a) * b[1])),
                    (float) bz + (float) (r * (Math.cos(a) * t[2] + Math.sin(a) * b[2])), to.x(), to.y(), to.z());
        }
        return this;
    }

    // ------------------------------------------------------------ frusta and skeletons

    /**
     * Adds the 12 edges of the frustum of a view-projection matrix: the eight corners are the NDC
     * cube unprojected, with the depth values of the {@link DepthRange} (near 0 and far 1 for
     * {@code ZERO_TO_ONE}, -1 and 1 for {@code NEGATIVE_ONE_TO_ONE}, 1 and 0 for
     * {@code REVERSED_ZERO_TO_ONE}).
     *
     * <p>When the far plane is at infinity (an infinite projection) the far corners are placed
     * {@code maxDistance} along the edges from the near corners.
     *
     * @param viewProjection the view projection; must not be {@code null}
     * @param depth the depth; must not be {@code null}
     * @param maxDistance the max distance
     * @return this instance, for chaining
     */
    public DebugLines frustum(Mat4f viewProjection, DepthRange depth, float maxDistance) {
        Mat4f inverse = viewProjection.invert(); // allocates; a frustum is drawn once per camera, not per object
        float zNear, zFar;
        switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> {
                zNear = -1f;
                zFar = 1f;
            }
            case ZERO_TO_ONE -> {
                zNear = 0f;
                zFar = 1f;
            }
            default -> {
                zNear = 1f;
                zFar = 0f;
            }
        }
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? -1f : 1f, y = (i & 2) == 0 ? -1f : 1f;
            Vec4f n = inverse.transform(new Vec4f(x, y, zNear, 1f));
            Vec4f f = inverse.transform(new Vec4f(x, y, zFar, 1f));
            float nx = n.x() / n.w(), ny = n.y() / n.w(), nz = n.z() / n.w();
            float fx, fy, fz;
            if (Math.abs(f.w()) < 1e-6f * (Math.abs(f.x()) + Math.abs(f.y()) + Math.abs(f.z()) + 1e-30f)) {
                // the far corner is at infinity: go maxDistance from the near corner along the edge, whose direction is found from a point halfway in depth
                Vec4f m = inverse.transform(new Vec4f(x, y, (zNear + zFar) / 2f, 1f));
                float dx = m.x() / m.w() - nx, dy = m.y() / m.w() - ny, dz = m.z() / m.w() - nz;
                float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                fx = nx + dx / l * maxDistance;
                fy = ny + dy / l * maxDistance;
                fz = nz + dz / l * maxDistance;
            } else {
                fx = f.x() / f.w();
                fy = f.y() / f.w();
                fz = f.z() / f.w();
            }
            corners[3 * i] = nx;
            corners[3 * i + 1] = ny;
            corners[3 * i + 2] = nz;
            corners[12 + 3 * i] = fx;
            corners[12 + 3 * i + 1] = fy;
            corners[12 + 3 * i + 2] = fz;
        }
        // near rectangle, far rectangle, and the four edges between them; corner index bit 0 is x, bit 1 is y
        int[][] rect = {{0, 1}, {1, 3}, {3, 2}, {2, 0}};
        for (int[] e : rect) {
            line(corners[3 * e[0]], corners[3 * e[0] + 1], corners[3 * e[0] + 2], corners[3 * e[1]], corners[3 * e[1] + 1], corners[3 * e[1] + 2]);
            line(corners[12 + 3 * e[0]], corners[12 + 3 * e[0] + 1], corners[12 + 3 * e[0] + 2], corners[12 + 3 * e[1]], corners[12 + 3 * e[1] + 1], corners[12 + 3 * e[1] + 2]);
        }
        for (int i = 0; i < 4; i++) {
            line(corners[3 * i], corners[3 * i + 1], corners[3 * i + 2], corners[12 + 3 * i], corners[12 + 3 * i + 1], corners[12 + 3 * i + 2]);
        }
        return this;
    }

    /**
     * Adds one line from each joint of the skeleton to its parent, from the world matrices (16
     * floats per joint, column-major, as {@code Skinning.worldMatrices} writes them; the position
     * of a joint is the translation column at 12 to 14).
     *
     * <p>A positive {@code markerSize} also adds a cross of that half size at every joint.
     *
     * @param skeleton the skeleton; must not be {@code null}
     * @param worldMatrices the world matrices
     * @param markerSize the marker size
     * @return this instance, for chaining
     * @throws IllegalArgumentException if {@code worldMatrices} holds fewer than 16 floats per
     *     joint
     */
    public DebugLines skeleton(Skeleton skeleton, float[] worldMatrices, float markerSize) {
        int n = skeleton.jointCount();
        if (worldMatrices.length < 16 * n) {
            throw new IllegalArgumentException("the skeleton has " + n + " joints and needs " + 16 * n + " floats of world matrices, got " + worldMatrices.length);
        }
        for (int j = 0; j < n; j++) {
            int p = skeleton.parent(j);
            if (p >= 0) {
                line(worldMatrices[16 * p + 12], worldMatrices[16 * p + 13], worldMatrices[16 * p + 14], worldMatrices[16 * j + 12], worldMatrices[16 * j + 13], worldMatrices[16 * j + 14]);
            }
            if (markerSize > 0f) {
                cross(worldMatrices[16 * j + 12], worldMatrices[16 * j + 13], worldMatrices[16 * j + 14], markerSize);
            }
        }
        return this;
    }

    // ------------------------------------------------------------ helpers

    private static void checkSegments(int segments) {
        if (segments < 3) {
            throw new IllegalArgumentException("a circle needs at least 3 segments: " + segments);
        }
    }

    /**
     * Two unit vectors {@code t, b} perpendicular to the unit vector {@code n} and to each other
     * (the basis of Duff et al.).
     */
    private static void basis(float nx, float ny, float nz, double[] t, double[] b) {
        double sign = Math.copySign(1.0, nz);
        double c = -1.0 / (sign + nz), d = nx * ny * c;
        t[0] = 1.0 + sign * nx * nx * c;
        t[1] = sign * d;
        t[2] = -sign * nx;
        b[0] = d;
        b[1] = sign + ny * ny * c;
        b[2] = -ny;
    }
}
