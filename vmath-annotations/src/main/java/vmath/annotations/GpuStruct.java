package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record that mirrors a GLSL struct. The build generates {@code <Name>Gpu} next to it, holding the member offsets,
 * the size and alignment under the chosen {@link Layout}, a matching GLSL declaration, and a
 * {@code write(record, MemorySegment, offset)} method.
 *
 * <p>Supported component types: {@code float}, {@code int} (and {@code uint} with {@link GpuUint}), {@code Vec2f},
 * {@code Vec3f}, {@code Vec4f}, {@code Quatf} (as a vec4), {@code Vec2i}, {@code Vec3i}, {@code Mat3f}, {@code Mat4f},
 * {@code Mat4x3f}, other {@code @GpuStruct} records, and arrays of those with {@link GpuArray}. Double-precision types are
 * rejected at build time.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface GpuStruct {

    /**
     * The memory layout rules; must match the GLSL block's {@code layout(...)} qualifier.
     *
     * @return the layout
     */
    Layout layout() default Layout.STD140;

    /** Mirrors {@code vmath.gl.GpuLayout} (this module cannot depend on it). */
    enum Layout {
        /** Uniform blocks: arrays and structs rounded up to 16 bytes. */
        STD140,
        /** Storage blocks: like std140 without the rounding of arrays and structs. */
        STD430,
        /** Scalar block layout: everything aligned to its 4-byte component. */
        SCALAR
    }
}
