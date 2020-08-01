/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */


package net.sourceforge.pmd.lang.java.types.internal.infer;

import java.io.PrintStream;
import java.util.Stack;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import net.sourceforge.pmd.lang.java.ast.JavaNode;
import net.sourceforge.pmd.lang.java.rule.security.TypeResTestRule;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.TypePrettyPrint;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.CtorInvocationMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.JInferenceVar.BoundKind;
import net.sourceforge.pmd.util.StringUtil;

/**
 * A strategy to log the execution traces of {@link Infer}.
 */
@SuppressWarnings("PMD.UncommentedEmptyMethodBody")
public interface TypeInferenceLogger {

    // computeCompileTimeDecl


    default void noApplicableCandidates(MethodCallSite site) { }

    default void noCompileTimeDeclaration(MethodCallSite site) { }

    default void startInference(JMethodSig sig, MethodCallSite site, MethodResolutionPhase phase) { }

    default void endInference(@Nullable JMethodSig result) { }

    default void fallBackCompileTimeDecl(JMethodSig ctdecl, MethodCallSite site) { }

    default void skipInstantiation(JMethodSig partiallyInferred, MethodCallSite site) { }

    default void ambiguityError(MethodCallSite site, JMethodSig m1, JMethodSig m2) { }

    // instantiateImpl


    default void ctxInitialization(InferenceContext ctx, JMethodSig sig) { }

    default void startArgsChecks() { }

    default void startArg(int i, ExprMirror expr, JTypeMirror formal) { }

    default void skipArgAsNonPertinent(int i, ExprMirror expr) { }

    default void endArg() { }

    default void endArgsChecks() { }

    default void startReturnChecks() { }

    default void endReturnChecks() { }

    default void propagateAndAbort(InferenceContext context, InferenceContext parent) { }

    // ivar events


    default void boundAdded(InferenceContext ctx, JInferenceVar var, BoundKind kind, JTypeMirror bound) { }

    default void ivarMerged(InferenceContext ctx, JInferenceVar var, JInferenceVar delegate) { }

    default void ivarInstantiated(InferenceContext ctx, JInferenceVar var, JTypeMirror inst) { }


    /**
     * Log that the instantiation of the method type m for the given
     * call site failed. The exception provides a detail message.
     * Such an event is perfectly normal and may happen repeatedly
     * when performing overload resolution.
     *
     * <p>Exceptions occuring in an {@link MethodResolutionPhase#isInvocation() invocation phase}
     * are compile-time errors though.
     *
     * @param exception Failure record
     */
    default void logResolutionFail(ResolutionFailure exception) { }

    default boolean isNoop() {
        return false;
    }

    static TypeInferenceLogger noop() {
        return SimpleLogger.NOOP;
    }


    class SimpleLogger implements TypeInferenceLogger {

        static final TypeInferenceLogger NOOP = new TypeInferenceLogger() {
            @Override
            public boolean isNoop() {
                return true;
            }
        };


        private final PrintStream out;
        protected static final int LEVEL_INCREMENT = 4;
        private int level;
        private String indent;

        protected static final String ANSI_RESET = "\u001B[0m";
        protected static final String ANSI_BLUE = "\u001B[34m";
        protected static final String ANSI_PURPLE = "\u001B[35m";
        protected static final String ANSI_RED = "\u001B[31m";
        protected static final String ANSI_YELLOW = "\u001B[33m";

        protected String color(Object str, String color) {
            return SystemUtils.IS_OS_UNIX ? color + str + ANSI_RESET : str.toString();
        }

        public SimpleLogger(PrintStream out) {
            this.out = out;
            updateLevel(0);
        }

        protected int getLevel() {
            return level;
        }

        protected void updateLevel(int increment) {
            level += increment;
            indent = StringUtils.repeat(' ', level);
        }

        protected void println(String str) {
            out.print(indent);
            out.println(str);
        }


        protected void endSection(String footer) {
            updateLevel(-LEVEL_INCREMENT);
            println(footer);
        }

        protected void startSection(String header) {
            println(header);
            updateLevel(+LEVEL_INCREMENT);
        }

        @Override
        public void logResolutionFail(ResolutionFailure exception) {
            if (exception.getCallSite() instanceof MethodCallSite && exception != ResolutionFailure.UNKNOWN) {
                ((MethodCallSite) exception.getCallSite()).acceptFailure(exception);
            }
        }

        @Override
        public void noApplicableCandidates(MethodCallSite site) {
            if (!site.isLogEnabled()) {
                return;
            }
            @Nullable JTypeMirror receiver = site.getExpr().getErasedReceiverType();
            if (receiver != null) {
                JTypeDeclSymbol symbol = receiver.getSymbol();
                if (symbol == null || symbol.isUnresolved()) {
                    return;
                }
            }
            println("");
            printExpr(site.getExpr());
            if (site.getExpr() instanceof CtorInvocationMirror) {
                println("[WARNING] No potentially applicable constructors in "
                            + ((CtorInvocationMirror) site.getExpr()).getNewType());
            } else {
                println("[WARNING] No potentially applicable methods in " + receiver);
            }
        }

