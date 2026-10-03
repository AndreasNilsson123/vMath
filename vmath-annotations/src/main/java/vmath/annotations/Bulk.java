package vmath.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Asks the generator for batch loops of a method: the scalar operation is written once, as a
 * normal method of a template record, and the generator emits the loops that apply it to arrays.
 *
 * <p>For a record {@code Vec3f} with a marked method {@code add(Vec3f o)} the generator writes a
 * class {@code Vec3fBulk} (and the twin {@code Vec3dBulk} for {@code double}) with two methods per
 * marked method: {@code add}, over <em>interleaved</em> arrays (element {@code i} of a
 * {@code Vec3f} operand starts at {@code offset + 3 * i}, the layout of the bulk containers), and
 * {@code addPlanar}, over <em>planar</em> (structure-of-arrays) data with one array per component.
 * The body of the method is copied into the loop with the components of the operands read into
 * local variables, so the result is bit-identical to calling the method per element.
 *
 * <p>The marked method may use the components of {@code this} and of its parameters (as fields or
 * through their accessors), local variables of primitive type, {@code if} statements, the
 * operators, casts and {@code Math}, {@code Float} and {@code Double} members. A {@code return}
 * gives the result of the element: {@code new T(...)} with every component, {@code this}, one of
 * the parameters, or a {@code float} expression. Parameters are records of the generator's
 * templates whose components are all {@code float}, or primitives, which are the same for every
 * element. Anything else (loops, calls to other methods, other types) is an error that names the
 * line, so a method that does not fit is written by hand.
 *
 * <p><b>Thread safety.</b> Not applicable: an annotation type has no state.
 *
 * <p><b>Example:</b>
 *
 * <pre>
 * &#64;GenerateDouble
 * public record Vec2f(float x, float y) {
 *     &#64;Bulk
 *     public Vec2f add(Vec2f o) {
 *         return new Vec2f(x + o.x, y + o.y);
 *     }
 *
 *     &#64;Bulk(uniform = "this")                     // one matrix, many vectors
 *     public float dot(Vec2f o) {
 *         return x * o.x + y * o.y;
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Bulk {

    /**
     * Names the generated methods, for the case of overloads that would otherwise have the same
     * parameter types in the generated class.
     *
     * @return the name of the generated method; empty for the name of the marked method
     */
    String name() default "";

    /**
     * Lists the operands that are one value for the whole batch instead of one per element, such
     * as the matrix when many vectors are transformed by it.
     *
     * <p>The entries are parameter names, or {@code "this"} for the receiver. A uniform operand is
     * read once, from the first element at its offset.
     *
     * @return the names of the uniform operands; none by default
     */
    String[] uniform() default {};
}
