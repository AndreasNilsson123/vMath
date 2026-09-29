package vmath.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec2f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

/** R11G11B10F, RGB9E5, octahedral normals, packed quaternions, position quantization and the format table. */
class CompactFormatsTest {

    final SplittableRandom r = new SplittableRandom(Long.getLong("vmath.seed", 0x5EEDL));

    private Vec3f randomUnit() {
        while (true) {
            double x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian();
            double l = Math.sqrt(x * x + y * y + z * z);
            if (l > 1e-3) {
                return new Vec3f((float) (x / l), (float) (y / l), (float) (z / l));
            }
        }
    }

    // ------------------------------------------------------------ unsigned small floats (R11G11B10F)

    @Test
    void everyFiniteSmallFloatCodeRoundTrips() {
        for (int mant : new int[] {6, 5}) {
            int codes = 1 << (5 + mant);
            float previous = -1f;
            for (int code = 0; code < codes; code++) {
                int exp = code >>> mant;
                float f = SmallFloat.decodeUnsigned(code, mant);
                if (exp == 31) {
                    if ((code & ((1 << mant) - 1)) == 0) {
                        assertEquals(Float.POSITIVE_INFINITY, f);
                        assertEquals(code, SmallFloat.encodeUnsigned(f, mant), "infinity");
                    } else {
                        assertTrue(Float.isNaN(f), "code " + code + " is NaN");
                        int back = SmallFloat.encodeUnsigned(f, mant);
                        assertEquals(31, back >>> mant);
                        assertTrue((back & ((1 << mant) - 1)) != 0, "NaN stays NaN");
                    }
                    continue;
                }
                assertEquals(code, SmallFloat.encodeUnsigned(f, mant), mant + "-bit mantissa, code " + code);
                assertTrue(f > previous, "values increase with the code: code " + code);
                previous = f;
            }
        }
    }

    @Test
    void smallFloatEncodingIsTheNearestRepresentableValue() {
        for (int mant : new int[] {6, 5}) {
            double max = mant == 6 ? SmallFloat.MAX_R11 : SmallFloat.MAX_B10;
            for (int i = 0; i < 100_000; i++) {
                float v = (float) (Math.pow(2, r.nextDouble(-24, 16)) * (r.nextBoolean() ? 1 : 0.999));
                if (v > max) {
                    continue;
                }
                int code = SmallFloat.encodeUnsigned(v, mant);
                double chosen = Math.abs(v - SmallFloat.decodeUnsigned(code, mant));
                for (int neighbour : new int[] {code - 1, code + 1}) {
                    if (neighbour >= 0 && (neighbour >>> mant) < 31) {
                        double other = Math.abs(v - SmallFloat.decodeUnsigned(neighbour, mant));
                        assertTrue(chosen <= other + 1e-12 * v, "value " + v + " (" + mant + "-bit mantissa) chose code " + code
                                + " over " + neighbour);
                    }
                }
            }
        }
    }

    @Test
    void r11g11b10Layout() {
        // 1.0 is exponent 15 with a zero mantissa
        assertEquals(15 << 6, SmallFloat.packR11G11B10F(1f, 0f, 0f));
        assertEquals((15 << 6) << 11, SmallFloat.packR11G11B10F(0f, 1f, 0f));
        assertEquals((15 << 5) << 22, SmallFloat.packR11G11B10F(0f, 0f, 1f));
        Vec3f v = SmallFloat.unpackR11G11B10F(SmallFloat.packR11G11B10F(new Vec3f(0.5f, 2f, 8f)));
        assertEquals(0.5f, v.x());
        assertEquals(2f, v.y());
        assertEquals(8f, v.z());
    }

    @Test
    void r11g11b10ClampsNegativesAndOverflowAndKeepsSpecials() {
        Vec3f neg = SmallFloat.unpackR11G11B10F(SmallFloat.packR11G11B10F(-1f, -0f, -1e9f));
        assertEquals(Vec3f.ZERO, neg);
        Vec3f big = SmallFloat.unpackR11G11B10F(SmallFloat.packR11G11B10F(1e6f, 1e6f, 1e6f));
        assertEquals(SmallFloat.MAX_R11, big.x());
        assertEquals(SmallFloat.MAX_B10, big.z());
        Vec3f special = SmallFloat.unpackR11G11B10F(SmallFloat.packR11G11B10F(Float.POSITIVE_INFINITY, Float.NaN, 0f));
        assertEquals(Float.POSITIVE_INFINITY, special.x());
        assertTrue(Float.isNaN(special.y()));
        // smallest subnormals and the boundary between subnormal and normal
        assertEquals((float) Math.pow(2, -20), SmallFloat.unpackR11G11B10F(1).x());
        assertEquals((float) Math.pow(2, -14), SmallFloat.unpackR11G11B10F(64).x());
        assertEquals(1, SmallFloat.packR11G11B10F((float) Math.pow(2, -20), 0f, 0f));
        assertEquals(0, SmallFloat.packR11G11B10F((float) Math.pow(2, -22), 0f, 0f), "below half the smallest step rounds to zero");
    }

