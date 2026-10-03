package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record that becomes a {@code value record} in the Valhalla build profile ({@code -Pvalhalla}).
 * In the default profile it is an ordinary record.
 *
 * <p>The retention is class so that the validating annotation processor of {@code vmath-validator} sees the marker on types that come from another module of the library, which
 * reaches it as class files. Like {@code @Experimental} it needs no run-time dependency.
 *
 * <p>Value types must be final, have only final fields and must not depend on identity: no {@code ==},
 * {@code synchronized}, or identity hash maps.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ValueType {
}
