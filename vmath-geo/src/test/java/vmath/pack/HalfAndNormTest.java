package vmath.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Vec2f;
import vmath.core.Vec4f;

/** Half floats and the normalized integer formats, checked exhaustively where the domain is small enough. */
class HalfAndNormTest {

    final SplittableRandom r = new SplittableRandom(Long.getLong("vmath.seed", 0x5EEDL));

    // ------------------------------------------------------------ Half

    @Test
    void everyHalfBitPatternRoundTripsAndMatchesTheJdk() {
        for (int bits = 0; bits < 65536; bits++) {
            short s = (short) bits;
            float f = Half.toFloat(s);
            assertEquals(Float.float16ToFloat(s), f, "bits " + bits);
            if (!Float.isNaN(f)) {
                assertEquals(s, Half.toBits(f), "round trip of bits " + Integer.toHexString(bits));
            } else {
                assertTrue(Float.isNaN(Half.toFloat(Half.toBits(f))), "NaN stays NaN");
            }
            assertEquals(Half.isFinite(s), Float.isFinite(f), "isFinite for " + bits);
        }
    }

    @Test
    void halfSpecialValuesAndLimits() {
        assertEquals(0, Half.toBits(0f));
        assertEquals((short) 0x8000, Half.toBits(-0f), "negative zero keeps its sign");
        assertEquals((short) 0x3C00, Half.toBits(1f));
        assertEquals((short) 0xBC00, Half.toBits(-1f));
        assertEquals(Half.MAX_VALUE, Half.toFloat((short) 0x7BFF));
        assertEquals((short) 0x7BFF, Half.toBits(Half.MAX_VALUE));
        assertEquals((short) 0x7C00, Half.toBits(65520f), "65520 is exactly halfway to the next value and rounds to infinity");
        assertEquals((short) 0x7BFF, Half.toBits(65519f));
        assertEquals((short) 0x7C00, Half.toBits(1e10f));
        assertEquals((short) 0xFC00, Half.toBits(Float.NEGATIVE_INFINITY));
        assertTrue(Float.isNaN(Half.toFloat(Half.toBits(Float.NaN))));
        assertEquals(Half.MIN_VALUE, Half.toFloat((short) 1));
        assertEquals(Half.MIN_NORMAL, Half.toFloat((short) 0x0400));
        assertEquals(0, Half.toBits(Half.MIN_VALUE * 0.4f), "below half the smallest subnormal rounds to zero");
        assertEquals(1, Half.toBits(Half.MIN_VALUE * 0.6f));
        assertEquals(Half.MAX_VALUE, Half.round(65519f));
        assertFalse(Half.isFinite((short) 0x7C00));
        // ties to even: 2049 is halfway between 2048 and 2050 (spacing 2 there), and 2048 has the even mantissa
        assertEquals(2048f, Half.round(2049f));
        assertEquals(2052f, Half.round(2051f), "2051 is halfway between 2050 and 2052; 2052 is even");
    }

    @Test
    void halfRoundingIsWithinHalfAnUlp() {
        for (int i = 0; i < 100_000; i++) {
            float f = (float) ((r.nextDouble() * 2 - 1) * Math.pow(2, r.nextInt(-14, 15)));
            float h = Half.round(f);
            // half has 10 mantissa bits (float has 23), and below 2^-14 the spacing is a constant 2^-24
            double ulp = Math.max(Math.ulp(h) * Math.pow(2, 13), Math.pow(2, -24));
            assertTrue(Math.abs(h - f) <= ulp / 2 * 1.0001 + 1e-12, "value " + f + " rounded to " + h);
        }
    }

    @Test
    void bulkConversionMatchesScalar() {
        int n = 1000;
        float[] src = new float[n + 5];
        for (int i = 0; i < src.length; i++) {
            src[i] = (float) ((r.nextDouble() * 2 - 1) * 1000);
        }
        short[] packed = new short[n + 3];
        Half.pack(src, 5, packed, 3, n - 5);
        float[] back = new float[n + 2];
        Half.unpack(packed, 3, back, 2, n - 5);
        for (int i = 0; i < n - 5; i++) {
            assertEquals(Half.toBits(src[5 + i]), packed[3 + i]);
            assertEquals(Half.round(src[5 + i]), back[2 + i]);
        }
        assertEquals(0, packed[0]);
        assertEquals(0f, back[0]);
    }

    @Test
    void packedHalfVectors() {
        for (int i = 0; i < 2000; i++) {
            float x = (float) r.nextDouble(-100, 100), y = (float) r.nextDouble(-100, 100);
            float z = (float) r.nextDouble(-100, 100), w = (float) r.nextDouble(-100, 100);
            int two = Half.pack2(x, y);
            assertEquals(Half.round(x), Half.unpack2(two, 0));
            assertEquals(Half.round(y), Half.unpack2(two, 1));
            assertEquals(Half.toBits(x), (short) two, "x is in the low half, like GLSL packHalf2x16");
            long four = Half.pack4(x, y, z, w);
            assertEquals(Half.round(x), Half.unpack4(four, 0));
            assertEquals(Half.round(y), Half.unpack4(four, 1));
            assertEquals(Half.round(z), Half.unpack4(four, 2));
            assertEquals(Half.round(w), Half.unpack4(four, 3));
        }
        assertEquals(-2f, Half.unpack2(Half.pack2(1f, -2f), 1), "negative values survive the sign bit");
    }

