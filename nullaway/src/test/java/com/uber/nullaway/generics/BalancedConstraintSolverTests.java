package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import com.uber.nullaway.generics.ConstraintSolver.SourceOrigin;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Large balanced solver graphs on real compiler types, without nested-call attribution or NullAway
 * frontend inference. Small end-to-end tests separately cover frontend behavior.
 */
@RunWith(JUnit4.class)
public class BalancedConstraintSolverTests {

  private static final ThreadLocal<List<String>> EXECUTED_MARKERS =
      ThreadLocal.withInitial(ArrayList::new);

  @Test
  public void nullableListLeaves255Nodes() {
    runFixture(255);
  }

  @Test
  public void nullableListLeaves511Nodes() {
    runFixture(511);
  }

  /** Attributes flat calls using only our checker and verifies its marker ran exactly once. */
  private void runFixture(int nodeCount) {
    String marker = "balanced" + nodeCount;
    StringBuilder source =
        new StringBuilder(
            """
            package com.uber;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T a, T b) { return a; }
            """);
    source
        .append("void ")
        .append(marker)
        .append("(List<@Nullable String> a, List<@Nullable String> b) {\n");
    for (int i = 0; i < nodeCount; i++) {
      source.append("pick(a, b);\n");
    }
    source.append("}\n}\n");
    EXECUTED_MARKERS.get().clear();
    try {
      CompilationTestHelper.newInstance(BalancedSolverChecker.class, getClass())
          .setArgs(
              JSpecifyJavacConfig.withJSpecifyModeArgs(
                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
          .addSourceLines("Test.java", source.toString())
          .doTest();
      assertThat(EXECUTED_MARKERS.get()).containsExactly(marker);
    } finally {
      EXECUTED_MARKERS.remove();
    }
  }

  /**
   * Runs direct assertions while the fixture's javac symbols and attributed types are available.
   */
  @BugPattern(summary = "Checks balanced constraint solver graphs", severity = SUGGESTION)
  public static final class BalancedSolverChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    /** Captures inputs before registering variables, then checks both independent solver modes. */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      String marker = tree.getName().toString();
      if (!Set.of("balanced255", "balanced511").contains(marker)) {
        return NO_MATCH;
      }
      List<MethodInvocationTree> sites = new ArrayList<>();
      for (var statement : Objects.requireNonNull(tree.getBody()).getStatements()) {
        sites.add((MethodInvocationTree) ((ExpressionStatementTree) statement).getExpression());
      }
      assertThat(sites).hasSize(marker.equals("balanced255") ? 255 : 511);
      assertThat(new LinkedHashSet<>(sites)).hasSize(sites.size());
      Element declared = ASTHelpers.getSymbol(sites.get(0)).getTypeParameters().get(0);
      NullAway analysis =
          new NullAway(
              ErrorProneFlags.fromMap(
                  Map.of(
                      "NullAway:AnnotatedPackages", "com.uber",
                      "NullAway:JSpecifyMode", "true",
                      "NullAway:JSpecifyExperimental", "true")));
      Type leaf = ASTHelpers.getSymbol(tree).getParameters().get(0).type;
      Fixture fixture = new Fixture(state, analysis, declared, sites, leaf);
      SourceSnapshot snapshot = new SourceSnapshot();
      Type declaredType = (Type) declared.asType();
      snapshot.capture(declaredType);
      for (var parameter : ASTHelpers.getSymbol(tree).getParameters()) {
        assertListType(parameter.type, fixture, false);
        snapshot.capture(parameter.type);
      }
      for (MethodInvocationTree site : sites) {
        assertThat(ASTHelpers.getSymbol(site).getTypeParameters().get(0))
            .isSameInstanceAs(declared);
        snapshot.capture(ASTHelpers.getType(site));
        for (var argument : site.getArguments()) {
          snapshot.capture(ASTHelpers.getType(argument));
        }
      }
      for (boolean contextual : List.of(true, false)) {
        checkGraph(fixture, contextual, snapshot);
        assertThat(declared.asType()).isSameInstanceAs(declaredType);
      }
      EXECUTED_MARKERS.get().add(marker);
      return NO_MATCH;
    }

    /** Builds a new solver per mode; repeated solves must also leave earlier results unchanged. */
    private static void checkGraph(Fixture fixture, boolean contextual, SourceSnapshot snapshot) {
      ConstraintSolver solver =
          new ConstraintSolverImpl(
              fixture.analysis().getConfig(), fixture.state(), fixture.analysis());
      Type.TypeVar[] fresh = new Type.TypeVar[fixture.sites().size()];
      assertThat(addNode(fixture, solver, fresh, 0, -1, contextual)).isEqualTo(2 * fresh.length);
      assertThat(solver.inferenceVariableSymbols()).hasSize(fresh.length);
      assertThat(solver.inferenceVariableSymbols()).doesNotContain(fixture.declared());
      assertThat(
              solver
                  .registerInferenceVariables(
                      fixture.sites().get(0),
                      List.of(fixture.declared()),
                      Map.of(),
                      Set.of(fixture.declared()),
                      Map.of(fixture.declared(), ASTHelpers.getType(fixture.sites().get(0))))
                  .get(fixture.declared()))
          .isSameInstanceAs(fresh[0]);
      Set<InferenceVariable> keys = new LinkedHashSet<>();
      for (MethodInvocationTree site : fixture.sites()) {
        keys.add(new InferenceVariable(fixture.declared(), site));
      }
      Solution first = solver.solve();
      assertSolution(first, keys, solver.inferenceVariableSymbols(), fixture);
      snapshot.assertUnchanged();
      Solution repeated = solver.solve();
      assertSolution(repeated, keys, solver.inferenceVariableSymbols(), fixture);
      assertSolution(first, keys, solver.inferenceVariableSymbols(), fixture);
      assertThat(repeated.inferredTypes().keySet())
          .containsExactlyElementsIn(first.inferredTypes().keySet())
          .inOrder();
      snapshot.assertUnchanged();
    }

    /**
     * Registers heap-indexed nodes in DFS order and adds exactly two argument constraints per node.
     * Origins belong to the logical receiving parent even though the attributed calls are flat.
     * This reproduces solver topology and source insertion order, not frontend AST nesting.
     */
    private static int addNode(
        Fixture fixture,
        ConstraintSolver solver,
        Type.TypeVar[] fresh,
        int node,
        int parent,
        boolean contextual) {
      MethodInvocationTree site = fixture.sites().get(node);
      Type shape = Objects.requireNonNull(ASTHelpers.getType(site));
      Type.TypeVar variable =
          Objects.requireNonNull(
              solver
                  .registerInferenceVariables(
                      site,
                      List.of(fixture.declared()),
                      Map.of(),
                      Set.of(fixture.declared()),
                      Map.of(fixture.declared(), shape))
                  .get(fixture.declared()));
      fresh[node] = variable;
      int constraints = 0;
      if (parent >= 0) {
        solver.addSubtypeConstraint(
            variable,
            Objects.requireNonNull(fresh[parent]),
            false,
            new SourceOrigin(site, fixture.sites().get(parent), shape));
        constraints++;
      } else if (contextual) {
        solver.addSubtypeConstraint(
            variable, fixture.leaf(), false, new SourceOrigin(site, site, shape));
      }
      int left = 2 * node + 1;
      if (left < fresh.length) {
        constraints += addNode(fixture, solver, fresh, left, node, contextual);
        constraints += addNode(fixture, solver, fresh, left + 1, node, contextual);
      } else {
        for (var argument : site.getArguments()) {
          Type leaf = Objects.requireNonNull(ASTHelpers.getType(argument));
          solver.addSubtypeConstraint(
              leaf, variable, false, new SourceOrigin(argument, site, leaf));
          constraints++;
        }
      }
      return constraints;
    }

    /** Checks every registered node, including certification, ownership, and nested annotations. */
    private static void assertSolution(
        Solution solution, Set<InferenceVariable> keys, Set<Element> symbols, Fixture fixture) {
      assertThat(solution.inferredTypes().keySet()).containsExactlyElementsIn(keys);
      assertThat(solution.incompleteVariables()).isEmpty();
      assertThat(solution.inconsistentVariables()).isEmpty();
      assertThat(solution.lowerConflicts()).isEmpty();
      assertThat(solution.inputDependencies().variables()).isEmpty();
      assertThat(solution.inputDependencies().sites()).isEmpty();
      assertThat(solution.isComplete()).isTrue();
      for (InferenceVariable key : keys) {
        assertThat(solution.isCompleteForSite(key.site())).isTrue();
        assertThat(solution.inputDependencies().isIndependentForSite(key.site())).isTrue();
        Type result = Objects.requireNonNull(solution.inferredTypes().get(key));
        assertListType(result, fixture, true);
        TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(symbols);
        result.accept(collector, null);
        assertThat(collector.getMatches()).isEmpty();
      }
    }

    /**
     * Requires the concrete List/String shape and distinguishes source defaults from solved roots.
     */
    private static void assertListType(Type type, Fixture fixture, boolean solved) {
      assertThat(type).isInstanceOf(Type.ClassType.class);
      assertThat(type.tsym).isSameInstanceAs(fixture.leaf().tsym);
      assertThat(type.isRaw()).isFalse();
      assertThat(type.getTypeArguments()).hasSize(1);
      assertThat(
              Nullness.hasNullableAnnotation(
                  type.getAnnotationMirrors().stream(), fixture.analysis().getConfig()))
          .isFalse();
      assertThat(
              Nullness.hasNonNullAnnotation(
                  type.getAnnotationMirrors().stream(), fixture.analysis().getConfig()))
          .isEqualTo(solved);
      Type element = type.getTypeArguments().head;
      assertThat(element).isInstanceOf(Type.ClassType.class);
      assertThat(element.tsym).isSameInstanceAs(fixture.state().getSymtab().stringType.tsym);
      assertThat(
              Nullness.hasNullableAnnotation(
                  element.getAnnotationMirrors().stream(), fixture.analysis().getConfig()))
          .isTrue();
      assertThat(
              Nullness.hasNonNullAnnotation(
                  element.getAnnotationMirrors().stream(), fixture.analysis().getConfig()))
          .isFalse();
    }
  }

