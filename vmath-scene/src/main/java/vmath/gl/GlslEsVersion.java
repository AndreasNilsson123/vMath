package vmath.gl;

import vmath.annotations.Experimental;

/**
 * A version of the OpenGL ES shading language: 3.00 (OpenGL ES 3.0 and WebGL 2), 3.10 and 3.20,
 * the targets of the mobile and web GPUs.
 *
 * <p>It is the counterpart of {@link GlslVersion} for the ES profile, whose table of constructs is
 * {@link GlslFeature#minimumEs()}: storage blocks, compute shaders, explicit binding points and memory
 * qualifiers only from 3.10, texture buffers only from 3.20, no double-precision types and no
 * {@code gl_DrawID}, and a precision that must be stated: a fragment shader has no default for
 * {@code float}, and a sampler or image needs one ({@link ShaderHeader.Builder#esVersion} writes the
 * statements). The numbers were checked with glslang (profile {@code es}) by {@code
 * GlslFeatureCompileTest}.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GlslEsVersion v = GlslEsVersion.V310;
 * boolean compute = v.supports(GlslFeature.COMPUTE_SHADER);       // true
 * String line = v.versionLine();                                  // "#version 310 es"
 * }</pre>
 *
 * @param number the version number as written after {@code #version}: 300, 310 or 320
 */
@Experimental("new in 0.2: the ES targets may change")
public record GlslEsVersion(int number) implements Comparable<GlslEsVersion> {

    /** GLSL ES 3.00 (OpenGL ES 3.0, WebGL 2). */
    public static final GlslEsVersion V300 = new GlslEsVersion(300);
    /** GLSL ES 3.10 (OpenGL ES 3.1): compute shaders and storage blocks. */
    public static final GlslEsVersion V310 = new GlslEsVersion(310);
    /** GLSL ES 3.20 (OpenGL ES 3.2): texture buffers. */
    public static final GlslEsVersion V320 = new GlslEsVersion(320);

    /**
     * Checks the number.
     *
     * @throws IllegalArgumentException if it is not 300, 310 or 320
     */
    public GlslEsVersion {
        if (number != 300 && number != 310 && number != 320) {
            throw new IllegalArgumentException("not a GLSL ES version from 3.00 on: " + number);
        }
    }

    /**
     * Gives the version of a number.
     *
     * @param number 300, 310 or 320
     * @return the version
     * @throws IllegalArgumentException if it is not one of them
     */
    public static GlslEsVersion of(int number) {
        return new GlslEsVersion(number);
    }

    /**
     * Tells whether this version is at or above another.
     *
     * @param other the other version; must not be {@code null}
     * @return {@code true} if this number is not below the other
     */
    public boolean atLeast(GlslEsVersion other) {
        return number >= other.number;
    }

    /**
     * Tells whether this version has a construct, by the ES column of {@link GlslFeature}.
     *
     * @param feature the construct; must not be {@code null}
     * @return {@code true} if the feature exists in ES and this version is at or above its lowest one
     */
    public boolean supports(GlslFeature feature) {
        GlslEsVersion minimum = feature.minimumEs();
        return minimum != null && atLeast(minimum);
    }

    /**
     * Fails fast if this version lacks a construct, naming it and the lowest ES version that has
     * it (or saying that ES has none).
     *
     * @param feature the construct that the caller is about to write; must not be {@code null}
     * @throws UnsupportedOperationException if this version does not have it
     */
    public void require(GlslFeature feature) {
        if (!supports(feature)) {
            GlslEsVersion minimum = feature.minimumEs();
            throw new UnsupportedOperationException(feature.description() + (minimum == null ? " does not exist in GLSL ES" : " needs " + minimum + " or later") + ", and this is " + this);
        }
    }

    /**
     * Gives the {@code #version} line.
     *
     * @return for example {@code #version 310 es}
     */
    public String versionLine() {
        return "#version " + number + " es";
    }

    @Override
    public int compareTo(GlslEsVersion other) {
        return Integer.compare(number, other.number);
    }

    @Override
    public String toString() {
        return "GLSL ES " + number / 100 + "." + String.format("%02d", number % 100);
    }
}
