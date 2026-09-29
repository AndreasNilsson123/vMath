package vmath.codegen;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Rewrites one template source. The source is parsed with the JDK compiler's tree API, so every edit lands on a
 * real token (identifier, primitive type, literal, cast) and never on comments or strings by accident. Edits are
 * position-based replacements on the original text, so formatting and comments survive untouched.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@link Mode#FLOAT}: drop {@code @DoubleOnly} members, strip codegen annotations.</li>
 *   <li>{@link Mode#DOUBLE}: additionally retype float to double, drop {@code @FloatOnly} members, apply
 *       {@code @Eps} and rename the template family. {@code @DoubleOnly} members are copied verbatim.</li>
 *   <li>{@link Mode#PLAIN}: only strip annotations (and apply {@code @ValueType}); for hand-written sources.</li>
 * </ul>
 */
public final class Transformer {

    public enum Mode { FLOAT, DOUBLE, PLAIN }

    /** {@code header} is inserted below the package line when non-null. */
    public record Options(Mode mode, boolean valhalla, Renames renames, String header) {
    }

    /** A {@code @GenerateDouble} type found in a template. */
    public record Family(String name, String twin) {
    }

    /** Reported for template mistakes; the message includes file and line. */
    public static final class TemplateException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TemplateException(String message) {
            super(message);
        }
    }

    private static final String ANNOTATION_PACKAGE = "vmath.annotations";
    private static final Set<String> OUR_ANNOTATIONS =
            Set.of("GenerateDouble", "FloatOnly", "DoubleOnly", "Eps", "ValueType", "GpuStruct", "GpuArray", "GpuUint");

    private Transformer() {
    }

    // ------------------------------------------------------------------ public API

    /** Finds the types a template asks to have doubled. */
    public static List<Family> families(String source, String fileName) {
        Parsed p = parse(source, fileName);
        List<Family> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree c, Void v) {
                AnnotationTree a = find(c.getModifiers(), "GenerateDouble");
                if (a != null) {
                    String explicit = stringArg(a, "twin");
                    out.add(new Family(c.getSimpleName().toString(), twinName(c.getSimpleName().toString(), explicit)));
                }
                return super.visitClass(c, v);
            }
        }.scan(p.unit, null);
        return out;
    }

    /** {@code Vec3f -> Vec3d}, {@code Vec3fTest -> Vec3dTest}; an explicit twin wins. */
    public static String twinName(String name, String explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        if (name.endsWith("fTest")) {
            return name.substring(0, name.length() - 5) + "dTest";
        }
        if (name.endsWith("f")) {
            return name.substring(0, name.length() - 1) + "d";
        }
        throw new TemplateException("Cannot derive a double twin for '" + name
                + "': name must end in 'f' or 'fTest', or use @GenerateDouble(twin = \"...\")");
    }

    public static String transform(String source, String fileName, Options opt) {
        Parsed p = parse(source, fileName);
        Run run = new Run(p, source, fileName, opt);
        return run.execute();
    }

    // ------------------------------------------------------------------ parsing

    record Parsed(JavacTask task, CompilationUnitTree unit, SourcePositions positions) {
    }

    static Parsed parse(String source, String fileName) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler: run the generator on a JDK, not a JRE");
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + fileName), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, null, diags, List.of("-proc:none"), null, List.of(file));
        CompilationUnitTree unit;
        try {
            unit = task.parse().iterator().next();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                throw new TemplateException(fileName + ":" + d.getLineNumber() + ": " + d.getMessage(null));
            }
        }
        return new Parsed(task, unit, Trees.instance(task).getSourcePositions());
    }

    // ------------------------------------------------------------------ annotation helpers

    static AnnotationTree find(ModifiersTree mods, String simpleName) {
        for (AnnotationTree a : mods.getAnnotations()) {
            if (isOurs(a) && annotationName(a).equals(simpleName)) {
                return a;
            }
        }
        return null;
    }

    static String annotationName(AnnotationTree a) {
        String s = a.getAnnotationType().toString();
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }

    private static boolean isOurs(AnnotationTree a) {
        String full = a.getAnnotationType().toString();
        if (full.contains(".")) {
            return full.startsWith(ANNOTATION_PACKAGE + ".") && OUR_ANNOTATIONS.contains(annotationName(a));
        }
        return OUR_ANNOTATIONS.contains(full);
    }

    private static String stringArg(AnnotationTree a, String attr) {
        for (ExpressionTree e : a.getArguments()) {
            if (e instanceof AssignmentTree as && as.getVariable().toString().equals(attr)
                    && as.getExpression() instanceof LiteralTree lt && lt.getValue() instanceof String s) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ edits

    private static final class Edit {
        final int start;
        final int end;
        final String text;
        /** Comment/string rewrites are dropped inside verbatim regions. */
        final boolean cosmetic;
        /** Removal of a whole member or line: swallows every edit inside it. */
        final boolean removal;

        Edit(int start, int end, String text, boolean cosmetic, boolean removal) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.cosmetic = cosmetic;
            this.removal = removal;
        }

        boolean zeroWidth() {
            return start == end;
        }

        boolean sameAs(Edit o) {
            return start == o.start && end == o.end && text.equals(o.text);
        }
    }

    private static final class Run {
        final Parsed parsed;
        final String src;
        final String file;
        final Options opt;
        final SourcePositions sp;
        final List<Edit> edits = new ArrayList<>();
        final List<int[]> verbatim = new ArrayList<>();
        /** Members dropped from this output; calls to them from kept code are errors. */
        final Set<String> droppedNames = new HashSet<>();
        final Set<String> keptOnlyNames = new HashSet<>();
        final List<Edit> importRemovals = new ArrayList<>();

        Run(Parsed parsed, String src, String file, Options opt) {
            this.parsed = parsed;
            this.src = src;
            this.file = file;
            this.opt = opt;
            this.sp = parsed.positions();
        }

        boolean dbl() {
            return opt.mode() == Mode.DOUBLE;
        }

        String execute() {
            collectPrecisionOnlyNames();
            new Scanner().scan(parsed.unit(), null);
            tidyImportGap();
            if (opt.header() != null && parsed.unit().getPackage() != null) {
                int end = (int) sp.getEndPosition(parsed.unit(), parsed.unit().getPackage());
                int nl = src.indexOf('\n', end);
                edits.add(new Edit(nl + 1, nl + 1, "\n" + opt.header() + "\n", false, false));
            }
            if (dbl()) {
                addTextEdits();
            }
            return apply();
        }

        /** When removing the annotation imports leaves a blank line on both sides, drop one of them. */
        void tidyImportGap() {
            if (importRemovals.isEmpty()) {
                return;
            }
            Edit first = importRemovals.stream().min(Comparator.comparingInt(e -> e.start)).get();
            Edit last = importRemovals.stream().max(Comparator.comparingInt(e -> e.end)).get();
            int before = first.start;
            boolean blankBefore = before >= 2 && src.charAt(before - 1) == '\n'
                    && src.substring(src.lastIndexOf('\n', before - 2) + 1, before - 1).isBlank();
            int after = last.end;
            int eol = src.indexOf('\n', after);
            boolean blankAfter = eol >= 0 && src.substring(after, eol).isBlank();
            if (blankBefore && blankAfter) {
                edits.add(new Edit(after, eol + 1, "", false, true));
            }
        }

        // -------- error reporting

        TemplateException error(Tree at, String message) {
            long pos = sp.getStartPosition(parsed.unit(), at);
            int line = 1;
            for (int i = 0; i < pos && i < src.length(); i++) {
                if (src.charAt(i) == '\n') {
                    line++;
                }
            }
            return new TemplateException(file + ":" + line + ": " + message);
        }

        void collectPrecisionOnlyNames() {
            if (opt.mode() == Mode.PLAIN) {
                return;
            }
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    note(m.getName().toString(), m.getModifiers());
                    return super.visitMethod(m, v);
                }

                @Override
                public Void visitVariable(VariableTree m, Void v) {
                    note(m.getName().toString(), m.getModifiers());
                    return super.visitVariable(m, v);
                }

                void note(String name, ModifiersTree mods) {
                    boolean dropped = dbl() ? find(mods, "FloatOnly") != null : find(mods, "DoubleOnly") != null;
                    if (dropped) {
                        droppedNames.add(name);
                    }
                }
            }.scan(parsed.unit(), null);
            // A name declared both dropped and kept (overloads) is ambiguous, so it isn't checked.
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    boolean dropped = dbl() ? find(m.getModifiers(), "FloatOnly") != null
                            : find(m.getModifiers(), "DoubleOnly") != null;
                    if (!dropped) {
                        keptOnlyNames.add(m.getName().toString());
                    }
                    return super.visitMethod(m, v);
                }
            }.scan(parsed.unit(), null);
            droppedNames.removeAll(keptOnlyNames);
        }

        // -------- the tree walk

        final class Scanner extends TreeScanner<Void, Void> {

            @Override
            public Void visitImport(ImportTree imp, Void v) {
                if (imp.getQualifiedIdentifier().toString().startsWith(ANNOTATION_PACKAGE + ".")) {
                    removeLines(imp);
                    importRemovals.add(edits.get(edits.size() - 1));
                    return null;
                }
                return super.visitImport(imp, v);
            }

            @Override
            public Void visitAnnotation(AnnotationTree a, Void v) {
                return isOurs(a) ? null : super.visitAnnotation(a, v);
            }

            @Override
            public Void visitClass(ClassTree c, Void v) {
                if (!gate(c, c.getModifiers())) {
                    return null;
                }
                if (find(c.getModifiers(), "ValueType") != null && opt.valhalla()) {
                    insertValue(c);
                }
                if (dbl()) {
                    renameDeclaration(c, c.getSimpleName().toString(), "\\b(?:class|record|interface|enum)\\s+(%s)\\b");
                }
                return super.visitClass(c, v);
            }

            @Override
            public Void visitMethod(MethodTree m, Void v) {
                if (!gate(m, m.getModifiers())) {
                    return null;
                }
                if (dbl() && !m.getName().contentEquals("<init>") && m.getReturnType() != null) {
                    renameDeclaredName((int) sp.getEndPosition(parsed.unit(), m.getReturnType()), m.getName().toString());
                }
                if (dbl() && m.getName().contentEquals("<init>") && m.getBody() != null) {
                    long s = sp.getStartPosition(parsed.unit(), m);
                    long e = sp.getStartPosition(parsed.unit(), m.getBody());
                    if (s >= 0 && e > s) {
                        // the name is followed by '(' for an ordinary constructor and by the body's '{' for a compact one
                        Matcher paren = Pattern.compile("([A-Za-z_]\\w*)\\s*[({]").matcher(src.substring((int) s, (int) e + 1));
                        while (paren.find()) {
                            renameRange((int) s + paren.start(1), (int) s + paren.end(1), paren.group(1));
                        }
                    }
                }
                return super.visitMethod(m, v);
            }

            @Override
            public Void visitVariable(VariableTree var, Void v) {
                if (!gate(var, var.getModifiers())) {
                    return null;
                }
                if (dbl() && var.getType() != null) {
                    renameDeclaredName((int) sp.getEndPosition(parsed.unit(), var.getType()), var.getName().toString());
                }
                AnnotationTree eps = find(var.getModifiers(), "Eps");
                if (eps != null && dbl()) {
                    if (var.getInitializer() == null) {
                        throw error(var, "@Eps requires an initializer on '" + var.getName() + "'");
                    }
                    ExpressionTree d = epsArg(var, eps);
                    int s = (int) sp.getStartPosition(parsed.unit(), var.getInitializer());
                    int e = (int) sp.getEndPosition(parsed.unit(), var.getInitializer());
                    edits.add(new Edit(s, e, src.substring(
                            (int) sp.getStartPosition(parsed.unit(), d), (int) sp.getEndPosition(parsed.unit(), d)),
                            false, false));
                    scan(var.getModifiers(), null);
                    scan(var.getType(), null);
                    return null;
                }
                if (eps != null) {
                    epsArg(var, eps); // validate even when not used
                }
                return super.visitVariable(var, v);
            }

            @Override
            public Void visitIdentifier(IdentifierTree id, Void v) {
                String name = id.getName().toString();
                checkDroppedUse(id, name);
                if (dbl()) {
                    String to = opt.renames().identifier(name);
                    if (!to.equals(name)) {
                        renameRange((int) sp.getStartPosition(parsed.unit(), id),
                                (int) sp.getEndPosition(parsed.unit(), id), name);
                    }
                }
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree ms, Void v) {
                scan(ms.getExpression(), null);
                String name = ms.getIdentifier().toString();
                checkDroppedUse(ms, name);
                if (dbl()) {
                    int end = (int) sp.getEndPosition(parsed.unit(), ms);
                    int start = end - name.length();
                    if (start >= 0 && src.startsWith(name, start)) {
                        String to = opt.renames().member(name);
                        if (!to.equals(name)) {
                            edits.add(new Edit(start, end, to, false, false));
                        }
                    }
                }
                return null;
            }

            @Override
            public Void visitPrimitiveType(PrimitiveTypeTree t, Void v) {
                if (dbl() && t.getPrimitiveTypeKind() == javax.lang.model.type.TypeKind.FLOAT) {
                    int s = (int) sp.getStartPosition(parsed.unit(), t);
                    edits.add(new Edit(s, s + "float".length(), "double", false, false));
                }
                return null;
            }

            @Override
            public Void visitTypeCast(TypeCastTree cast, Void v) {
                if (dbl() && cast.getType() instanceof PrimitiveTypeTree p
                        && p.getPrimitiveTypeKind() == javax.lang.model.type.TypeKind.FLOAT) {
                    // Narrowing casts of double expressions are exactly what disappears in the double twin.
                    int s = (int) sp.getStartPosition(parsed.unit(), cast);
                    int e = (int) sp.getStartPosition(parsed.unit(), cast.getExpression());
                    edits.add(new Edit(s, e, "", false, false));
                    scan(cast.getExpression(), null);
                    return null;
                }
                return super.visitTypeCast(cast, v);
            }

            @Override
            public Void visitLiteral(LiteralTree lit, Void v) {
                if (dbl() && lit.getKind() == Tree.Kind.FLOAT_LITERAL) {
                    int s = (int) sp.getStartPosition(parsed.unit(), lit);
                    int e = (int) sp.getEndPosition(parsed.unit(), lit);
                    edits.add(new Edit(s, e, floatLiteralToDouble(src.substring(s, e)), false, false));
                }
                return null;
            }
        }

        // -------- helpers used by the scanner

        void checkDroppedUse(Tree at, String name) {
            if (droppedNames.contains(name)) {
                throw error(at, "'" + name + "' is " + (dbl() ? "@FloatOnly" : "@DoubleOnly")
                        + " but is used by code that is also emitted for the "
                        + (dbl() ? "double" : "float") + " twin");
            }
        }

        ExpressionTree epsArg(VariableTree var, AnnotationTree eps) {
            for (ExpressionTree e : eps.getArguments()) {
                if (e instanceof AssignmentTree as && as.getVariable().toString().equals("d")) {
                    return as.getExpression();
                }
            }
            throw error(var, "@Eps on '" + var.getName() + "' needs a 'd' value");
        }

        void renameRange(int start, int end, String oldName) {
            String to = opt.renames().identifier(oldName);
            if (!to.equals(oldName)) {
                edits.add(new Edit(start, end, to, false, false));
            }
        }

        /** Renames the name of a method or variable declaration; it is not an identifier node in the tree. */
        void renameDeclaredName(int searchFrom, String name) {
            String to = opt.renames().identifier(name);
            if (to.equals(name) || searchFrom < 0) {
                return;
            }
            Matcher m = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(src);
            if (m.find(searchFrom)) {
                edits.add(new Edit(m.start(), m.end(), to, false, false));
            }
        }

        void renameDeclaration(ClassTree c, String name, String patternFmt) {
            String to = opt.renames().identifier(name);
            if (to.equals(name)) {
                return;
            }
            int s = (int) sp.getStartPosition(parsed.unit(), c);
            Matcher m = Pattern.compile(patternFmt.formatted(Pattern.quote(name))).matcher(src);
            if (s >= 0 && m.find(s)) {
                edits.add(new Edit(m.start(1), m.end(1), to, false, false));
            }
        }

        void insertValue(ClassTree c) {
            int s = (int) sp.getStartPosition(parsed.unit(), c);
            Matcher m = Pattern.compile("\\b(record|class)\\s+" + Pattern.quote(c.getSimpleName().toString())).matcher(src);
            if (!m.find(Math.max(s, 0))) {
                throw error(c, "@ValueType supports records and classes only");
            }
            edits.add(new Edit(m.start(1), m.start(1), "value ", false, false));
        }

        /**
         * Applies {@code @FloatOnly}/{@code @DoubleOnly} and strips codegen annotations.
         *
         * @return whether the member's contents should be scanned (false: dropped or verbatim)
         */
        boolean gate(Tree member, ModifiersTree mods) {
            AnnotationTree floatOnly = find(mods, "FloatOnly");
            AnnotationTree doubleOnly = find(mods, "DoubleOnly");
            if (floatOnly != null && doubleOnly != null) {
                throw error(member, "a member cannot be both @FloatOnly and @DoubleOnly");
            }
            for (AnnotationTree a : mods.getAnnotations()) {
                if (isOurs(a)) {
                    removeLines(a);
                }
            }
            if (opt.mode() == Mode.PLAIN) {
                return true;
            }
            boolean drop = dbl() ? floatOnly != null : doubleOnly != null;
            if (drop) {
                removeMember(member);
                return false;
            }
            if (dbl() && doubleOnly != null) {
                int start = memberStart(member);
                int end = (int) sp.getEndPosition(parsed.unit(), member);
                verbatim.add(new int[] {start, end});
                return false;
            }
            return true;
        }

        // -------- source-range helpers

        /** Start of a member including its leading comments. */
        int memberStart(Tree member) {
            int start = (int) sp.getStartPosition(parsed.unit(), member);
            boolean moved = true;
            while (moved) {
                moved = false;
                int q = start;
                while (q > 0 && Character.isWhitespace(src.charAt(q - 1))) {
                    q--;
                }
                if (q >= 2 && src.startsWith("*/", q - 2)) {
                    int open = src.lastIndexOf("/*", q - 2);
                    if (open >= 0) {
                        start = open;
                        moved = true;
                    }
                } else if (q > 0) {
                    int ls = src.lastIndexOf('\n', q - 1) + 1;
                    if (src.substring(ls, q).stripLeading().startsWith("//")) {
                        start = ls + (src.substring(ls, q).length() - src.substring(ls, q).stripLeading().length());
                        moved = true;
                    }
                }
            }
            return start;
        }

        void removeMember(Tree member) {
            int start = memberStart(member);
            int end = (int) sp.getEndPosition(parsed.unit(), member);
            int ls = src.lastIndexOf('\n', start - 1) + 1;
            if (src.substring(ls, start).isBlank()) {
                start = ls;
                // Also swallow the blank separator line above, so no double blank lines remain.
                int prevEnd = start - 1;
                if (prevEnd > 0) {
                    int prevStart = src.lastIndexOf('\n', prevEnd - 1) + 1;
                    if (src.substring(prevStart, prevEnd).isBlank()) {
                        start = prevStart;
                    }
                }
            }
            end = consumeTrailingNewline(end);
            edits.add(new Edit(start, end, "", false, true));
        }

        /** Removes {@code node} and, when it is alone on its lines, the lines themselves. */
        void removeLines(Tree node) {
            int start = (int) sp.getStartPosition(parsed.unit(), node);
            int end = (int) sp.getEndPosition(parsed.unit(), node);
            int ls = src.lastIndexOf('\n', start - 1) + 1;
            int e = end;
            while (e < src.length() && (src.charAt(e) == ' ' || src.charAt(e) == '\t')) {
                e++;
            }
            boolean aloneBefore = src.substring(ls, start).isBlank();
            boolean aloneAfter = e >= src.length() || src.charAt(e) == '\n' || src.charAt(e) == '\r';
            if (aloneBefore && aloneAfter) {
                edits.add(new Edit(ls, consumeTrailingNewline(e), "", false, true));
            } else {
                edits.add(new Edit(start, e, "", false, true));
            }
        }

        int consumeTrailingNewline(int end) {
            int e = end;
            while (e < src.length() && (src.charAt(e) == ' ' || src.charAt(e) == '\t' || src.charAt(e) == '\r')) {
                e++;
            }
            return e < src.length() && src.charAt(e) == '\n' ? e + 1 : end;
        }

        // -------- comments and strings

        void addTextEdits() {
            for (int[] r : Lexical.textRanges(src)) {
                String original = src.substring(r[0], r[1]);
                String converted = opt.renames().text(original);
                if (!converted.equals(original)) {
                    edits.add(new Edit(r[0], r[1], converted, true, false));
                }
            }
        }

        // -------- applying

        String apply() {
            List<Edit> sorted = new ArrayList<>(edits);
            sorted.sort(Comparator.<Edit>comparingInt(e -> e.start).thenComparingInt(e -> e.end));
            List<Edit> removals = sorted.stream().filter(e -> e.removal).toList();
            List<Edit> kept = new ArrayList<>();
            for (Edit e : sorted) {
                if (!e.removal && (insideRemoval(e, removals) || (e.cosmetic && insideVerbatim(e)))) {
                    continue;
                }
                if (e.removal && removals.stream().anyMatch(o -> o != e && contains(o, e) && !e.sameAs(o))) {
                    continue;
                }
                if (!kept.isEmpty() && kept.get(kept.size() - 1).sameAs(e)) {
                    continue; // record components are visited twice
                }
                kept.add(e);
            }
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            for (Edit e : kept) {
                if (e.start < pos) {
                    throw new TemplateException(file + ": overlapping generator edits at offset " + e.start
                            + " (" + e.text + "); this is a generator bug");
                }
                sb.append(src, pos, e.start).append(e.text);
                pos = e.end;
            }
            sb.append(src, pos, src.length());
            return sb.toString();
        }

        boolean contains(Edit outer, Edit inner) {
            return outer.start <= inner.start && inner.end <= outer.end
                    && !(inner.zeroWidth() && inner.start == outer.end);
        }

        boolean insideRemoval(Edit e, List<Edit> removals) {
            for (Edit r : removals) {
                if (contains(r, e)) {
                    return true;
                }
            }
            return false;
        }

        boolean insideVerbatim(Edit e) {
            for (int[] r : verbatim) {
                if (r[0] <= e.start && e.end <= r[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ literals

    /** {@code 1e-4f -> 1e-4}, {@code 1f -> 1.0}, {@code 0.5F -> 0.5}. */
    static String floatLiteralToDouble(String lit) {
        String s = lit;
        char last = s.charAt(s.length() - 1);
        if (last == 'f' || last == 'F') {
            s = s.substring(0, s.length() - 1);
        }
        boolean hex = s.startsWith("0x") || s.startsWith("0X");
        boolean hasPoint = s.indexOf('.') >= 0;
        boolean hasExp = hex ? s.indexOf('p') >= 0 || s.indexOf('P') >= 0 : s.indexOf('e') >= 0 || s.indexOf('E') >= 0;
        return hasPoint || hasExp ? s : s + ".0";
    }
}
