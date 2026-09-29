package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Gives a record component of array type its fixed GLSL array length, e.g. {@code @GpuArray(4) Vec4f[] cascades}. */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface GpuArray {

    /** Number of elements (at least 1). The generated writer requires the array to have exactly this length. */
    int value();
}
