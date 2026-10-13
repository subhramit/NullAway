package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;
import static com.uber.nullaway.generics.TypeSubstitutionUtils.hasNullableRoot;
import static com.uber.nullaway.generics.TypeSubstitutionUtils.removeNullableAnnotation;
import static org.mockito.Mockito.mock;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.handlers.Handler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.element.Element;
import org.junit.Test;

/**
 * Compiler-backed proofs of request-local call preprocessing, without timing or inference caches.
 */
public class CallPreprocessingReuseTests {
  private static final ThreadLocal<Consumer<Context>> ASSERTIONS = new ThreadLocal<>();
  private static final ThreadLocal<Integer> CHECKED = ThreadLocal.withInitial(() -> 0);
  private static final String ANNOTATED_SOURCE =
      """
      @NullMarked class Test<E extends @Nullable Object> {
        Test<@Nullable String> strings;
        Test<Integer> integers;
        <T extends List<E>, U extends @Nullable T, Missing>
            U probe(T first, @Nullable T later, U value) { return value; }
        void test() { strings.probe(null, null, null); }
      }
      """;

  @Test
  public void oneTraversalRetainsFirstOccurrenceAndRequestedVariableOrder() {
    compile(
        ANNOTATED_SOURCE,
        ctx -> {
          Type.MethodType signature = ctx.signature("strings");
          List<Symbol.TypeVariableSymbol> variables = ctx.method.getTypeParameters();
          Type integerBound =
              ((Type.TypeVar) ctx.signature("integers").argtypes.head).getUpperBound();
          Type.TypeVar later =
              TYPE_METADATA_BUILDER.createDetachedTypeVar(
                  (Type.TypeVar) signature.argtypes.head, integerBound);
          Type.MethodType conflicting =
              new Type.MethodType(
                  signature.argtypes.tail.prepend(later),
                  signature.restype,
                  signature.thrown,
                  signature.tsym);
          List<Symbol.TypeVariableSymbol> requested =
              List.of(variables.get(2), variables.get(0), variables.get(1));
          Map<Element, Type> old = singletonBounds(conflicting, requested, ctx.config);
          CountingMethodType executable = new CountingMethodType(conflicting, conflicting.restype);
          Map<Element, Type> batched = ctx.bounds(executable, requested);
          assertThat(executable.traversals).isEqualTo(1);
          assertThat(batched).containsExactlyEntriesIn(old).inOrder();
          assertThat(batched.keySet())
              .containsExactly(variables.get(0), variables.get(1))
              .inOrder();
          assertThat(batched.get(variables.get(0)).getTypeArguments().head.tsym)
              .isSameInstanceAs(ctx.state.getSymtab().stringType.tsym);
          assertThat(later.getUpperBound()).isSameInstanceAs(integerBound);
        });
  }

  @Test
  public void annotatedPeerBoundsRecomputeForChangedReceiverWithoutMutation() {
    compile(
        ANNOTATED_SOURCE,
        ctx -> {
          Type.MethodType strings = ctx.signature("strings");
          Type.MethodType integers = ctx.signature("integers");
          List<Symbol.TypeVariableSymbol> variables = ctx.method.getTypeParameters();
          List<BoundSnapshot> originals =
              snapshotBounds(variables, ctx.method.type, strings, integers);
          Type.TypeVar peer =
              TYPE_METADATA_BUILDER.createDetachedTypeVar(
                  (Type.TypeVar)
                      removeNullableAnnotation(
                          ((Type.TypeVar) strings.restype).getUpperBound(), ctx.config),
                  ((Type.TypeVar) strings.argtypes.head).getUpperBound());
          Type.TypeVar result =
              TYPE_METADATA_BUILDER.createDetachedTypeVar((Type.TypeVar) strings.restype, peer);
          CountingMethodType executable = new CountingMethodType(strings, result);
          Map<Element, Type> first = ctx.bounds(executable, variables);
          assertThat(executable.traversals).isEqualTo(1);
          Type firstBound = first.get(variables.get(0));
          Type.TypeVar firstPeer = (Type.TypeVar) first.get(variables.get(1));
          assertThat(hasNullableRoot(firstBound.getTypeArguments().head, ctx.config)).isTrue();
          assertThat(hasNullableRoot(firstPeer, ctx.config)).isTrue();
          assertThat(firstPeer.getUpperBound()).isSameInstanceAs(firstBound);
          assertThat(hasNullableRoot(peer, ctx.config)).isFalse();
          Type changedBound = ((Type.TypeVar) integers.argtypes.head).getUpperBound();
          peer.setUpperBound(changedBound);
          Map<Element, Type> second = ctx.bounds(executable, variables);
          assertThat(executable.traversals).isEqualTo(2);
          assertThat(second.get(variables.get(0))).isSameInstanceAs(changedBound);
          assertThat(hasNullableRoot(changedBound.getTypeArguments().head, ctx.config)).isFalse();
          assertThat(hasNullableRoot(second.get(variables.get(1)), ctx.config)).isTrue();
          assertThat(((Type.TypeVar) second.get(variables.get(1))).getUpperBound())
              .isSameInstanceAs(changedBound);
          assertThat(firstPeer.getUpperBound()).isSameInstanceAs(firstBound);
          assertThat(peer.getUpperBound()).isSameInstanceAs(changedBound);
          assertThat(firstBound.getTypeArguments().head.tsym)
              .isSameInstanceAs(ctx.state.getSymtab().stringType.tsym);
          originals.forEach(
              saved -> assertThat(saved.variable.getUpperBound()).isSameInstanceAs(saved.bound));
        });
  }

