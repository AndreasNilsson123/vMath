/** Build-time source generator for the vmath float/double twins. Runs on a JDK: it needs the compiler's tree API. */
module vmath.codegen {
    requires jdk.compiler;

    exports vmath.codegen;
}
