package vmath.simd;

/**
 * The part of the two SIMD kernel providers that does not depend on the kind of kernel: the name
 * and priority under which they register and the test for whether the machine can run them.
 *
 * <p>Internal: the providers add only {@code create()}.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 */
abstract class SimdProvider {

    public String name() {
        return SimdSupport.NAME;
    }

    public int priority() {
        return SimdSupport.PRIORITY;
    }

    public boolean isSupported() {
        return SimdSupport.vectorsAvailable();
    }
}
