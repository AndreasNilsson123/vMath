package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The member exists only in the double twin and is dropped from the float output (e.g.
 * {@code toFloat()}).
 *
 * <p>Unlike everything else in a template, a {@code @DoubleOnly} member is copied <b>verbatim</b>:
 * no float→double conversion or renaming is applied, so it is written in its final double form and
 * may name the float twin ({@code Vec3f}) directly. The generator reports an error if float-emitted
 * code calls it.
 *
 * <p><b>Thread safety.</b> Not applicable: an annotation type has no state.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.TYPE})
public @interface DoubleOnly {
}
