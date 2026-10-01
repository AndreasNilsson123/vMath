package vmath.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;
import vmath.mesh.Mesh;
import vmath.mesh.MeshExport;
import vmath.mesh.Primitives;
import vmath.mesh.VertexLayout;

class QuantizationTest {

    private static final long SEED = Long.getLong("vmath.seed", 71L);

    // ---------------------------------------------------------------- unorm and snorm of any width

    @Test
    void unormAndSnormOfAnyWidthMatchTheFixedWidthFormatsAndClamp() {
        SplittableRandom r = new SplittableRandom(SEED);
        for (int k = 0; k < 20000; k++) {
            float v = (float) (r.nextDouble() * 2.4 - 1.2);
            // the fixed-width formats compute in float, this in double: they may differ by one code where the value is within float rounding of a tie
            assertTrue(Math.abs(Norm.packUnorm8(v) - Quantize.unorm(v, 8)) <= 1, "unorm8 of " + v);
            assertTrue(Math.abs(Norm.packUnorm16(v) - Quantize.unorm(v, 16)) <= 1, "unorm16 of " + v);
            assertTrue(Math.abs(Norm.packUnorm10(v) - Quantize.unorm(v, 10)) <= 1, "unorm10 of " + v);
            assertTrue(Math.abs((Norm.packSnorm8(v) << 24 >> 24) - Quantize.snorm(v, 8)) <= 1, "snorm8 of " + v);
            assertTrue(Math.abs((Norm.packSnorm16(v) << 16 >> 16) - Quantize.snorm(v, 16)) <= 1, "snorm16 of " + v);
        }
        for (int bits = 1; bits <= 24; bits++) {
            int max = Quantize.unormMax(bits);
            assertEquals(0, Quantize.unorm(-3f, bits));
            assertEquals(0, Quantize.unorm(Float.NaN, bits));
            assertEquals(max, Quantize.unorm(7f, bits));
            assertEquals(max, Quantize.unorm(1f, bits));
            assertEquals(0f, Quantize.fromUnorm(0, bits));
            assertEquals(1f, Quantize.fromUnorm(max, bits));
            if (bits >= 2) {
                int smax = Quantize.snormMax(bits);
                assertEquals(0, Quantize.snorm(0f, bits), "zero is exact");
                assertEquals(0, Quantize.snorm(Float.NaN, bits));
                assertEquals(smax, Quantize.snorm(1f, bits));
                assertEquals(-smax, Quantize.snorm(-1f, bits));
                assertEquals(-smax, Quantize.snorm(-9f, bits));
                assertEquals(-1f, Quantize.fromSnorm(-smax, bits));
                assertEquals(-1f, Quantize.fromSnorm(-smax - 1, bits), "the most negative two's complement code also means -1");
                assertEquals(1f, Quantize.fromSnorm(smax, bits));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Quantize.unorm(0f, 0));
        assertThrows(IllegalArgumentException.class, () -> Quantize.unorm(0f, 25));
        assertThrows(IllegalArgumentException.class, () -> Quantize.snorm(0f, 1));
    }

    @Test
    void roundTripErrorIsHalfAStepAtEveryWidth() {
        SplittableRandom r = new SplittableRandom(SEED + 1);
        for (int bits = 2; bits <= 20; bits++) {
            double stepU = 1.0 / Quantize.unormMax(bits), stepS = 1.0 / Quantize.snormMax(bits);
            double worstU = 0, worstS = 0;
            for (int k = 0; k < 5000; k++) {
                float v = (float) r.nextDouble();
                worstU = Math.max(worstU, Math.abs(Quantize.fromUnorm(Quantize.unorm(v, bits), bits) - v));
                float s = (float) (r.nextDouble() * 2 - 1);
                worstS = Math.max(worstS, Math.abs(Quantize.fromSnorm(Quantize.snorm(s, bits), bits) - s));
            }
            assertTrue(worstU <= stepU / 2 * 1.0001 + 1e-7, "unorm " + bits + " bits: " + worstU + " vs half step " + stepU / 2);
            assertTrue(worstS <= stepS / 2 * 1.0001 + 1e-7, "snorm " + bits + " bits: " + worstS + " vs half step " + stepS / 2);
        }
    }

    // ---------------------------------------------------------------- mantissa rounding

    @Test
    void mantissaRoundingKeepsTheRequestedBitsAndTheErrorBound() {
        SplittableRandom r = new SplittableRandom(SEED + 2);
        for (int bits = 0; bits <= 23; bits++) {
            double bound = Math.pow(2, -(bits + 1));
            for (int k = 0; k < 4000; k++) {
                float f = Float.intBitsToFloat(r.nextInt() & 0x7FFFFFFF);
                if (!Float.isFinite(f) || f < Float.MIN_NORMAL) {
                    continue;
                }
                float sign = r.nextBoolean() ? -1f : 1f;
                float q = Quantize.mantissa(sign * f, bits);
                if (Float.isInfinite(q)) {
                    assertTrue(f > 2.5e38f, "rounded to infinity only at the very top of the range");
                    continue;
                }
                assertTrue(Math.signum(q) == sign);
                assertEquals(0, Float.floatToRawIntBits(q) & ((1 << (23 - bits)) - 1), "low mantissa bits are zero, bits=" + bits);
                assertTrue(Math.abs((double) q - (double) sign * f) <= bound * Math.abs(f) * 1.0000001, "bits=" + bits + " f=" + f + " q=" + q);
            }
        }
        // exact cases
        assertEquals(1f, Quantize.mantissa(1.25f, 0), "1.25 with no mantissa bits rounds to 1");
        assertEquals(2f, Quantize.mantissa(1.5f, 0), "a tie goes away from zero");
        assertEquals(-2f, Quantize.mantissa(-1.5f, 0));
        assertEquals(2f, Quantize.mantissa(1.999f, 3), "rounding up across a power of two");
        assertEquals(1.5f, Quantize.mantissa(1.5f, 1));
        assertEquals(0f, Quantize.mantissa(0f, 5));
        assertEquals(Float.POSITIVE_INFINITY, Quantize.mantissa(Float.POSITIVE_INFINITY, 4));
        assertTrue(Float.isNaN(Quantize.mantissa(Float.NaN, 4)));
        assertEquals(Float.POSITIVE_INFINITY, Quantize.mantissa(Float.MAX_VALUE, 0), "rounds up to infinity");
        assertEquals(0.1f, Quantize.mantissa(0.1f, 23));
        float[] a = {1.3f, -2.7f, 5.1f};
        Quantize.mantissa(a, 1, 2, 2);
        assertEquals(1.3f, a[0]);
        assertEquals(-2.5f, a[1]);
        assertEquals(5f, a[2]);
        assertThrows(IllegalArgumentException.class, () -> Quantize.mantissa(1f, 24));
    }

    // ---------------------------------------------------------------- grids

    @Test
    void gridQuantizerErrorIsHalfAStepAndTheMatrixRestoresPositions() {
        SplittableRandom r = new SplittableRandom(SEED + 3);
        Aabbf box = new Aabbf(-3f, 1f, 10f, 5f, 2f, 16f);
        for (boolean uniform : new boolean[] {false, true}) {
            for (int bits : new int[] {1, 5, 10, 14, 16}) {
                GridQuantizer g = uniform ? GridQuantizer.uniform(box, bits) : GridQuantizer.of(box, bits);
                Vec3f err = g.maxError();
                short[] dst = new short[3];
                double worstX = 0, worstY = 0, worstZ = 0;
                for (int k = 0; k < 3000; k++) {
                    Vec3f p = new Vec3f((float) (-3 + r.nextDouble() * 8), (float) (1 + r.nextDouble()), (float) (10 + r.nextDouble() * 6));
                    g.pack(p, dst, 0);
                    assertTrue((dst[0] & 0xFFFF) <= g.levels() && (dst[1] & 0xFFFF) <= g.levels() && (dst[2] & 0xFFFF) <= g.levels());
                    Vec3f q = g.unpack(dst, 0);
                    worstX = Math.max(worstX, Math.abs(q.x() - p.x()));
                    worstY = Math.max(worstY, Math.abs(q.y() - p.y()));
                    worstZ = Math.max(worstZ, Math.abs(q.z() - p.z()));
                    // the matrix applied to the normalized code gives the same position
                    Vec3f viaMatrix = g.dequantizationMatrix().transformPosition(new Vec3f((float) (dst[0] & 0xFFFF) / g.levels(), (float) (dst[1] & 0xFFFF) / g.levels(),
                            (float) (dst[2] & 0xFFFF) / g.levels()));
                    assertEquals(q.x(), viaMatrix.x(), 1e-4f);
                    assertEquals(q.y(), viaMatrix.y(), 1e-4f);
                    assertEquals(q.z(), viaMatrix.z(), 1e-4f);
                }
                assertTrue(worstX <= err.x() * 1.001 + 1e-6 && worstY <= err.y() * 1.001 + 1e-6 && worstZ <= err.z() * 1.001 + 1e-6,
                        "uniform=" + uniform + " bits=" + bits + ": " + worstX + ", " + worstY + ", " + worstZ + " vs " + err);
            }
        }
        // cubic cells: the same error on every axis, and the shorter axes use fewer levels
        GridQuantizer u = GridQuantizer.uniform(box, 10);
        assertEquals(u.maxError().x(), u.maxError().y());
        assertEquals(u.maxError().x(), u.maxError().z());
        assertEquals(1023, u.quantize(5f, 0));
        assertTrue(u.quantize(2f, 1) < 200, "the y axis covers an eighth of the grid: " + u.quantize(2f, 1));
        // out-of-box values clamp; a flat axis maps to 0
        assertEquals(0, GridQuantizer.of(box, 8).quantize(-100f, 0));
        assertEquals(255, GridQuantizer.of(box, 8).quantize(100f, 0));
        GridQuantizer flat = GridQuantizer.of(new Aabbf(0f, 4f, 0f, 1f, 4f, 1f), 8);
        assertEquals(0, flat.quantize(4f, 1));
        assertThrows(IllegalArgumentException.class, () -> GridQuantizer.of(box, 17));
        assertThrows(IllegalArgumentException.class, () -> GridQuantizer.of(Aabbf.EMPTY, 8));
        // 16 bits agrees with the older Quantizer
        Quantizer old = new Quantizer(box);
        GridQuantizer g16 = GridQuantizer.of(box, 16);
        short[] a = new short[3], b = new short[3];
        for (int k = 0; k < 1000; k++) {
            Vec3f p = new Vec3f((float) (-3 + r.nextDouble() * 8), (float) (1 + r.nextDouble()), (float) (10 + r.nextDouble() * 6));
            old.pack(p, a, 0);
            g16.pack(p, b, 0);
            assertTrue(Math.abs(a[0] - b[0]) <= 1 && Math.abs(a[1] - b[1]) <= 1 && Math.abs(a[2] - b[2]) <= 1, "within one code of the float-arithmetic Quantizer");
        }
    }

    @Test
    void uvQuantizerFitsTheRectangleAndBoundsTheError() {
        SplittableRandom r = new SplittableRandom(SEED + 4);
        float[] uv = new float[2 * 500];
        for (int i = 0; i < 500; i++) {
            uv[2 * i] = (float) (-2 + r.nextDouble() * 5);   // tiling coordinates outside [0, 1]
            uv[2 * i + 1] = (float) (0.25 + r.nextDouble() * 0.5);
        }
        for (int bits : new int[] {8, 12, 16}) {
            UvQuantizer q = UvQuantizer.fit(uv, 500, bits);
            short[] dst = new short[2];
            for (int i = 0; i < 500; i++) {
                q.pack(uv[2 * i], uv[2 * i + 1], dst, 0);
                assertEquals(uv[2 * i], q.unpackU(dst[0]), q.maxErrorU() * 1.001f + 1e-6f, "u at " + bits + " bits");
                assertEquals(uv[2 * i + 1], q.unpackV(dst[1]), q.maxErrorV() * 1.001f + 1e-6f, "v at " + bits + " bits");
            }
        }
        UvQuantizer unit = UvQuantizer.fit(new float[0], 0, 16);
        assertEquals(0, unit.quantizeU(0f));
        assertEquals(65535, unit.quantizeU(1f));
        assertEquals(1f, unit.unpackU(65535));
        assertThrows(IllegalArgumentException.class, () -> new UvQuantizer(1f, 0f, 0f, 1f, 8));
        assertThrows(IllegalArgumentException.class, () -> UvQuantizer.fit(new float[] {0f, Float.NaN}, 1, 8));
        assertEquals(0, new UvQuantizer(2f, 2f, 2f, 2f, 8).quantizeU(2f), "a degenerate rectangle maps to 0");
    }

    // ---------------------------------------------------------------- the mesh pipeline

    @Test
    void quantizedMeshLayoutRoundTripsPositionsAndUvs() {
        Mesh m = Primitives.uvSphere(3f, 24, 12);
        // stretch the uvs outside [0, 1] so the rectangle is not the unit one
        for (int v = 0; v < m.vertexCount(); v++) {
            m.setUv(0, v, m.uvs(0)[v * 2] * 3f - 1f, m.uvs(0)[v * 2 + 1] * 2f + 4f);
        }
        VertexLayout layout = VertexLayout.builder().positionUnorm16().normalOct16().uvUnorm16(0).build();
        assertEquals(8 + 4 + 4, layout.stride(), "an unorm16x4 position, a 2x16 normal and a 2x16 uv: 16 bytes against 12 + 4 + 8 = 24 unquantized");
        MemorySegment seg = MemorySegment.ofArray(new byte[(int) MeshExport.vertexBytes(m, layout)]);
        MeshExport.writeVertices(m, layout, seg, 0);
        Quantizer pq = MeshExport.positionQuantizer(m);
        UvQuantizer uq = MeshExport.uvQuantizer(m, 0);
        Vec3f maxErr = pq.maxError();
        var attrs = layout.attributes();
        for (int v = 0; v < m.vertexCount(); v++) {
            long base = (long) v * layout.stride();
            long po = base + attrs.get(0).offset();
            int x = Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, po)), y = Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, po + 2)),
                    z = Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, po + 4)), w = Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, po + 6));
            assertEquals(65535, w);
            // the GPU reads the codes as normalized integers; the matrix puts them back in model space
            Vec4f restored = pq.dequantizationMatrix().transform(new Vec4f(x / 65535f, y / 65535f, z / 65535f, 1f));
            assertEquals(m.positions()[v * 3], restored.x(), maxErr.x() * 1.01f + 1e-5f);
            assertEquals(m.positions()[v * 3 + 1], restored.y(), maxErr.y() * 1.01f + 1e-5f);
            assertEquals(m.positions()[v * 3 + 2], restored.z(), maxErr.z() * 1.01f + 1e-5f);
            long uo = base + attrs.get(2).offset();
            int cu = Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, uo)), cv = Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, uo + 2));
            assertEquals(m.uvs(0)[v * 2], uq.unpackU(cu), uq.maxErrorU() * 1.01f + 1e-5f);
            assertEquals(m.uvs(0)[v * 2 + 1], uq.unpackV(cv), uq.maxErrorV() * 1.01f + 1e-5f);
        }
        // the buffer description follows
        var buffer = layout.toBufferLayout();
        assertEquals(vmath.gl.VertexFormat.UNORM16X4, buffer.attribute("position").format());
        assertEquals(vmath.gl.VertexFormat.UNORM16X2, buffer.attribute("uv0").format());
        assertThrows(IllegalStateException.class, () -> MeshExport.positionQuantizer(new Mesh()));
        Mesh noUv = Primitives.uvSphere(1f, 6, 4);
        noUv.disableUvs(0);
        assertThrows(IllegalStateException.class, () -> MeshExport.uvQuantizer(noUv, 0));
    }
}
