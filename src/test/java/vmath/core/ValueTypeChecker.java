package vmath.core;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Checks the identity rules of JEP 401 on the syntax tree of a source file: a value class has no identity, so the value types of this library must never be compared
 * with {@code ==} or {@code !=}, locked with {@code synchronized}, or passed to {@code System.identityHashCode}.
 *
 * <p>This works on the parsed tree, not on the text, but without type attribution (the sources are checked before anything is compiled, and the float sources are
 * templates). A name counts as a value-typed variable when its innermost visible declaration (field, parameter, local, pattern binding or record component, resolved by scope in
 * the same file) has a value type, or when the operand is a {@code Type.CONSTANT} select on a value type. A name declared nowhere in the file (inherited from another class) is not value-typed, so this is a net with holes, not a proof. The rule table is the checker's whole behaviour; {@code
 * ValueTypeCheckerTest} proves each rule on a violating source.
 */
final class ValueTypeChecker {

    /** The simple names of the library's value types, both precisions (the templates say the float name, the generated twins the double name). */
    private static final Pattern VALUE_TYPE = Pattern.compile("(Vec[234]|Quat|Mat[34]|Mat4x3|Transform|Aabb|Sphere|Plane|Ray|Triangle|Obb|Frustum|Segment|Capsule)[fd]");

    record Violation(int line, String rule, String text) {
        @Override
        public String toString() {
            return "line " + line + ": " + rule + " (" + text + ")";
        }
    }

    private ValueTypeChecker() {}

    static boolean isValueType(String simpleName) {
        return VALUE_TYPE.matcher(simpleName).matches();
    }

    static List<Violation> check(String fileName, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + fileName), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { }, List.of("-proc:none"), null, List.of(file));
        SourcePositions positions = com.sun.source.util.Trees.instance(task).getSourcePositions();
        List<Violation> out = new ArrayList<>();
        for (CompilationUnitTree unit : task.parse()) {
            ArrayDeque<Map<String, Boolean>> scopes = new ArrayDeque<>();
            new TreeScanner<Void, Void>() {
                @SuppressWarnings("removal") // JDK 28 deprecates getStartPosition(unit, tree); there is no replacement on the JDK 25 baseline
                private void add(Tree at, String rule, String text) {
                    long pos = positions.getStartPosition(unit, at);
                    out.add(new Violation((int) unit.getLineMap().getLineNumber(pos), rule, text));
                }

                private boolean valueTyped(ExpressionTree e) {
                    while (e instanceof com.sun.source.tree.ParenthesizedTree par) {
                        e = par.getExpression();
                    }
                    if (e instanceof IdentifierTree id) {
                        return isValueNamed(scopes, id.getName().toString());
                    }
                    if (e instanceof MemberSelectTree ms && ms.getExpression() instanceof IdentifierTree owner) {
                        String id = ms.getIdentifier().toString();
                        boolean constant = !id.isEmpty() && id.equals(id.toUpperCase());
                        return (constant && isValueType(owner.getName().toString())) || (owner.getName().contentEquals("this") && isValueNamed(scopes, id));
                    }
                    return false;
                }

                private Void scoped(Runnable body) {
                    scopes.push(new HashMap<>());
                    try {
                        body.run();
                    } finally {
                        scopes.pop();
                    }
                    return null;
                }

                @Override
                public Void visitClass(com.sun.source.tree.ClassTree c, Void p) {
                    return scoped(() -> {
                        for (Tree member : c.getMembers()) {
                            if (member instanceof VariableTree v) {
                                declare(scopes, v); // fields are visible in every method, before and after their declaration
                            }
                        }
                        super.visitClass(c, p);
                    });
                }

                @Override
                public Void visitMethod(com.sun.source.tree.MethodTree m, Void p) {
                    return scoped(() -> super.visitMethod(m, p));
                }

                @Override
                public Void visitBlock(com.sun.source.tree.BlockTree b, Void p) {
                    return scoped(() -> super.visitBlock(b, p));
                }

                @Override
                public Void visitForLoop(com.sun.source.tree.ForLoopTree f, Void p) {
                    return scoped(() -> super.visitForLoop(f, p));
                }

                @Override
                public Void visitEnhancedForLoop(com.sun.source.tree.EnhancedForLoopTree f, Void p) {
                    return scoped(() -> super.visitEnhancedForLoop(f, p));
                }

                @Override
                public Void visitLambdaExpression(com.sun.source.tree.LambdaExpressionTree l, Void p) {
                    return scoped(() -> super.visitLambdaExpression(l, p));
                }

                @Override
                public Void visitCatch(com.sun.source.tree.CatchTree c, Void p) {
                    return scoped(() -> super.visitCatch(c, p));
                }

                @Override
                public Void visitVariable(VariableTree v, Void p) {
                    declare(scopes, v);
                    return super.visitVariable(v, p);
                }

                @Override
                public Void visitBinary(BinaryTree b, Void p) {
                    if ((b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO) && !isNull(b.getLeftOperand()) && !isNull(b.getRightOperand())
                            && (valueTyped(b.getLeftOperand()) || valueTyped(b.getRightOperand()))) {
                        add(b, "reference comparison of a value type; use equals() or approxEquals()", b.toString());
                    }
                    return super.visitBinary(b, p);
                }

                @Override
                public Void visitSynchronized(SynchronizedTree s, Void p) {
                    if (valueTyped(s.getExpression())) {
                        add(s, "synchronized on a value type; value objects cannot be locked", s.getExpression().toString());
                    }
                    return super.visitSynchronized(s, p);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void p) {
                    if (m.getMethodSelect().toString().endsWith("identityHashCode") && !m.getArguments().isEmpty() && valueTyped(m.getArguments().get(0))) {
                        add(m, "identityHashCode of a value type", m.toString());
                    }
                    return super.visitMethodInvocation(m, p);
                }
            }.scan(unit, null);
        }
        return out;
    }

    private static void declare(ArrayDeque<Map<String, Boolean>> scopes, VariableTree v) {
        if (!scopes.isEmpty()) {
            scopes.peek().put(v.getName().toString(), v.getType() != null && isValueType(simpleName(v.getType())));
        }
    }

    /** The innermost declaration of {@code name} decides; a name that was never declared (a field of another class, a static import) is not value-typed. */
    private static boolean isValueNamed(ArrayDeque<Map<String, Boolean>> scopes, String name) {
        for (Map<String, Boolean> scope : scopes) {
            Boolean value = scope.get(name);
            if (value != null) {
                return value;
            }
        }
        return false;
    }

    private static boolean isNull(ExpressionTree e) {
        return e instanceof LiteralTree l && l.getKind() == Tree.Kind.NULL_LITERAL;
    }

    private static String simpleName(Tree type) {
        if (type instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        if (type instanceof MemberSelectTree ms) {
            return ms.getIdentifier().toString();
        }
        return ""; // arrays, generics and primitives are not value-typed variables
    }
}