    // ------------------------------------------------------------ 8 and 16 bit normalized

    @Test
    void unorm8RoundTripsEveryValue() {
        for (int b = 0; b < 256; b++) {
            assertEquals(b, Norm.packUnorm8(Norm.unpackUnorm8(b)), "byte " + b);
        }
        assertEquals(0f, Norm.unpackUnorm8(0));
        assertEquals(1f, Norm.unpackUnorm8(255));
        assertEquals(Norm.unpackUnorm8(0x1FF), Norm.unpackUnorm8(0xFF), "only the low byte counts");
    }

    @Test
    void snorm8RoundTripsEveryValueAndTreatsMinus128AsMinusOne() {
        for (int b = -127; b <= 127; b++) {
            assertEquals(b, Norm.packSnorm8(Norm.unpackSnorm8(b)), "byte " + b);
        }
        assertEquals(-1f, Norm.unpackSnorm8(-128));
        assertEquals(-127, Norm.packSnorm8(-1f), "the most negative code is never produced");
        assertEquals(0, Norm.packSnorm8(0f), "zero is exactly representable");
        assertEquals(0f, Norm.unpackSnorm8(0));
        assertEquals(1f, Norm.unpackSnorm8(127));
    }

    @Test
    void unorm16AndSnorm16RoundTripEveryValue() {
        for (int v = 0; v < 65536; v++) {
            assertEquals(v, Norm.packUnorm16(Norm.unpackUnorm16(v)), "unorm16 " + v);
        }
        for (int v = -32767; v <= 32767; v++) {
            assertEquals(v, Norm.packSnorm16(Norm.unpackSnorm16(v)), "snorm16 " + v);
        }
        assertEquals(-1f, Norm.unpackSnorm16(-32768));
    }

    @Test
    void ten_bitValuesRoundTripAndAlphaHasFourSteps() {
        for (int v = 0; v < 1024; v++) {
            assertEquals(v, Norm.packUnorm10(Norm.unpackUnorm10(v)), "unorm10 " + v);
        }
        for (int v = -511; v <= 511; v++) {
            assertEquals(v & 0x3FF, Norm.packSnorm10(Norm.unpackSnorm10(v & 0x3FF)), "snorm10 " + v);
        }
        assertEquals(-1f, Norm.unpackSnorm10(0x200), "-512 decodes to -1");
    }

    @Test
    void clampingNaNAndRoundingErrorBounds() {
        assertEquals(0, Norm.packUnorm8(-3f));
        assertEquals(255, Norm.packUnorm8(3f));
        assertEquals(0, Norm.packUnorm8(Float.NaN), "NaN becomes 0");
        assertEquals(0, Norm.packSnorm8(Float.NaN));
        assertEquals(-127, Norm.packSnorm8(-9f));
        assertEquals(32767, Norm.packSnorm16(9f));
        assertEquals(255, Norm.packUnorm8(Float.POSITIVE_INFINITY));
        for (int i = 0; i < 100_000; i++) {
            float u = (float) r.nextDouble(), s = (float) r.nextDouble(-1, 1);
            assertTrue(Math.abs(Norm.unpackUnorm8(Norm.packUnorm8(u)) - u) <= 0.5 / 255 + 1e-7, "unorm8 error for " + u);
            assertTrue(Math.abs(Norm.unpackSnorm8(Norm.packSnorm8(s)) - s) <= 0.5 / 127 + 1e-7, "snorm8 error for " + s);
            assertTrue(Math.abs(Norm.unpackUnorm16(Norm.packUnorm16(u)) - u) <= 0.5 / 65535 + 1e-7, "unorm16 error for " + u);
            assertTrue(Math.abs(Norm.unpackSnorm16(Norm.packSnorm16(s)) - s) <= 0.5 / 32767 + 1e-7, "snorm16 error for " + s);
        }
    }

    // ------------------------------------------------------------ packed vectors and layouts

    @Test
    void glslLayoutPutsTheFirstComponentInTheLowestBits() {
        int p = Norm.packUnorm4x8(new Vec4f(1f, 0f, 0f, 0f));
        assertEquals(0xFF, p, "x is the lowest byte");
        assertEquals(0xFF000000, Norm.packUnorm4x8(new Vec4f(0f, 0f, 0f, 1f)));
        assertEquals(0x7F, Norm.packSnorm4x8(new Vec4f(1f, 0f, 0f, 0f)));
        assertEquals(0x81, Norm.packSnorm4x8(new Vec4f(-1f, 0f, 0f, 0f)) & 0xFF, "-127 as a byte");
        assertEquals(0xFFFF, Norm.packUnorm2x16(new Vec2f(1f, 0f)));
        assertEquals(0xFFFF0000, Norm.packUnorm2x16(new Vec2f(0f, 1f)));
        assertEquals(0x7FFF, Norm.packSnorm2x16(new Vec2f(1f, 0f)));
        assertEquals(0x8001, Norm.packSnorm2x16(new Vec2f(-1f, 0f)) & 0xFFFF);
    }

