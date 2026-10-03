package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The member exists only in the float output and is dropped from the double twin (e.g.
 * {@code toDouble()}).
 *
 * <p>Write it as normal float code. The generator reports an error if double-emitted code calls it.
 *
 * <p><b>Thread safety.</b> Not applicable: an annotation type has no state.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.TYPE})
public @interface FloatOnly {
}
