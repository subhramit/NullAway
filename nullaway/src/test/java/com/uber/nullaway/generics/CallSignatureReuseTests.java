package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.handlers.Handler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** Compiler-backed proofs that successful signature reuse does not re-resolve its receiver. */
public class CallSignatureReuseTests {

  private static final ThreadLocal<Consumer<Context>> ASSERTIONS = new ThreadLocal<>();
  private static final ThreadLocal<Integer> CHECKED = ThreadLocal.withInitial(() -> 0);

  @Test
  public void persistentSnapshotSkipsReceiverResolutionAndStillAppliesModels() {
    compile(
        context -> {
          AtomicInteger modelCalls = new AtomicInteger();
          Type.MethodType modeled = context.nullableRootSignature();
          Harness harness =
              context.harness(
                  new Handler() {
                    @Override
                    public Type.MethodType onOverrideMethodType(
                        Symbol.MethodSymbol symbol,
                        Type.MethodType signature,
                        VisitorState state,
                        @Nullable MethodInvocationTree invocation) {
                      assertThat(symbol).isSameInstanceAs(ASTHelpers.getSymbol(context.tree()));
                      assertThat(signature).isSameInstanceAs(context.signature());
                      assertThat(invocation).isSameInstanceAs(context.tree());
                      modelCalls.incrementAndGet();
                      return modeled;
                    }
                  });
          map(harness.checks(), "inferredTypeVarNullabilityForGenericCalls")
              .put(context.tree(), context.success(harness.checks(), context.signature()));

          for (TreePath path : new TreePath[] {context.state().getPath(), null}) {
            assertThat(context.lookup(harness.checks(), "getResolvedCallSignature", path, false))
                .isSameInstanceAs(context.signature());
            assertThat(context.lookup(harness.checks(), "getInvokedMethodTypeAtCall", path, false))
                .isSameInstanceAs(modeled);
          }
          assertThat(modelCalls.get()).isEqualTo(2);
          assertThat(
                  TypeSubstitutionUtils.hasNullableRoot(modeled.getReturnType(), context.config()))
              .isTrue();
          assertThat(
                  TypeSubstitutionUtils.hasNullableRoot(
                      context.signature().getReturnType().getTypeArguments().head,
                      context.config()))
              .isTrue();
          verifyNoInteractions(harness.flow());
        });
  }

  @Test
  public void scopedSnapshotReplaysReceiverInputsIntoEveryCollectorBeforeReturning() {
    compile(
        context -> {
          Harness harness = context.harness(new Handler() {});
          Object success = context.success(harness.checks(), context.signature());
          Object input = context.input(Nullness.NONNULL);
          Object scope = context.scope(harness.checks(), success, input);
          List<Map<Tree, Object>> collectors = collectors(scope);
          Map<Tree, Object> outer = new LinkedHashMap<>();
          Map<Tree, Object> suspended = new LinkedHashMap<>();
          collectors.add(outer);
          collectors.add(suspended);
          List<Map<Tree, Object>> requestCollectors = new ArrayList<>();
          when(harness.flow().isRunning(any(TreePath.class), same(context.state().context)))
              .thenReturn(true);
          when(harness
                  .flow()
                  .getNullnessFromRunning(any(TreePath.class), same(context.state().context)))
              .thenAnswer(
                  invocation -> {
                    assertThat(invocation.getArgument(0, TreePath.class).getLeaf())
                        .isSameInstanceAs(context.argument());
                    assertThat(collectors).hasSize(3);
                    requestCollectors.add(collectors.get(2));
                    return Nullness.NONNULL;
                  });
          // Scoped reuse must win over an independently available persistent snapshot.
          map(harness.checks(), "inferredTypeVarNullabilityForGenericCalls")
              .put(
                  context.tree(),
                  context.success(harness.checks(), context.nullableRootSignature()));

          for (int i = 0; i < 2; i++) {
            assertThat(
                    context.lookup(
                        harness.checks(),
                        "getResolvedCallSignature",
                        context.state().getPath(),
                        true))
                .isSameInstanceAs(context.signature());
            assertThat(collectors).containsExactly(outer, suspended).inOrder();
            assertThat(outer).containsExactly(context.argument(), input);
            assertThat(suspended).containsExactly(context.argument(), input);
          }
          assertThat(requestCollectors).hasSize(2);
          for (Map<Tree, Object> request : requestCollectors) {
            assertThat(request).containsExactly(context.argument(), input);
          }
          assertThat(field(harness.checks(), "dataflowInferenceScope")).isSameInstanceAs(scope);
          verify(harness.flow(), times(2))
              .getNullnessFromRunning(any(TreePath.class), same(context.state().context));
        });
  }

