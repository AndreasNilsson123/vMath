package vmath.lines;

import java.lang.foreign.MemorySegment;
import java.util.List;
import vmath.annotations.Experimental;
import vmath.gl.GlslType;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;
import vmath.gl.GpuLayout;
import vmath.gl.GpuWriter;
import vmath.gl.StructLayout;

/**
 * The records that the line strategies put in buffers: one per segment and one per style.
 *
 * <p>Both are made of scalars and vectors only, so that every access mode of
 * {@link vmath.gl.StructArrayAccess} can read them, and are written in the {@code std430} layout:
 *
 * <pre>
 * LineSegment (48 bytes):  vec3 p0, float along0, vec3 p1, float along1, vec3 prev, uint flagsAndStyle
 * LineStyle   (64 bytes):  vec4 color, vec4 dash0, vec4 dash1, float width, float miterLimit, uint flags, uint dashCount
 * </pre>
 *
 * <p>The positions are relative to the origin of the batch. {@code flagsAndStyle} holds the style
 * index in its low 24 bits and the segment flags of {@link LineGeometry} above them. The style
 * {@code flags} hold the cap in bits 0 and 1, the join in bits 2 and 3 and the width unit in bit 4
 * (1 for world units).
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads that write to
 * different places.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MemorySegment styles = MemorySegment.ofArray(new byte[(int) LineGpu.STYLE_BYTES]);
 * LineGpu.writeStyle(styles, 0, LineStyle.pixels(2f).withColor(0xFF0000FF));
 * }</pre>
 */
@Experimental("the record layouts may change")
public final class LineGpu {

    /** The element type of the segment buffer. */
    public static final Struct SEGMENT = new Struct("LineSegment", List.of(
            new Member("p0", GlslType.VEC3), new Member("along0", GlslType.FLOAT),
            new Member("p1", GlslType.VEC3), new Member("along1", GlslType.FLOAT),
            new Member("prev", GlslType.VEC3), new Member("flagsAndStyle", GlslType.UINT)));

    /** The element type of the style buffer. */
    public static final Struct STYLE = new Struct("LineStyle", List.of(
            new Member("color", GlslType.VEC4), new Member("dash0", GlslType.VEC4), new Member("dash1", GlslType.VEC4),
            new Member("width", GlslType.FLOAT), new Member("miterLimit", GlslType.FLOAT), new Member("flags", GlslType.UINT), new Member("dashCount", GlslType.UINT)));

    /** The layout of a segment record. */
    public static final StructLayout SEGMENT_LAYOUT = SEGMENT.layout(GpuLayout.STD430);
    /** The layout of a style record. */
    public static final StructLayout STYLE_LAYOUT = STYLE.layout(GpuLayout.STD430);

    /** The size of a segment record in bytes. */
    public static final long SEGMENT_BYTES = SEGMENT_LAYOUT.size();
    /** The size of a style record in bytes. */
    public static final long STYLE_BYTES = STYLE_LAYOUT.size();
    /** The size of a vertex of the hairline strategy in bytes: a position and a colour. */
    public static final long HAIRLINE_VERTEX_BYTES = 16;

    /** The largest style index. */
    public static final int MAX_STYLES = (1 << 24) - 1;
    /** The shift of the segment flags in {@code flagsAndStyle}. */
    public static final int FLAGS_SHIFT = 24;

    private static final long O_P0 = SEGMENT_LAYOUT.offsetOf("p0");
    private static final long O_ALONG0 = SEGMENT_LAYOUT.offsetOf("along0");
    private static final long O_P1 = SEGMENT_LAYOUT.offsetOf("p1");
    private static final long O_ALONG1 = SEGMENT_LAYOUT.offsetOf("along1");
    private static final long O_PREV = SEGMENT_LAYOUT.offsetOf("prev");
    private static final long O_PACKED = SEGMENT_LAYOUT.offsetOf("flagsAndStyle");

    private LineGpu() {
    }

    /**
     * Writes a segment record.
     *
     * @param dst the buffer; must not be {@code null}
     * @param offset the byte offset of the record
     * @param p0 the start position relative to the origin, 3 values at {@code p0Offset}
     * @param p1 the end position, 3 values at {@code p1Offset}
     * @param prev the previous position, 3 values at {@code prevOffset}
     * @param along0 the distance along the polyline at the start
     * @param along1 the distance at the end
     * @param styleIndex the index in the style table, at most {@link #MAX_STYLES}
     * @param flags the segment flags of {@link LineGeometry}
     * @throws IllegalArgumentException if the style index is out of range
     */
    public static void writeSegment(MemorySegment dst, long offset, float[] p0, float[] p1, float[] prev, float along0, float along1, int styleIndex, int flags) {
        if (styleIndex < 0 || styleIndex > MAX_STYLES) {
            throw new IllegalArgumentException("the style index must be 0 to " + MAX_STYLES + ": " + styleIndex);
        }
        for (int k = 0; k < 3; k++) {
            GpuWriter.putFloat(dst, offset + O_P0 + 4L * k, p0[k]);
            GpuWriter.putFloat(dst, offset + O_P1 + 4L * k, p1[k]);
            GpuWriter.putFloat(dst, offset + O_PREV + 4L * k, prev[k]);
        }
        GpuWriter.putFloat(dst, offset + O_ALONG0, along0);
        GpuWriter.putFloat(dst, offset + O_ALONG1, along1);
        GpuWriter.putInt(dst, offset + O_PACKED, styleIndex | flags << FLAGS_SHIFT);
    }

