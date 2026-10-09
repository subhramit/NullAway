# Patch 007: Preserve fixed bounds through wildcard inference

Apply after the complete stack through 006:

```text
01 → 02 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006 → 007
```

Or substitute combined 03 for 01+02. This is an incremental correctness patch, not a replacement for 005 or a wholesale import of open PR #1834.

Addresses #1947 and the acceptance case added in https://github.com/uber/NullAway/issues/1932#issuecomment-6080536601. Deferred `? extends U` obligations use complete root-inference evidence to reject a nullable-admitting fixed variable when `U`'s bound excludes null. Dedicated root evidence respects projected formal parameters; structured evidence remains separate. A distinct exception attributes deferred failures to their owning call and preserves existing contextual scalar diagnostic policy.

Adds eleven source-level regression methods and three direct compiler-backed solver methods, including both independently reproduced review findings. Existing test expectations are unchanged. Final uncached module suite: **1,201 tests, zero failures/errors, 21 skipped**; self-check passes. See `ISSUE-1947-VALIDATION.md` for acceptance, review, and scope.

```diff
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
index bae4fefa..27522c15 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
@@ -156,6 +156,17 @@ public interface ConstraintSolver {
     }
   }
 
+  /**
+   * A fixed-variable bound cannot satisfy a non-null wildcard inference requirement. Unlike a
+   * contextual scalar contradiction, this deferred proof belongs to the wildcard's own call site.
+   */
+  class NonNullWildcardBoundViolationException extends UnsatisfiableConstraintsException {
+    /** Records the variable whose non-null bound conflicts with the actual's fixed bound. */
+    public NonNullWildcardBoundViolationException(InferenceVariable variable) {
+      super(variable.typeVariable(), true, variable.site());
+    }
+  }
+
   /**
    * Indicates a proven nested-nullness violation of a declaration upper bound for which no safe
    * annotation source can preserve ordinary compatibility diagnostics. This includes cyclic
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
index c590b75f..f3abe6a4 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
@@ -119,6 +119,11 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     /** Structural fingerprints used to deduplicate {@link #lowerBoundTypes}. */
     final Set<String> lowerBoundKeys = new LinkedHashSet<>();
 
+    /** Fixed lower uses that constrain this variable's root, not an annotated projection of it. */
+    final List<Type> rootFixedLowerBounds = new ArrayList<>();
+
+    final Set<String> rootFixedLowerBoundKeys = new LinkedHashSet<>();
+
     /** Fixed type-variable lower uses retained as declaration-diagnostic provenance. */
     final List<Type> fixedTypeVariableLowerBounds = new ArrayList<>();
 
@@ -146,6 +151,12 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
    */
   private final Map<InferenceVariable, VarState> vars = new LinkedHashMap<>();
 
+  /** Deferred containment obligations, checked after every call's lower bounds are available. */
+  private final Set<NonNullWildcardRequirement> nonNullWildcardRequirements = new LinkedHashSet<>();
+
+  /** A wildcard's actual upper bound must fit an inferred variable whose bound excludes null. */
+  private record NonNullWildcardRequirement(Type actual, InferenceVariable required) {}
+
   /** Incremented whenever a variable, structured bound, or variable edge is added. */
   private int structuredConstraintVersion = 0;
 
@@ -437,8 +448,17 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
           case UNBOUND, EXTENDS -> {
             Type subtypeUpperBound =
                 GenericsUtils.effectiveWildcardUpperBound(subtypeTypeArg, state, config, handler);
-            subtypeUpperBound.accept(
-                this, GenericsUtils.wildcardUpperBound(supertypeWildcard, state, config, handler));
+            Type supertypeUpperBound =
+                GenericsUtils.wildcardUpperBound(supertypeWildcard, state, config, handler);
+            InferenceVariable required = inferenceVariableForUse(supertypeUpperBound);
+            if (supertypeWildcard.kind == BoundKind.EXTENDS
+                && !(subtypeTypeArg instanceof CapturedType)
+                && required != null
+                && !getState(required).nullableAllowed) {
+              nonNullWildcardRequirements.add(
+                  new NonNullWildcardRequirement(subtypeUpperBound, required));
+            }
+            subtypeUpperBound.accept(this, supertypeUpperBound);
           }
           case SUPER -> {
             Type supertypeLowerBound = castToNonNull(supertypeWildcard.getSuperBound());
@@ -493,6 +513,7 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   @Override
   public Solution solve() throws UnsatisfiableConstraintsException {
     prepareStructuredConstraints();
+    constrainNonNullWildcardRequirements();
 
     /* ---------- work-list propagation of nullability ---------- */
     Deque<InferenceVariable> work = new ArrayDeque<>();
@@ -684,6 +705,92 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     }
   }
 
+  /**
+   * Applies wildcard containment obligations after nested calls have contributed their evidence.
+   * A fixed variable with a nullable bound remains symbolic for nullable-accepting calls, but no
+   * instantiation of a non-null-bounded wildcard variable can contain all its possible values.
+   */
+  private void constrainNonNullWildcardRequirements() {
+    for (NonNullWildcardRequirement requirement : nonNullWildcardRequirements) {
+      if (hasNullableFixedLowerBound(requirement.actual())) {
+        throw new NonNullWildcardBoundViolationException(requirement.required());
+      }
+    }
+  }
+
+  /**
+   * Finds explicitly nullable fixed-variable evidence through root-inference subtype edges. Using
+   * structural edges here would ignore occurrence-level non-null projections. The graph is complete
+   * before this walk, so the result does not depend on whether an outer call was visited first.
+   */
+  private boolean hasNullableFixedLowerBound(Type actual) {
+    if (Nullness.hasNonNullAnnotation(actual.getAnnotationMirrors().stream(), config)) {
+      return false;
+    }
+    InferenceVariable variable = inferenceVariableForUse(actual);
+    if (variable == null) {
+      return actual instanceof TypeVar
+          && !(actual instanceof CapturedType)
+          && inferenceVariableForStructure(actual) == null
+          && explicitlyNullableBound(actual, new LinkedHashSet<>());
+    }
+    Deque<InferenceVariable> work = new ArrayDeque<>();
+    Set<InferenceVariable> visited = new LinkedHashSet<>();
+    work.add(variable);
+    visited.add(variable);
+    while (!work.isEmpty()) {
+      VarState current = castToNonNull(vars.get(work.removeFirst()));
+      for (Type lower : current.rootFixedLowerBounds) {
+        if (lower instanceof TypeVar
+            && !(lower instanceof CapturedType)
+            && inferenceVariableForStructure(lower) == null
+            && !Nullness.hasNonNullAnnotation(lower.getAnnotationMirrors().stream(), config)
+            && explicitlyNullableBound(lower, new LinkedHashSet<>())) {
+          return true;
+        }
+      }
+      for (InferenceVariable subtype : current.subtypes) {
+        if (visited.add(subtype)) {
+          work.add(subtype);
+        }
+      }
+    }
+    return false;
+  }
+
+  /**
+   * Reads explicit nullable-bound evidence without treating an unmarked default as nullable. Root
+   * projections take precedence over inherited bounds; an intersection permits null only if all
+   * components do. Cyclic bounds provide no additional evidence.
+   */
+  private boolean explicitlyNullableBound(Type type, Set<Element> visiting) {
+    if (Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)) {
+      return false;
+    }
+    if (isKnownNullable(type)) {
+      return true;
+    }
+    if (type instanceof Type.IntersectionClassType intersection) {
+      for (TypeMirror component : intersection.getBounds()) {
+        if (!explicitlyNullableBound((Type) component, visiting)) {
+          return false;
+        }
+      }
+      return true;
+    }
+    if (!(type instanceof TypeVar variable)
+        || type instanceof CapturedType
+        || !visiting.add(variable.asElement())) {
+      return false;
+    }
+    try {
+      return hasNullableUpperBoundOverride(variable.asElement())
+          || explicitlyNullableBound(variable.getUpperBound(), visiting);
+    } finally {
+      visiting.remove(variable.asElement());
+    }
+  }
+
   /** Applies solved root nullness without imposing a default on symbolic fixed variables. */
   private Type inferredRoot(Type source, VarState st) {
     Type annotated = markNestedTypesNonNull(source);
@@ -1932,6 +2039,17 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
       recordStructuredBound(sStructure, t, false);
     }
 
+    if (tVar != null
+        && sStructure == null
+        && s instanceof TypeVar
+        && !(s instanceof CapturedType)) {
+      VarState target = getState(tVar);
+      if (target.rootFixedLowerBoundKeys.add(structuredTypeKey(s))) {
+        target.rootFixedLowerBounds.add(s);
+        structuredConstraintVersion++;
+      }
+    }
+
     /* top-level nullability rules */
     if (isKnownNonNull(t) && sVar != null) {
       updateNullness(sVar, NullnessState.NONNULL);
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
index fa8ce05f..cd6469c9 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
@@ -59,6 +59,7 @@ import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
 import com.uber.nullaway.dataflow.EnclosingEnvironmentNullness;
 import com.uber.nullaway.dataflow.NullnessStore;
 import com.uber.nullaway.generics.ConstraintSolver.NestedUpperBoundViolationException;
+import com.uber.nullaway.generics.ConstraintSolver.NonNullWildcardBoundViolationException;
 import com.uber.nullaway.generics.ConstraintSolver.Solution;
 import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
 import com.uber.nullaway.generics.GenericsUtils.MethodRefTypeRelationKind;
@@ -1609,6 +1610,11 @@ public final class GenericsChecks {
       return failureResult;
     } catch (UnsatisfiableConstraintsException e) {
       String inferenceFailureMessage = inferenceFailureMessage(e);
+      Tree reportingSite =
+          e instanceof NonNullWildcardBoundViolationException
+              ? castToNonNull(e.getInferenceSite())
+              : callTree;
+      VisitorState reportingState = stateForInferenceDiagnostic(reportingSite, state);
       if (config.warnOnGenericInferenceFailure()
           && callsWithReportedInferenceFailures.add(
               e.getInferenceSite() != null ? e.getInferenceSite() : callTree)) {
@@ -1618,14 +1624,14 @@ public final class GenericsChecks {
                 ErrorMessage.MessageTypes.GENERIC_INFERENCE_FAILURE, inferenceFailureMessage);
         state.reportMatch(
             errorBuilder.createErrorDescription(
-                errorMessage, analysis.buildDescription(callTree), state, null));
+                errorMessage, analysis.buildDescription(reportingSite), reportingState, null));
       }
       InferenceFailure failureResult = new InferenceFailure(inferenceFailureMessage);
       if (okToCacheInferenceResult(calledFromDataflow)) {
         invalidateMethodReferenceResults(inferenceCacheState);
-        // Contextual scalar contradictions complete only this root problem. Nested calls must
-        // still infer their own arguments and contexts independently.
-        inferredTypeVarNullabilityForGenericCalls.put(callTree, failureResult);
+        // A deferred wildcard failure completes only its owning site; contextual scalar failures
+        // retain the established root cache policy. Independent nested calls must still be checked.
+        inferredTypeVarNullabilityForGenericCalls.put(reportingSite, failureResult);
       }
       return failureResult;
     }
diff --git a/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java b/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java
index af95de64..13e4b7f9 100644
--- a/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java
@@ -26,6 +26,7 @@ import com.uber.nullaway.Config;
 import com.uber.nullaway.NullAway;
 import com.uber.nullaway.Nullness;
 import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
+import com.uber.nullaway.generics.ConstraintSolver.NonNullWildcardBoundViolationException;
 import com.uber.nullaway.generics.ConstraintSolver.Solution;
 import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
 import com.uber.nullaway.handlers.Handler;
@@ -141,6 +142,40 @@ public class ConstraintSolverImplTests {
         "missingShapes", "<T extends @Nullable Object> void inference(String evidence) {}");
   }
 
+  @Test
+  public void nonNullWildcardObligationIsIndependentOfEvidenceOrderAndRepeatedSolves() {
+    runFixture(
+        "wildcardEvidenceOrder",
+        """
+        <E extends @Nullable Object, R extends @Nullable Object, U> void inference(
+            Box<E> inner, Box<R> outer, Box<? extends U> required,
+            OuterT fixed, Object shape) {}
+        """);
+  }
+
+  @Test
+  public void annotatedOccurrencesBlockNonNullWildcardRootEvidence() {
+    runFixture(
+        "wildcardProjectionBarriers",
+        """
+        <E, R extends @Nullable Object, U> void inference(
+            Box<E> inner, Box<R> outer, Box<? extends U> required,
+            OuterT fixed, @Nullable E nullableDestination, @NonNull OuterT nonNullSource,
+            Object shape) {}
+        """);
+  }
+
+  @Test
+  public void modeledUnregisteredFixedSourceProvesNonNullWildcardViolation() {
+    runFixture(
+        "wildcardModeledFixedSource",
+        """
+        static <S, E extends @Nullable Object, R extends @Nullable Object, U> void inference(
+            Box<E> inner, Box<R> outer, Box<? extends U> required,
+            S fixed, @NonNull S nonNullSource, Object shape) {}
+        """);
+  }
+
   /** Compiles one marker-field scenario and verifies its assertions ran exactly once. */
   private void runFixture(String marker, String members) {
     EXECUTED_MARKERS.get().clear();
@@ -154,6 +189,7 @@ public class ConstraintSolverImplTests {
               """
               package com.uber;
               import java.util.function.Supplier;
+              import org.jspecify.annotations.NonNull;
               import org.jspecify.annotations.Nullable;
               class Test<OuterT extends @Nullable Object> {
                 static class Box<E extends @Nullable Object> {}
@@ -188,7 +224,10 @@ public class ConstraintSolverImplTests {
             "sourceIsolation",
             "lateEvidence",
             "modeledBound",
-            "missingShapes");
+            "missingShapes",
+            "wildcardEvidenceOrder",
+            "wildcardProjectionBarriers",
+            "wildcardModeledFixedSource");
 
     @Override
     public Description matchVariable(VariableTree tree, VisitorState state) {
@@ -208,6 +247,9 @@ public class ConstraintSolverImplTests {
         case "lateEvidence" -> checkLateEvidence(context);
         case "modeledBound" -> checkModeledBound(context);
         case "missingShapes" -> checkMissingShapes(context);
+        case "wildcardEvidenceOrder" -> checkWildcardEvidenceOrder(context);
+        case "wildcardProjectionBarriers" -> checkWildcardProjectionBarriers(context);
+        case "wildcardModeledFixedSource" -> checkWildcardModeledFixedSource(context);
         default -> throw new AssertionError("Unhandled marker: " + marker);
       }
       EXECUTED_MARKERS.get().add(marker);
@@ -595,6 +637,190 @@ public class ConstraintSolverImplTests {
       assertThat(solution.isCompleteForSite(context.siteA())).isFalse();
     }
 
+    /** Registers two inner variables at site A and the non-null wildcard variable at site B. */
+    private static Map<Element, Type.TypeVar> registerWildcardChain(
+        TestContext context, ConstraintSolver solver, int firstIndex, Type shape) {
+      Element inner = context.variable(firstIndex);
+      Element outer = context.variable(firstIndex + 1);
+      Element required = context.variable(firstIndex + 2);
+      Map<Element, Type.TypeVar> innerVariables =
+          solver.registerInferenceVariables(
+              context.siteA(),
+              List.of(inner, outer),
+              Map.of(),
+              Set.of(inner, outer),
+              Map.of(inner, shape, outer, shape));
+      Type.TypeVar requiredVariable =
+          solver.registerInferenceVariables(
+                  context.siteB(),
+                  List.of(required),
+                  Map.of(),
+                  Set.of(required),
+                  Map.of(required, shape))
+              .get(required);
+      return Map.of(
+          inner, innerVariables.get(inner),
+          outer, innerVariables.get(outer),
+          required, requiredVariable);
+    }
+
+    /** Substitutes registered occurrences into attributed fixture types, retaining projections. */
+    private static Type wildcardFixtureType(
+        TestContext context, int parameter, Map<Element, ? extends Type> replacements) {
+      return TypeSubstitutionUtils.substituteTypeVariables(
+          context.parameter(parameter), replacements, context.state().getTypes(), context.config());
+    }
+
+    /** Requires the deferred exception, not a contextual scalar contradiction, on every solve. */
+    private static void assertWildcardFailure(
+        TestContext context, ConstraintSolver solver, Element required) {
+      for (int attempt = 0; attempt < 2; attempt++) {
+        NonNullWildcardBoundViolationException exception =
+            assertThrows(NonNullWildcardBoundViolationException.class, solver::solve);
+        assertThat(exception.getClass()).isEqualTo(NonNullWildcardBoundViolationException.class);
+        assertThat(exception.getTypeVariable()).isSameInstanceAs(required);
+        assertThat(exception.getInferenceSite()).isSameInstanceAs(context.siteB());
+        assertThat(exception.isCausedByNonNullUpperBound()).isTrue();
+      }
+    }
+
+    /** Tests both obligation orders, both lower/edge orders, and evidence added after a solve. */
+    private static void checkWildcardEvidenceOrder(TestContext context) {
+      Element inner = context.variable(0);
+      Element outer = context.variable(1);
+      Element required = context.variable(2);
+      for (boolean obligationFirst : List.of(false, true)) {
+        for (boolean lowerFirst : List.of(false, true)) {
+          ConstraintSolver solver = newSolver(context);
+          Map<Element, Type.TypeVar> fresh =
+              registerWildcardChain(context, solver, 0, context.parameter(4));
+          Type actual = wildcardFixtureType(context, 1, fresh);
+          Type formal = wildcardFixtureType(context, 2, fresh);
+          if (obligationFirst) {
+            solver.addSubtypeConstraint(actual, formal, false);
+          }
+          if (lowerFirst) {
+            solver.addSubtypeConstraint(context.parameter(3), fresh.get(inner), false);
+          }
+          solver.addSubtypeConstraint(fresh.get(inner), fresh.get(outer), false);
+          if (!lowerFirst) {
+            solver.addSubtypeConstraint(context.parameter(3), fresh.get(inner), false);
+          }
+          if (!obligationFirst) {
+            solver.addSubtypeConstraint(actual, formal, false);
+          }
+          assertWildcardFailure(context, solver, required);
+        }
+      }
+      ConstraintSolver late = newSolver(context);
+      Map<Element, Type.TypeVar> fresh =
+          registerWildcardChain(context, late, 0, context.parameter(4));
+      late.addSubtypeConstraint(
+          wildcardFixtureType(context, 1, fresh), wildcardFixtureType(context, 2, fresh), false);
+      late.addSubtypeConstraint(fresh.get(inner), fresh.get(outer), false);
+      for (int attempt = 0; attempt < 2; attempt++) {
+        assertComplete(
+            late.solve(),
+            new InferenceVariable(inner, context.siteA()),
+            new InferenceVariable(outer, context.siteA()),
+            new InferenceVariable(required, context.siteB()));
+      }
+      late.addSubtypeConstraint(context.parameter(3), fresh.get(inner), false);
+      assertWildcardFailure(context, late, required);
+    }
+
+    /** Keeps structural evidence while blocking scalar provenance at annotated occurrences. */
+    private static void checkWildcardProjectionBarriers(TestContext context) {
+      Element inner = context.variable(0);
+      Element outer = context.variable(1);
+      Element required = context.variable(2);
+      List<InferenceVariable> keys =
+          List.of(
+              new InferenceVariable(inner, context.siteA()),
+              new InferenceVariable(outer, context.siteA()),
+              new InferenceVariable(required, context.siteB()));
+      for (boolean nullableDestination : List.of(true, false)) {
+        ConstraintSolver solver = newSolver(context);
+        Map<Element, Type.TypeVar> fresh =
+            registerWildcardChain(context, solver, 0, context.parameter(6));
+        solver.addSubtypeConstraint(
+            wildcardFixtureType(context, 1, fresh), wildcardFixtureType(context, 2, fresh), false);
+        solver.addSubtypeConstraint(fresh.get(inner), fresh.get(outer), false);
+        if (nullableDestination) {
+          Type destination = wildcardFixtureType(context, 4, fresh);
+          assertNullness(destination, true, context);
+          solver.addSubtypeConstraint(context.parameter(3), destination, false);
+        } else {
+          assertNullness(context.parameter(5), false, context);
+          solver.addSubtypeConstraint(context.parameter(5), fresh.get(inner), false);
+        }
+        Solution solution = solver.solve();
+        assertThat(solution.inferredTypes().keySet()).containsExactlyElementsIn(keys);
+        // Structural validation excludes root occurrence checks. Both explicit projections allow
+        // the non-null Java shape, without leaking the fixed variable's nullable bound into E.
+        assertComplete(solution, keys.toArray(new InferenceVariable[0]));
+        for (InferenceVariable key : keys) {
+          Type result = solution.inferredTypes().get(key);
+          assertThat(result.tsym).isSameInstanceAs(context.parameter(6).tsym);
+          assertNullness(result, false, context);
+          assertNoFreshVariables(result, List.copyOf(fresh.values()));
+        }
+      }
+    }
+
+    /** Checks a real unregistered method variable with model, no-model, and source projections. */
+    private static void checkWildcardModeledFixedSource(TestContext context) {
+      Element fixed = context.variable(0);
+      Element inner = context.variable(1);
+      Element outer = context.variable(2);
+      Element required = context.variable(3);
+      Handler modeledHandler =
+          new Handler() {
+            @Override
+            public boolean onOverrideMethodTypeVariableUpperBound(
+                Symbol.MethodSymbol methodSymbol, int index, VisitorState state) {
+              return methodSymbol.equals(context.declaration()) && index == 0;
+            }
+          };
+      assertThat(context.parameter(3).tsym).isSameInstanceAs(fixed);
+      assertThat(context.parameter(3).getAnnotationMirrors()).isEmpty();
+      for (boolean transitive : List.of(false, true)) {
+        for (int control = 0; control < 3; control++) {
+          ConstraintSolver solver =
+              control == 1 ? newSolver(context) : newSolver(context, modeledHandler);
+          Map<Element, Type.TypeVar> fresh =
+              registerWildcardChain(context, solver, 1, context.parameter(5));
+          assertThat(fresh).doesNotContainKey(fixed);
+          Type source = context.parameter(control == 2 ? 4 : 3);
+          Type formal = wildcardFixtureType(context, 2, fresh);
+          if (transitive) {
+            solver.addSubtypeConstraint(
+                wildcardFixtureType(context, 1, fresh), formal, false);
+            solver.addSubtypeConstraint(fresh.get(inner), fresh.get(outer), false);
+            solver.addSubtypeConstraint(source, fresh.get(inner), false);
+          } else {
+            Type actual = wildcardFixtureType(context, 0, Map.of(inner, source));
+            solver.addSubtypeConstraint(actual, formal, false);
+          }
+          if (control == 0) {
+            assertWildcardFailure(context, solver, required);
+          } else {
+            Solution solution = solver.solve();
+            assertComplete(
+                solution,
+                new InferenceVariable(inner, context.siteA()),
+                new InferenceVariable(outer, context.siteA()),
+                new InferenceVariable(required, context.siteB()));
+            for (Type result : solution.inferredTypes().values()) {
+              assertThat(result.tsym).isSameInstanceAs(context.parameter(5).tsym);
+              assertNullness(result, false, context);
+              assertNoFreshVariables(result, List.copyOf(fresh.values()));
+            }
+          }
+        }
+      }
+    }
+
     /** Checks exact result coverage and both independent certification status sets. */
     private static void assertComplete(Solution solution, InferenceVariable... variables) {
       assertThat(solution.inferredTypes().keySet()).containsExactlyElementsIn(List.of(variables));
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/WildcardTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/WildcardTests.java
index 16c06a82..37050178 100644
--- a/nullaway/src/test/java/com/uber/nullaway/jspecify/WildcardTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/jspecify/WildcardTests.java
@@ -1757,6 +1757,383 @@ public class WildcardTests extends NullAwayTestsBase {
         .doTest();
   }
 
+  @Test
+  public void issue1947NullableBoundThroughNestedGenericCalls() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.ArrayList;
+            import java.util.List;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E extends @Nullable Object> Box<E> id(Box<E> b) { return b; }
+              static <U> void requireNonNull(Box<? extends U> b) {}
+              static <T extends @Nullable Object> void boxes(Box<T> box) {
+                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
+                requireNonNull(box);
+                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
+                requireNonNull(id(box));
+              }
+              static <T extends @Nullable Object> List<T> direct(List<T> list) {
+                // BUG: Diagnostic contains: inference failure: type variable E is constrained to be @Nullable
+                return List.copyOf(list);
+              }
+              static <T extends @Nullable Object> List<T> nested(List<T> list) {
+                // BUG: Diagnostic contains: inference failure: type variable E is constrained to be @Nullable
+                return List.copyOf(new ArrayList<>(list));
+              }
+              public static void main(String[] args) {
+                List<@Nullable String> in = new ArrayList<>();
+                in.add(null);
+                nested(in);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947NonNullBoundAcceptedDirectAndNested() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.ArrayList;
+            import java.util.List;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <U> void requireNonNull(Box<? extends U> box) {}
+              static <E extends @Nullable Object> List<E> id(List<E> list) { return list; }
+              static <T> void boxes(Box<T> box) {
+                requireNonNull(box);
+                requireNonNull(id(box));
+                requireNonNull(id(id(box)));
+              }
+              static <T> List<T> direct(List<T> list) {
+                return List.copyOf(list);
+              }
+              static <T> List<T> nested(List<T> list) {
+                return List.copyOf(new ArrayList<>(new ArrayList<>(list)));
+              }
+              static <T> List<T> mixedNested(List<T> list) {
+                return List.copyOf(id(new ArrayList<>(id(list))));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947NullableAcceptingWildcardPreservesParametricType() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.ArrayList;
+            import java.util.List;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <U extends @Nullable Object> Box<U> copy(Box<? extends U> box) {
+                return new Box<>();
+              }
+              static <U extends @Nullable Object> List<U> copyList(List<? extends U> list) {
+                return new ArrayList<>(list);
+              }
+              static <T extends @Nullable Object> void preserve(Box<T> box, List<T> list) {
+                // Invariant targets require T itself, not @Nullable T or its Object bound.
+                Box<T> direct = copy(box);
+                Box<T> nested = copy(id(box));
+                Box<T> deeper = copy(id(id(box)));
+                List<T> directList = copyList(list);
+                List<T> nestedList = copyList(new ArrayList<>(list));
+                List<T> deeperList = copyList(new ArrayList<>(new ArrayList<>(list)));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947ExplicitTypeVariableProjections() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.ArrayList;
+            import java.util.List;
+            import org.jspecify.annotations.NonNull;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <U> void requireNonNull(Box<? extends U> box) {}
+              static <T extends @Nullable Object> void nonNullProjection(
+                  Box<@NonNull T> box, List<@NonNull T> list) {
+                requireNonNull(box);
+                requireNonNull(id(box));
+                List<@NonNull T> direct = List.copyOf(list);
+                List<@NonNull T> nested = List.copyOf(new ArrayList<>(list));
+              }
+              static <T extends @Nullable Object> void nullableProjection(
+                  Box<@Nullable T> box, List<@Nullable T> list) {
+                // BUG: Diagnostic contains: inference failure
+                requireNonNull(box);
+                // BUG: Diagnostic contains: inference failure
+                requireNonNull(id(box));
+                // BUG: Diagnostic contains: inference failure
+                List<@Nullable T> direct = List.copyOf(list);
+                // BUG: Diagnostic contains: inference failure
+                List<@Nullable T> nested = List.copyOf(new ArrayList<>(list));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947NullableBoundChainThroughWildcard() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <U> void requireNonNull(Box<? extends U> box) {}
+              static <T extends @Nullable Object, S extends T> void nullableChain(Box<S> box) {
+                // BUG: Diagnostic contains: inference failure
+                requireNonNull(box);
+                // BUG: Diagnostic contains: inference failure
+                requireNonNull(id(box));
+              }
+              static <T, S extends T> void nonNullChain(Box<S> box) {
+                requireNonNull(box);
+                requireNonNull(id(box));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947IntersectionBoundNullabilityThroughWildcard() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <U> void requireNonNull(Box<? extends U> box) {}
+              static <T extends @Nullable Object & @Nullable Runnable> void allNullable(Box<T> box) {
+                // BUG: Diagnostic contains: inference failure
+                requireNonNull(box);
+                // BUG: Diagnostic contains: inference failure
+                requireNonNull(id(box));
+              }
+              static <T extends @Nullable Object & Runnable> void nonNullInterface(Box<T> box) {
+                requireNonNull(box);
+                requireNonNull(id(box));
+              }
+              static <T extends Object & @Nullable Runnable> void nonNullClass(Box<T> box) {
+                requireNonNull(box);
+                requireNonNull(id(box));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947UnmarkedBareBoundRemainsOptimistic() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.NullUnmarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <U> void requireNonNull(Box<? extends U> box) {}
+              @NullUnmarked
+              static class BareBound<T> {
+                @NullMarked
+                void check(Box<T> box) {
+                  requireNonNull(box);
+                  requireNonNull(id(box));
+                }
+              }
+              @NullUnmarked
+              static class ExplicitNullableBound<T extends @Nullable Object> {
+                @NullMarked
+                void check(Box<T> box) {
+                  // BUG: Diagnostic contains: inference failure
+                  requireNonNull(box);
+                  // BUG: Diagnostic contains: inference failure
+                  requireNonNull(id(box));
+                }
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947NullableBoundThroughMultipleNestedCalls() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.ArrayList;
+            import java.util.List;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <E extends @Nullable Object> List<E> id(List<E> list) { return list; }
+              static <U> void requireNonNull(Box<? extends U> box) {}
+              static <T extends @Nullable Object> void nested(Box<T> box, List<T> list) {
+                // BUG: Diagnostic contains: inference failure
+                requireNonNull(id(id(id(box))));
+                // BUG: Diagnostic contains: inference failure
+                List.copyOf(id(id(list)));
+                // BUG: Diagnostic contains: inference failure
+                List.copyOf(new ArrayList<>(new ArrayList<>(list)));
+                // BUG: Diagnostic contains: inference failure
+                List.copyOf(id(new ArrayList<>(id(list))));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947LowerWildcardKeepsParametricDirection() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {
+                void set(E value) {}
+              }
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <U extends @Nullable Object> void put(Box<? super U> box, U value) {
+                box.set(value);
+              }
+              static <T extends @Nullable Object> void sameType(Box<T> box, T value) {
+                put(box, value);
+                put(id(box), value);
+                put(id(id(box)), value);
+              }
+              static <T extends @Nullable Object> void cannotWriteNull(Box<T> box) {
+                // A nullable upper bound permits nullable instantiations; it does not make T nullable.
+                // BUG: Diagnostic contains: passing @Nullable parameter
+                box.set(null);
+                // BUG: Diagnostic contains: incompatible types
+                Box<? super @Nullable T> nullableTarget = box;
+              }
+              static <T extends @Nullable Object> void nullableSuperTarget(
+                  Box<? super @Nullable T> box) {
+                box.set(null);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947NullableFormalProjectionDoesNotConstrainResult() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <E> Box<E> empty(@Nullable E ignored) { return new Box<>(); }
+              static <E extends @Nullable Object> Box<E> id(Box<E> box) { return box; }
+              static <U> void requireNonNull(Box<? extends U> box) {}
+              static <T extends @Nullable Object> void accepted(@Nullable T value) {
+                requireNonNull(empty(value));
+                requireNonNull(id(empty(value)));
+                requireNonNull(id(id(id(empty(value)))));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1947NestedInferenceFailureReportedAtInnerCall() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.List;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static <E extends @Nullable Object> List<E> id(List<E> xs) { return xs; }
+              static <E extends @Nullable Object> List<E> id(List<E> xs, List<E> ys) {
+                return xs;
+              }
+              static <T extends @Nullable Object> List<T> rejected(List<T> xs) {
+                return id(
+                    // BUG: Diagnostic contains: inference failure: type variable E is constrained to be @Nullable, but its upper bound requires it to be @NonNull
+                    List.copyOf(xs));
+              }
+              static <T extends @Nullable Object> List<T> siblings(List<T> xs, List<T> ys) {
+                return id(
+                    // BUG: Diagnostic contains: inference failure: type variable E is constrained to be @Nullable, but its upper bound requires it to be @NonNull
+                    List.copyOf(xs),
+                    // BUG: Diagnostic contains: inference failure: type variable E is constrained to be @Nullable, but its upper bound requires it to be @NonNull
+                    List.copyOf(ys));
+              }
+              static <T extends @Nullable Object> void localInitializers(List<T> xs) {
+                @SuppressWarnings("NullAway")
+                List<T> suppressed = id(
+                    List.copyOf(xs));
+                List<T> unsuppressed = id(
+                    // BUG: Diagnostic contains: inference failure: type variable E is constrained to be @Nullable, but its upper bound requires it to be @NonNull
+                    List.copyOf(xs));
+              }
+            }
+            """)
+        .doTest();
+  }
+
   private CompilationTestHelper makeHelper() {
     return makeTestHelperWithArgs(
         JSpecifyJavacConfig.withJSpecifyModeArgs(
```