  @Test
  public void scopedSnapshotRequiresUnchangedFlowFactsAndLambdaInputs() {
    compile(
        context -> {
          Harness harness = context.harness(new Handler() {});
          context.scope(
              harness.checks(),
              context.success(harness.checks(), context.signature()),
              context.input(Nullness.NONNULL));
          Type.MethodType persistent = context.nullableRootSignature();
          map(harness.checks(), "inferredTypeVarNullabilityForGenericCalls")
              .put(context.tree(), context.success(harness.checks(), persistent));
          Object scope = field(harness.checks(), "dataflowInferenceScope");
          Map<Tree, Object> parent = new LinkedHashMap<>();
          collectors(scope).add(parent);
          when(harness.flow().isRunning(any(TreePath.class), same(context.state().context)))
              .thenReturn(true);
          when(harness
                  .flow()
                  .getNullnessFromRunning(any(TreePath.class), same(context.state().context)))
              .thenReturn(Nullness.NULLABLE);

          assertThat(
                  context.lookup(
                      harness.checks(),
                      "getResolvedCallSignature",
                      context.state().getPath(),
                      true))
              .isSameInstanceAs(persistent);
          assertThat(parent).isEmpty();
          assertThat(collectors(scope)).containsExactly(parent);

          when(harness
                  .flow()
                  .getNullnessFromRunning(any(TreePath.class), same(context.state().context)))
              .thenReturn(Nullness.NONNULL);
          setField(
              harness.checks(),
              "lambdaParameterTypesForInference",
              Map.of(
                  ASTHelpers.getSymbol(context.argument()),
                  ASTHelpers.getType(context.argument())));
          assertThat(
                  context.lookup(
                      harness.checks(),
                      "getResolvedCallSignature",
                      context.state().getPath(),
                      true))
              .isSameInstanceAs(persistent);
          assertThat(parent).isEmpty();
          assertThat(collectors(scope)).containsExactly(parent);
          // A symbolic-context mismatch rejects the outcome before reading its flow observations.
          verify(harness.flow(), times(1))
              .getNullnessFromRunning(any(TreePath.class), same(context.state().context));
        });
  }

  @Test
  public void missingSnapshotPartialAndFailureResultsStillResolveReceiver() {
    compile(
        context -> {
          Harness harness = context.harness(new Handler() {});
          doReturn(context.receiverType())
              .when(harness.checks())
              .getTreeType(same(context.receiver()), any(VisitorState.class), anyBoolean());
          ConstraintSolver.Solution incomplete =
              new ConstraintSolver.Solution(
                  Map.of(),
                  Set.of(
                      new ConstraintSolver.InferenceVariable(
                          ASTHelpers.getSymbol(context.tree()).getTypeParameters().get(0),
                          context.tree())),
                  Set.of());
          List<Object> results =
              List.of(
                  construct(
                      "InferenceSuccess",
                      new ConstraintSolver.Solution(Map.of(), Set.of(), Set.of()),
                      Map.of(),
                      Map.of()),
                  construct("InferencePartial", incomplete, Map.of(), Map.of()),
                  construct("InferenceFailure", "test failure"));
          for (Object result : results) {
            map(harness.checks(), "inferredTypeVarNullabilityForGenericCalls")
                .put(context.tree(), result);
            Type.MethodType signature =
                context.lookup(
                    harness.checks(), "getResolvedCallSignature", context.state().getPath(), false);
            assertThat(
                    TypeSubstitutionUtils.hasNullableRoot(
                        signature.getReturnType().getTypeArguments().head, context.config()))
                .isTrue();
          }
          verify(harness.checks(), times(3))
              .getTreeType(same(context.receiver()), any(VisitorState.class), anyBoolean());
          verifyNoInteractions(harness.flow());
        });
  }

