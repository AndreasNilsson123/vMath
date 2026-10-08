package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks the table of {@link GlslFeature} with a compiler, in both directions: a minimal shader that
 * uses each construct is compiled at every desktop version from 3.30, and it must be accepted
 * exactly from the version that the table gives and refused below it. Where the compiler disagrees
 * with the table the table is wrong (or the compiler is: say which, in the table's own
 * documentation). Skipped without a compiler (see {@link GlslCompiler}).
 */
class GlslFeatureCompileTest {

    private static final List<GlslVersion> VERSIONS = List.of(GlslVersion.V330, GlslVersion.V400, GlslVersion.V410, GlslVersion.V420, GlslVersion.V430, GlslVersion.V440, GlslVersion.V450,
            GlslVersion.V460);

    @Test
    void everyFeatureIsAcceptedExactlyFromItsVersionAndRefusedBelow() {
        GlslCompiler.require();
        StringBuilder disagreements = new StringBuilder();
        for (GlslFeature f : GlslFeature.values()) {
            StringBuilder row = new StringBuilder();
            for (GlslVersion v : VERSIONS) {
                boolean accepted = GlslCompiler.accepts(f.probeStage(), v.versionLine() + "\n" + f.probeBody());
                row.append(accepted ? 'Y' : '-');
                if (accepted != v.supports(f)) {
                    disagreements.append(f).append(" at ").append(v).append(": the table says ").append(v.supports(f) ? "accepted" : "refused").append(", the compiler ")
                            .append(accepted ? "accepted" : "refused").append('\n');
                }
            }
            System.out.println(String.format("%-28s %s   (table: from %s)", f, row, f.minimum()));
        }
        assertEquals("", disagreements.toString(), "the compiler and the table of GlslFeature disagree");
    }

    @Test
    void everyFeatureIsAcceptedExactlyFromItsEsVersionAndRefusedBelow() {
        GlslCompiler.require();
        List<GlslEsVersion> versions = List.of(GlslEsVersion.V300, GlslEsVersion.V310, GlslEsVersion.V320);
        StringBuilder disagreements = new StringBuilder();
        for (GlslFeature f : GlslFeature.values()) {
            StringBuilder row = new StringBuilder();
            for (GlslEsVersion v : versions) {
                boolean accepted = GlslCompiler.accepts(f.probeStage(), v.versionLine() + "\n" + f.probeBody());
                row.append(accepted ? 'Y' : '-');
                if (accepted != v.supports(f)) {
                    disagreements.append(f).append(" at ").append(v).append(": the table says ").append(v.supports(f) ? "accepted" : "refused").append(", the compiler ")
                            .append(accepted ? "accepted" : "refused").append('\n');
                }
            }
            System.out.println(String.format("%-28s %s   (ES table: %s)", f, row, f.minimumEs() == null ? "not in ES" : "from " + f.minimumEs()));
        }
        assertEquals("", disagreements.toString(), "the compiler and the ES column of GlslFeature disagree");
    }

    @Test
    void aFragmentShaderOfEsNeedsADefaultPrecision() {
        GlslCompiler.require();
        for (GlslEsVersion v : List.of(GlslEsVersion.V300, GlslEsVersion.V310, GlslEsVersion.V320)) {
            assertEquals(false, GlslCompiler.accepts("frag", v.versionLine() + "\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n"), "without a precision at " + v);
            assertEquals(true, GlslCompiler.accepts("frag", v.versionLine() + "\nprecision highp float;\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n"), "with a precision at " + v);
        }
    }
}
