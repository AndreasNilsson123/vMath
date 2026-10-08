package vmath.map;

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
 * The record that the symbol strategies put in a buffer: one per symbol.
 *
 * <p>It is made of scalars and vectors only, so that every access mode of
 * {@link vmath.gl.StructArrayAccess} can read it, and is written in the {@code std430} layout:
 *
 * <pre>
 * MapSymbol (48 bytes):  vec2 position, float angle, float size, vec4 uv, uint color, uint flags, vec2 offsetPixels
 * </pre>
 *
 * <p>{@code position} is relative to the origin of the batch, in projected metres. {@code angle} is
 * counter-clockwise radians in the map frame (a symbol that points up the sprite at a heading of
 * {@code b} radians clockwise from grid north has the angle {@code -b}). {@code size} is the side of
 * the square in pixels, or in projected metres with {@link #SIZE_IN_MAP_UNITS}. {@code uv} is the
 * atlas rectangle {@code u0, v0, u1, v1} of the sprite: {@code (u0, v0)} is its top left corner.
 * {@code color} is {@code 0xRRGGBBAA}, multiplied with the sprite. {@code offsetPixels} moves the
 * symbol on the screen, after the rotation (a declutter leader offset).
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads that write to
 * different places.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MemorySegment buffer = MemorySegment.ofArray(new byte[(int) SymbolGpu.BYTES]);
 * SymbolGpu.write(buffer, 0, 10f, 20f, 0f, 24f, new float[] {0f, 0f, 0.5f, 0.5f}, 0xFFFFFFFF, SymbolGpu.ROTATE_WITH_MAP, 0f, 0f);
 * }</pre>
 */
@Experimental("the record layout may change")
public final class SymbolGpu {

    /** Flag: the angle is relative to the map, so the shader adds the rotation of the map. */
    public static final int ROTATE_WITH_MAP = 1;
    /** Flag: the size is in projected metres, not pixels. */
    public static final int SIZE_IN_MAP_UNITS = 2;
    /** Flag: the symbol is not drawn (it was removed by the declutter, say). */
    public static final int HIDDEN = 4;

    /** The element type of the symbol buffer. */
    public static final Struct SYMBOL = new Struct("MapSymbol", List.of(
            new Member("position", GlslType.VEC2), new Member("angle", GlslType.FLOAT), new Member("size", GlslType.FLOAT),
            new Member("uv", GlslType.VEC4),
            new Member("color", GlslType.UINT), new Member("flags", GlslType.UINT), new Member("offsetPixels", GlslType.VEC2)));

    /** The layout of a symbol record. */
    public static final StructLayout LAYOUT = SYMBOL.layout(GpuLayout.STD430);

    /** The size of a symbol record in bytes. */
    public static final long BYTES = LAYOUT.size();

    private static final long O_POSITION = LAYOUT.offsetOf("position");
    private static final long O_ANGLE = LAYOUT.offsetOf("angle");
    private static final long O_SIZE = LAYOUT.offsetOf("size");
    private static final long O_UV = LAYOUT.offsetOf("uv");
    private static final long O_COLOR = LAYOUT.offsetOf("color");
    private static final long O_FLAGS = LAYOUT.offsetOf("flags");
    private static final long O_OFFSET = LAYOUT.offsetOf("offsetPixels");

    private SymbolGpu() {
    }

    /**
     * Writes a symbol record.
     *
     * @param dst the buffer; must not be {@code null}
     * @param offset the byte offset of the record
     * @param x the position east of the origin, in projected metres
     * @param y the position north of the origin
     * @param angle the counter-clockwise angle in radians
     * @param size the side of the square, in pixels or (with {@link #SIZE_IN_MAP_UNITS}) projected metres
     * @param uv the atlas rectangle {@code u0, v0, u1, v1}, 4 values; must not be {@code null}
     * @param color the colour, {@code 0xRRGGBBAA}
     * @param flags the flags: {@link #ROTATE_WITH_MAP}, {@link #SIZE_IN_MAP_UNITS}, {@link #HIDDEN}
     * @param offsetX the screen offset to the right in pixels
     * @param offsetY the screen offset upwards in pixels
     * @throws IllegalArgumentException if {@code uv} has fewer than 4 values
     */
    public static void write(MemorySegment dst, long offset, float x, float y, float angle, float size, float[] uv, int color, int flags, float offsetX, float offsetY) {
        if (uv.length < 4) {
            throw new IllegalArgumentException("uv needs 4 values");
        }
        GpuWriter.putFloat(dst, offset + O_POSITION, x);
        GpuWriter.putFloat(dst, offset + O_POSITION + 4, y);
        GpuWriter.putFloat(dst, offset + O_ANGLE, angle);
        GpuWriter.putFloat(dst, offset + O_SIZE, size);
        for (int k = 0; k < 4; k++) {
            GpuWriter.putFloat(dst, offset + O_UV + 4L * k, uv[k]);
        }
        GpuWriter.putInt(dst, offset + O_COLOR, color);
        GpuWriter.putInt(dst, offset + O_FLAGS, flags);
        GpuWriter.putFloat(dst, offset + O_OFFSET, offsetX);
        GpuWriter.putFloat(dst, offset + O_OFFSET + 4, offsetY);
    }

    /**
     * Reads one float field of a record, for the CPU model of the shaders.
     *
     * @param src the buffer
     * @param offset the byte offset of the record
     * @param field 0 position x, 1 position y, 2 angle, 3 size, 4 to 7 the uv rectangle, 8 and 9 the offset
     * @return the value
     * @throws IllegalArgumentException if {@code field} is not 0 to 9
     */
    public static float read(MemorySegment src, long offset, int field) {
        return switch (field) {
            case 0 -> GpuWriter.getFloat(src, offset + O_POSITION);
            case 1 -> GpuWriter.getFloat(src, offset + O_POSITION + 4);
            case 2 -> GpuWriter.getFloat(src, offset + O_ANGLE);
            case 3 -> GpuWriter.getFloat(src, offset + O_SIZE);
            case 4, 5, 6, 7 -> GpuWriter.getFloat(src, offset + O_UV + 4L * (field - 4));
            case 8 -> GpuWriter.getFloat(src, offset + O_OFFSET);
            case 9 -> GpuWriter.getFloat(src, offset + O_OFFSET + 4);
            default -> throw new IllegalArgumentException("field must be 0 to 9: " + field);
        };
    }

    /**
     * Reads the colour of a record.
     *
     * @param src the buffer
     * @param offset the byte offset of the record
     * @return {@code 0xRRGGBBAA}
     */
    public static int color(MemorySegment src, long offset) {
        return GpuWriter.getInt(src, offset + O_COLOR);
    }

    /**
     * Reads the flags of a record.
     *
     * @param src the buffer
     * @param offset the byte offset of the record
     * @return the flag bits
     */
    public static int flags(MemorySegment src, long offset) {
        return GpuWriter.getInt(src, offset + O_FLAGS);
    }
}