        @Override
        public void noCompileTimeDeclaration(MethodCallSite site) {
            if (!site.isLogEnabled()) {
                return;
            }
            println("");
            printExpr(site.getExpr());
            startSection("[WARNING] CTDecl resolution failed. Summary of failures:");
            site.getResolutionFailures()
                .forEach((phase, failures) -> {
                    startSection(phase.toString() + ":");
                    failures.forEach(it -> println(it.getReason() + "\t\t" + ppMethod(it.getFailedMethod())));
                    endSection("");
                });
            endSection("");
        }

        @Override
        public void fallBackCompileTimeDecl(JMethodSig ctdecl, MethodCallSite site) {
            println("[WARNING] Falling back on "
                        + color(ctdecl, ANSI_BLUE)
                        + " (this may cause future mistakes)");
        }

        @Override
        public void ambiguityError(MethodCallSite site, JMethodSig m1, JMethodSig m2) {
            println("");
            printExpr(site.getExpr());
            startSection("[WARNING] Ambiguity error: both methods are maximally specific");
            println(color(m1, ANSI_RED));
            println(color(m2, ANSI_RED));
            endSection("");
        }

        protected void printExpr(ExprMirror expr) {
            String exprText = expr.getLocation().getText().toString();
            exprText = exprText.replaceAll("\\R\\s+", "");
            exprText = StringUtil.escapeJava(StringUtils.truncate(exprText, 100));
            println("At:   " + fileLocation(expr));
            println("Expr: " + color(exprText, ANSI_YELLOW));
        }

        private String fileLocation(ExprMirror mirror) {
            JavaNode node = mirror.getLocation();
            return TypeResTestRule.FILENAME.get() + ":" + node.getBeginLine() + " :" + node.getBeginColumn() + ".."
                + node.getEndLine() + ":" + node.getEndColumn();
        }

        @NonNull
        protected String ppMethod(JMethodSig sig) {
            return TypePrettyPrint.prettyPrint(sig, false);
        }

    }

    /**
     * This is mega verbose, should only be used for unit tests.
     */
    class VerboseLogger extends SimpleLogger {


        private final Stack<Integer> marks = new Stack<>();

        public VerboseLogger(PrintStream out) {
            super(out);
            mark();
        }

        void mark() {
            marks.push(getLevel());
        }

        void rollback() {
            int pop = marks.pop();
            updateLevel(pop - getLevel());
        }

        @Override
        public void startInference(JMethodSig sig, MethodCallSite site, MethodResolutionPhase phase) {
            mark();
            startSection("Phase " + phase + ", " + sig);
        }

        @Override
        public void endInference(@Nullable JMethodSig result) {
            rollback();
            println(result != null ? "Success: " + color(ppMethod(result), ANSI_RED)
                                   : "FAILED! SAD!");
        }

        @Override
        public void skipInstantiation(JMethodSig partiallyInferred, MethodCallSite site) {
            println("Skipping instantiation of " + partiallyInferred + ", it's already complete");
        }

        @Override
        public void ctxInitialization(InferenceContext ctx, JMethodSig sig) {
            println("Context " + ctx.getId() + ",\t\t\t" + color(ppMethod(ctx.mapToIVars(sig)), ANSI_BLUE));
        }


        @Override
        public void startArgsChecks() {
            startSection("ARGUMENTS");
        }

        @Override
        public void startReturnChecks() {
            startSection("RETURN");
        }


        @Override
        public void propagateAndAbort(InferenceContext context, InferenceContext parent) {
            println("Ctx " + parent.getId() + " adopts " + color(context.getFreeVars(), ANSI_BLUE) + " from ctx "
                        + context.getId());
        }

        @Override
        public void startArg(int i, ExprMirror expr, JTypeMirror formalType) {
            startSection("Checking arg " + i + " against " + formalType);
            printExpr(expr);
        }

        @Override
        public void skipArgAsNonPertinent(int i, ExprMirror expr) {
            println("Argument " + i + " is not pertinent to applicability");
            printExpr(expr);
        }

        @Override
        public void endArgsChecks() {
            endSection("");
        }

        @Override
        public void endArg() {
            endSection("");
        }

        @Override
        public void endReturnChecks() {
            endSection("");
        }

        @Override
        public void boundAdded(InferenceContext ctx, JInferenceVar ivar, BoundKind kind, JTypeMirror bound) {
            println(addCtxInfo(ctx, "New bound") + kind.format(ivar, bound));
        }

        @Override
        public void ivarMerged(InferenceContext ctx, JInferenceVar var, JInferenceVar delegate) {
            println(addCtxInfo(ctx, "Ivar merged") + var + " -> " + delegate);
        }

        @Override
        public void ivarInstantiated(InferenceContext ctx, JInferenceVar var, JTypeMirror inst) {
            println(addCtxInfo(ctx, "Ivar instantiated") + var + " := " + inst);
        }

        @NonNull
        private String addCtxInfo(InferenceContext ctx, String event) {
            return event + "  (ctx " + ctx.getId() + "):\t";
        }

        @Override
        public void logResolutionFail(ResolutionFailure exception) {
            super.logResolutionFail(exception);
            println("Failed: " + exception.getReason());
        }

    }


}
