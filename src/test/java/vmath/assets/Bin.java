package vmath.assets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** A growing little-endian byte buffer for building binary files in tests. */
public final class Bin {
    private byte[] data = new byte[256];
    private int size;

    private void room(int n) {
        if (size + n > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, size + n));
        }
    }

    public int size() {
        return size;
    }

    public Bin u8(int... v) {
        for (int x : v) {
            room(1);
            data[size++] = (byte) x;
        }
        return this;
    }

    public Bin u16(int... v) {
        for (int x : v) {
            room(2);
            data[size++] = (byte) x;
            data[size++] = (byte) (x >> 8);
        }
        return this;
    }

    public Bin u32(long... v) {
        for (long x : v) {
            room(4);
            for (int i = 0; i < 4; i++) {
                data[size++] = (byte) (x >> (8 * i));
            }
        }
        return this;
    }

    public Bin u64(long... v) {
        for (long x : v) {
            room(8);
            for (int i = 0; i < 8; i++) {
                data[size++] = (byte) (x >> (8 * i));
            }
        }
        return this;
    }

    public Bin f32(float... v) {
        for (float x : v) {
            u32(Float.floatToIntBits(x) & 0xFFFFFFFFL);
        }
        return this;
    }

    public Bin bytes(byte[] b) {
        room(b.length);
        System.arraycopy(b, 0, data, size, b.length);
        size += b.length;
        return this;
    }

    /** Pads with zero bytes until the size is a multiple of {@code n}. */
    public Bin align(int n) {
        while (size % n != 0) {
            u8(0);
        }
        return this;
    }

    /** Overwrites a 32-bit value at {@code offset}. */
    public Bin patch32(int offset, long value) {
        for (int i = 0; i < 4; i++) {
            data[offset + i] = (byte) (value >> (8 * i));
        }
        return this;
    }

    public byte[] toBytes() {
        return Arrays.copyOf(data, size);
    }

    public ByteBuffer toBuffer() {
        return ByteBuffer.wrap(toBytes()).order(ByteOrder.LITTLE_ENDIAN);
    }
}