    /**
     * Reads one component of a segment record, for the CPU model of the shaders.
     *
     * @param src the buffer
     * @param offset the byte offset of the record
     * @param field 0 {@code p0}, 1 {@code p1}, 2 {@code prev}
     * @param axis 0, 1 or 2
     * @return the value
     */
    static float position(MemorySegment src, long offset, int field, int axis) {
        long base = field == 0 ? O_P0 : field == 1 ? O_P1 : O_PREV;
        return GpuWriter.getFloat(src, offset + base + 4L * axis);
    }

    static float along(MemorySegment src, long offset, int end) {
        return GpuWriter.getFloat(src, offset + (end == 0 ? O_ALONG0 : O_ALONG1));
    }

    static int packed(MemorySegment src, long offset) {
        return GpuWriter.getInt(src, offset + O_PACKED);
    }

    /**
     * Packs the cap, the join and the width unit of a style into the style flags.
     *
     * @param s the style; must not be {@code null}
     * @return the flags
     */
    public static int styleFlags(LineStyle s) {
        return LineGeometry.capCode(s.cap()) | LineGeometry.joinCode(s.join()) << 2 | (s.unit() == LineStyle.WidthUnit.WORLD ? 1 : 0) << 4;
    }

    /**
     * Writes a style record.
     *
     * @param dst the buffer; must not be {@code null}
     * @param offset the byte offset of the record
     * @param s the style; must not be {@code null}
     */
    public static void writeStyle(MemorySegment dst, long offset, LineStyle s) {
        int c = s.color();
        float[] rgba = {(c >>> 24 & 255) / 255f, (c >>> 16 & 255) / 255f, (c >>> 8 & 255) / 255f, (c & 255) / 255f};
        float[] dash = s.dash();
        for (int k = 0; k < 4; k++) {
            GpuWriter.putFloat(dst, offset + STYLE_LAYOUT.offsetOf("color") + 4L * k, rgba[k]);
            GpuWriter.putFloat(dst, offset + STYLE_LAYOUT.offsetOf("dash0") + 4L * k, k < dash.length ? dash[k] : 0f);
            GpuWriter.putFloat(dst, offset + STYLE_LAYOUT.offsetOf("dash1") + 4L * k, 4 + k < dash.length ? dash[4 + k] : 0f);
        }
        GpuWriter.putFloat(dst, offset + STYLE_LAYOUT.offsetOf("width"), s.width());
        GpuWriter.putFloat(dst, offset + STYLE_LAYOUT.offsetOf("miterLimit"), s.miterLimit());
        GpuWriter.putInt(dst, offset + STYLE_LAYOUT.offsetOf("flags"), styleFlags(s));
        GpuWriter.putInt(dst, offset + STYLE_LAYOUT.offsetOf("dashCount"), dash.length);
    }

    /**
     * Reads the style record at an offset back into numbers, for the CPU model of the shaders.
     *
     * @param src the buffer
     * @param offset the byte offset of the record
     * @param dashOut receives the eight dash numbers; must have room for 8 values
     * @param scalars receives {@code width, miterLimit} at 0 and 1; must have room for 2 values
     * @return {@code flags} in the low 16 bits and {@code dashCount} above them, and the colour
     *     is in {@link #colorOf}
     */
    static int readStyle(MemorySegment src, long offset, float[] dashOut, float[] scalars) {
        for (int k = 0; k < 4; k++) {
            dashOut[k] = GpuWriter.getFloat(src, offset + STYLE_LAYOUT.offsetOf("dash0") + 4L * k);
            dashOut[4 + k] = GpuWriter.getFloat(src, offset + STYLE_LAYOUT.offsetOf("dash1") + 4L * k);
        }
        scalars[0] = GpuWriter.getFloat(src, offset + STYLE_LAYOUT.offsetOf("width"));
        scalars[1] = GpuWriter.getFloat(src, offset + STYLE_LAYOUT.offsetOf("miterLimit"));
        int flags = GpuWriter.getInt(src, offset + STYLE_LAYOUT.offsetOf("flags"));
        int dashCount = GpuWriter.getInt(src, offset + STYLE_LAYOUT.offsetOf("dashCount"));
        return flags | dashCount << 16;
    }

    static int colorOf(MemorySegment src, long offset) {
        long o = offset + STYLE_LAYOUT.offsetOf("color");
        int r = Math.round(GpuWriter.getFloat(src, o) * 255f), g = Math.round(GpuWriter.getFloat(src, o + 4) * 255f);
        int b = Math.round(GpuWriter.getFloat(src, o + 8) * 255f), a = Math.round(GpuWriter.getFloat(src, o + 12) * 255f);
        return r << 24 | g << 16 | b << 8 | a;
    }
}