  @Test
  public void unmarkedBoundOnlyVariableUsesSuppliedUnfilteredBounds() {
    compile(
        """
        @NullUnmarked class Test<E> {
          Test<@Nullable String> strings;
          <T extends List<E>, U extends T, R extends Comparable<R>, D extends List<R>>
              void probe(U value) {}
          void test() { strings.probe(null); }
        }
        """,
        ctx -> {
          List<Symbol.TypeVariableSymbol> variables = ctx.method.getTypeParameters();
          CountingMethodType executable =
              new CountingMethodType(ctx.signature("strings"), ctx.method.getReturnType());
          Map<Element, Type> unfiltered = ctx.bounds(executable, variables);
          Map<Element, Type> originalBounds = new LinkedHashMap<>(unfiltered);
          Type bound = unfiltered.get(variables.get(0));
          assertThat(
                  InferenceTypeShapes.containsVariable(
                      executable, variables.get(0), ctx.state, ctx.config))
              .isFalse();
          Set<Element> marked =
              ctx.invoke(
                  "variablesWithNullnessMarkedBounds",
                  new Class<?>[] {List.class, VisitorState.class},
                  variables,
                  ctx.state);
          assertThat(marked).isEmpty();
          Map<Element, Type> solverBounds = new LinkedHashMap<>(unfiltered);
          solverBounds.keySet().retainAll(marked);
          assertThat(solverBounds).isEmpty();
          Map<Element, Type> shapes =
              ctx.invoke(
                  "getJavacInstantiationsForCall",
                  new Class<?>[] {
                    ExpressionTree.class,
                    Type.MethodType.class,
                    List.class,
                    Map.class,
                    VisitorState.class
                  },
                  ctx.call,
                  executable,
                  variables,
                  unfiltered,
                  ctx.state);
          assertThat(executable.traversals).isEqualTo(1);
          assertThat(shapes.get(variables.get(0))).isSameInstanceAs(bound);
          assertThat(bound.getTypeArguments().head.tsym)
              .isSameInstanceAs(ctx.state.getSymtab().stringType.tsym);
          assertThat(hasNullableRoot(bound.getTypeArguments().head, ctx.config)).isTrue();
          assertThat(shapes.keySet()).containsExactly(variables.get(0), variables.get(1));
          assertThat(unfiltered).containsExactlyEntriesIn(originalBounds).inOrder();
        });
  }

  /** Runs a class-based checker once at the attributed probe invocation and clears thread state. */
  private void compile(String source, Consumer<Context> assertions) {
    ASSERTIONS.set(assertions);
    CHECKED.set(0);
    try {
      CompilationTestHelper.newInstance(CallAssertions.class, getClass())
          .setArgs(JSpecifyJavacConfig.withJSpecifyModeArgs(List.of()))
          .addSourceLines(
              "Test.java",
              "package com.uber;",
              "import java.util.List;",
              "import org.jspecify.annotations.*;",
              source)
          .doTest();
      assertThat(CHECKED.get()).isEqualTo(1);
    } finally {
      ASSERTIONS.remove();
      CHECKED.remove();
    }
  }

