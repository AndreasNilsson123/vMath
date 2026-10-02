package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.geo.Frustumf;

/**
 * Providers registered through {@code META-INF/services/vmath.spatial.FrustumKernelProvider} in the test resources, to exercise the selection logic of
 * {@link FrustumKernels} in the core module: each one is a way a real provider can misbehave.
 */
public final class TestKernelProviders {

    private TestKernelProviders() {
    }

    /** A kernel that is the scalar one under another name, so that a test can tell which provider was used. */
    static final class Named implements FrustumKernel {
        private final String name;
        private final FrustumKernel inner = new FrustumCuller();

        Named(String name) {
            this.name = name;
        }

        @Override
        public void cull(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible) {
            inner.cull(frustum, bounds, from, to, visible);
        }

        @Override
        public String name() {
            return name;
        }
    }

    /** Supported, but with a priority below the scalar kernel's 0: only selectable by name. */
    public static final class Low implements FrustumKernelProvider {
        public Low() {
        }

        @Override
        public String name() {
            return "test-low";
        }

        @Override
        public int priority() {
            return -5;
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public FrustumKernel create() {
            return new Named("test-low");
        }
    }

    /** Supported, with priority exactly 0: it does not beat the scalar kernel. */
    public static final class Zero implements FrustumKernelProvider {
        public Zero() {
        }

        @Override
        public String name() {
            return "test-zero";
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public FrustumKernel create() {
            return new Named("test-zero");
        }
    }

    /** A high priority that must not matter: the provider says it cannot run here. */
    public static final class Unsupported implements FrustumKernelProvider {
        public Unsupported() {
        }

        @Override
        public String name() {
            return "test-unsupported";
        }

        @Override
        public int priority() {
            return 500;
        }

        @Override
        public boolean isSupported() {
            return false;
        }

        @Override
        public FrustumKernel create() {
            throw new AssertionError("an unsupported provider must never be asked for a kernel");
        }
    }

    /** {@code isSupported()} breaks its contract and throws: the provider must be skipped. */
    public static final class ThrowsWhenAsked implements FrustumKernelProvider {
        public ThrowsWhenAsked() {
        }

        @Override
        public String name() {
            return "test-throws";
        }

        @Override
        public int priority() {
            return 400;
        }

        @Override
        public boolean isSupported() {
            throw new IllegalStateException("probing the hardware failed");
        }

        @Override
        public FrustumKernel create() {
            throw new AssertionError("never asked");
        }
    }

    /** The best priority and supported, but it cannot build its kernel: selection must fall back to the scalar kernel. */
    public static final class FailingCreate implements FrustumKernelProvider {
        public FailingCreate() {
        }

        @Override
        public String name() {
            return "test-failing-create";
        }

        @Override
        public int priority() {
            return 600;
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public FrustumKernel create() {
            throw new IllegalStateException("cannot allocate the kernel");
        }
    }

    /** Its constructor throws, so {@code ServiceLoader} reports a {@code ServiceConfigurationError} that selection must survive. */
    public static final class BrokenConstructor implements FrustumKernelProvider {
        public BrokenConstructor() {
            throw new IllegalStateException("cannot be constructed");
        }

        @Override
        public String name() {
            return "test-broken-constructor";
        }

        @Override
        public int priority() {
            return 700;
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public FrustumKernel create() {
            throw new AssertionError("never constructed");
        }
    }
}
