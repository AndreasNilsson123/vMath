package vmath.bench;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import vmath.color.ColorSpaces;
import vmath.color.PremultipliedAlpha;
import vmath.color.Srgb;
import vmath.color.ToneMap;

/** Colour conversions over 100 000 RGBA pixels (400 000 floats): the exact and fast sRGB transfer functions, tone mapping, Oklab and HSV conversion, premultiplication. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ColorBench {

    private static final int N = 100_000;

    private float[] source, work;
    private final float[] tmp = new float[3];

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(6);
        source = new float[4 * N];
        work = new float[4 * N];
        for (int i = 0; i < source.length; i++) {
            source[i] = (float) r.nextDouble();
        }
    }

    private void reset() {
        System.arraycopy(source, 0, work, 0, source.length);
    }

    @Benchmark
    public float srgbToLinearExact() {
        reset();
        Srgb.toLinear(work, 0, N, 4, false);
        return work[0];
    }

    @Benchmark
    public float srgbToLinearFast() {
        reset();
        Srgb.toLinear(work, 0, N, 4, true);
        return work[0];
    }

    @Benchmark
    public float srgbFromLinearExact() {
        reset();
        Srgb.fromLinear(work, 0, N, 4, false);
        return work[0];
    }

    @Benchmark
    public float srgbFromLinearFast() {
        reset();
        Srgb.fromLinear(work, 0, N, 4, true);
        return work[0];
    }

    @Benchmark
    public int byteToLinearTable() {
        float sum = 0;
        for (int i = 0; i < N; i++) {
            sum += Srgb.byteToLinear(i);
        }
        return (int) sum;
    }

    @Benchmark
    public int linearToByteExact() {
        int sum = 0;
        for (int i = 0; i < N; i++) {
            sum += Srgb.linearToByte(source[i]);
        }
        return sum;
    }

    @Benchmark
    public int linearToByteFast() {
        int sum = 0;
        for (int i = 0; i < N; i++) {
            sum += Srgb.linearToByteFast(source[i]);
        }
        return sum;
    }

    @Benchmark
    public float toneMapAces() {
        reset();
        ToneMap.apply(ToneMap.Curve.ACES, 0f, work, 0, N, 4);
        return work[0];
    }

    @Benchmark
    public float toneMapHable() {
        reset();
        ToneMap.apply(ToneMap.Curve.HABLE, 0f, work, 0, N, 4);
        return work[0];
    }

    @Benchmark
    public float oklabRoundTrip() {
        float sum = 0;
        for (int i = 0; i < N; i++) {
            ColorSpaces.linearSrgbToOklab(source[4 * i], source[4 * i + 1], source[4 * i + 2], tmp);
            ColorSpaces.oklabToLinearSrgb(tmp[0], tmp[1], tmp[2], tmp);
            sum += tmp[0];
        }
        return sum;
    }

    @Benchmark
    public float hsvRoundTrip() {
        float sum = 0;
        for (int i = 0; i < N; i++) {
            ColorSpaces.rgbToHsv(source[4 * i], source[4 * i + 1], source[4 * i + 2], tmp);
            ColorSpaces.hsvToRgb(tmp[0], tmp[1], tmp[2], tmp);
            sum += tmp[0];
        }
        return sum;
    }

    @Benchmark
    public float premultiplyUnpremultiply() {
        reset();
        PremultipliedAlpha.premultiply(work, 0, N);
        PremultipliedAlpha.unpremultiply(work, 0, N);
        return work[0];
    }

    /** The baseline for the copy that every array benchmark pays. */
    @Benchmark
    public float copyOnly() {
        reset();
        return work[0];
    }
}