    @Test
    void packedVectorsRoundTripWithinHalfAStep() {
        for (int i = 0; i < 20_000; i++) {
            Vec4f u = new Vec4f((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble());
            Vec4f s = new Vec4f((float) r.nextDouble(-1, 1), (float) r.nextDouble(-1, 1), (float) r.nextDouble(-1, 1),
                    (float) r.nextDouble(-1, 1));
            Vec4f u8 = Norm.unpackUnorm4x8(Norm.packUnorm4x8(u));
            Vec4f s8 = Norm.unpackSnorm4x8(Norm.packSnorm4x8(s));
            for (int c = 0; c < 4; c++) {
                assertTrue(Math.abs(u8.get(c) - u.get(c)) <= 0.5 / 255 + 1e-6);
                assertTrue(Math.abs(s8.get(c) - s.get(c)) <= 0.5 / 127 + 1e-6);
            }
            Vec2f u16 = Norm.unpackUnorm2x16(Norm.packUnorm2x16(new Vec2f(u.x(), u.y())));
            Vec2f s16 = Norm.unpackSnorm2x16(Norm.packSnorm2x16(new Vec2f(s.x(), s.y())));
            assertTrue(Math.abs(u16.x() - u.x()) <= 0.5 / 65535 + 1e-6 && Math.abs(u16.y() - u.y()) <= 0.5 / 65535 + 1e-6);
            assertTrue(Math.abs(s16.x() - s.x()) <= 0.5 / 32767 + 1e-6 && Math.abs(s16.y() - s.y()) <= 0.5 / 32767 + 1e-6);
        }
    }

    @Test
    void rgb10A2LayoutAndRoundTrip() {
        // red is in the lowest bits, alpha in the top two
        assertEquals(0x3FF, Norm.packRgb10A2(new Vec4f(1f, 0f, 0f, 0f)));
        assertEquals(0x3FF << 10, Norm.packRgb10A2(new Vec4f(0f, 1f, 0f, 0f)));
        assertEquals(0x3FF << 20, Norm.packRgb10A2(new Vec4f(0f, 0f, 1f, 0f)));
        assertEquals(0xC0000000, Norm.packRgb10A2(new Vec4f(0f, 0f, 0f, 1f)));
        for (int a = 0; a < 4; a++) {
            Vec4f v = Norm.unpackRgb10A2(a << 30);
            assertEquals(a / 3f, v.w(), 1e-6f, "alpha step " + a);
            assertEquals(a << 30, Norm.packRgb10A2(v) & 0xC0000000);
        }
        for (int i = 0; i < 20_000; i++) {
            Vec4f v = new Vec4f((float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble(), (float) r.nextDouble());
            Vec4f back = Norm.unpackRgb10A2(Norm.packRgb10A2(v));
            for (int c = 0; c < 3; c++) {
                assertTrue(Math.abs(back.get(c) - v.get(c)) <= 0.5 / 1023 + 1e-6, "channel " + c);
            }
            assertTrue(Math.abs(back.w() - v.w()) <= 0.5 / 3 + 1e-6);
        }
    }

    @Test
    void rgb10A2SnormRoundTripAndAlphaValues() {
        for (int i = 0; i < 20_000; i++) {
            Vec4f v = new Vec4f((float) r.nextDouble(-1, 1), (float) r.nextDouble(-1, 1), (float) r.nextDouble(-1, 1), 0f);
            Vec4f back = Norm.unpackRgb10A2Snorm(Norm.packRgb10A2Snorm(v));
            for (int c = 0; c < 3; c++) {
                assertTrue(Math.abs(back.get(c) - v.get(c)) <= 0.5 / 511 + 1e-6, "channel " + c);
            }
        }
        assertEquals(1f, Norm.unpackRgb10A2Snorm(Norm.packRgb10A2Snorm(new Vec4f(0f, 0f, 0f, 1f))).w());
        assertEquals(-1f, Norm.unpackRgb10A2Snorm(Norm.packRgb10A2Snorm(new Vec4f(0f, 0f, 0f, -1f))).w());
        assertEquals(0f, Norm.unpackRgb10A2Snorm(Norm.packRgb10A2Snorm(new Vec4f(0f, 0f, 0f, 0.2f))).w());
        assertEquals(-1f, Norm.unpackRgb10A2Snorm(0x80000000).w(), "the fourth 2-bit value (-2) also decodes to -1");
    }
}
