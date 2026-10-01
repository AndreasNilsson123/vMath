package vmath.assets;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A minimal, deterministic PNG encoder (8-bit RGBA, no filtering, stored deflate blocks so the bytes do not depend on the zlib version). The tests decode
 * its output with the JDK's own PNG reader, which checks this writer independently.
 */
public final class PngWriter {

    private PngWriter() {
    }

    public static byte[] rgba(int width, int height, int[] argb) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        Bin ihdr = new Bin();
        // PNG integers are big endian
        ihdr.u8(width >>> 24, width >>> 16, width >>> 8, width).u8(height >>> 24, height >>> 16, height >>> 8, height).u8(8, 6, 0, 0, 0);
        chunk(out, "IHDR", ihdr.toBytes());
        byte[] raw = new byte[height * (1 + width * 4)];
        int o = 0;
        for (int y = 0; y < height; y++) {
            raw[o++] = 0; // filter: none
            for (int x = 0; x < width; x++) {
                int p = argb[y * width + x];
                raw[o++] = (byte) (p >> 16);
                raw[o++] = (byte) (p >> 8);
                raw[o++] = (byte) p;
                raw[o++] = (byte) (p >>> 24);
            }
        }
        Deflater d = new Deflater(Deflater.NO_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            z.write(buf, 0, d.deflate(buf));
        }
        d.end();
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        int n = data.length;
        out.writeBytes(new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        long c = crc.getValue();
        out.writeBytes(new byte[] {(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
    }

    /** A checkerboard of two colours with cells of {@code cell} pixels. */
    public static int[] checker(int width, int height, int cell, int colorA, int colorB) {
        int[] px = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                px[y * width + x] = ((x / cell) + (y / cell)) % 2 == 0 ? colorA : colorB;
            }
        }
        return px;
    }
}
