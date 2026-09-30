package vmath.tex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import vmath.annotations.Experimental;

/**
 * Reads the header and the level index of a KTX 2.0 file. It does not decode pixels and does not decompress supercompressed data (Basis Universal, Zstandard,
 * ZLIB): it tells you where each level is and how it is stored, so a loader can hand the bytes to the GPU or to a transcoder.
 *
 * <p>Layout of the file (all little-endian), from the KTX 2.0 specification: a 12-byte identifier, then nine 32-bit header fields ({@code vkFormat, typeSize,
 * pixelWidth, pixelHeight, pixelDepth, layerCount, faceCount, levelCount, supercompressionScheme}), four 32-bit index fields for the data format descriptor
 * and the key/value data, two 64-bit fields for the supercompression global data, and then {@code max(levelCount, 1)} level index entries of three 64-bit values
 * ({@code byteOffset, byteLength, uncompressedByteLength}).
 */
@Experimental("covers the header and level index only; key/value data and the data format descriptor are reported as ranges, not parsed")
public final class Ktx2 {

    private Ktx2() {
    }

    /** The 12 bytes every KTX2 file starts with: {@code "«KTX 20»\r\n\u001A\n"}. */
    public static final byte[] IDENTIFIER = {(byte) 0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xBB, 0x0D, 0x0A, 0x1A, 0x0A};

    /** Bytes of the fixed header including the identifier. */
    public static final int HEADER_BYTES = 80;
    /** Bytes of one level index entry. */
    public static final int LEVEL_INDEX_ENTRY_BYTES = 24;

    public static final int SUPERCOMPRESSION_NONE = 0;
    public static final int SUPERCOMPRESSION_BASIS_LZ = 1;
    public static final int SUPERCOMPRESSION_ZSTD = 2;
    public static final int SUPERCOMPRESSION_ZLIB = 3;

    /** One entry of the level index; level 0 is the largest image. Offsets are from the start of the file. */
    public record Level(long byteOffset, long byteLength, long uncompressedByteLength) {
    }

    /**
     * The parsed header. {@code levelCount} is as stored: 0 means the file holds the base level only and the loader is expected to generate the rest.
     * {@code format} is {@code null} when {@code vkFormat} is not in {@link TextureFormat} (and 0, {@code VK_FORMAT_UNDEFINED}, for Basis Universal data).
     */
    public record Header(int vkFormat, int typeSize, int pixelWidth, int pixelHeight, int pixelDepth, int layerCount, int faceCount, int levelCount,
                         int supercompressionScheme, int dfdByteOffset, int dfdByteLength, int kvdByteOffset, int kvdByteLength,
                         long sgdByteOffset, long sgdByteLength, Level[] levels) {

        public TextureFormat format() {
            return TextureFormat.fromVkFormat(vkFormat);
        }

        public boolean isCube() {
            return faceCount == 6;
        }

        public boolean isSupercompressed() {
            return supercompressionScheme != SUPERCOMPRESSION_NONE;
        }

        /** Number of layers as an array would count them: a stored 0 (not an array) is 1. */
        public int layers() {
            return Math.max(1, layerCount);
        }

        /** The addressing of the decoded texture, when the format is known and the file is not supercompressed; otherwise {@code null}. */
        public TextureLayout layout() {
            TextureFormat f = format();
            if (f == null) {
                return null;
            }
            int levelsStored = Math.max(1, levelCount);
            return new TextureLayout(f, pixelWidth, Math.max(1, pixelHeight), Math.max(1, pixelDepth), levelsStored, layers(), Math.max(1, faceCount));
        }
    }

