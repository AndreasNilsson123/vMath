package vmath.gl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import vmath.annotations.Experimental;
import vmath.gl.GraphicsCapabilities.Feature;

/**
 * Chooses among the ways of doing one thing, the best first, by what the
 * {@link GraphicsCapabilities} allow.
 *
 * <p>A chooser holds an ordered list of strategies, each with the features it needs and the GLSL
 * version it needs if it has shaders. {@link #choose} returns the first one that the capabilities
 * satisfy; {@link #chooseAtMost} starts further down the list (a caller that wants to save a
 * feature); {@link #force} checks that a named strategy is possible. A request that cannot be met
 * is an {@link UnsupportedOperationException} that says what is missing, never a quiet change of
 * meaning.
 *
 * <p>{@link #markdownTable()} prints the decision table, which the documentation embeds; a test
 * keeps the text and the code in step.
 *
 * <p>The CPU kernels use their own {@code KernelSelector}, which chooses by what is installed; this
 * class chooses by what the graphics driver can do.
 *
 * <p>{@link #choose}, {@link #force} and {@link #supports} allocate nothing when they succeed (a
 * refusal builds its message), so they may be called every frame; the lookup of {@code indexOf}
 * is a scan of a short list.
 *
 * <p><b>Thread safety.</b> Immutable once built: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * StrategyChooser<String> how = StrategyChooser.<String>builder("copy")
 *         .option("persistent", "map once", Feature.PERSISTENT_MAPPING)
 *         .option("subdata", "upload each frame")
 *         .build();
 * String best = how.choose(GraphicsCapabilities.openGl(4, 5, List.of()));   // "persistent"
 * String low = how.choose(GraphicsCapabilities.baseline());                  // "subdata"
 * }</pre>
 *
 * @param <S> the type of the strategies, usually an enum
 */
@Experimental("the selection API may change")
public final class StrategyChooser<S> {

    private record Option<S>(S strategy, EnumSet<Feature> needs, Feature[] needsArray, GlslVersion minGlsl, String note) {
    }

    private final String what;
    private final List<Option<S>> options;

    private StrategyChooser(String what, List<Option<S>> options) {
        this.what = what;
        this.options = options;
    }

    /**
     * Starts a chooser.
     *
     * @param what what is being chosen, for the messages and the table; must not be {@code null}
     * @param <S> the type of the strategies
     * @return a builder
     */
    public static <S> Builder<S> builder(String what) {
        return new Builder<>(what);
    }

    /**
     * Collects the options, the best first.
     *
     * @param <S> the type of the strategies
     */
    public static final class Builder<S> {
        private final String what;
        private final List<Option<S>> options = new ArrayList<>();

        private Builder(String what) {
            this.what = what;
        }

        /**
         * Adds a strategy that needs some features.
         *
         * @param strategy the strategy; must not be {@code null} and not be in the list yet
         * @param note a few words for the table: what the strategy does
         * @param needs the features it needs; none means it works everywhere
         * @return this builder
         * @throws IllegalArgumentException if the strategy is already in the list
         */
        public Builder<S> option(S strategy, String note, Feature... needs) {
            return optionGlsl(strategy, null, note, needs);
        }

        /**
         * Adds a strategy that needs some features and a GLSL version.
         *
         * @param strategy the strategy; must not be {@code null} and not be in the list yet
         * @param minGlsl the oldest GLSL version its shaders are written for, or {@code null}
         * @param note a few words for the table: what the strategy does
         * @param needs the features it needs; none means it works everywhere
         * @return this builder
         * @throws IllegalArgumentException if the strategy is already in the list
         */
        public Builder<S> optionGlsl(S strategy, GlslVersion minGlsl, String note, Feature... needs) {
            for (Option<S> o : options) {
                if (o.strategy().equals(strategy)) {
                    throw new IllegalArgumentException("the strategy " + strategy + " is already an option of " + what);
                }
            }
            EnumSet<Feature> set = EnumSet.noneOf(Feature.class);
            set.addAll(Arrays.asList(needs));
            options.add(new Option<>(strategy, set, set.toArray(new Feature[0]), minGlsl, note));
            return this;
        }

        /**
         * Finishes the chooser.
         *
         * @return the chooser
         * @throws IllegalStateException if there is no option
         */
        public StrategyChooser<S> build() {
            if (options.isEmpty()) {
                throw new IllegalStateException("a chooser needs at least one option");
            }
            return new StrategyChooser<>(what, List.copyOf(options));
        }
    }

    // the check of the hot path: no allocation, the messages are built only when something is refused
    private static <S> boolean satisfied(Option<S> o, GraphicsCapabilities caps) {
        Feature[] needs = o.needsArray();
        for (int i = 0; i < needs.length; i++) {
            if (!caps.has(needs[i])) {
                return false;
            }
        }
        return o.minGlsl() == null || caps.glsl().atLeast(o.minGlsl());
    }

    private static <S> List<String> lacks(Option<S> o, GraphicsCapabilities caps) {
        List<String> out = new ArrayList<>();
        for (Feature f : caps.missing(o.needs())) {
            out.add(f.name());
        }
        if (o.minGlsl() != null && !caps.glsl().atLeast(o.minGlsl())) {
            out.add(o.minGlsl() + " (the context has " + caps.glsl() + ")");
        }
        return out;
    }