  /** Compiler-owned inputs shared between two isolated solvers, never registered as a frontend. */
  private record Fixture(
      VisitorState state,
      NullAway analysis,
      Element declared,
      List<MethodInvocationTree> sites,
      Type leaf) {}

  /**
   * Snapshots annotations and child identities in this fixture's class/type-variable input graphs.
   */
  private static final class SourceSnapshot {
    private final IdentityHashMap<Type, Boolean> seen = new IdentityHashMap<>();
    private final List<Runnable> checks = new ArrayList<>();

    /** Recursively captures source metadata and declaration bounds without expanding cycles. */
    void capture(Type type) {
      if (type == null || seen.put(type, true) != null) {
        return;
      }
      Symbol symbol = type.tsym;
      var annotations = List.copyOf(type.getAnnotationMirrors());
      checks.add(() -> assertThat(type.tsym).isSameInstanceAs(symbol));
      checks.add(
          () ->
              assertThat(type.getAnnotationMirrors())
                  .containsExactlyElementsIn(annotations)
                  .inOrder());
      List<Type> arguments = List.copyOf(type.getTypeArguments());
      checks.add(() -> assertThat(type.getTypeArguments()).hasSize(arguments.size()));
      for (int i = 0; i < arguments.size(); i++) {
        int index = i;
        Type argument = arguments.get(i);
        checks.add(() -> assertThat(type.getTypeArguments().get(index)).isSameInstanceAs(argument));
        capture(argument);
      }
      if (type instanceof Type.TypeVar variable) {
        Type upper = variable.getUpperBound();
        Type lower = variable.getLowerBound();
        checks.add(() -> assertThat(variable.getUpperBound()).isSameInstanceAs(upper));
        checks.add(() -> assertThat(variable.getLowerBound()).isSameInstanceAs(lower));
        capture(upper);
        capture(lower);
      } else if (type instanceof Type.ClassType classType) {
        Type enclosing = classType.getEnclosingType();
        checks.add(() -> assertThat(classType.getEnclosingType()).isSameInstanceAs(enclosing));
        capture(enclosing);
      }
    }

    /** Rechecks source annotations and links after each solve, including repeated solves. */
    void assertUnchanged() {
      checks.forEach(Runnable::run);
    }
  }
}
