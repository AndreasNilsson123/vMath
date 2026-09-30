package vmath.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API that is new and may still change or disappear in any release, including a patch release, without a deprecation period. It is excluded from
 * the japicmp compatibility check. An experimental API leaves this state by losing the annotation in a release that says so in the changelog; from then on
 * it follows the normal rules in {@code docs/VERSIONING.md}.
 *
 * <p>Class retention: the marker is in the class file (so tools such as japicmp can see it) but costs nothing at run time, and a consumer does not need the
 * annotations jar on its path.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Experimental {
    /** Why it is experimental or what is expected to change. */
    String value() default "";
}
