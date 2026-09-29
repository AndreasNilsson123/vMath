package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-precision constant (typically a test tolerance). The field's initializer is used for float; the double
 * twin receives {@link #d()} instead. Requires an initializer.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface Eps {

    /** Initializer for the double twin. Must be a literal or constant expression. */
    double d();
}
