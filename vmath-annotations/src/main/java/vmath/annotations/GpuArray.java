package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gives a record component of array type its fixed GLSL array length, e.g.
 * {@code @GpuArray(4) Vec4f[] cascades}.
 *
 * <p><b>Thread safety.</b> Not applicable: an annotation type has no state.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface GpuArray {

    /**
     * Declares the array length of a GPU array field so that the layout generator can size and
     * align it.
     *
     * <p>The generated writer requires the array to have exactly this length.
     *
     * @return the array length
     */
    int value();
}
