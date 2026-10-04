package vmath;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Assumptions;

/**
 * Where tests that need something of the machine (the module jars, {@code jdeps}, an enabled JIT)
 * say so.
 *
 * <p>Such a test is skipped when the thing is missing, which is right on a developer machine and
 * wrong on a CI machine, where a skipped layering test looks like a passed one. With
 * {@code -Dvmath.requireEnvironment=true} (set by CI) a missing requirement fails the test
 * instead.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 */
public final class Environment {

    private static final boolean REQUIRED = Boolean.getBoolean("vmath.requireEnvironment");

    private Environment() {
    }

    /**
     * Skips the calling test, or fails it when the environment is required, unless a requirement
     * holds.
     *
     * @param holds whether the requirement is met
     * @param what what is missing when it is not; must not be {@code null}
     */
    public static void require(boolean holds, String what) {
        if (holds) {
            return;
        }
        if (REQUIRED) {
            fail("the environment is required (-Dvmath.requireEnvironment=true) but " + what);
        }
        Assumptions.abort(what);
    }
}