  /** Supplies real javac types and symbols, without running NullAway's dataflow analysis. */
  @BugPattern(summary = "Checks call preprocessing reuse", severity = SUGGESTION)
  public static final class CallAssertions extends BugChecker
      implements BugChecker.MethodInvocationTreeMatcher {
    /** Executes the installed proof with a fresh GenericsChecks instance for this compilation. */
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (!method.getSimpleName().contentEquals("probe")) {
        return NO_MATCH;
      }
      NullAway analysis =
          new NullAway(
              ErrorProneFlags.fromMap(
                  Map.of(
                      "NullAway:AnnotatedPackages",
                      "com.uber",
                      "NullAway:JSpecifyMode",
                      "true",
                      "NullAway:JSpecifyExperimental",
                      "true")));
      Config config = analysis.getConfig();
      Consumer<Context> assertions = ASSERTIONS.get();
      assertThat(assertions).isNotNull();
      assertions.accept(
          new Context(
              state,
              tree,
              method,
              config,
              new GenericsChecks(analysis, config, mock(Handler.class))));
      CHECKED.set(CHECKED.get() + 1);
      return NO_MATCH;
    }
  }

  /** Compiler state and the same preprocessing instance shared by repeated requests. */
  private record Context(
      VisitorState state,
      MethodInvocationTree call,
      Symbol.MethodSymbol method,
      Config config,
      GenericsChecks checks) {
    /** Resolves a receiver field's annotated executable, preserving receiver-dependent bounds. */
    private Type.MethodType signature(String fieldName) {
      Type receiver =
          ASTHelpers.getEnclosedElements(method.owner).stream()
              .filter(field -> field.getSimpleName().contentEquals(fieldName))
              .map(field -> ((Symbol) field).type)
              .findFirst()
              .orElseThrow();
      return TypeSubstitutionUtils.memberType(state.getTypes(), receiver, method, config)
          .asMethodType();
    }

    /** Requests upper bounds through the unchanged private production signature. */
    private Map<Element, Type> bounds(
        Type.MethodType executable, List<Symbol.TypeVariableSymbol> variables) {
      return invoke(
          "getInstantiatedUpperBounds",
          new Class<?>[] {Type.MethodType.class, List.class},
          executable,
          variables);
    }

    /** Treats private-signature drift or reflective invocation failures as linkage failures. */
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"}) // private reflection harness
    private <T> T invoke(String name, Class<?>[] parameters, Object... arguments) {
      try {
        Method helper = GenericsChecks.class.getDeclaredMethod(name, parameters);
        helper.setAccessible(true);
        return (T) helper.invoke(checks, arguments);
      } catch (ReflectiveOperationException exception) {
        throw new LinkageError("Could not exercise call preprocessing: " + name, exception);
      }
    }
  }

  /** Reproduces the old singleton algorithm as an independent first-occurrence oracle. */
  private static Map<Element, Type> singletonBounds(
      Type.MethodType executable, List<Symbol.TypeVariableSymbol> variables, Config config) {
    Map<Element, Type> bounds = new LinkedHashMap<>();
    for (Symbol.TypeVariableSymbol variable : variables) {
      TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(variable);
      executable.accept(collector, null);
      if (!collector.getMatches().isEmpty()) {
        bounds.put(
            variable,
            TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                ((Type.TypeVar) variable.type).getUpperBound(),
                collector.getMatches().iterator().next().getUpperBound(),
                config));
      }
    }
    return bounds;
  }

  /** Captures declaration and receiver-instantiated upper-bound identities before preprocessing. */
  private static List<BoundSnapshot> snapshotBounds(
      List<Symbol.TypeVariableSymbol> variables, Type... roots) {
    List<BoundSnapshot> snapshots = new ArrayList<>();
    for (Type root : roots) {
      TypeVarWithSymbolCollector collector =
          new TypeVarWithSymbolCollector(new LinkedHashSet<>(variables));
      root.accept(collector, null);
      collector
          .getMatches()
          .forEach(
              variable -> snapshots.add(new BoundSnapshot(variable, variable.getUpperBound())));
    }
    return snapshots;
  }

  private record BoundSnapshot(Type.TypeVar variable, Type bound) {}

  /** Counts actual executable visitor entries, not elapsed time or calls to a mock. */
  private static final class CountingMethodType extends Type.MethodType {
    private int traversals;

    private CountingMethodType(Type.MethodType original, Type result) {
      super(original.argtypes, result, original.thrown, original.tsym);
    }

    /** Counts only the bounds collectors; other javac visitors are not preprocessing traversals. */
    @Override
    public <R, S> R accept(Type.Visitor<R, S> visitor, S parameter) {
      if (visitor instanceof TypeVarWithSymbolCollector) {
        traversals++;
      }
      return super.accept(visitor, parameter);
    }
  }
}