  /**
   * Runs assertions once on the same compiler-attributed invocation, not lookalike source sites.
   */
  private void compile(Consumer<Context> assertions) {
    ASSERTIONS.set(assertions);
    CHECKED.set(0);
    try {
      CompilationTestHelper.newInstance(CallAssertions.class, getClass())
          .setArgs(JSpecifyJavacConfig.withJSpecifyModeArgs(List.of()))
          .addSourceLines(
              "Test.java",
              """
              package com.uber;
              import java.util.List;
              import org.jspecify.annotations.NullMarked;
              import org.jspecify.annotations.Nullable;
              @NullMarked
              class Test {
                static class Box<T extends @Nullable Object> {
                  <U> List<T> list(U ignored) { throw new AssertionError(); }
                }
                static Box<@Nullable String> receiver(@Nullable String value) {
                  throw new AssertionError();
                }
                void test(@Nullable String value) {
                  receiver(value).list(0);
                }
              }
              """)
          .doTest();
      assertThat(CHECKED.get()).isEqualTo(1);
    } finally {
      ASSERTIONS.remove();
      CHECKED.remove();
    }
  }

  /** Supplies actual javac symbols, annotated receiver types, and source paths to each proof. */
  @BugPattern(summary = "Checks cached call signature reuse", severity = SUGGESTION)
  public static final class CallAssertions extends BugChecker
      implements BugChecker.MethodInvocationTreeMatcher {

    /**
     * Builds a completed annotated signature and runs the proof only at the fixture's list call.
     */
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
      Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(tree);
      if (!symbol.getSimpleName().contentEquals("list")) {
        return NO_MATCH;
      }
      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:AnnotatedPackages", "com.uber",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:JSpecifyExperimental", "true")))
              .getConfig();
      MethodInvocationTree receiver =
          (MethodInvocationTree) ((MemberSelectTree) tree.getMethodSelect()).getExpression();
      Type receiverType = ASTHelpers.getSymbol(receiver).getReturnType();
      Type.MethodType declaration =
          TypeSubstitutionUtils.memberType(state.getTypes(), receiverType, symbol, config)
              .asMethodType();
      Type.MethodType attributed = ASTHelpers.getType(tree.getMethodSelect()).asMethodType();
      Type.MethodType completed =
          new Type.MethodType(
              attributed.argtypes, declaration.restype, attributed.thrown, attributed.tsym);
      Consumer<Context> assertions = ASSERTIONS.get();
      assertThat(assertions).isNotNull();
      assertions.accept(new Context(state, tree, receiver, receiverType, completed, config));
      CHECKED.set(CHECKED.get() + 1);
      return NO_MATCH;
    }
  }

  /** Test-only collaborators; no production counters or changes to publication are needed. */
  private record Harness(GenericsChecks checks, AccessPathNullnessAnalysis flow) {}

  /** Builds cache-boundary fixtures without running inference or a second dataflow analysis. */
  private record Context(
      VisitorState state,
      MethodInvocationTree tree,
      MethodInvocationTree receiver,
      Type receiverType,
      Type.MethodType signature,
      Config config) {
    /** Makes any attempted receiver re-resolution fail rather than relying on elapsed time. */
    private Harness harness(Handler handler) {
      NullAway analysis = mock(NullAway.class);
      AccessPathNullnessAnalysis flow = mock(AccessPathNullnessAnalysis.class);
      when(analysis.getNullnessAnalysis(any(VisitorState.class))).thenReturn(flow);
      GenericsChecks checks = spy(new GenericsChecks(analysis, config, handler));
      doThrow(new AssertionError("A completed call snapshot must bypass receiver resolution"))
          .when(checks)
          .getTreeType(same(receiver), any(VisitorState.class), anyBoolean());
      return new Harness(checks, flow);
    }

    /** Creates a distinguishable modeled/persistent signature without mutating the cached one. */
    private Type.MethodType nullableRootSignature() {
      return new Type.MethodType(
          signature.argtypes,
          TypeSubstitutionUtils.typeWithAnnot(
              signature.restype, GenericsChecks.getSyntheticNullableAnnotType(state)),
          signature.thrown,
          signature.tsym);
    }

    /**
     * Seeds the private completed-call representation using this exact compiler-attributed tree.
     */
    private Object success(GenericsChecks checks, Type.MethodType completed) {
      Object site = invoke(checks, "callSite", new Class<?>[] {ExpressionTree.class}, tree);
      Object call = construct("InferenceCall", site, completed, completed.getReturnType());
      return construct(
          "InferenceSuccess",
          new ConstraintSolver.Solution(Map.of(), Set.of(), Set.of()),
          Map.of(),
          Map.of(tree, call));
    }

    /** Identifies the scalar input on which the annotated receiver is allowed to depend. */
    private ExpressionTree argument() {
      return receiver.getArguments().get(0);
    }

    /** Retains a real expression path and declared type for running-flow validation. */
    private Object input(Nullness nullness) {
      return construct(
          "DataflowInferenceInput",
          TreePath.getPath(state.getPath().getCompilationUnit(), argument()),
          ASTHelpers.getSymbol(argument()).type,
          nullness);
    }

    /** Installs a scoped outcome with matching empty symbolic-lambda environments. */
    private Object scope(GenericsChecks checks, Object success, Object input) {
      Tree enclosing =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath()).getLeaf();
      Object scope = construct("DataflowInferenceScope", enclosing);
      Object outcome =
          construct(
              "DataflowInferenceOutcome", success, Map.of(argument(), input), Map.of(), Map.of());
      map(scope, "outcomes").put(tree, outcome);
      setField(checks, "dataflowInferenceScope", scope);
      return scope;
    }

    /** Calls either signature consumer with a real source tree, including its null-path API. */
    private Type.MethodType lookup(
        GenericsChecks checks, String name, @Nullable TreePath path, boolean fromDataflow) {
      return (Type.MethodType)
          invoke(
              checks,
              name,
              new Class<?>[] {
                name.equals("getResolvedCallSignature") ? ExpressionTree.class : Tree.class,
                TreePath.class,
                VisitorState.class,
                boolean.class
              },
              tree,
              path,
              state,
              fromDataflow);
    }
  }

  /** Reads the inference collector stack while leaving the production representation private. */
  @SuppressWarnings("unchecked")
  private static List<Map<Tree, Object>> collectors(Object scope) {
    return (List<Map<Tree, Object>>) field(scope, "activeInputs");
  }

  /** Reads a private cache with an opaque value type so private records need not become public. */
  @SuppressWarnings("unchecked")
  private static Map<Tree, Object> map(Object owner, String name) {
    return (Map<Tree, Object>) field(owner, name);
  }

  /** Reads a private instance field without replacing any final cache objects. */
  private static Object field(Object owner, String name) {
    try {
      Field field = owner.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(owner);
    } catch (ReflectiveOperationException exception) {
      throw new LinkageError("Could not read inference field " + name, exception);
    }
  }

  /** Installs only test-owned scoped state and symbolic lambda maps. */
  private static void setField(Object owner, String name, Object value) {
    try {
      Field field = owner.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(owner, value);
    } catch (ReflectiveOperationException exception) {
      throw new LinkageError("Could not set inference field " + name, exception);
    }
  }

  /** Constructs the single-constructor private snapshots used at the cache boundary. */
  private static Object construct(String name, Object... arguments) {
    try {
      for (Class<?> nested : GenericsChecks.class.getDeclaredClasses()) {
        if (nested.getSimpleName().equals(name)) {
          Constructor<?>[] constructors = nested.getDeclaredConstructors();
          assertThat(constructors).hasLength(1);
          constructors[0].setAccessible(true);
          return constructors[0].newInstance(arguments);
        }
      }
      throw new AssertionError("Missing private inference class " + name);
    } catch (ReflectiveOperationException exception) {
      throw new LinkageError("Could not construct inference snapshot " + name, exception);
    }
  }

  /** Invokes a private production stage while preserving assertion failures from its body. */
  private static Object invoke(
      GenericsChecks checks, String name, Class<?>[] parameters, Object... arguments) {
    try {
      Method method = GenericsChecks.class.getDeclaredMethod(name, parameters);
      method.setAccessible(true);
      return method.invoke(checks, arguments);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Error error) {
        throw error;
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new LinkageError("Could not execute inference stage " + name, exception);
    } catch (ReflectiveOperationException exception) {
      throw new LinkageError("Could not invoke inference stage " + name, exception);
    }
  }
}
