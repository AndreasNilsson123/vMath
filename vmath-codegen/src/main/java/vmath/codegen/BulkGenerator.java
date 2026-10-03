package vmath.codegen;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;

/**
 * Generates a {@code <Name>Bulk} class for every record that has methods marked {@code @Bulk}: the
 * batch loops of the marked methods over interleaved and over planar float arrays.
 *
 * <p>The body of a marked method is copied into the loop. The components of the operands become
 * local variables read from the arrays (named like the component for the receiver and
 * {@code parameter_component} for a parameter), and every {@code return} becomes the stores of the
 * element followed by {@code continue}. The generator reads the source only (by simple name), so
 * it needs no compiled classes; the float output is then taken through the same transformation as
 * the templates to get the double twin.
 *
 * <p>Internal: part of the build tool, not of the library's API. The language it accepts is
 * described on {@code vmath.annotations.Bulk}; everything outside it is reported as an error with
 * the line of the method.
 *
 * <p><b>Thread safety.</b> Not thread-safe: the registry of records is filled before the
 * generation, and an instance is used by one thread.
 */
final class BulkGenerator {

    /**
     * One generated file: the float source of the class, which still has to be transformed for the
     * double twin.
     *
     * @param className the simple name of the class, such as {@code Vec3fBulk}
     * @param source the float source, annotated {@code @GenerateDouble}
     */
    record Output(String className, String source) {
    }

    /**
     * A record whose components are all {@code float}.
     *
     * @param name the simple name
     * @param pkg the package, empty for none
     * @param components the component names, in order
     */
    private record Rec(String name, String pkg, List<String> components) {

        int stride() {
            return components.size();
        }
    }

    /**
     * One operand of a generated method: the receiver, a record parameter or a scalar parameter.
     *
     * @param name the name used in the generated method: {@code self} for the receiver
     * @param rec the record type, or {@code null} for a scalar
     * @param scalarType the type of a scalar, such as {@code float}; {@code null} for a record
     * @param uniform whether it is one value for the whole batch
     * @param isThis whether it is the receiver
     */
    private record Operand(String name, Rec rec, String scalarType, boolean uniform, boolean isThis) {

        /**
         * Gives the local variable that holds a component of this operand inside the loop.
         *
         * @param component the component name
         * @return the name of the local variable
         */
        String local(String component) {
            return isThis ? component : name + "_" + component;
        }
    }

    /**
     * What a marked method turns into.
     *
     * @param name the base name of the generated methods
     * @param method the name of the marked method
     * @param isStatic whether the marked method is static
     * @param operands the receiver (unless static) and the parameters, in order
     * @param ret the record the method returns, or {@code null} for a {@code float}
     */
    private record Spec(String name, String method, boolean isStatic, List<Operand> operands, Rec ret) {
    }

    /**
     * A text replacement in the source of a marked method.
     *
     * @param start the first character
     * @param end the end, exclusive
     * @param text the replacement
     */
    private record Edit(int start, int end, String text) {
    }

    private static final Set<String> QUALIFIERS = Set.of("Math", "StrictMath", "Float", "Double", "Integer", "Long", "Boolean");
    private static final Set<String> RESERVED = Set.of("out", "outOffset", "count", "self", "selfOffset");
    private static final int WIDTH = 100;

    private final Map<String, Rec> records = new HashMap<>();

    /**
     * Records the records of a file that have only {@code float} components, so that marked
     * methods can take them as parameters.
     *
     * @param source the source text; must not be {@code null}
     * @param fileName the name used in messages; must not be {@code null}
     * @throws Transformer.TemplateException if the source does not parse
     */
    void register(String source, String fileName) {
        if (!source.contains("record ")) {
            return;
        }
        Transformer.Parsed p = Transformer.parse(source, fileName);
        String pkg = p.unit().getPackageName() == null ? "" : p.unit().getPackageName().toString();
        for (Tree t : p.unit().getTypeDecls()) {
            if (t instanceof ClassTree c && c.getKind() == Tree.Kind.RECORD) {
                List<String> comps = floatComponents(c);
                if (comps != null) {
                    records.put(c.getSimpleName().toString(), new Rec(c.getSimpleName().toString(), pkg, comps));
                }
            }
        }
    }

