package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a float-precision template type.
 *
 * <p>{@code vmath-codegen} emits both the float type (this source with the {@link DoubleOnly}
 * members removed) and its double twin.
 *
 * <p>The template's name must end in {@code f} (or {@code fTest} for tests), e.g. {@code Vec3f}
 * becomes {@code Vec3d}. Types with other names must state their twin explicitly.
 *
 * <p><b>Thread safety.</b> Not applicable: an annotation type has no state.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface GenerateDouble {

    /**
     * Selects the name under which the generator emits the double twin of an annotated type.
     *
     * <p>Empty means derive it from the template name.
     *
     * @return the twin's simple name, or empty to derive it
     */
    String twin() default "";
}
