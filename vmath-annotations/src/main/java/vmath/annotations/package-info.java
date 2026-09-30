/**
 * Source-retention annotations that drive {@code vmath-codegen}. They have no runtime footprint: the generator
 * strips them from its output, and javac discards them anyway. {@link vmath.annotations.Experimental} is the exception: class retention, so tools can
 * see it, but still nothing at run time.
 */
package vmath.annotations;
