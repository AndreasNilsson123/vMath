package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code int} record component as a GLSL {@code uint}.
 *
 * <p>The bits are the same; only the GLSL declaration differs.
 *
 * <p><b>Thread safety.</b> Not applicable: an annotation type has no state.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface GpuUint {
}