    private static List<String> floatComponents(ClassTree record) {
        List<String> comps = new ArrayList<>();
        for (Tree m : record.getMembers()) {
            if (m instanceof VariableTree v && !v.getModifiers().getFlags().contains(Modifier.STATIC)) {
                if (!(v.getType() instanceof PrimitiveTypeTree pt) || pt.getPrimitiveTypeKind() != TypeKind.FLOAT) {
                    return null;
                }
                comps.add(v.getName().toString());
            }
        }
        return comps.isEmpty() ? null : comps;
    }

    /**
     * Generates the bulk classes of the records of a file.
     *
     * @param source the source text of the template; must not be {@code null}
     * @param fileName the name used in messages; must not be {@code null}
     * @return one output for each record with marked methods, none if there are none
     * @throws Transformer.TemplateException if a marked method uses something that cannot be
     *     turned into a loop
     */
    List<Output> generate(String source, String fileName) {
        List<Output> out = new ArrayList<>();
        if (!source.contains("Bulk")) {
            return out;
        }
        Transformer.Parsed p = Transformer.parse(source, fileName);
        String pkg = p.unit().getPackageName() == null ? "" : p.unit().getPackageName().toString();
        for (Tree t : p.unit().getTypeDecls()) {
            if (t instanceof ClassTree c && c.getKind() == Tree.Kind.RECORD) {
                List<MethodTree> marked = new ArrayList<>();
                for (Tree m : c.getMembers()) {
                    if (m instanceof MethodTree mt && Transformer.find(mt.getModifiers(), "Bulk") != null) {
                        marked.add(mt);
                    }
                }
                if (!marked.isEmpty()) {
                    out.add(generateOne(p, source, fileName, c, marked, pkg));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- one record

    private Output generateOne(Transformer.Parsed p, String source, String fileName, ClassTree record, List<MethodTree> marked,
            String pkg) {
        String name = record.getSimpleName().toString();
        Rec self = records.get(name);
        if (self == null) {
            throw error(p, fileName, record, name + ": @Bulk needs a record whose components are all float");
        }
        String className = name + "Bulk";
        Set<String> imports = new LinkedHashSet<>();
        imports.add("import vmath.annotations.GenerateDouble;");
        StringBuilder methods = new StringBuilder();
        Set<String> signatures = new HashSet<>();
        Spec first = null;
        for (MethodTree m : marked) {
            Spec spec = specOf(p, fileName, m, self);
            for (Operand op : spec.operands()) {
                addImport(imports, op.rec(), pkg);
            }
            addImport(imports, spec.ret(), pkg);
            String key = spec.name() + spec.operands().stream()
                    .map(o -> o.rec() != null ? o.rec().name() + (o.uniform() ? "!" : "") : o.scalarType()).toList();
            if (!signatures.add(key)) {
                throw error(p, fileName, m, name + "." + m.getName() + ": the generated method " + spec.name()
                        + " would have the same parameter types as another; give one of them a name with @Bulk(name = ...)");
            }
            String interleavedBody = loopBody(p, source, fileName, m, spec, false);
            String planarBody = loopBody(p, source, fileName, m, spec, true);
            methods.append(method(spec, name, false, interleavedBody));
            methods.append(method(spec, name, true, planarBody));
            if (first == null) {
                first = spec;
            }
        }
        StringBuilder src = new StringBuilder();
        if (!pkg.isEmpty()) {
            src.append("package ").append(pkg).append(";\n\n");
        }
        for (String imp : imports) {
            src.append(imp).append('\n');
        }
        src.append('\n').append(classDoc(name, className, first));
        src.append("@GenerateDouble\n");
        src.append("public final class ").append(className).append(" {\n\n");
        src.append("    private ").append(className).append("() {\n    }\n\n");
        src.append(methods.toString().stripTrailing()).append("\n}\n");
        try {
            Transformer.parse(src.toString(), className + ".java");
        } catch (Transformer.TemplateException e) {
            throw new Transformer.TemplateException(fileName + ": the generated " + className + " does not parse: " + e.getMessage() + "\n" + src);
        }
        return new Output(className, src.toString());
    }

    private static void addImport(Set<String> imports, Rec rec, String pkg) {
        if (rec != null && !rec.pkg().isEmpty() && !rec.pkg().equals(pkg)) {
            imports.add("import " + rec.pkg() + "." + rec.name() + ";");
        }
    }

    private Spec specOf(Transformer.Parsed p, String fileName, MethodTree m, Rec self) {
        AnnotationTree ann = Transformer.find(m.getModifiers(), "Bulk");
        String given = null;
        Set<String> uniform = new HashSet<>();
        for (ExpressionTree arg : ann.getArguments()) {
            if (arg instanceof AssignmentTree a) {
                String attr = a.getVariable().toString();
                if (attr.equals("name")) {
                    given = literal(a.getExpression());
                } else if (attr.equals("uniform")) {
                    if (a.getExpression() instanceof NewArrayTree na && na.getInitializers() != null) {
                        for (ExpressionTree e : na.getInitializers()) {
                            uniform.add(literal(e));
                        }
                    } else {
                        uniform.add(literal(a.getExpression()));
                    }
                } else {
                    throw error(p, fileName, m, "@Bulk has no attribute " + attr);
                }
            }
        }
        String where = self.name() + "." + m.getName();
        if (m.getBody() == null) {
            throw error(p, fileName, m, where + ": a @Bulk method needs a body");
        }
        List<Operand> operands = new ArrayList<>();
        boolean isStatic = m.getModifiers().getFlags().contains(Modifier.STATIC);
        if (!isStatic) {
            operands.add(new Operand("self", self, null, uniform.remove("this"), true));
        }
        for (VariableTree v : m.getParameters()) {
            String pn = v.getName().toString();
            if (RESERVED.contains(pn) || pn.contains("_") || pn.endsWith("Offset")) {
                throw error(p, fileName, m, where + ": the parameter name " + pn + " is reserved by the generated code");
            }
            String type = v.getType().toString();
            boolean isUniform = uniform.remove(pn);
            if (v.getType() instanceof PrimitiveTypeTree) {
                operands.add(new Operand(pn, null, type, true, false));
            } else if (records.containsKey(type)) {
                operands.add(new Operand(pn, records.get(type), null, isUniform, false));
            } else {
                throw error(p, fileName, m, where + ": the parameter " + pn + " has the type " + type
                        + ", which is neither a primitive nor a record with only float components of the templates");
            }
        }
        if (!uniform.isEmpty()) {
            throw error(p, fileName, m, where + ": uniform names an operand that does not exist: " + uniform);
        }
        Rec ret;
        String rt = String.valueOf(m.getReturnType());
        if (rt.equals("float")) {
            ret = null;
        } else if (records.containsKey(rt)) {
            ret = records.get(rt);
        } else {
            throw error(p, fileName, m, where + ": the return type " + rt + " is not float or a record with only float components");
        }
        for (Operand op : operands) {
            if (op.rec() == null && !isStatic && self.components().contains(op.name())) {
                throw error(p, fileName, m, where + ": the parameter " + op.name() + " has the name of a component");
            }
        }
        return new Spec(given != null && !given.isEmpty() ? given : m.getName().toString(), m.getName().toString(), isStatic, operands, ret);
    }

    private static String literal(ExpressionTree e) {
        if (e instanceof LiteralTree l && l.getValue() instanceof String s) {
            return s;
        }
        throw new Transformer.TemplateException("@Bulk: expected a string literal, got " + e);
    }

    // ---------------------------------------------------------------- the body

    /**
     * Rewrites the pieces of a marked method for the loop: components and accessors become locals,
     * and returns become stores.
     *
     * <p>Not thread-safe: it collects its edits while it scans.
     */
    private final class Rewriter extends TreeScanner<Void, Void> {
        private final Transformer.Parsed p;
        private final String source;
        private final String fileName;
        private final String where;
        private final Spec spec;
        private final boolean planar;
        private final Map<String, Operand> byName;
        private final Set<String> declared;
        private final Operand receiver;
        private final List<Edit> edits = new ArrayList<>();
        private ReturnTree last;

        Rewriter(Transformer.Parsed p, String source, String fileName, String where, Spec spec, boolean planar,
                Map<String, Operand> byName, Set<String> declared) {
            this.p = p;
            this.source = source;
            this.fileName = fileName;
            this.where = where;
            this.spec = spec;
            this.planar = planar;
            this.byName = byName;
            this.declared = declared;
            this.receiver = byName.get("this");
        }

        /**
         * Gives the text of a piece of the source with the edits found by this rewriter applied.
         *
         * @param start the first character of the piece
         * @param end the end of the piece, exclusive
         * @return the piece with the components replaced
         */
        String text(int start, int end) {
            StringBuilder sb = new StringBuilder(source.substring(start, end));
            List<Edit> sorted = new ArrayList<>(edits);
            sorted.sort(Comparator.comparingInt(Edit::start).reversed());
            for (Edit e : sorted) {
                sb.replace(e.start() - start, e.end() - start, e.text());
            }
            return sb.toString();
        }

        /**
         * Gives an expression with the components replaced, checking it by the same rules as the
         * body.
         *
         * @param e the expression
         * @return its text for the loop
         */
        String piece(ExpressionTree e) {
            Rewriter r = new Rewriter(p, source, fileName, where, spec, planar, byName, declared);
            r.scan(e, null);
            return r.text(start(e), end(e));
        }

        private int start(Tree t) {
            return (int) p.positions().getStartPosition(p.unit(), t);
        }

        private int end(Tree t) {
            return (int) p.positions().getEndPosition(p.unit(), t);
        }

        private Operand operandOf(ExpressionTree e) {
            return e instanceof IdentifierTree id ? byName.get(id.getName().toString()) : null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree id, Void unused) {
            String n = id.getName().toString();
            if (!declared.contains(n) && !QUALIFIERS.contains(n)) {
                throw error(p, fileName, id, where + ": unknown name " + n + " (only components, parameters, locals and members of Math,"
                        + " Float and Double can be used; qualify or inline anything else)");
            }
            return null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree ms, Void unused) {
            Operand op = operandOf(ms.getExpression());
            if (op != null) {
                if (op.rec() == null || !op.rec().components().contains(ms.getIdentifier().toString())) {
                    throw error(p, fileName, ms, where + ": " + ms + " is not a component");
                }
                edits.add(new Edit(start(ms), end(ms), op.local(ms.getIdentifier().toString())));
                return null;
            }
            if (ms.getExpression() instanceof IdentifierTree id && QUALIFIERS.contains(id.getName().toString())) {
                return null;
            }
            throw error(p, fileName, ms, where + ": " + ms + " is not supported");
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree mi, Void unused) {
            ExpressionTree sel = mi.getMethodSelect();
            if (mi.getArguments().isEmpty()) {
                if (sel instanceof IdentifierTree id && receiver != null && receiver.rec().components().contains(id.getName().toString())) {
                    edits.add(new Edit(start(mi), end(mi), id.getName().toString()));
                    return null;
                }
                if (sel instanceof MemberSelectTree ms) {
                    Operand op = operandOf(ms.getExpression());
                    if (op != null && op.rec() != null && op.rec().components().contains(ms.getIdentifier().toString())) {
                        edits.add(new Edit(start(mi), end(mi), op.local(ms.getIdentifier().toString())));
                        return null;
                    }
                }
            }
            if (sel instanceof MemberSelectTree ms && ms.getExpression() instanceof IdentifierTree id && QUALIFIERS.contains(id.getName().toString())) {
                for (ExpressionTree a : mi.getArguments()) {
                    scan(a, null);
                }
                return null;
            }
            throw error(p, fileName, mi, where + ": the call " + mi + " is not supported (only methods of Math, Float and Double, and the"
                    + " component accessors)");
        }

        @Override
        public Void visitReturn(ReturnTree r, Void unused) {
            edits.add(new Edit(start(r), end(r), returnText(r)));
            return null;
        }

        private String returnText(ReturnTree r) {
            ExpressionTree e = r.getExpression();
            if (e == null) {
                throw error(p, fileName, r, where + ": return needs a value");
            }
            while (e instanceof ParenthesizedTree par) {
                e = par.getExpression();
            }
            int lineStart = source.lastIndexOf('\n', start(r)) + 1;
            String indent = source.substring(lineStart, start(r)).replaceAll("[^ \\t]", " ");
            List<String> statements = new ArrayList<>();
            if (spec.ret() == null) {
                statements.add("out[outOffset + _i] = " + piece(e) + ";");
            } else {
                Rec ret = spec.ret();
                List<String> values = new ArrayList<>();
                if (e instanceof NewClassTree nc && nc.getIdentifier().toString().equals(ret.name())) {
                    if (nc.getArguments().size() != ret.stride()) {
                        throw error(p, fileName, r, where + ": new " + ret.name() + " needs " + ret.stride() + " components");
                    }
                    for (ExpressionTree a : nc.getArguments()) {
                        values.add(piece(a));
                    }
                } else if (e instanceof IdentifierTree id && byName.containsKey(id.getName().toString())
                        && byName.get(id.getName().toString()).rec() == ret) {
                    Operand op = byName.get(id.getName().toString());
                    for (String c : ret.components()) {
                        values.add(op.local(c));
                    }
                } else {
                    throw error(p, fileName, r, where + ": a return must be new " + ret.name() + "(...), this, or a parameter of that type");
                }
                for (int i = 0; i < values.size(); i++) {
                    statements.add("float _r" + i + " = " + values.get(i) + ";");
                }
                if (!planar) {
                    statements.add("int _o = outOffset + _i * " + ret.stride() + ";");
                }
                for (int i = 0; i < values.size(); i++) {
                    statements.add(planar ? "out" + capital(ret.components().get(i)) + "[outOffset + _i] = _r" + i + ";"
                            : "out[_o + " + i + "] = _r" + i + ";");
                }
            }
            if (r == last) {
                return String.join("\n" + indent, statements); // the last statement of the body: nothing follows it, no continue needed
            }
            StringBuilder sb = new StringBuilder("{");
            for (String s : statements) {
                sb.append('\n').append(indent).append("    ").append(s);
            }
            return sb.append('\n').append(indent).append("    continue;\n").append(indent).append("}").toString();
        }

        @Override
        public Void visitNewClass(NewClassTree nc, Void unused) {
            throw error(p, fileName, nc, where + ": new is only supported in a return statement");
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree t, Void unused) {
            throw error(p, fileName, t, where + ": lambdas are not supported");
        }

        @Override
        public Void visitForLoop(ForLoopTree t, Void unused) {
            throw error(p, fileName, t, where + ": loops are not supported");
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void unused) {
            throw error(p, fileName, t, where + ": loops are not supported");
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree t, Void unused) {
            throw error(p, fileName, t, where + ": loops are not supported");
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree t, Void unused) {
            throw error(p, fileName, t, where + ": loops are not supported");
        }

        @Override
        public Void visitSwitch(SwitchTree t, Void unused) {
            throw error(p, fileName, t, where + ": switch is not supported");
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree t, Void unused) {
            throw error(p, fileName, t, where + ": switch is not supported");
        }

        @Override
        public Void visitTry(TryTree t, Void unused) {
            throw error(p, fileName, t, where + ": try is not supported");
        }

        @Override
        public Void visitThrow(ThrowTree t, Void unused) {
            throw error(p, fileName, t, where + ": throw is not supported");
        }

        @Override
        public Void visitSynchronized(SynchronizedTree t, Void unused) {
            throw error(p, fileName, t, where + ": synchronized is not supported");
        }
    }

    private String loopBody(Transformer.Parsed p, String source, String fileName, MethodTree m, Spec spec, boolean planar) {
        BlockTree body = m.getBody();
        String where = spec.method();
        Map<String, Operand> byName = new LinkedHashMap<>();
        Set<String> declared = new HashSet<>();
        for (Operand op : spec.operands()) {
            byName.put(op.isThis() ? "this" : op.name(), op);
            if (op.rec() == null) {
                declared.add(op.name());
            } else if (op.isThis()) {
                declared.addAll(op.rec().components());
            }
        }
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree v, Void unused) {
                if (!(v.getType() instanceof PrimitiveTypeTree)) {
                    throw error(p, fileName, v, where + ": local variables must be primitive");
                }
                declared.add(v.getName().toString());
                return super.visitVariable(v, unused);
            }
        }.scan(body, null);
        // a record parameter's components are reached as parameter.component only
        Rewriter r = new Rewriter(p, source, fileName, where, spec, planar, byName, declared);
        int start = (int) p.positions().getStartPosition(p.unit(), body) + 1;
        int end = (int) p.positions().getEndPosition(p.unit(), body) - 1;
        List<? extends com.sun.source.tree.StatementTree> statements = body.getStatements();
        if (!statements.isEmpty() && statements.get(statements.size() - 1) instanceof ReturnTree lastReturn) {
            r.last = lastReturn;
        }
        r.scan(body, null);
        return reindent(r.text(start, end));
    }

    private static String reindent(String text) {
        String[] lines = text.strip().split("\n");
        int common = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                int n = 0;
                while (n < lines[i].length() && lines[i].charAt(n) == ' ') {
                    n++;
                }
                common = Math.min(common, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i > 0 && common != Integer.MAX_VALUE && l.length() >= common) {
                l = l.substring(common);
            }
            sb.append("            ").append(i == 0 ? l.stripLeading() : l.stripTrailing()).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- the methods

    private static String capital(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String javadocLink(String recName, Spec spec) {
        StringBuilder sb = new StringBuilder("{@link ").append(recName).append('#').append(spec.method()).append('(');
        boolean firstParam = true;
        for (Operand op : spec.operands()) {
            if (op.isThis()) {
                continue;
            }
            sb.append(firstParam ? "" : ", ").append(op.rec() != null ? op.rec().name() : op.scalarType());
            firstParam = false;
        }
        return sb.append(")}").toString();
    }

    private String method(Spec spec, String recName, boolean planar, String body) {
        String name = planar ? spec.name() + "Planar" : spec.name();
        List<String> params = new ArrayList<>();
        List<String> docs = new ArrayList<>();
        StringBuilder loads = new StringBuilder();
        StringBuilder uniformLoads = new StringBuilder();
        String link = javadocLink(recName, spec);
        for (Operand op : spec.operands()) {
            String many = op.isThis() ? "the receivers of " + link : "the values of the parameter {@code " + op.name() + "} of " + link;
            String one = op.isThis() ? "the receiver of " + link : "the parameter {@code " + op.name() + "} of " + link;
            if (op.rec() == null) {
                params.add(op.scalarType() + " " + op.name());
                docs.add(tag("param", op.name(), "the parameter {@code " + op.name() + "} of " + link + ", the same for every element"));
                continue;
            }
            Rec rec = op.rec();
            String n = op.name();
            if (planar && !op.uniform()) {
                for (String c : rec.components()) {
                    params.add("float[] " + n + capital(c));
                    docs.add(tag("param", n + capital(c), op.uniform()
                            ? "the {@code " + c + "} component of " + one + ", read once; must not be {@code null}"
                            : "the {@code " + c + "} components of " + many + ", one float per element; must not be {@code null}"));
                }
            } else {
                params.add("float[] " + n);
                docs.add(tag("param", n, op.uniform()
                        ? one + ", a single {@link " + rec.name() + "} of " + rec.stride() + " floats that is read once; must not be {@code null}"
                        : many + ", " + rec.stride() + " floats for each {@link " + rec.name() + "}; must not be {@code null}"));
            }
            params.add("int " + n + "Offset");
            docs.add(tag("param", n + "Offset", planar && !op.uniform()
                    ? "the index of the first element in each of the component arrays"
                    : "the index in {@code " + n + "} of the first float of " + (op.uniform() ? "the value" : "the first element")));
            for (int k = 0; k < rec.stride(); k++) {
                String c = rec.components().get(k);
                String local = op.local(c);
                String read;
                if (planar && !op.uniform()) {
                    read = n + capital(c) + "[" + n + "Offset + _i]";
                } else {
                    String idx = n + "Offset" + (op.uniform() ? "" : " + _i * " + rec.stride()) + (k == 0 ? "" : " + " + k);
                    read = n + "[" + idx + "]";
                }
                (op.uniform() ? uniformLoads : loads).append(op.uniform() ? "        float " : "            float ").append(local).append(" = ").append(read).append(";\n");
            }
        }
        String outDoc = spec.ret() == null ? "one float per element" : (planar ? "one float per element in each component array"
                : spec.ret().stride() + " floats for each {@link " + spec.ret().name() + "}");
        if (planar && spec.ret() != null) {
            for (String c : spec.ret().components()) {
                params.add("float[] out" + capital(c));
                docs.add(tag("param", "out" + capital(c), "receives the {@code " + c + "} components of the results, one float per element;"
                        + " may be the array of an operand; must not be {@code null}"));
            }
        } else {
            params.add("float[] out");
            docs.add(tag("param", "out", "receives the results, " + outDoc + "; may be the array of an operand, element for element; must not be"
                    + " {@code null}"));
        }
        params.add("int outOffset");
        docs.add(tag("param", "outOffset", planar && spec.ret() != null ? "the index of the first result in the component arrays"
                : "the index in {@code out} of the first float of the first result"));
        params.add("int count");
        docs.add(tag("param", "count", "the number of elements; zero or less does nothing"));

        StringBuilder sb = new StringBuilder();
        List<String> doc = new ArrayList<>();
        doc.add("Applies " + link + " to {@code count} elements of " + (planar ? "planar" : "interleaved") + " arrays.");
        doc.add("");
        doc.add(wrapPara("<p>" + (planar
                ? "Each operand has one array per component, and element {@code i} is at {@code offset + i} in each of them. "
                : "Element {@code i} of an operand starts at {@code offset + i * stride}, where the stride is the number of floats of its type "
                        + "(3 for a {@link Vec3f}, for example), which is the layout of the bulk containers. ")
                + "The loop body is the body of the method, so every result is bit-identical to the call for that element. An element is read "
                + "completely before its result is written, so the output may be written over an operand, element for element. An operand "
                + "that is a single value for the whole batch is read once, with its components one after another in one array in both layouts."));
        doc.add("");
        doc.addAll(docs);
        doc.add(tag("throws", "ArrayIndexOutOfBoundsException", "if an array is too short for {@code count} elements at its offset, or an"
                + " offset is negative"));
        doc.add(tag("throws", "NullPointerException", "if an array is {@code null} and {@code count} is positive"));
        sb.append(renderDoc("    ", doc));
        sb.append(signature(name, params));
        sb.append(uniformLoads);
        sb.append("        for (int _i = 0; _i < count; _i++) {\n");
        sb.append(loads);
        sb.append(body);
        sb.append("        }\n    }\n\n");
        return sb.toString();
    }

    private static String tag(String kind, String name, String text) {
        return "@" + kind + " " + name + " " + text;
    }

    /**
     * Writes the declaration of a generated method, breaking the parameter list after commas so
     * that it fits the line width.
     */
    private static String signature(String name, List<String> params) {
        StringBuilder out = new StringBuilder();
        StringBuilder cur = new StringBuilder("    public static void " + name + "(");
        boolean firstParam = true;
        for (String param : params) {
            String piece = (firstParam ? "" : ", ") + param;
            if (!firstParam && cur.length() + piece.length() + 1 > WIDTH) {
                out.append(cur.toString().stripTrailing()).append(",\n");
                cur = new StringBuilder("            ");
                piece = param;
            }
            cur.append(piece);
            firstParam = false;
        }
        return out.append(cur).append(") {\n").toString();
    }

    private String classDoc(String recName, String className, Spec first) {
        List<String> doc = new ArrayList<>();
        doc.add("Provides batch versions of the {@link " + recName + "} operations marked {@code @Bulk} in its template, as loops over arrays"
                + " with the arithmetic of the method itself.");
        doc.add("");
        doc.add(wrapPara("<p>Every operation comes in two layouts. The interleaved form, for example {@link #" + first.name() + "}, reads and "
                + "writes elements stored one after another in a single array, which is the layout of the bulk containers and of vertex buffers. "
                + "The planar form, for example {@link #" + first.name() + "Planar}, takes one array per component (structure of arrays), "
                + "which suits data that is already split and the vectorisation of the JIT."));
        doc.add("");
        doc.add(wrapPara("<p>The loop body is the body of the method, so every result is bit-identical to calling the method for each element. "
                + "An element is read completely before its result is written, so a result may be written over an operand, element for element. "
                + "The lengths of the arrays are not checked beyond the array accesses themselves."));
        doc.add("");
        doc.add(wrapPara("<p>Generated from the methods marked {@code @Bulk}; edit the template, not this class."));
        doc.add("");
        doc.add(wrapPara("<p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays "
                + "you pass in are not synchronised, so two threads must not write the same one."));
        doc.add("");
        doc.add("<p><b>Example:</b>");
        doc.add("");
        doc.add("<pre>{@code");
        StringBuilder call = new StringBuilder(className + "." + first.name() + "(");
        boolean firstArg = true;
        for (Operand op : first.operands()) {
            if (op.rec() == null) {
                doc.add(op.scalarType() + " " + op.name() + " = " + (op.scalarType().equals("float") ? "0.5f" : op.scalarType().equals("boolean") ? "true" : "1")
                        + ";");
                call.append(firstArg ? "" : ", ").append(op.name());
            } else {
                int n = op.uniform() ? op.rec().stride() : op.rec().stride() * 4;
                doc.add("float[] " + op.name() + " = new float[" + n + "];");
                call.append(firstArg ? "" : ", ").append(op.name()).append(", 0");
            }
            firstArg = false;
        }
        int outLen = (first.ret() == null ? 1 : first.ret().stride()) * 4;
        doc.add("float[] out = new float[" + outLen + "];");
        call.append(firstArg ? "" : ", ").append("out, 0, 4);");
        doc.add(call.toString());
        doc.add("}</pre>");
        return renderDoc("", doc);
    }

    // ---------------------------------------------------------------- documentation text

    private static String wrapPara(String text) {
        return text;
    }

    /**
     * Renders documentation lines as a comment, wrapping prose at the line width and giving the
     * tags a hanging indent.
     *
     * @param indent the indentation of the comment
     * @param lines the lines; an empty one is a blank line, a line starting with {@code @} is a tag
     * @return the comment text, ending with a newline
     */
    private static String renderDoc(String indent, List<String> lines) {
        StringBuilder sb = new StringBuilder(indent).append("/**\n");
        boolean inPre = false;
        for (String line : lines) {
            if (line.startsWith("<pre>")) {
                inPre = true;
            }
            if (line.isEmpty()) {
                sb.append(indent).append(" *\n");
            } else if (inPre) {
                sb.append(indent).append(" * ").append(line).append('\n');
                if (line.contains("</pre>")) {
                    inPre = false;
                }
            } else {
                boolean isTag = line.startsWith("@");
                String prefix = indent + " * ";
                String cont = indent + " * " + (isTag ? "    " : "");
                int width = WIDTH;
                StringBuilder cur = new StringBuilder(prefix);
                boolean fresh = true;
                for (String word : line.split(" ")) {
                    String piece = fresh ? word : " " + word;
                    if (!fresh && cur.length() + piece.length() > width) {
                        sb.append(cur).append('\n');
                        cur = new StringBuilder(cont);
                        piece = word;
                    }
                    cur.append(piece);
                    fresh = false;
                }
                sb.append(cur).append('\n');
            }
        }
        return sb.append(indent).append(" */\n").toString();
    }

    private static Transformer.TemplateException error(Transformer.Parsed p, String file, Tree at, String message) {
        long pos = p.positions().getStartPosition(p.unit(), at);
        long line = pos < 0 ? 0 : p.unit().getLineMap().getLineNumber(pos);
        return new Transformer.TemplateException(file + ":" + line + ": " + message);
    }
}