    private UnsupportedOperationException none(GraphicsCapabilities caps, int from) {
        StringBuilder sb = new StringBuilder("no way of ").append(what).append(" is possible with ").append(caps).append(':');
        for (int i = from; i < options.size(); i++) {
            Option<S> o = options.get(i);
            sb.append("\n  ").append(o.strategy()).append(" lacks ").append(String.join(", ", lacks(o, caps)));
        }
        return new UnsupportedOperationException(sb.toString());
    }

    /**
     * Returns the best strategy that the capabilities allow.
     *
     * @param caps the capabilities; must not be {@code null}
     * @return the first strategy of the list whose needs are met
     * @throws UnsupportedOperationException if none is possible; the message lists what each lacks
     */
    public S choose(GraphicsCapabilities caps) {
        return chooseFrom(caps, 0);
    }

    /**
     * Returns the best strategy that the capabilities allow among the given one and the ones
     * below it in the list: a caller asks not to use the better ones.
     *
     * @param caps the capabilities; must not be {@code null}
     * @param ceiling the best strategy that may be chosen; must be one of the options
     * @return the first strategy from {@code ceiling} on whose needs are met
     * @throws IllegalArgumentException if {@code ceiling} is not an option
     * @throws UnsupportedOperationException if none is possible; the message lists what each lacks
     */
    public S chooseAtMost(GraphicsCapabilities caps, S ceiling) {
        return chooseFrom(caps, indexOf(ceiling));
    }

    private S chooseFrom(GraphicsCapabilities caps, int from) {
        for (int i = from; i < options.size(); i++) {
            if (satisfied(options.get(i), caps)) {
                return options.get(i).strategy();
            }
        }
        throw none(caps, from);
    }

    /**
     * Checks that a named strategy is possible and returns it: the way to ask for a lower strategy
     * on purpose.
     *
     * @param strategy the strategy; must be one of the options
     * @param caps the capabilities; must not be {@code null}
     * @return {@code strategy}
     * @throws IllegalArgumentException if {@code strategy} is not an option
     * @throws UnsupportedOperationException if the capabilities do not allow it; the message says
     *     what is missing
     */
    public S force(S strategy, GraphicsCapabilities caps) {
        Option<S> o = options.get(indexOf(strategy));
        if (!satisfied(o, caps)) {
            throw new UnsupportedOperationException(strategy + " (" + what + ") needs " + String.join(", ", lacks(o, caps)) + ", which " + caps + " does not have");
        }
        return strategy;
    }

    private int indexOf(S strategy) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).strategy().equals(strategy)) {
                return i;
            }
        }
        throw new IllegalArgumentException(strategy + " is not an option of " + what);
    }

    /**
     * Tells whether a strategy is possible.
     *
     * @param strategy the strategy; must be one of the options
     * @param caps the capabilities; must not be {@code null}
     * @return {@code true} if its needs are met
     * @throws IllegalArgumentException if {@code strategy} is not an option
     */
    public boolean supports(S strategy, GraphicsCapabilities caps) {
        return satisfied(options.get(indexOf(strategy)), caps);
    }

    /**
     * Lists the strategies that the capabilities allow, the best first.
     *
     * @param caps the capabilities; must not be {@code null}
     * @return the possible strategies
     */
    public List<S> available(GraphicsCapabilities caps) {
        List<S> out = new ArrayList<>();
        for (Option<S> o : options) {
            if (satisfied(o, caps)) {
                out.add(o.strategy());
            }
        }
        return out;
    }

    /**
     * Lists all the strategies, the best first.
     *
     * @return the strategies
     */
    public List<S> strategies() {
        List<S> out = new ArrayList<>();
        for (Option<S> o : options) {
            out.add(o.strategy());
        }
        return out;
    }

    /**
     * Lists the features that a strategy needs.
     *
     * @param strategy the strategy; must be one of the options
     * @return the features, in the order of {@link Feature}
     * @throws IllegalArgumentException if {@code strategy} is not an option
     */
    public Set<Feature> needs(S strategy) {
        return EnumSet.copyOf(options.get(indexOf(strategy)).needs());
    }

    /**
     * Prints the decision as a table: the strategies from the best to the worst with what each
     * needs.
     *
     * @return a Markdown table; {@link #choose} takes the first row whose needs are met
     */
    public String markdownTable() {
        StringBuilder sb = new StringBuilder("| # | Strategy | Needs | What it does |\n|---|---|---|---|\n");
        int n = 1;
        for (Option<S> o : options) {
            List<String> needs = new ArrayList<>();
            for (Feature f : o.needs()) {
                needs.add("`" + f.name() + "`");
            }
            if (o.minGlsl() != null) {
                needs.add(o.minGlsl().toString());
            }
            sb.append("| ").append(n++).append(" | `").append(o.strategy()).append("` | ").append(needs.isEmpty() ? "nothing" : String.join(", ", needs)).append(" | ")
                    .append(o.note()).append(" |\n");
        }
        return sb.toString();
    }
}