    @Test
    void r11g11b10RelativeErrorBounds() {
        for (int i = 0; i < 100_000; i++) {
            float v = (float) Math.pow(2, r.nextDouble(-13, 15.9));
            float red = SmallFloat.unpackR11G11B10F(SmallFloat.packR11G11B10F(v, 0f, 0f)).x();
            float blue = SmallFloat.unpackR11G11B10F(SmallFloat.packR11G11B10F(0f, 0f, v)).z();
            assertTrue(Math.abs(red - v) / v <= 1.0 / 128 + 1e-6, "11-bit: " + v + " became " + red);
            assertTrue(Math.abs(blue - v) / v <= 1.0 / 64 + 1e-6, "10-bit: " + v + " became " + blue);
        }
    }

    // ------------------------------------------------------------ RGB9E5

    /** Reference by brute force: the smallest shared exponent whose 9-bit mantissas can hold all three channels. */
    private static int referenceRgb9E5(float r, float g, float b) {
        double[] c = {clampRef(r), clampRef(g), clampRef(b)};
        for (int e = 0; e < 32; e++) {
            double step = Math.pow(2, e - 15 - 9);
            int[] m = new int[3];
            boolean fits = true;
            for (int k = 0; k < 3; k++) {
                m[k] = (int) Math.floor(c[k] / step + 0.5);
                fits &= m[k] <= 511;
            }
            if (fits) {
                return m[0] | (m[1] << 9) | (m[2] << 18) | (e << 27);
            }
        }
        throw new AssertionError("no exponent fits " + r + " " + g + " " + b);
    }

    private static double clampRef(float v) {
        return v > 0f ? Math.min(v, SmallFloat.MAX_RGB9E5) : 0.0;
    }

    @Test
    void rgb9e5MatchesTheBruteForceReference() {
        for (int i = 0; i < 200_000; i++) {
            float scale = (float) Math.pow(2, r.nextDouble(-20, 16));
            float rr = (float) (r.nextDouble() * scale), gg = (float) (r.nextDouble() * scale * (r.nextBoolean() ? 1 : 0.001));
            float bb = (float) (r.nextDouble() * scale * (r.nextBoolean() ? 1 : 0.01));
            assertEquals(Integer.toHexString(referenceRgb9E5(rr, gg, bb)), Integer.toHexString(SmallFloat.packRgb9E5(rr, gg, bb)),
                    "for " + rr + ", " + gg + ", " + bb);
        }
    }

    @Test
    void rgb9e5ErrorBoundAndLimits() {
        for (int i = 0; i < 100_000; i++) {
            float scale = (float) Math.pow(2, r.nextDouble(-14, 15));
            Vec3f in = new Vec3f((float) (r.nextDouble() * scale), (float) (r.nextDouble() * scale), (float) (r.nextDouble() * scale));
            Vec3f out = SmallFloat.unpackRgb9E5(SmallFloat.packRgb9E5(in));
            float max = Math.max(in.x(), Math.max(in.y(), in.z()));
            // every channel is within half a step of the shared exponent, i.e. within 2^-9 of the largest channel. When the
            // largest channel rounds up to 512 the exponent carries and the step doubles, which allows 2^-10 more (hence 1.002);
            // and the step cannot go below 2^-24 however small the values are
            double bound = Math.max(max / 512.0 * 1.002, Math.pow(2, -25)) + 1e-9;
            assertTrue(Math.abs(out.x() - in.x()) <= bound && Math.abs(out.y() - in.y()) <= bound
                    && Math.abs(out.z() - in.z()) <= bound, "in " + in + " out " + out);
        }
        assertEquals(new Vec3f(0f, 0f, 0f), SmallFloat.unpackRgb9E5(SmallFloat.packRgb9E5(0f, 0f, 0f)));
        assertEquals(new Vec3f(SmallFloat.MAX_RGB9E5, 0f, 0f), SmallFloat.unpackRgb9E5(SmallFloat.packRgb9E5(1e9f, -3f, Float.NaN)));
        assertEquals(1f, SmallFloat.unpackRgb9E5(SmallFloat.packRgb9E5(1f, 0.5f, 0.25f)).x());
        assertEquals(0.5f, SmallFloat.unpackRgb9E5(SmallFloat.packRgb9E5(1f, 0.5f, 0.25f)).y());
        assertEquals(0.25f, SmallFloat.unpackRgb9E5(SmallFloat.packRgb9E5(1f, 0.5f, 0.25f)).z());
        // decoded values re-encode to themselves
        for (int i = 0; i < 50_000; i++) {
            int bits = r.nextInt() & 0x7FFFFFFF | (r.nextInt(2) << 31);
            Vec3f v = SmallFloat.unpackRgb9E5(bits);
            Vec3f again = SmallFloat.unpackRgb9E5(SmallFloat.packRgb9E5(v));
            assertEquals(v, again, "idempotent for bits " + Integer.toHexString(bits));
        }
    }

