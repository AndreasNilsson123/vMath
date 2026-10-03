package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-precision constant (typically a test tolerance).
 *
 * <p>The field's initializer is used for float; the double twin receives {@link #d()} instead.
 * Requires an initializer.
 *
 * <p><b>Thread safety.</b> Not applicable: an annotation type has no state.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface Eps {

    /**
     * Declares the source text of the initializer that the generator emits for the double twin of a
     * constant.
     *
     * <p>Must be a literal or constant expression.
     *
     * @return the double value
     */
    double d();
}
