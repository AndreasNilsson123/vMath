/**
 * vmath-core: the vector, quaternion and matrix value types (float and double), and the packages
 * that need nothing above them: memory allocators, colour and texture formats.
 *
 * <p>The annotations are class-retention build-time markers, so the dependency is {@code static}:
 * it is needed to compile but never at run time, and the module resolves without it.
 */
module vmath.core {
    requires static vmath.annotations;

    exports vmath.core;
    exports vmath.mem;
    exports vmath.color;
    exports vmath.tex;
}