    // ------------------------------------------------------------ octahedral

    @Test
    void octahedralEncodingIsExactBeforeQuantization() {
        for (int i = 0; i < 100_000; i++) {
            Vec3f n = randomUnit();
            Vec2f e = Octahedral.encode(n);
            assertTrue(Math.abs(e.x()) <= 1f && Math.abs(e.y()) <= 1f, "inside the square: " + e);
            Vec3f d = Octahedral.decode(e);
            assertEquals(1f, d.length(), 1e-6f);
            assertTrue(Octahedral.angleBetween(n, d) < 2e-6f, "unquantized round trip of " + n);
        }
    }

    @Test
    void octahedralSpecialDirections() {
        Vec3f[] axes = {Vec3f.UNIT_X, Vec3f.UNIT_Y, Vec3f.UNIT_Z, Vec3f.UNIT_X.negate(), Vec3f.UNIT_Y.negate(), Vec3f.UNIT_Z.negate()};
        for (Vec3f a : axes) {
            assertTrue(Octahedral.angleBetween(a, Octahedral.unpack16(Octahedral.pack16(a))) < 1e-6f, "16-bit axis " + a);
            assertTrue(Octahedral.angleBetween(a, Octahedral.unpack8(Octahedral.pack8(a))) < 1e-6f, "8-bit axis " + a);
        }
        // diagonals lie on the fold of the octahedron
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    Vec3f d = new Vec3f(sx, sy, sz).normalize();
                    assertTrue(Octahedral.angleBetween(d, Octahedral.unpack16(Octahedral.pack16(d))) < 5e-5f);
                    assertTrue(Octahedral.angleBetween(d, Octahedral.unpack8(Octahedral.pack8(d))) < 1.3e-2f);
                }
            }
        }
        // out-of-range input is clamped, never NaN
        assertEquals(1f, Octahedral.decode(5f, -7f).length(), 1e-6f);
    }

    /** The bounds are the worst cases measured over 2 million random directions (see docs/FORMATS.md), with some margin. */
    @Test
    void octahedralWorstCaseAngularError() {
        double max16 = 0, max8 = 0;
        for (int i = 0; i < 300_000; i++) {
            Vec3f n = randomUnit();
            max16 = Math.max(max16, Octahedral.angleBetween(n, Octahedral.unpack16(Octahedral.pack16(n))));
            max8 = Math.max(max8, Octahedral.angleBetween(n, Octahedral.unpack8(Octahedral.pack8(n))));
        }
        assertTrue(max16 < 5e-5, "16-bit worst error " + max16 + " rad");
        assertTrue(max8 < 1.3e-2, "8-bit worst error " + max8 + " rad");
        assertTrue(max16 > 1e-5 && max8 > 1e-3, "the bounds must not be vacuous: " + max16 + ", " + max8);
    }

    @Test
    void bestOfFourRoundingBeatsPlainRoundingAtEightBits() {
        double plainWorst = 0, bestWorst = 0;
        for (int i = 0; i < 100_000; i++) {
            Vec3f n = randomUnit();
            Vec2f e = Octahedral.encode(n);
            Vec3f plain = Octahedral.decode(Norm.unpackSnorm8(Norm.packSnorm8(e.x())), Norm.unpackSnorm8(Norm.packSnorm8(e.y())));
            double plainErr = Octahedral.angleBetween(n, plain);
            double bestErr = Octahedral.angleBetween(n, Octahedral.unpack8(Octahedral.pack8(n)));
            plainWorst = Math.max(plainWorst, plainErr);
            bestWorst = Math.max(bestWorst, bestErr);
            assertTrue(bestErr <= plainErr + 1e-6, "best-of-four is never worse than plain rounding for " + n);
        }
        assertTrue(bestWorst < plainWorst * 0.85, "worst " + bestWorst + " vs plain " + plainWorst);
    }

    @Test
    void octahedralPackingLayout() {
        Vec3f n = randomUnit();
        int p16 = Octahedral.pack16(n);
        Vec3f d = Octahedral.decode(Norm.unpackSnorm16(p16), Norm.unpackSnorm16(p16 >>> 16));
        assertEquals(0f, Octahedral.angleBetween(d, Octahedral.unpack16(p16)), 1e-6f, "x is the low half");
        int p8 = Octahedral.pack8(n);
        assertEquals(0, p8 >>> 16, "the 8-bit form uses only the low 16 bits");
        Vec3f d8 = Octahedral.decode(Norm.unpackSnorm8(p8), Norm.unpackSnorm8(p8 >>> 8));
        assertEquals(0f, Octahedral.angleBetween(d8, Octahedral.unpack8(p8)), 1e-6f, "x is the low byte");
    }

    // ------------------------------------------------------------ packed quaternions

    private static double rotationError(Quatf a, Quatf b) {
        return 2 * Math.acos(Math.min(1.0, Math.abs(a.dot(b))));
    }

    @Test
    void packedQuaternionWorstCaseError() {
        double worst = 0;
        for (int i = 0; i < 300_000; i++) {
            double x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian(), w = r.nextGaussian();
            double l = Math.sqrt(x * x + y * y + z * z + w * w);
            Quatf q = new Quatf((float) (x / l), (float) (y / l), (float) (z / l), (float) (w / l));
            Quatf u = QuatPacked.unpack(QuatPacked.pack(q));
            assertEquals(1f, u.length(), 1e-5f, "decoded quaternions are unit");
            worst = Math.max(worst, rotationError(q, u));
        }
        assertTrue(worst < 4.5e-3, "worst rotation error " + worst + " rad");
        assertTrue(worst > 1e-3, "the bound must not be vacuous: " + worst);
    }

    @Test
    void packedQuaternionHandlesEveryDroppedComponentAndBothSigns() {
        Quatf[] axes = {new Quatf(1f, 0f, 0f, 0f), new Quatf(0f, 1f, 0f, 0f), new Quatf(0f, 0f, 1f, 0f), Quatf.IDENTITY};
        Set<Integer> dropped = new HashSet<>();
        for (Quatf axis : axes) {
            for (float sign : new float[] {1f, -1f}) {
                Quatf q = new Quatf(axis.x() * sign, axis.y() * sign, axis.z() * sign, axis.w() * sign);
                int packed = QuatPacked.pack(q);
                dropped.add(packed >>> 30);
                assertTrue(rotationError(q, QuatPacked.unpack(packed)) < 1e-4, "axis-aligned " + q);
            }
        }
        assertEquals(4, dropped.size(), "each of x, y, z, w is the dropped component for some input");
        // q and -q are the same rotation and pack identically
        for (int i = 0; i < 5000; i++) {
            Quatf q = new Quatf((float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian()).normalize();
            assertEquals(QuatPacked.pack(q), QuatPacked.pack(new Quatf(-q.x(), -q.y(), -q.z(), -q.w())));
        }
        // a slightly drifted input is normalized first
        Quatf drift = new Quatf(0.5f, 0.5f, 0.5f, 0.5f * 1.01f);
        assertTrue(rotationError(drift.normalize(), QuatPacked.unpack(QuatPacked.pack(drift))) < 4.5e-3);
    }

    // ------------------------------------------------------------ position quantization

    @Test
    void quantizationErrorStaysWithinHalfAStep() {
        Aabbf box = Aabbf.of(new Vec3f(-3f, 10f, 200f), new Vec3f(5f, 12f, 1000f));
        Quantizer q = new Quantizer(box);
        Vec3f maxErr = q.maxError();
        short[] bits = new short[6];
        for (int i = 0; i < 100_000; i++) {
            Vec3f p = new Vec3f((float) r.nextDouble(-3, 5), (float) r.nextDouble(10, 12), (float) r.nextDouble(200, 1000));
            q.pack(p, bits, 3);
            Vec3f back = q.unpack(bits, 3);
            assertTrue(Math.abs(back.x() - p.x()) <= maxErr.x() * 1.01f + 1e-5f, "x error for " + p);
            assertTrue(Math.abs(back.y() - p.y()) <= maxErr.y() * 1.01f + 1e-5f, "y error for " + p);
            assertTrue(Math.abs(back.z() - p.z()) <= maxErr.z() * 1.01f + 5e-5f, "z error for " + p);
        }
        assertEquals(8f / 131070f, maxErr.x(), 1e-9f);
    }

    @Test
    void quantizationCornersAreExactAndOutsidePointsClamp() {
        Aabbf box = Aabbf.of(new Vec3f(-1f, -2f, -3f), new Vec3f(1f, 2f, 3f));
        Quantizer q = new Quantizer(box);
        short[] bits = new short[3];
        q.pack(box.min(), bits, 0);
        assertEquals(0, bits[0]);
        assertEquals(0, bits[1]);
        assertEquals(0, bits[2]);
        q.pack(box.max(), bits, 0);
        assertEquals((short) 65535, bits[0]);
        assertEquals(box.max(), q.unpack(bits, 0));
        q.pack(new Vec3f(100f, -100f, 0f), bits, 0);
        assertEquals((short) 65535, bits[0], "clamped to the box");
        assertEquals(0, bits[1]);
        assertEquals(box.min(), q.unpack((short) 0, (short) 0, (short) 0));
    }

    @Test
    void dequantizationMatrixRestoresPositionsFromNormalizedCoordinates() {
        Aabbf box = Aabbf.of(new Vec3f(-4f, 1f, 10f), new Vec3f(8f, 3f, 30f));
        Quantizer q = new Quantizer(box);
        Mat4f m = q.dequantizationMatrix();
        short[] bits = new short[3];
        for (int i = 0; i < 1000; i++) {
            Vec3f p = new Vec3f((float) r.nextDouble(-4, 8), (float) r.nextDouble(1, 3), (float) r.nextDouble(10, 30));
            q.pack(p, bits, 0);
            Vec3f normalized = new Vec3f(Norm.unpackUnorm16(bits[0]), Norm.unpackUnorm16(bits[1]), Norm.unpackUnorm16(bits[2]));
            Vec3f viaMatrix = m.transformPosition(normalized);
            Vec3f direct = q.unpack(bits, 0);
            assertTrue(viaMatrix.distance(direct) < 1e-4f, "matrix and unpack agree: " + viaMatrix + " vs " + direct);
        }
    }

    @Test
    void quantizingAFlatBoxAndRejectingAnEmptyOne() {
        Quantizer flat = new Quantizer(Aabbf.of(new Vec3f(0f, 5f, 0f), new Vec3f(4f, 5f, 4f)));
        short[] bits = new short[3];
        flat.pack(new Vec3f(2f, 5f, 2f), bits, 0);
        assertEquals(0, bits[1], "a zero-size axis stores 0");
        assertEquals(5f, flat.unpack(bits, 0).y());
        assertEquals(0f, flat.maxError().y());
        assertThrows(IllegalArgumentException.class, () -> new Quantizer(Aabbf.EMPTY));
    }

    // ------------------------------------------------------------ format table

    @Test
    void formatTokensAreConsistent() {
        Set<Integer> vk = new HashSet<>(), gl = new HashSet<>();
        for (PackedFormat f : PackedFormat.values()) {
            assertTrue(vk.add(f.vkFormat()), "duplicate Vulkan format " + f);
            if (f.glInternalFormat() != 0) {
                assertTrue(gl.add(f.glInternalFormat()), "duplicate GL format " + f);
            }
            assertTrue(f.bytesPerTexel() >= 1 && f.bytesPerTexel() <= 16);
        }
        // spot checks against values from the Khronos headers (glcorearb.h and vulkan_core.h)
        assertEquals(0x881A, PackedFormat.RGBA16_SFLOAT.glInternalFormat());
        assertEquals(97, PackedFormat.RGBA16_SFLOAT.vkFormat());
        assertEquals(0x8C3A, PackedFormat.R11G11B10_UFLOAT.glInternalFormat());
        assertEquals(122, PackedFormat.R11G11B10_UFLOAT.vkFormat());
        assertEquals(0x8C3D, PackedFormat.RGB9_E5_UFLOAT.glInternalFormat());
        assertEquals(123, PackedFormat.RGB9_E5_UFLOAT.vkFormat());
        assertEquals(0x8059, PackedFormat.RGB10_A2_UNORM.glInternalFormat());
        assertEquals(64, PackedFormat.RGB10_A2_UNORM.vkFormat());
        assertEquals(92, PackedFormat.RGBA16_SNORM.vkFormat());
        assertEquals(0, PackedFormat.RGB10_A2_SNORM.glInternalFormat(), "no OpenGL sized format for it");
        // sizes follow the channel layout
        assertEquals(8, PackedFormat.RGBA16_SFLOAT.bytesPerTexel());
        assertEquals(4, PackedFormat.RG16_SNORM.bytesPerTexel());
        assertEquals(16, PackedFormat.RGBA32_SFLOAT.bytesPerTexel());
        assertFalse(PackedFormat.valueOf("R8_UNORM").bytesPerTexel() != 1);
    }
}
