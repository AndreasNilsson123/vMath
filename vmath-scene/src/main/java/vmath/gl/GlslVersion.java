package vmath.gl;

import java.util.List;
import vmath.annotations.Experimental;

/**
 * A desktop GLSL version, from the 3.30 that the library takes as its floor up to 4.60.
 *
 * <p>Only the versions that exist are values: 330, 400, 410, 420, 430, 440, 450 and 460. A number
 * below 330 is refused, because nothing below the floor is promised.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GlslVersion v = GlslVersion.ofOpenGl(4, 3);                  // 430
 * String line = v.versionLine();                               // "#version 430 core"
 * boolean explicitBindings = v.atLeast(GlslVersion.V420);      // true
 * }</pre>
 *
 * @param number the version number as it is written after {@code #version}, for example 450
 */
@Experimental("the set of versions may grow")
public record GlslVersion(int number) implements Comparable<GlslVersion> {

    /** GLSL 3.30, the floor (OpenGL 3.3). */
    public static final GlslVersion V330 = new GlslVersion(330);
    /** GLSL 4.00 (OpenGL 4.0). */
    public static final GlslVersion V400 = new GlslVersion(400);
    /** GLSL 4.10 (OpenGL 4.1). */
    public static final GlslVersion V410 = new GlslVersion(410);
    /** GLSL 4.20 (OpenGL 4.2): explicit binding points. */
    public static final GlslVersion V420 = new GlslVersion(420);
    /** GLSL 4.30 (OpenGL 4.3): compute shaders and storage blocks. */
    public static final GlslVersion V430 = new GlslVersion(430);
    /** GLSL 4.40 (OpenGL 4.4). */
    public static final GlslVersion V440 = new GlslVersion(440);
    /** GLSL 4.50 (OpenGL 4.5), also the version of GLSL for Vulkan. */
    public static final GlslVersion V450 = new GlslVersion(450);
    /** GLSL 4.60 (OpenGL 4.6). */
    public static final GlslVersion V460 = new GlslVersion(460);

    private static final List<GlslVersion> ALL = List.of(V330, V400, V410, V420, V430, V440, V450, V460);

    /**
     * Checks the number.
     *
     * @throws IllegalArgumentException if {@code number} is not one of the desktop versions from
     *     330 on
     */
    public GlslVersion {
        switch (number) {
            case 330, 400, 410, 420, 430, 440, 450, 460 -> {
            }
            default -> throw new IllegalArgumentException("not a desktop GLSL version from 3.30 on: " + number);
        }
    }

    /**
     * Returns the version with a number.
     *
     * @param number the number, for example 430
     * @return the version
     * @throws IllegalArgumentException if {@code number} is not one of the desktop versions from
     *     330 on
     */
    public static GlslVersion of(int number) {
        for (GlslVersion v : ALL) {
            if (v.number == number) {
                return v;
            }
        }
        throw new IllegalArgumentException("not a desktop GLSL version from 3.30 on: " + number);
    }

    /**
     * Returns the version of GLSL that goes with an OpenGL version: 330 for OpenGL 3.3, and
     * {@code 400 + 10 * minor} for 4.0 to 4.6. A version newer than 4.6 gives 460.
     *
     * @param major the major version of OpenGL
     * @param minor the minor version of OpenGL
     * @return the matching GLSL version
     * @throws IllegalArgumentException if the OpenGL version is below 3.3
     */
    public static GlslVersion ofOpenGl(int major, int minor) {
        int v = major * 10 + minor;
        if (major < 3 || v < 33) {
            throw new IllegalArgumentException("OpenGL " + major + "." + minor + " is below the floor of 3.3");
        }
        if (v >= 46 && major >= 4) {
            return V460;
        }
        if (major == 3) {
            return V330;
        }
        return of(400 + 10 * minor);
    }

    /**
     * Compares with another version.
     *
     * @param other the other version; must not be {@code null}
     * @return {@code true} if this version is the same as {@code other} or newer
     */
    public boolean atLeast(GlslVersion other) {
        return number >= other.number;
    }

    /**
     * Writes the directive that starts a shader of this version, with the core profile that every
     * version from 3.30 on has.
     *
     * @return for example {@code "#version 330 core"}
     */
    public String versionLine() {
        return "#version " + number + " core";
    }

    @Override
    public int compareTo(GlslVersion other) {
        return Integer.compare(number, other.number);
    }

    @Override
    public String toString() {
        return "GLSL " + number / 100 + "." + String.format("%02d", number % 100);
    }
}
