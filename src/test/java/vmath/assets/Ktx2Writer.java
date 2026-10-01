package vmath.assets;

import java.nio.charset.StandardCharsets;

/**
 * Writes complete KTX 2.0 files in the layout the specification prescribes: the 80-byte header, the level index, the data format descriptor, the key/value data,
 * and the image data of every level from the smallest level to the largest, each level starting at a 16-byte boundary. Not validated by an external tool (none is
 * available offline); the tests read the files back with the library's own parser and decode the pixels.
 */
public final class Ktx2Writer {

    private Ktx2Writer() {
    }

    /** A sample of a data format descriptor. */
    public record Sample(int bitOffset, int bitLength, int channelType, boolean linear, long lower, long upper) {
    }

    /** A basic data format descriptor block (KHR_DF). */
    public static byte[] dfd(int colorModel, int primaries, int transfer, int[] blockDimensions, int bytesPlane0, Sample... samples) {
        int blockSize = 24 + 16 * samples.length;
        Bin b = new Bin();
        b.u32(4 + blockSize);                                      // totalSize
        b.u32(0);                                                  // vendorId 0, descriptorType 0 (basic)
        b.u32(2 | (long) blockSize << 16);                         // versionNumber 2, descriptorBlockSize
        b.u32(colorModel | (long) primaries << 8 | (long) transfer << 16);
        b.u32((blockDimensions[0] - 1) | (long) (blockDimensions[1] - 1) << 8 | (long) (blockDimensions[2] - 1) << 16 | (long) (blockDimensions[3] - 1) << 24);
        b.u32(bytesPlane0).u32(0);
        for (Sample s : samples) {
            b.u32(s.bitOffset() | (long) (s.bitLength() - 1) << 16 | (long) s.channelType() << 24 | (s.linear() ? 1L << 28 : 0));
            b.u32(0);
            b.u32(s.lower());
            b.u32(s.upper());
        }
        return b.toBytes();
    }

    /** Key/value data: entries sorted by key, each padded to 4 bytes. */
    public static byte[] kvd(String[][] sortedEntries) {
        Bin b = new Bin();
        for (String[] e : sortedEntries) {
            byte[] key = e[0].getBytes(StandardCharsets.UTF_8), value = e[1].getBytes(StandardCharsets.UTF_8);
            b.u32(key.length + 1 + value.length + 1);
            b.bytes(key).u8(0).bytes(value).u8(0);
            b.align(4);
        }
        return b.toBytes();
    }

    /**
     * @param levelCount the value stored in the header (0 means "generate the rest at load time"; the file then holds one level)
     * @param levels the image data of each stored level, level 0 first; each is all layers, faces and slices of that level in KTX2 order
     */
    public static byte[] write(int vkFormat, int typeSize, int width, int height, int depth, int layers, int faces, int levelCount, byte[] dfd, byte[] kvd,
                               byte[][] levels) {
        int stored = Math.max(1, levelCount);
        if (levels.length != stored) {
            throw new IllegalArgumentException("need " + stored + " levels, got " + levels.length);
        }
        int dfdOffset = align(80 + stored * 24, 4);
        int kvdOffset = align(dfdOffset + dfd.length, 4);
        int dataStart = align(kvdOffset + kvd.length, 16);
        long[] offset = new long[stored];
        long at = dataStart;
        for (int l = stored - 1; l >= 0; l--) { // the smallest level comes first in the file
            at = align((int) at, 16);
            offset[l] = at;
            at += levels[l].length;
        }
        Bin b = new Bin();
        b.bytes(new byte[] {(byte) 0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xBB, 0x0D, 0x0A, 0x1A, 0x0A});
        b.u32(vkFormat, typeSize, width, height, depth, layers, faces, levelCount, 0);
        b.u32(dfdOffset, dfd.length, kvdOffset, kvd.length);
        b.u64(0, 0); // no supercompression global data
        for (int l = 0; l < stored; l++) {
            b.u64(offset[l], levels[l].length, levels[l].length);
        }
        b.align(4);
        if (b.size() != dfdOffset) {
            throw new IllegalStateException("layout error: " + b.size() + " != " + dfdOffset);
        }
        b.bytes(dfd);
        b.align(4);
        b.bytes(kvd);
        for (int l = stored - 1; l >= 0; l--) {
            b.align(16);
            if (b.size() != offset[l]) {
                throw new IllegalStateException("level " + l + " offset mismatch");
            }
            b.bytes(levels[l]);
        }
        return b.toBytes();
    }

    private static int align(int v, int a) {
        return (v + a - 1) / a * a;
    }
}