    /** Thrown for data that is not a well-formed KTX2 header. */
    public static final class FormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public FormatException(String message) {
            super(message);
        }
    }

    /** Parses the header and level index at the start of {@code data} (absolute reads; the buffer's position and byte order are not changed). */
    public static Header parse(ByteBuffer data) {
        ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.limit() < HEADER_BYTES) {
            throw new FormatException("too short for a KTX2 header: " + b.limit() + " bytes");
        }
        for (int i = 0; i < IDENTIFIER.length; i++) {
            if (b.get(i) != IDENTIFIER[i]) {
                throw new FormatException("not a KTX2 file (identifier mismatch at byte " + i + ")");
            }
        }
        int vkFormat = b.getInt(12), typeSize = b.getInt(16);
        int w = b.getInt(20), h = b.getInt(24), d = b.getInt(28);
        int layers = b.getInt(32), faces = b.getInt(36), levelCount = b.getInt(40), scheme = b.getInt(44);
        int dfdOff = b.getInt(48), dfdLen = b.getInt(52), kvdOff = b.getInt(56), kvdLen = b.getInt(60);
        long sgdOff = b.getLong(64), sgdLen = b.getLong(72);
        if (w < 1) {
            throw new FormatException("pixelWidth must be at least 1: " + w);
        }
        if (h < 0 || d < 0 || layers < 0 || levelCount < 0) {
            throw new FormatException("negative dimension, layer or level count");
        }
        if (faces != 1 && faces != 6) {
            throw new FormatException("faceCount must be 1 or 6: " + faces);
        }
        if (faces == 6 && (h == 0 || w != h)) {
            throw new FormatException("a cube map needs square faces: " + w + " x " + h);
        }
        int stored = Math.max(1, levelCount);
        if (levelCount > TextureLayout.maxLevels(w, Math.max(1, h), Math.max(1, d))) {
            throw new FormatException("levelCount " + levelCount + " is more than the dimensions allow");
        }
        long indexEnd = HEADER_BYTES + (long) stored * LEVEL_INDEX_ENTRY_BYTES;
        if (indexEnd > b.limit()) {
            throw new FormatException("the level index (" + stored + " entries) runs past the end of the data");
        }
        Level[] levels = new Level[stored];
        for (int i = 0; i < stored; i++) {
            int o = HEADER_BYTES + i * LEVEL_INDEX_ENTRY_BYTES;
            levels[i] = new Level(b.getLong(o), b.getLong(o + 8), b.getLong(o + 16));
            if (levels[i].byteOffset() < 0 || levels[i].byteLength() < 0 || levels[i].byteOffset() > b.limit()
                    || levels[i].byteLength() > b.limit() - levels[i].byteOffset()) {
                throw new FormatException("level " + i + " lies outside the data");
            }
        }
        return new Header(vkFormat, typeSize, w, h, d, layers, faces, levelCount, scheme, dfdOff, dfdLen, kvdOff, kvdLen, sgdOff, sgdLen, levels);
    }

    /**
     * Writes the 80-byte header and the level index into {@code out} at its position (little-endian regardless of the buffer's order, which is restored).
     * Used to make test files and to write simple uncompressed containers; the caller writes the level data at the offsets it put in {@code h.levels()}.
     */
    public static void writeHeader(Header h, ByteBuffer out) {
        ByteOrder saved = out.order();
        out.order(ByteOrder.LITTLE_ENDIAN);
        out.put(IDENTIFIER);
        out.putInt(h.vkFormat()).putInt(h.typeSize()).putInt(h.pixelWidth()).putInt(h.pixelHeight()).putInt(h.pixelDepth());
        out.putInt(h.layerCount()).putInt(h.faceCount()).putInt(h.levelCount()).putInt(h.supercompressionScheme());
        out.putInt(h.dfdByteOffset()).putInt(h.dfdByteLength()).putInt(h.kvdByteOffset()).putInt(h.kvdByteLength());
        out.putLong(h.sgdByteOffset()).putLong(h.sgdByteLength());
        for (Level l : h.levels()) {
            out.putLong(l.byteOffset()).putLong(l.byteLength()).putLong(l.uncompressedByteLength());
        }
        out.order(saved);
    }
}
