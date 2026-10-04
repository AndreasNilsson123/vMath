package vmath.samples.framework;

import java.util.Locale;

/**
 * Formats the {@link Stats} of a run as the text that a scripted run prints and as the markdown
 * table that {@code --report} appends to a file.
 *
 * <p>Internal: part of the samples, not of the library. Series without a measured sample are left
 * out, so a demo that does not time the GPU shows no GPU line.
 *
 * <p><b>Thread safety.</b> Stateless: the methods may be called from any number of threads.
 */
public final class Report {

    private Report() {
    }

    /**
     * Formats the averages as aligned text lines.
     *
     * @param stats the statistics of the run; must not be {@code null}
     * @return the lines, one per series with samples and one per note, each ending in a line
     *     break
     */
    public static String text(Stats stats) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < stats.size(); i++) {
            if (stats.samples(i) == 0) {
                continue;
            }
            out.append(String.format(Locale.ROOT, "%-22s %14s %-3s  (%s)%n", stats.name(i), value(stats, i), stats.unit(i), stats.description(i)));
        }
        for (String note : stats.notes()) {
            out.append(note).append(System.lineSeparator());
        }
        return out.toString();
    }

    /**
     * Formats the averages as a markdown table followed by the notes.
     *
     * @param heading the heading of the section, for example the demo id and the settings; must not
     *     be {@code null}
     * @param stats the statistics of the run; must not be {@code null}
     * @return the markdown, ending in a line break
     */
    public static String markdown(String heading, Stats stats) {
        StringBuilder out = new StringBuilder();
        out.append("### ").append(heading).append(System.lineSeparator()).append(System.lineSeparator());
        out.append("| measurement | average | unit | what |").append(System.lineSeparator());
        out.append("|---|---:|---|---|").append(System.lineSeparator());
        for (int i = 0; i < stats.size(); i++) {
            if (stats.samples(i) == 0) {
                continue;
            }
            out.append("| ").append(stats.name(i)).append(" | ").append(value(stats, i).trim()).append(" | ").append(stats.unit(i)).append(" | ")
                    .append(stats.description(i)).append(" |").append(System.lineSeparator());
        }
        out.append(System.lineSeparator());
        for (String note : stats.notes()) {
            out.append("- ").append(note).append(System.lineSeparator());
        }
        out.append(System.lineSeparator());
        return out.toString();
    }

    private static String value(Stats stats, int series) {
        return String.format(Locale.ROOT, "%,." + stats.decimals(series) + "f", stats.average(series));
    }
}
