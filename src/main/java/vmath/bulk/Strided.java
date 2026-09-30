package vmath.bulk;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Copies runs of floats between a {@code float[]} and a byte-addressed destination with a chosen byte order and a byte stride between elements, so a
 * container can be written straight into one attribute of an interleaved vertex or instance buffer.
 *
 * <p>An element is {@code components} consecutive floats; element {@code i} is at byte {@code offset + i * stride} of the destination. A stride equal to
 * {@code components * 4} is the tightly packed case and is copied in one bulk operation (when the byte order is the native one). A larger stride
 * leaves the bytes between elements untouched, which is how other attributes of an interleaved buffer survive. Bounds are checked by the destination.
 * Nothing allocates.
 */
public final class Strided {

    private Strided() {
    }

    private static final ValueLayout.OfFloat NATIVE = ValueLayout.JAVA_FLOAT_UNALIGNED;

    private static void checkStride(int components, long stride) {
        if (components <= 0) {
            throw new IllegalArgumentException("components must be positive: " + components);
        }
        if (stride < (long) components * Float.BYTES) {
            throw new IllegalArgumentException("stride " + stride + " is smaller than one element (" + components * Float.BYTES + " bytes)");
        }
    }

    /** Writes {@code count} elements of {@code components} floats from {@code src} (starting at float index {@code srcOffset}) into {@code dst}. */
    public static void write(float[] src, int srcOffset, int components, int count, MemorySegment dst, long offset, long stride, ByteOrder order) {
        checkStride(components, stride);
        ValueLayout.OfFloat layout = NATIVE.withOrder(order);
        if (stride == (long) components * Float.BYTES && order == ByteOrder.nativeOrder()) {
            MemorySegment.copy(src, srcOffset, dst, layout, offset, count * components);
            return;
        }
        int s = srcOffset;
        long d = offset;
        for (int i = 0; i < count; i++, d += stride) {
            for (int c = 0; c < components; c++) {
                dst.set(layout, d + (long) c * Float.BYTES, src[s++]);
            }
        }
    }

    /** As {@link #write(float[], int, int, int, MemorySegment, long, long, ByteOrder)} into a {@link ByteBuffer}, at absolute byte positions, in the buffer's own byte order. */
    public static void write(float[] src, int srcOffset, int components, int count, ByteBuffer dst, int offset, int stride) {
        checkStride(components, stride);
        int s = srcOffset;
        int d = offset;
        for (int i = 0; i < count; i++, d += stride) {
            for (int c = 0; c < components; c++) {
                dst.putFloat(d + c * Float.BYTES, src[s++]);
            }
        }
    }

    /** Reads {@code count} elements of {@code components} floats from {@code src} into {@code dst} (starting at float index {@code dstOffset}). */
    public static void read(MemorySegment src, long offset, long stride, ByteOrder order, float[] dst, int dstOffset, int components, int count) {
        checkStride(components, stride);
        ValueLayout.OfFloat layout = NATIVE.withOrder(order);
        if (stride == (long) components * Float.BYTES && order == ByteOrder.nativeOrder()) {
            MemorySegment.copy(src, layout, offset, dst, dstOffset, count * components);
            return;
        }
        int d = dstOffset;
        long s = offset;
        for (int i = 0; i < count; i++, s += stride) {
            for (int c = 0; c < components; c++) {
                dst[d++] = src.get(layout, s + (long) c * Float.BYTES);
            }
        }
    }

    /** As {@link #read(MemorySegment, long, long, ByteOrder, float[], int, int, int)} from a {@link ByteBuffer}, at absolute byte positions, in the buffer's own byte order. */
    public static void read(ByteBuffer src, int offset, int stride, float[] dst, int dstOffset, int components, int count) {
        checkStride(components, stride);
        int d = dstOffset;
        int s = offset;
        for (int i = 0; i < count; i++, s += stride) {
            for (int c = 0; c < components; c++) {
                dst[d++] = src.getFloat(s + c * Float.BYTES);
            }
        }
    }
}
