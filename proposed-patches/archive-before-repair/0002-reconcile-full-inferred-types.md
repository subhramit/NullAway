# Patch 2: Reconcile full inferred types safely

Status: APPROVED on top of Patch 1 after independent review.

This replaces the supplied patches 2–4. It reconciles structured bounds to a semantic fixed point, preserves declaration/model and markedness semantics, handles receiver-instantiated bounds for calls and method references, keeps wildcard/capture/cycle handling conservative, publishes nested poly-expression types safely, and retains the existing repair visitor as fallback.

```diff
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
index 790b681e..d6c708df 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
@@ -4,6 +4,7 @@ import com.sun.source.tree.Tree;
 import com.sun.tools.javac.code.Type;
 import java.util.List;
 import java.util.Map;
+import java.util.Set;
 import javax.lang.model.element.Element;
 
 /**
@@ -39,10 +40,17 @@ public interface ConstraintSolver {
    *
    * @param site the tree whose type arguments are inferred
    * @param typeVariables the declared type variables inferred at {@code site}
+   * @param instantiatedUpperBounds upper bounds after receiver/class substitutions, keyed by the
+   *     corresponding declared type variable
+   * @param variablesWithNullnessMarkedBounds variables whose declaration upper-bound annotations
+   *     are an authoritative nullness contract
    * @return a map from each declared type variable to its fresh type variable for {@code site}
    */
   Map<Element, Type.TypeVar> registerInferenceVariables(
-      Tree site, List<? extends Element> typeVariables);
+      Tree site,
+      List<? extends Element> typeVariables,
+      Map<? extends Element, ? extends Type> instantiatedUpperBounds,
+      Set<? extends Element> variablesWithNullnessMarkedBounds);
 
   /**
    * Exception thrown when the constraints added to the solver are determined to be unsatisfiable.
@@ -77,11 +85,42 @@ public interface ConstraintSolver {
     }
   }
 
+  /**
+   * Indicates a proven nested-nullness violation of a declaration upper bound whose inferred javac
+   * shape cannot carry the bound's annotations, such as a non-generic subclass of an annotated
+   * generic bound.
+   */
+  class NestedUpperBoundViolationException extends UnsatisfiableConstraintsException {
+    private final Tree site;
+    private final Type lowerBound;
+    private final Type upperBound;
+
+    public NestedUpperBoundViolationException(
+        Element typeVariable, Tree site, Type lowerBound, Type upperBound) {
+      super(typeVariable);
+      this.site = site;
+      this.lowerBound = lowerBound;
+      this.upperBound = upperBound;
+    }
+
+    public Tree getSite() {
+      return site;
+    }
+
+    public Type getLowerBound() {
+      return lowerBound;
+    }
+
+    public Type getUpperBound() {
+      return upperBound;
+    }
+  }
+
   /**
    * Add a subtype constraint between two types. Also constrains nested types appropriately (e.g.,
    * generic type parameters of the two types must have identical nullability). Inference variables
    * must appear in the types as the fresh type variables returned by {@link
-   * #registerInferenceVariables(Tree, List)}.
+   * #registerInferenceVariables(Tree, List, Map, Set)}.
    *
    * @param subtype the subtype
    * @param supertype the supertype
@@ -93,17 +132,27 @@ public interface ConstraintSolver {
   void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType)
       throws UnsatisfiableConstraintsException;
 
-  enum InferredNullability {
-    NONNULL,
-    NULLABLE
-  }
-
   /**
-   * Solve the constraints, returning a map from inference variables to their inferred nullability.
-   * The map only contains inference variables that appear in constraints.
+   * Solve the constraints, returning a map from inference variables to their inferred
+   * nullness-annotated types. The map only contains inference variables that appear in
+   * constraints.
+   *
+   * <p>The top-level annotation of each inferred type is a synthetic {@code @Nullable} or {@code
+   * @NonNull} annotation (see {@link GenericsChecks#getSyntheticNullableAnnotType}) giving the
+   * variable's inferred top-level nullability. When the constraints determine the structure of the
+   * type argument, e.g., {@code R = Box<@Nullable String>} for a constraint {@code
+   * Box<Box<@Nullable String>> <: Box<R>}, the inferred type is that type, including its nested
+   * nullability annotations. Covariant array components may be merged, while invariant generic
+   * arguments must remain consistent. Declaration upper bounds are enforced when selecting a
+   * candidate. When lower-bound evidence conflicts with a contextual constraint, the lower-bound
+   * type can be returned as annotation evidence so the existing ordinary argument or
+   * method-reference check reports the incompatibility at its established source location. If no
+   * safe annotation source can be established, the inferred type is the declared type variable
+   * itself, meaning only top-level nullability is inferred and the rest of the type argument comes
+   * from javac's inference and the repair fallback.
    *
-   * @return a map from inference variables to their inferred nullability
+   * @return a map from inference variables to their inferred types
    * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
    */
-  Map<InferenceVariable, InferredNullability> solve() throws UnsatisfiableConstraintsException;
+  Map<InferenceVariable, Type> solve() throws UnsatisfiableConstraintsException;
 }
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
index 9cb268ea..2a57ffee 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
@@ -1,6 +1,7 @@
 package com.uber.nullaway.generics;
 
 import static com.uber.nullaway.NullabilityUtil.castToNonNull;
+import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;
 
 import com.google.common.base.Verify;
 import com.google.errorprone.VisitorState;
@@ -13,11 +14,13 @@ import com.sun.tools.javac.code.Type.ClassType;
 import com.sun.tools.javac.code.Type.TypeVar;
 import com.sun.tools.javac.code.Type.WildcardType;
 import com.sun.tools.javac.code.Types;
+import com.uber.nullaway.CodeAnnotationInfo;
 import com.uber.nullaway.Config;
 import com.uber.nullaway.NullAway;
 import com.uber.nullaway.Nullness;
 import com.uber.nullaway.handlers.Handler;
 import java.util.ArrayDeque;
+import java.util.ArrayList;
 import java.util.Collections;
 import java.util.Deque;
 import java.util.IdentityHashMap;
@@ -26,8 +29,10 @@ import java.util.LinkedHashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Set;
+import javax.lang.model.element.AnnotationMirror;
 import javax.lang.model.element.Element;
 import javax.lang.model.type.NullType;
+import javax.lang.model.type.TypeMirror;
 import javax.lang.model.type.TypeVariable;
 import org.jspecify.annotations.Nullable;
 
@@ -42,7 +47,7 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
 
   /**
    * Maps the symbol of each fresh type variable created by {@link #registerInferenceVariables(Tree,
-   * List)} to the inference variable it represents. Only type variables with these symbols are
+   * List, Map, Set)} to the inference variable it represents. Only type variables with these symbols are
    * treated as inference variables; all other type variables are fixed.
    */
   private final Map<Element, InferenceVariable> inferenceVariables = new LinkedHashMap<>();
@@ -51,6 +56,17 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   private final Map<Tree, Map<Element, Type.TypeVar>> freshTypeVariablesForSite =
       new LinkedHashMap<>();
 
+  /** Fresh type variable corresponding to each inference variable. */
+  private final Map<InferenceVariable, Type.TypeVar> freshTypeVariableForInferenceVariable =
+      new LinkedHashMap<>();
+
+  /** Effective upper-bound nullability after receiver/class substitution. */
+  private final Map<InferenceVariable, Boolean> nullableAllowedForInferenceVariable =
+      new LinkedHashMap<>();
+
+  /** Variables whose declaration upper bounds are authoritative nullness contracts. */
+  private final Set<InferenceVariable> nullnessMarkedDeclarationBounds = new LinkedHashSet<>();
+
   public ConstraintSolverImpl(Config config, VisitorState state, NullAway analysis) {
     this.config = config;
     this.handler = analysis.getHandler();
@@ -83,6 +99,32 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     /** Important to use a LinkedHashSet here for determinism in error messages. */
     final Set<InferenceVariable> subtypes = new LinkedHashSet<>();
 
+    /**
+     * Structured evidence {@code S} with a constraint {@code S <: var}, including arrays, non-raw
+     * classes, and annotated upper bounds of fixed type variables. Non-generic subclasses are kept
+     * because alignment to a generic supertype can expose nested nullability.
+     */
+    final List<Type> lowerBoundTypes = new ArrayList<>();
+
+    /** Structural fingerprints used to deduplicate {@link #lowerBoundTypes}. */
+    final Set<String> lowerBoundKeys = new LinkedHashSet<>();
+
+    /** Fixed type-variable uses whose upper bounds supplied entries in {@link #lowerBoundTypes}. */
+    final List<Type> fixedTypeVariableLowerBounds = new ArrayList<>();
+
+    /** Structural fingerprints used to deduplicate fixed type-variable provenance. */
+    final Set<String> fixedTypeVariableLowerBoundKeys = new LinkedHashSet<>();
+
+    /**
+     * Structured types {@code S} with a constraint {@code var <: S}, in the order the constraints
+     * were added. A type appearing in both this list and {@link #lowerBoundTypes} arises from an
+     * equality constraint, e.g., between invariant type arguments.
+     */
+    final List<Type> upperBoundTypes = new ArrayList<>();
+
+    /** Structural fingerprints used to deduplicate {@link #upperBoundTypes}. */
+    final Set<String> upperBoundKeys = new LinkedHashSet<>();
+
     VarState(boolean nullableAllowed) {
       this.nullableAllowed = nullableAllowed;
     }
@@ -94,11 +136,23 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
    */
   private final Map<InferenceVariable, VarState> vars = new LinkedHashMap<>();
 
+  /** Incremented whenever a variable, structured bound, or variable edge is added. */
+  private int structuredConstraintVersion = 0;
+
+  /** Variables currently being structurally resolved for invariant comparison. */
+  private final Set<InferenceVariable> invariantResolutionInProgress = new LinkedHashSet<>();
+
+  /** Stable per-run IDs for symbols appearing in structural fingerprints. */
+  private final IdentityHashMap<Symbol, Integer> fingerprintSymbolIds = new IdentityHashMap<>();
+
   /* ───────────────────── public API ───────────────────── */
 
   @Override
   public Map<Element, Type.TypeVar> registerInferenceVariables(
-      Tree site, List<? extends Element> typeVariables) {
+      Tree site,
+      List<? extends Element> typeVariables,
+      Map<? extends Element, ? extends Type> instantiatedUpperBounds,
+      Set<? extends Element> variablesWithNullnessMarkedBounds) {
     Map<Element, Type.TypeVar> existing = freshTypeVariablesForSite.get(site);
     if (existing != null) {
       return existing;
@@ -113,13 +167,25 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
           typeVariable, new Type.TypeVar(symbol.name, symbol.owner, declared.getLowerBound()));
     }
     Types types = state.getTypes();
+    Set<Element> receiverInstantiatedBounds = new LinkedHashSet<>();
     for (Map.Entry<Element, Type.TypeVar> entry : fresh.entrySet()) {
       Type.TypeVar declared = (Type.TypeVar) ((Symbol) entry.getKey()).type;
+      Type instantiatedUpperBound = instantiatedUpperBounds.get(entry.getKey());
+      Type upperBound =
+          instantiatedUpperBound != null ? instantiatedUpperBound : declared.getUpperBound();
+      Symbol.TypeVariableSymbol declaredSymbol = (Symbol.TypeVariableSymbol) entry.getKey();
+      boolean ownerIsUnannotated =
+          CodeAnnotationInfo.instance(state.context)
+              .isSymbolUnannotated(declaredSymbol.owner, config, handler);
+      if (instantiatedUpperBound != null
+          && !ownerIsUnannotated
+          && !types.isSameType(instantiatedUpperBound, declared.getUpperBound())) {
+        receiverInstantiatedBounds.add(entry.getKey());
+      }
       entry
           .getValue()
           .setUpperBound(
-              TypeSubstitutionUtils.substituteTypeVariables(
-                  declared.getUpperBound(), fresh, types, config));
+              TypeSubstitutionUtils.substituteTypeVariables(upperBound, fresh, types, config));
     }
     // The fresh type variables are not among their owner's type parameters, so upper bound
     // nullability coming from library models (which are keyed on type parameter index) is not
@@ -131,7 +197,8 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
       boolean declaredNullable =
           GenericsUtils.upperBoundIsNullable(entry.getKey(), config, handler, state);
       Type upperBound = freshVar.getUpperBound();
-      if (declaredNullable
+      if (!receiverInstantiatedBounds.contains(entry.getKey())
+          && declaredNullable
               != GenericsUtils.upperBoundIsNullable(freshVar.tsym, config, handler, state)
           && !upperBound.isCompound()) {
         freshVar.setUpperBound(
@@ -141,7 +208,17 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
                     ? GenericsChecks.getSyntheticNullableAnnotType(state)
                     : GenericsChecks.getSyntheticNonNullAnnotType(state)));
       }
-      inferenceVariables.put(freshVar.tsym, new InferenceVariable(entry.getKey(), site));
+      InferenceVariable inferenceVariable = new InferenceVariable(entry.getKey(), site);
+      inferenceVariables.put(freshVar.tsym, inferenceVariable);
+      freshTypeVariableForInferenceVariable.put(inferenceVariable, freshVar);
+      if (variablesWithNullnessMarkedBounds.contains(entry.getKey())) {
+        nullnessMarkedDeclarationBounds.add(inferenceVariable);
+      }
+      if (variablesWithNullnessMarkedBounds.contains(entry.getKey())
+          && receiverInstantiatedBounds.contains(entry.getKey())) {
+        nullableAllowedForInferenceVariable.put(
+            inferenceVariable, upperBoundAllowsNullable(freshVar.getUpperBound()));
+      }
     }
     freshTypeVariablesForSite.put(site, fresh);
     return fresh;
@@ -361,8 +438,9 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   }
 
   @Override
-  public Map<InferenceVariable, InferredNullability> solve()
-      throws UnsatisfiableConstraintsException {
+  public Map<InferenceVariable, Type> solve() throws UnsatisfiableConstraintsException {
+    prepareStructuredConstraints();
+
     /* ---------- work-list propagation of nullability ---------- */
     Deque<InferenceVariable> work = new ArrayDeque<>();
     vars.forEach(
@@ -401,20 +479,874 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     }
 
     /* ---------- build final solution map ---------- */
-    Map<InferenceVariable, InferredNullability> result = new LinkedHashMap<>();
-    vars.forEach(
-        (inferenceVar, st) -> {
-          // Note: if the nullness state is UNKNOWN, we infer NONNULL arbitrarily
-          // TODO does this matter?  should we use NULLABLE instead?
-          result.put(
-              inferenceVar,
-              st.nullness == NullnessState.NULLABLE
-                  ? InferredNullability.NULLABLE
-                  : InferredNullability.NONNULL);
-        });
+    Map<InferenceVariable, Type> result = new LinkedHashMap<>();
+    Map<InferenceVariable, Type> inferredTypes = new LinkedHashMap<>();
+    for (InferenceVariable inferenceVar : vars.keySet()) {
+      result.put(inferenceVar, inferredType(inferenceVar, inferredTypes, new LinkedHashSet<>()));
+    }
     return result;
   }
 
+  /**
+   * Computes the nullness-annotated type inferred for {@code inferenceVar}, after nullability
+   * propagation has reached a fixed point.
+   *
+   * <p>The top-level annotation of the result is a synthetic {@code @Nullable} or {@code @NonNull}
+   * annotation giving the variable's inferred top-level nullability. (If the nullness state is
+   * UNKNOWN, we infer NONNULL arbitrarily; TODO does this matter? should we use NULLABLE instead?)
+   *
+   * <p>If a structured bound for the variable is available (see {@link #structuredBound}), the
+   * result is that bound, with nested nullability annotations preserved, inference variables of
+   * this problem within it replaced by their own inferred types, and unannotated nested class and
+   * array types marked as {@code @NonNull}. Marking those types matters since javac's inferred type
+   * argument may contain misplaced {@code @Nullable} annotations, and consumers overlay the nested
+   * annotations of the result onto javac's type. Otherwise, the result is the declared type
+   * variable itself, indicating that only top-level nullability was inferred and the rest of the
+   * type should come from javac's inferred type argument.
+   *
+   * @param inferenceVar the inference variable
+   * @param memo already computed inferred types
+   * @param inProgress variables whose inferred types are currently being computed, to guard against
+   *     cycles, e.g., from a constraint {@code List<T> <: T}
+   * @return the inferred type
+   */
+  private Type inferredType(
+      InferenceVariable inferenceVar,
+      Map<InferenceVariable, Type> memo,
+      Set<InferenceVariable> inProgress) {
+    Type memoized = memo.get(inferenceVar);
+    if (memoized != null) {
+      return memoized;
+    }
+    VarState st = vars.get(inferenceVar);
+    Type nullnessAnnotType =
+        st != null && st.nullness == NullnessState.NULLABLE
+            ? GenericsChecks.getSyntheticNullableAnnotType(state)
+            : GenericsChecks.getSyntheticNonNullAnnotType(state);
+    Type declaredTypeVariable = (Type) inferenceVar.typeVariable().asType();
+    Type bound =
+        hasStructuredDependencyCycle(inferenceVar)
+            ? null
+            : structuredBound(inferenceVar);
+    if (bound == null || !inProgress.add(inferenceVar)) {
+      // only top-level nullability is known; don't memoize a result computed due to a cycle
+      Type result = TypeSubstitutionUtils.typeWithAnnot(declaredTypeVariable, nullnessAnnotType);
+      if (bound == null) {
+        memo.put(inferenceVar, result);
+      }
+      return result;
+    }
+    try {
+      // replace inference variables of this problem occurring within the bound with their inferred
+      // types; only their annotations matter, since consumers copy annotations, not types, from
+      // the result
+      Map<Element, Type> replacements = new LinkedHashMap<>();
+      for (Map.Entry<Element, InferenceVariable> entry : inferenceVariables.entrySet()) {
+        TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(entry.getKey());
+        bound.accept(collector, null);
+        if (!collector.getMatches().isEmpty()) {
+          replacements.put(entry.getKey(), inferredType(entry.getValue(), memo, inProgress));
+        }
+      }
+      Type resolved =
+          TypeSubstitutionUtils.substituteTypeVariables(
+              bound, replacements, state.getTypes(), config);
+      Type result =
+          TypeSubstitutionUtils.typeWithAnnot(markNestedTypesNonNull(resolved), nullnessAnnotType);
+      memo.put(inferenceVar, result);
+      return result;
+    } finally {
+      inProgress.remove(inferenceVar);
+    }
+  }
+
+  /**
+   * Returns whether structured evidence reachable from {@code start} contains a dependency cycle.
+   * Cyclic components use top-level-only inference so no traversal-order-dependent partial unfolding
+   * is published.
+   */
+  private boolean hasStructuredDependencyCycle(InferenceVariable start) {
+    return hasStructuredDependencyCycle(start, new LinkedHashSet<>(), new LinkedHashSet<>());
+  }
+
+  /** Performs depth-first cycle detection over direct structured-bound dependencies. */
+  private boolean hasStructuredDependencyCycle(
+      InferenceVariable current,
+      Set<InferenceVariable> visiting,
+      Set<InferenceVariable> completed) {
+    if (completed.contains(current)) {
+      return false;
+    }
+    if (!visiting.add(current)) {
+      return true;
+    }
+    for (InferenceVariable dependency : directStructuredDependencies(current)) {
+      if (hasStructuredDependencyCycle(dependency, visiting, completed)) {
+        return true;
+      }
+    }
+    visiting.remove(current);
+    completed.add(current);
+    return false;
+  }
+
+  /** Returns inference variables occurring directly in this variable's structured evidence. */
+  private Set<InferenceVariable> directStructuredDependencies(InferenceVariable inferenceVar) {
+    Set<InferenceVariable> dependencies = new LinkedHashSet<>();
+    List<Type> evidence = new ArrayList<>();
+    VarState st = vars.get(inferenceVar);
+    if (st != null) {
+      evidence.addAll(st.lowerBoundTypes);
+      evidence.addAll(st.upperBoundTypes);
+    }
+    Type.TypeVar freshTypeVariable = freshTypeVariableForInferenceVariable.get(inferenceVar);
+    if (freshTypeVariable != null && nullnessMarkedDeclarationBounds.contains(inferenceVar)) {
+      evidence.add(freshTypeVariable.getUpperBound());
+    }
+    for (Map.Entry<Element, InferenceVariable> entry : inferenceVariables.entrySet()) {
+      for (Type type : evidence) {
+        TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(entry.getKey());
+        type.accept(collector, null);
+        if (!collector.getMatches().isEmpty()) {
+          dependencies.add(entry.getValue());
+          break;
+        }
+      }
+    }
+    return dependencies;
+  }
+
+  private enum BoundRelation {
+    SATISFIED,
+    VIOLATED,
+    UNKNOWN
+  }
+
+  private enum BoundNullness {
+    NONNULL,
+    NULLABLE,
+    UNKNOWN
+  }
+
+  /**
+   * Structured bounds relevant to one inference variable. {@code declaredUpper} is a subset of
+   * {@code upper} retained separately so declaration violations can use a conservative diagnostic
+   * fallback without changing the established reporting site for contextual constraints.
+   */
+  private record StructuredBounds(
+      List<Type> lower, List<Type> upper, List<Type> declaredUpper) {}
+
+  /**
+   * Adds constraints implied by declared upper bounds and by every structured lower/upper pair.
+   *
+   * <p>The latter constraints are transitive consequences of {@code lower <: variable <: upper}.
+   * Generating them before nullness propagation lets dependent bounds such as {@code U extends
+   * Box<T>} constrain {@code T}, rather than defaulting {@code T} before the structured candidate is
+   * checked.
+   */
+  private void prepareStructuredConstraints() {
+    int versionBeforePass;
+    do {
+      versionBeforePass = structuredConstraintVersion;
+      for (InferenceVariable inferenceVar : new ArrayList<>(vars.keySet())) {
+        addDeclaredUpperBoundEdge(inferenceVar);
+        StructuredBounds bounds = structuredBounds(inferenceVar);
+        for (Type lower : bounds.lower()) {
+          for (Type upper : bounds.upper()) {
+            lower.accept(new AddSubtypeConstraintsVisitor(false), upper);
+          }
+        }
+      }
+    } while (structuredConstraintVersion != versionBeforePass);
+  }
+
+  /** Adds a variable edge for a declared dependent upper bound such as {@code U extends T}. */
+  private void addDeclaredUpperBoundEdge(InferenceVariable inferenceVar) {
+    if (!nullnessMarkedDeclarationBounds.contains(inferenceVar)) {
+      return;
+    }
+    Type.TypeVar freshTypeVariable = freshTypeVariableForInferenceVariable.get(inferenceVar);
+    if (freshTypeVariable == null) {
+      return;
+    }
+    InferenceVariable upperVariable = inferenceVariableForUse(freshTypeVariable.getUpperBound());
+    if (upperVariable != null) {
+      addInferenceVariableEdge(inferenceVar, upperVariable);
+    }
+  }
+
+  /** Adds {@code subtype <: supertype}, recording whether the structural graph changed. */
+  private void addInferenceVariableEdge(
+      InferenceVariable subtype, InferenceVariable supertype) {
+    boolean changed = getState(subtype).supertypes.add(supertype);
+    changed |= getState(supertype).subtypes.add(subtype);
+    if (changed) {
+      structuredConstraintVersion++;
+    }
+  }
+
+  /**
+   * Returns a deterministic structured annotation source for {@code inferenceVar}, or {@code null}
+   * to leave nested nullability to javac and the existing repair visitor.
+   *
+   * <p>All reachable lower and upper bounds participate. Lower bounds are merged only through
+   * covariant positions; generic type arguments remain invariant. If there are no lower bounds, the
+   * most specific compatible upper bound is used. Unknown structure falls back to javac and the
+   * repair visitor. A contextual upper-bound mismatch is retained as lower-bound annotation evidence
+   * so the established ordinary argument or method-reference check reports the incompatibility.
+   */
+  private @Nullable Type structuredBound(InferenceVariable inferenceVar) {
+    StructuredBounds bounds = structuredBounds(inferenceVar);
+    Type candidate = null;
+    for (Type lower : bounds.lower()) {
+      if (candidate == null) {
+        candidate = lower;
+        continue;
+      }
+      Type merged = mergeCovariantLowerBounds(candidate, lower, false);
+      if (merged == null) {
+        return null;
+      }
+      candidate = merged;
+    }
+    if (candidate == null) {
+      for (Type upper : bounds.upper()) {
+        candidate = candidate == null ? upper : moreSpecificUpperBound(candidate, upper);
+        if (candidate == null) {
+          return null;
+        }
+      }
+    }
+    if (candidate == null) {
+      return null;
+    }
+    for (Type lower : bounds.lower()) {
+      BoundRelation relation = nullabilitySubtype(lower, candidate, false);
+      if (relation == BoundRelation.VIOLATED) {
+        return null;
+      }
+      if (relation == BoundRelation.UNKNOWN) {
+        return null;
+      }
+    }
+    for (Type upper : bounds.upper()) {
+      BoundRelation relation = nullabilitySubtype(candidate, upper, false);
+      if (relation == BoundRelation.VIOLATED) {
+        if (containsIdentical(bounds.declaredUpper(), upper)) {
+          if (cannotOverlayDeclarationBound(inferenceVar, candidate, upper)) {
+            throw new NestedUpperBoundViolationException(
+                inferenceVar.typeVariable(),
+                inferenceVar.site(),
+                declarationViolationSource(inferenceVar, candidate),
+                upper);
+          }
+          return validatedUpperBoundFallback(bounds.upper());
+        }
+        return candidate;
+      }
+      if (relation == BoundRelation.UNKNOWN) {
+        return null;
+      }
+    }
+    return candidate;
+  }
+
+  /**
+   * Returns whether a declaration upper bound cannot be represented by annotation overlay on the
+   * inferred candidate's javac shape.
+   */
+  private boolean cannotOverlayDeclarationBound(
+      InferenceVariable inferenceVar, Type candidate, Type upper) {
+    VarState st = vars.get(inferenceVar);
+    if (st != null && !st.fixedTypeVariableLowerBounds.isEmpty()) {
+      return true;
+    }
+    return candidate instanceof ClassType candidateClass
+        && upper instanceof ClassType upperClass
+        && !candidateClass.tsym.equals(upperClass.tsym)
+        && upperClass.tsym instanceof Symbol.ClassSymbol upperSymbol
+        && TypeSubstitutionUtils.asSuper(state.getTypes(), candidate, upperSymbol, config) != null;
+  }
+
+  /** Returns the source-level lower type to display for a declaration-bound violation. */
+  private Type declarationViolationSource(InferenceVariable inferenceVar, Type candidate) {
+    VarState st = vars.get(inferenceVar);
+    return st != null && !st.fixedTypeVariableLowerBounds.isEmpty()
+        ? st.fixedTypeVariableLowerBounds.get(0)
+        : candidate;
+  }
+
+  /**
+   * Returns a structured upper bound satisfying every upper bound, for use as a diagnostic fallback
+   * when lower bounds violate a declaration bound. This fallback causes ordinary argument
+   * compatibility checking to report the offending lower bound; it is never selected merely by
+   * lower-bound order.
+   */
+  private @Nullable Type validatedUpperBoundFallback(List<Type> upperBounds) {
+    Type fallback = null;
+    for (Type upper : upperBounds) {
+      fallback = fallback == null ? upper : moreSpecificUpperBound(fallback, upper);
+      if (fallback == null) {
+        return null;
+      }
+    }
+    if (fallback == null) {
+      return null;
+    }
+    Type validatedFallback = fallback;
+    for (Type upper : upperBounds) {
+      if (nullabilitySubtype(validatedFallback, upper, false) != BoundRelation.SATISFIED) {
+        return null;
+      }
+    }
+    return validatedFallback;
+  }
+
+  /** Collects all structured bounds flowing to and from {@code inferenceVar} in breadth-first order. */
+  private StructuredBounds structuredBounds(InferenceVariable inferenceVar) {
+    return new StructuredBounds(
+        collectStructuredBounds(inferenceVar, true),
+        collectStructuredBounds(inferenceVar, false),
+        collectDeclaredUpperBounds(inferenceVar));
+  }
+
+  /** Collects declaration/model structured upper bounds reachable through supertype edges. */
+  private List<Type> collectDeclaredUpperBounds(InferenceVariable start) {
+    List<Type> result = new ArrayList<>();
+    Deque<InferenceVariable> worklist = new ArrayDeque<>();
+    Set<InferenceVariable> visited = new LinkedHashSet<>();
+    worklist.add(start);
+    visited.add(start);
+    while (!worklist.isEmpty()) {
+      InferenceVariable current = worklist.removeFirst();
+      Type.TypeVar freshTypeVariable = freshTypeVariableForInferenceVariable.get(current);
+      if (freshTypeVariable != null && nullnessMarkedDeclarationBounds.contains(current)) {
+        addStructuredDeclaredUpperBounds(result, freshTypeVariable.getUpperBound());
+      }
+      VarState st = vars.get(current);
+      if (st != null) {
+        for (InferenceVariable supertype : st.supertypes) {
+          if (visited.add(supertype)) {
+            worklist.add(supertype);
+          }
+        }
+      }
+    }
+    return result;
+  }
+
+  /**
+   * Collects structured lower bounds through subtype edges or upper bounds through supertype edges.
+   * Declared structured upper bounds, including each component of an intersection bound, are added
+   * while collecting upper bounds.
+   */
+  private List<Type> collectStructuredBounds(InferenceVariable start, boolean lower) {
+    List<Type> result = new ArrayList<>();
+    Deque<InferenceVariable> worklist = new ArrayDeque<>();
+    Set<InferenceVariable> visited = new LinkedHashSet<>();
+    worklist.add(start);
+    visited.add(start);
+    while (!worklist.isEmpty()) {
+      InferenceVariable current = worklist.removeFirst();
+      VarState st = vars.get(current);
+      if (st == null) {
+        continue;
+      }
+      for (Type bound : lower ? st.lowerBoundTypes : st.upperBoundTypes) {
+        addIfNotIdentical(result, bound);
+      }
+      if (!lower) {
+        Type.TypeVar freshTypeVariable = freshTypeVariableForInferenceVariable.get(current);
+        if (freshTypeVariable != null && nullnessMarkedDeclarationBounds.contains(current)) {
+          addStructuredDeclaredUpperBounds(result, freshTypeVariable.getUpperBound());
+        }
+      }
+      for (InferenceVariable next : lower ? st.subtypes : st.supertypes) {
+        if (visited.add(next)) {
+          worklist.add(next);
+        }
+      }
+    }
+    return result;
+  }
+
+  /** Adds the structured components of a declared upper bound to {@code result}. */
+  private void addStructuredDeclaredUpperBounds(List<Type> result, Type upperBound) {
+    if (upperBound instanceof Type.IntersectionClassType intersectionType) {
+      for (TypeMirror component : intersectionType.getBounds()) {
+        addStructuredDeclaredUpperBounds(result, (Type) component);
+      }
+    } else if (isStructuredType(upperBound)) {
+      addIfNotIdentical(result, upperBound);
+    }
+  }
+
+  /** Returns whether {@code boundType} can expose nested nullability during alignment. */
+  private static boolean isStructuredType(Type boundType) {
+    return boundType instanceof Type.ArrayType
+        || (boundType instanceof ClassType classType && !classType.isRaw());
+  }
+
+  /** Adds {@code type} unless the same javac type object is already present. */
+  private static void addIfNotIdentical(List<Type> types, Type type) {
+    if (!containsIdentical(types, type)) {
+      types.add(type);
+    }
+  }
+
+  /**
+   * Merges two lower bounds at a covariant position. Arrays recurse covariantly into their
+   * components; class type arguments must have identical nullability because Java generics are
+   * invariant. Returns {@code null} when no safe, order-independent annotation source is known.
+   */
+  private @Nullable Type mergeCovariantLowerBounds(Type first, Type second, boolean mergeTopLevel) {
+    if (first instanceof Type.ArrayType firstArray
+        && second instanceof Type.ArrayType secondArray) {
+      Type component =
+          mergeCovariantLowerBounds(
+              firstArray.getComponentType(), secondArray.getComponentType(), true);
+      if (component == null) {
+        return null;
+      }
+      Type merged = TYPE_METADATA_BUILDER.createArrayType(firstArray, component);
+      return mergeTopLevel ? mergeLowerBoundTopLevel(merged, first, second) : merged;
+    }
+    Type firstAsSecond = alignSubtypeWithSupertype(first, second);
+    Type secondAsFirst = alignSubtypeWithSupertype(second, first);
+    Type chosen;
+    Type otherAligned;
+    if (firstAsSecond != null) {
+      chosen = second;
+      otherAligned = firstAsSecond;
+    } else if (secondAsFirst != null) {
+      chosen = first;
+      otherAligned = secondAsFirst;
+    } else {
+      return null;
+    }
+    if (sameInvariantStructure(chosen, otherAligned, false) != BoundRelation.SATISFIED) {
+      return null;
+    }
+    return mergeTopLevel ? mergeLowerBoundTopLevel(chosen, first, second) : chosen;
+  }
+
+  /**
+   * Returns {@code subtype} viewed as the base type of {@code supertype}, or {@code null} if javac
+   * does not establish that relationship.
+   */
+  private @Nullable Type alignSubtypeWithSupertype(Type subtype, Type supertype) {
+    if (!state.getTypes().isSubtype(subtype, supertype)) {
+      return null;
+    }
+    if (subtype instanceof ClassType
+        && supertype instanceof ClassType
+        && supertype.tsym instanceof Symbol.ClassSymbol superSymbol) {
+      return TypeSubstitutionUtils.asSuper(state.getTypes(), subtype, superSymbol, config);
+    }
+    return subtype;
+  }
+
+  /** Applies the least upper nullness of two lower bounds to {@code base}. */
+  private @Nullable Type mergeLowerBoundTopLevel(Type base, Type first, Type second) {
+    BoundNullness firstNullness = boundNullness(first);
+    BoundNullness secondNullness = boundNullness(second);
+    if (firstNullness == BoundNullness.UNKNOWN || secondNullness == BoundNullness.UNKNOWN) {
+      return sameInferenceVariable(first, second) ? base : null;
+    }
+    boolean nullable =
+        firstNullness == BoundNullness.NULLABLE || secondNullness == BoundNullness.NULLABLE;
+    Type annotationSource =
+        nullable
+            ? firstNullness == BoundNullness.NULLABLE ? first : second
+            : firstNullness == BoundNullness.NONNULL ? first : second;
+    for (AnnotationMirror annotation : annotationSource.getAnnotationMirrors()) {
+      String annotationName = annotation.getAnnotationType().toString();
+      if ((nullable && Nullness.isNullableAnnotation(annotationName, config))
+          || (!nullable && Nullness.isNonNullAnnotation(annotationName, config))) {
+        return TypeSubstitutionUtils.typeWithAnnot(
+            base, (Type) annotation.getAnnotationType());
+      }
+    }
+    Type syntheticAnnotation =
+        nullable
+            ? GenericsChecks.getSyntheticNullableAnnotType(state)
+            : GenericsChecks.getSyntheticNonNullAnnotType(state);
+    return TypeSubstitutionUtils.typeWithAnnot(base, syntheticAnnotation);
+  }
+
+
+  /** Returns the more specific of two compatible upper bounds, with deterministic tie-breaking. */
+  private @Nullable Type moreSpecificUpperBound(Type first, Type second) {
+    if (nullabilitySubtype(first, second, false) == BoundRelation.SATISFIED) {
+      return first;
+    }
+    if (nullabilitySubtype(second, first, false) == BoundRelation.SATISFIED) {
+      return second;
+    }
+    return null;
+  }
+
+  /**
+   * Checks the nullability-aware subtype relation needed for structured bounds. Generic arguments
+   * are compared invariantly, while array components are compared covariantly.
+   */
+  private BoundRelation nullabilitySubtype(Type subtype, Type supertype, boolean compareTopLevel) {
+    if (compareTopLevel) {
+      BoundRelation topLevel = topLevelNullabilitySubtype(subtype, supertype);
+      if (topLevel != BoundRelation.SATISFIED) {
+        return topLevel;
+      }
+    }
+    if (subtype instanceof TypeVar || supertype instanceof TypeVar) {
+      return compareTopLevel ? BoundRelation.SATISFIED : BoundRelation.UNKNOWN;
+    }
+    if (subtype instanceof Type.ArrayType subtypeArray
+        && supertype instanceof Type.ArrayType supertypeArray) {
+      return nullabilitySubtype(
+          subtypeArray.getComponentType(), supertypeArray.getComponentType(), true);
+    }
+    if (subtype instanceof ClassType && supertype instanceof ClassType supertypeClass) {
+      if (!(supertypeClass.tsym instanceof Symbol.ClassSymbol superSymbol)) {
+        return BoundRelation.UNKNOWN;
+      }
+      Type aligned =
+          TypeSubstitutionUtils.asSuper(state.getTypes(), subtype, superSymbol, config);
+      if (!(aligned instanceof ClassType alignedClass)
+          || alignedClass.isRaw()
+          || supertypeClass.isRaw()) {
+        return BoundRelation.VIOLATED;
+      }
+      if (alignedClass.getTypeArguments().size() != supertypeClass.getTypeArguments().size()) {
+        return BoundRelation.VIOLATED;
+      }
+      for (int i = 0; i < alignedClass.getTypeArguments().size(); i++) {
+        BoundRelation argumentRelation =
+            sameInvariantStructure(
+                alignedClass.getTypeArguments().get(i),
+                supertypeClass.getTypeArguments().get(i),
+                true);
+        if (argumentRelation != BoundRelation.SATISFIED) {
+          return argumentRelation;
+        }
+      }
+      return sameInvariantStructure(
+          alignedClass.getEnclosingType(), supertypeClass.getEnclosingType(), true);
+    }
+    return state.getTypes().isSubtype(subtype, supertype)
+        ? BoundRelation.SATISFIED
+        : BoundRelation.VIOLATED;
+  }
+
+  /** Checks identical nullability and nested structure for an invariant generic position. */
+  private BoundRelation sameInvariantStructure(Type first, Type second, boolean compareTopLevel) {
+    if (first instanceof CapturedType
+        || second instanceof CapturedType
+        || first instanceof WildcardType
+        || second instanceof WildcardType) {
+      return BoundRelation.UNKNOWN;
+    }
+    if (compareTopLevel) {
+      BoundNullness firstNullness = boundNullness(first);
+      BoundNullness secondNullness = boundNullness(second);
+      if (firstNullness == BoundNullness.UNKNOWN || secondNullness == BoundNullness.UNKNOWN) {
+        if (!sameInferenceVariable(first, second)) {
+          return BoundRelation.UNKNOWN;
+        }
+      } else if (firstNullness != secondNullness) {
+        return BoundRelation.VIOLATED;
+      }
+    }
+    if (first instanceof TypeVar || second instanceof TypeVar) {
+      return compareTypeVariableStructure(first, second, compareTopLevel);
+    }
+    if (first instanceof Type.ArrayType firstArray
+        && second instanceof Type.ArrayType secondArray) {
+      return sameInvariantStructure(
+          firstArray.getComponentType(), secondArray.getComponentType(), true);
+    }
+    if (first instanceof ClassType firstClass && second instanceof ClassType secondClass) {
+      if (!firstClass.tsym.equals(secondClass.tsym)
+          || firstClass.isRaw()
+          || secondClass.isRaw()
+          || firstClass.getTypeArguments().size() != secondClass.getTypeArguments().size()) {
+        return BoundRelation.VIOLATED;
+      }
+      for (int i = 0; i < firstClass.getTypeArguments().size(); i++) {
+        BoundRelation argumentRelation =
+            sameInvariantStructure(
+                firstClass.getTypeArguments().get(i),
+                secondClass.getTypeArguments().get(i),
+                true);
+        if (argumentRelation != BoundRelation.SATISFIED) {
+          return argumentRelation;
+        }
+      }
+      return sameInvariantStructure(
+          firstClass.getEnclosingType(), secondClass.getEnclosingType(), true);
+    }
+    return state.getTypes().isSameType(first, second)
+        ? BoundRelation.SATISFIED
+        : BoundRelation.VIOLATED;
+  }
+
+  /**
+   * Compares invariant structure involving type variables. Acyclic inference variables are replaced
+   * by their reconciled structured evidence; unresolved, distinct, or cyclic variables remain
+   * unknown rather than being treated as equal.
+   */
+  private BoundRelation compareTypeVariableStructure(
+      Type first, Type second, boolean compareTopLevel) {
+    InferenceVariable firstVariable = inferenceVariableForUse(first);
+    InferenceVariable secondVariable = inferenceVariableForUse(second);
+    if (firstVariable != null && firstVariable.equals(secondVariable)) {
+      return BoundRelation.SATISFIED;
+    }
+    Type resolvedFirst = resolveInvariantVariable(firstVariable);
+    Type resolvedSecond = resolveInvariantVariable(secondVariable);
+    if (resolvedFirst == null && resolvedSecond == null) {
+      return first instanceof TypeVar firstTypeVar
+              && second instanceof TypeVar secondTypeVar
+              && firstTypeVar.tsym.equals(secondTypeVar.tsym)
+          ? BoundRelation.SATISFIED
+          : BoundRelation.UNKNOWN;
+    }
+    return sameInvariantStructure(
+        resolvedFirst != null ? resolvedFirst : first,
+        resolvedSecond != null ? resolvedSecond : second,
+        compareTopLevel);
+  }
+
+  /** Returns reconciled acyclic structure for {@code variable}, or {@code null} if unavailable. */
+  private @Nullable Type resolveInvariantVariable(@Nullable InferenceVariable variable) {
+    if (variable == null
+        || hasStructuredDependencyCycle(variable)
+        || !invariantResolutionInProgress.add(variable)) {
+      return null;
+    }
+    try {
+      return structuredBound(variable);
+    } finally {
+      invariantResolutionInProgress.remove(variable);
+    }
+  }
+
+  /** Returns whether the top-level nullness of {@code subtype} is a subtype of {@code supertype}. */
+  private BoundRelation topLevelNullabilitySubtype(Type subtype, Type supertype) {
+    BoundNullness subtypeNullness = boundNullness(subtype);
+    BoundNullness supertypeNullness = boundNullness(supertype);
+    if (subtypeNullness == BoundNullness.UNKNOWN || supertypeNullness == BoundNullness.UNKNOWN) {
+      return sameInferenceVariable(subtype, supertype)
+          ? BoundRelation.SATISFIED
+          : BoundRelation.UNKNOWN;
+    }
+    return subtypeNullness == BoundNullness.NULLABLE
+            && supertypeNullness == BoundNullness.NONNULL
+        ? BoundRelation.VIOLATED
+        : BoundRelation.SATISFIED;
+  }
+
+  /** Returns the known top-level nullness of a bound type, consulting solved variable state. */
+  private BoundNullness boundNullness(Type type) {
+    if (isKnownNullable(type)) {
+      return BoundNullness.NULLABLE;
+    }
+    if (Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)) {
+      return BoundNullness.NONNULL;
+    }
+    InferenceVariable inferenceVariable = inferenceVariableForUse(type);
+    if (inferenceVariable != null) {
+      VarState st = vars.get(inferenceVariable);
+      if (st == null || st.nullness == NullnessState.UNKNOWN) {
+        return BoundNullness.UNKNOWN;
+      }
+      return st.nullness == NullnessState.NULLABLE
+          ? BoundNullness.NULLABLE
+          : BoundNullness.NONNULL;
+    }
+    return type instanceof TypeVar ? BoundNullness.UNKNOWN : BoundNullness.NONNULL;
+  }
+
+  /** Returns whether both types denote the same unannotated inference variable. */
+  private boolean sameInferenceVariable(Type first, Type second) {
+    InferenceVariable firstVariable = inferenceVariableForUse(first);
+    return firstVariable != null && firstVariable.equals(inferenceVariableForUse(second));
+  }
+
+  /** Returns whether {@code types} contains {@code type} itself (by reference). */
+  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
+  private static boolean containsIdentical(List<Type> types, Type type) {
+    for (Type t : types) {
+      if (t == type) {
+        return true;
+      }
+    }
+    return false;
+  }
+
+  /**
+   * Records structured evidence for a lower or upper bound. A fixed type variable contributes its
+   * annotated upper bound while the original use is retained as provenance for declaration-bound
+   * diagnostics. Arrays and all non-raw classes are retained; alignment can expose nested
+   * nullability even when a concrete subclass has no direct type arguments.
+   */
+  private void recordStructuredBound(
+      InferenceVariable inferenceVar, Type boundType, boolean lower) {
+    Type evidence = boundType;
+    VarState st = getState(inferenceVar);
+    if (lower
+        && boundType instanceof TypeVar typeVariable
+        && inferenceVariableForUse(boundType) == null) {
+      evidence = typeVariable.getUpperBound();
+      if (st.fixedTypeVariableLowerBoundKeys.add(structuredTypeKey(boundType))) {
+        st.fixedTypeVariableLowerBounds.add(boundType);
+        structuredConstraintVersion++;
+      }
+    }
+    if (!isStructuredType(evidence)) {
+      return;
+    }
+    List<Type> bounds = lower ? st.lowerBoundTypes : st.upperBoundTypes;
+    Set<String> keys = lower ? st.lowerBoundKeys : st.upperBoundKeys;
+    if (keys.add(structuredTypeKey(evidence))) {
+      bounds.add(evidence);
+      structuredConstraintVersion++;
+    }
+  }
+
+  /** Returns a deterministic fingerprint including nested nullness and inference-variable identity. */
+  private String structuredTypeKey(Type type) {
+    StringBuilder result = new StringBuilder();
+    appendStructuredTypeKey(type, result, new IdentityHashMap<>());
+    return result.toString();
+  }
+
+  /** Appends one type node to a structured-bound fingerprint, guarding recursive javac types. */
+  private void appendStructuredTypeKey(
+      Type type, StringBuilder result, IdentityHashMap<Type, Boolean> active) {
+    if (active.put(type, Boolean.TRUE) != null) {
+      result.append("cycle@").append(symbolFingerprint(type.tsym));
+      return;
+    }
+    try {
+      result.append(type.getKind()).append('[');
+      List<String> annotations = new ArrayList<>();
+      for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
+        annotations.add(annotation.getAnnotationType().toString());
+      }
+      Collections.sort(annotations);
+      annotations.forEach(annotation -> result.append(annotation).append(';'));
+      result.append(']');
+      if (type instanceof Type.ArrayType arrayType) {
+        result.append("array(");
+        appendStructuredTypeKey(arrayType.getComponentType(), result, active);
+        result.append(')');
+      } else if (type instanceof Type.IntersectionClassType intersectionType) {
+        result.append("intersection(");
+        for (TypeMirror bound : intersectionType.getBounds()) {
+          appendStructuredTypeKey((Type) bound, result, active);
+          result.append('&');
+        }
+        result.append(')');
+      } else if (type instanceof ClassType classType) {
+        result
+            .append("class:")
+            .append(classType.tsym.getQualifiedName())
+            .append('@')
+            .append(symbolFingerprint(classType.tsym))
+            .append('<');
+        for (Type argument : classType.getTypeArguments()) {
+          appendStructuredTypeKey(argument, result, active);
+          result.append(',');
+        }
+        result.append('>');
+        if (classType.getEnclosingType() instanceof ClassType) {
+          result.append(" enclosing(");
+          appendStructuredTypeKey(classType.getEnclosingType(), result, active);
+          result.append(')');
+        }
+      } else if (type instanceof WildcardType wildcardType) {
+        result.append("wildcard:").append(wildcardType.kind).append('(');
+        Type bound =
+            wildcardType.kind == BoundKind.SUPER
+                ? wildcardType.getSuperBound()
+                : wildcardType.getExtendsBound();
+        if (bound != null) {
+          appendStructuredTypeKey(bound, result, active);
+        }
+        result.append(')');
+      } else if (type instanceof TypeVar typeVariable) {
+        result.append("var@").append(symbolFingerprint(typeVariable.tsym));
+      } else {
+        result.append(type);
+      }
+    } finally {
+      active.remove(type);
+    }
+  }
+
+  /** Returns a collision-free identity ID for a symbol during this solver run. */
+  private int symbolFingerprint(@Nullable Symbol symbol) {
+    if (symbol == null) {
+      return 0;
+    }
+    Integer existing = fingerprintSymbolIds.get(symbol);
+    if (existing != null) {
+      return existing;
+    }
+    int created = fingerprintSymbolIds.size() + 1;
+    fingerprintSymbolIds.put(symbol, created);
+    return created;
+  }
+
+  /**
+   * Returns {@code type} with each nested class or array type that has no explicit nullability
+   * annotation marked with a synthetic {@code @NonNull} annotation. The top-level type itself is
+   * not changed. Type variables, wildcards, and captured types are left as is, since no annotation
+   * on them does not mean non-null.
+   */
+  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
+  private Type markNestedTypesNonNull(Type type) {
+    if (type instanceof Type.ArrayType arrayType) {
+      Type elemType = arrayType.getComponentType();
+      Type newElemType = markNonNullIfUnannotated(elemType);
+      return newElemType == elemType
+          ? type
+          : TYPE_METADATA_BUILDER.createArrayType(arrayType, newElemType);
+    }
+    if (type instanceof ClassType classType && !classType.isRaw()) {
+      boolean changed = false;
+      List<Type> newTypeArgs = new ArrayList<>();
+      for (Type typeArg : classType.getTypeArguments()) {
+        Type newTypeArg = markNonNullIfUnannotated(typeArg);
+        changed |= newTypeArg != typeArg;
+        newTypeArgs.add(newTypeArg);
+      }
+      return changed
+          ? TYPE_METADATA_BUILDER.createClassType(
+              classType, classType.getEnclosingType(), newTypeArgs)
+          : type;
+    }
+    return type;
+  }
+
+  /**
+   * Like {@link #markNestedTypesNonNull(Type)}, but also marks {@code type} itself if it is an
+   * unannotated class or array type.
+   */
+  private Type markNonNullIfUnannotated(Type type) {
+    if (!(type instanceof ClassType) && !(type instanceof Type.ArrayType)) {
+      return type;
+    }
+    Type result = type;
+    if (!Nullness.hasNullableAnnotation(type.getAnnotationMirrors().stream(), config)
+        && !Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)) {
+      result =
+          TypeSubstitutionUtils.typeWithAnnot(
+              type, GenericsChecks.getSyntheticNonNullAnnotType(state));
+    }
+    return markNestedTypesNonNull(result);
+  }
+
   private void directlyConstrainTypePair(Type s, Type t) throws UnsatisfiableConstraintsException {
     Verify.verify(
         s instanceof TypeVariable || t instanceof TypeVariable,
@@ -425,8 +1357,15 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     InferenceVariable sVar = inferenceVariableForUse(s);
     InferenceVariable tVar = inferenceVariableForUse(t);
     if (sVar != null && tVar != null) {
-      getState(sVar).supertypes.add(tVar);
-      getState(tVar).subtypes.add(sVar);
+      addInferenceVariableEdge(sVar, tVar);
+    }
+
+    /* structured bounds, retaining nested nullability annotations */
+    if (tVar != null && sVar == null) {
+      recordStructuredBound(tVar, s, true);
+    }
+    if (sVar != null && tVar == null) {
+      recordStructuredBound(sVar, t, false);
     }
 
     /* top-level nullability rules */
@@ -469,12 +1408,29 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
 
   /* ───────────────────── helpers & stubs ───────────────────── */
 
+  /** Returns whether an instantiated upper bound permits a nullable inference result. */
+  private boolean upperBoundAllowsNullable(Type upperBound) {
+    if (Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config)) {
+      return true;
+    }
+    return upperBound instanceof TypeVar typeVariable
+        && GenericsUtils.upperBoundIsNullable(typeVariable.asElement(), config, handler, state);
+  }
+
   private VarState getState(InferenceVariable inferenceVar) {
-    return vars.computeIfAbsent(
-        inferenceVar,
-        v ->
-            new VarState(
-                GenericsUtils.upperBoundIsNullable(v.typeVariable(), config, handler, state)));
+    VarState existing = vars.get(inferenceVar);
+    if (existing != null) {
+      return existing;
+    }
+    VarState created =
+        new VarState(
+            nullableAllowedForInferenceVariable.getOrDefault(
+                inferenceVar,
+                GenericsUtils.upperBoundIsNullable(
+                    inferenceVar.typeVariable(), config, handler, state)));
+    vars.put(inferenceVar, created);
+    structuredConstraintVersion++;
+    return created;
   }
 
   /**
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
index 966b77bf..97bcac4b 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
@@ -58,6 +58,8 @@ import com.uber.nullaway.Nullness;
 import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
 import com.uber.nullaway.dataflow.EnclosingEnvironmentNullness;
 import com.uber.nullaway.dataflow.NullnessStore;
+import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
+import com.uber.nullaway.generics.ConstraintSolver.NestedUpperBoundViolationException;
 import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
 import com.uber.nullaway.generics.GenericsUtils.MethodRefTypeRelationKind;
 import com.uber.nullaway.handlers.Handler;
@@ -90,35 +92,62 @@ public final class GenericsChecks {
   private interface CallInferenceResult {}
 
   /**
-   * Indicates successful inference of nullability of type variables at a call. Stores the inferred
-   * nullability of every inference variable in the inference problem, which may span several calls
-   * (nested calls, generic method references, etc.).
+   * Indicates successful inference of type arguments at a call. Stores the inferred type of every
+   * inference variable in the inference problem, which may span several calls (nested calls,
+   * generic method references, etc.). See {@link ConstraintSolver#solve()} for the form of the
+   * inferred types.
    */
   private record InferenceSuccess(
-      Map<ConstraintSolver.InferenceVariable, ConstraintSolver.InferredNullability>
-          inferenceVariableNullability)
+      Map<InferenceVariable, Type> inferenceVariableTypes)
       implements CallInferenceResult {
 
     /**
-     * Returns the inferred nullability of the type variables inferred at {@code site}. The result
+     * Returns the inferred types of the type variables inferred at {@code site}. The result
      * excludes type variables inferred at other sites in the same inference problem, including
      * other calls to the same generic method.
      *
      * @param site a call or method reference participating in the inference problem
-     * @return a map from declared type variables to their inferred nullability at {@code site}
+     * @return a map from declared type variables to their inferred types at {@code site}
      */
-    Map<Element, ConstraintSolver.InferredNullability> typeVarNullabilityForSite(Tree site) {
-      Map<Element, ConstraintSolver.InferredNullability> result = new LinkedHashMap<>();
-      inferenceVariableNullability.forEach(
-          (inferenceVar, nullability) -> {
+    Map<Element, Type> inferredTypesForSite(Tree site) {
+      Map<Element, Type> result = new LinkedHashMap<>();
+      inferenceVariableTypes.forEach(
+          (inferenceVar, inferredType) -> {
             if (inferenceVar.site().equals(site)) {
-              result.put(inferenceVar.typeVariable(), nullability);
+              result.put(inferenceVar.typeVariable(), inferredType);
             }
           });
       return result;
     }
   }
 
+  /**
+   * Tracks which participating calls can safely share a persisted inference result. Once any call
+   * is analyzed with provisional lambda parameter types, no result from that inference problem is
+   * persisted, since all participating calls can be connected through the shared constraint graph.
+   */
+  private static final class InferenceCacheState {
+    final Set<Tree> cacheableCalls = new LinkedHashSet<>();
+    final Set<MemberReferenceTree> methodReferences = new LinkedHashSet<>();
+    boolean provisionalLambdaTypesSeen = false;
+
+    /**
+     * Records a participating call, permanently disabling persistence for the inference problem if
+     * the call depends on provisional lambda parameter types.
+     *
+     * @param call the participating generic call
+     * @param usesProvisionalLambdaTypes whether provisional lambda parameter types are active
+     */
+    void recordCall(Tree call, boolean usesProvisionalLambdaTypes) {
+      if (usesProvisionalLambdaTypes) {
+        provisionalLambdaTypesSeen = true;
+        cacheableCalls.clear();
+      } else if (!provisionalLambdaTypesSeen) {
+        cacheableCalls.add(call);
+      }
+    }
+  }
+
   /** Indicates failed inference of nullability of type variables at a call */
   private record InferenceFailure(@SuppressWarnings("UnusedVariable") @Nullable String errorMessage)
       implements CallInferenceResult {
@@ -135,9 +164,21 @@ public final class GenericsChecks {
   private final Map<Tree, CallInferenceResult> inferredTypeVarNullabilityForGenericCalls =
       new LinkedHashMap<>();
 
+  /**
+   * Completed enclosing inference results indexed by participating generic method references. This
+   * site-scoped cache remains valid when reusable call caching is disabled because provisional
+   * lambda parameter types participated: the enclosing problem has reached a complete solution
+   * before entries are published.
+   */
+  private final Map<MemberReferenceTree, InferenceSuccess>
+      inferredResultsForGenericMethodReferences = new LinkedHashMap<>();
+
   /** Calls for which a generic inference failure diagnostic has already been reported. */
   private final Set<Tree> callsWithReportedInferenceFailures = new LinkedHashSet<>();
 
+  /** Sites for which a nested declaration-upper-bound violation has already been reported. */
+  private final Set<Tree> reportedNestedUpperBoundViolations = new LinkedHashSet<>();
+
   /**
    * Maps poly expressions for which we have computed a context-derived type to that type, if
    * inference succeeded.
@@ -152,6 +193,19 @@ public final class GenericsChecks {
    */
   private Map<Symbol, Type> lambdaParameterTypesForInference = Map.of();
 
+  /**
+   * While generating constraints for an inference problem, maps each participating call whose
+   * lambda and method reference arguments get their types published after successful inference
+   * (see {@link #inferredPolyExpressionTypes}) to its executable type, as computed by {@link
+   * #getExecutableTypeForInference}. Calls inside lambda bodies are excluded, since their
+   * executable types can refer to unsolved inference variables of enclosing calls through
+   * provisional lambda parameter types (see {@link #lambdaParameterTypesForInference}). {@code
+   * null} when no constraints are being generated. Inference problems can nest, e.g., for a generic
+   * call in the initializer of a {@code var} local inside a lambda body, so this is saved and
+   * restored around each constraint generation.
+   */
+  private @Nullable Map<Tree, Type.MethodType> callTypesForPolyArguments = null;
+
   /** Maps each {@code var}-declared local to its inferred NullAway type */
   private final Map<Symbol, Type> inferredVarLocalTypes = new LinkedHashMap<>();
 
@@ -1335,7 +1389,7 @@ public final class GenericsChecks {
     // which may itself require inference, so compute it only once
     Type.MethodType executableType =
         getExecutableTypeForInference(callTree, path, state, calledFromDataflow);
-    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability = null;
+    Map<Element, Type> typeVarNullability = null;
     CallInferenceResult result = inferredTypeVarNullabilityForGenericCalls.get(callTree);
     if (result == null) { // have not yet attempted inference for this call
       result =
@@ -1349,7 +1403,7 @@ public final class GenericsChecks {
               calledFromDataflow);
     }
     if (result instanceof InferenceSuccess successResult) {
-      typeVarNullability = successResult.typeVarNullabilityForSite(callTree);
+      typeVarNullability = successResult.inferredTypesForSite(callTree);
     }
     Type typeAtCallSite = castToNonNull(ASTHelpers.getType(callTree));
     if (callTree instanceof MethodInvocationTree) {
@@ -1377,7 +1431,7 @@ public final class GenericsChecks {
    *     {@code null} if the type is unavailable or the method result is not assigned anywhere
    * @param assignedToLocal true if the call result is assigned to a local variable, false otherwise
    * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
-   * @return the inference result, either success with inferred type variable nullability or failure
+   * @return the inference result, either success with inferred nullness-annotated types or failure
    *     with an error message
    */
   private CallInferenceResult runInferenceForCall(
@@ -1389,61 +1443,74 @@ public final class GenericsChecks {
       boolean assignedToLocal,
       boolean calledFromDataflow) {
     ConstraintSolver solver = makeSolver(state, analysis);
-    // allCalls tracks the top-level call and any nested calls that also require inference
-    Set<Tree> allCalls = new LinkedHashSet<>();
-    allCalls.add(callTree);
+    InferenceCacheState inferenceCacheState = new InferenceCacheState();
+    // calls whose lambda and method reference arguments get published types on success
+    Map<Tree, Type.MethodType> callTypesForPolyArgs = new LinkedHashMap<>();
     try {
-      generateConstraintsForCall(
-          state,
-          path,
-          typeFromAssignmentContext,
-          assignedToLocal,
-          solver,
-          callTree,
-          executableType,
-          allCalls,
-          calledFromDataflow);
-      Map<ConstraintSolver.InferenceVariable, ConstraintSolver.InferredNullability> solution =
-          new LinkedHashMap<>(solver.solve());
+      Map<Tree, Type.MethodType> enclosingCallTypesForPolyArgs = callTypesForPolyArguments;
+      callTypesForPolyArguments = callTypesForPolyArgs;
+      try {
+        generateConstraintsForCall(
+            state,
+            path,
+            typeFromAssignmentContext,
+            assignedToLocal,
+            solver,
+            callTree,
+            executableType,
+            inferenceCacheState,
+            calledFromDataflow);
+      } finally {
+        callTypesForPolyArguments = enclosingCallTypesForPolyArgs;
+      }
+      inferenceCacheState.methodReferences.forEach(
+          inferredResultsForGenericMethodReferences::remove);
+      Map<InferenceVariable, Type> solution = new LinkedHashMap<>(solver.solve());
       // The solver only computes a solution for variables that appear in constraints. For
       // unconstrained variables of the top-level call, treat them as NONNULL, consistent with
       // solver behavior for unconstrained variables that do appear in the constraint graph.
       for (Symbol.TypeVariableSymbol typeVar : getCallTypeParameters(callTree)) {
         solution.putIfAbsent(
-            new ConstraintSolver.InferenceVariable(typeVar, callTree),
-            ConstraintSolver.InferredNullability.NONNULL);
+            new InferenceVariable(typeVar, callTree),
+            TypeSubstitutionUtils.typeWithAnnot(typeVar.type, getSyntheticNonNullAnnotType(state)));
       }
 
       InferenceSuccess successResult = new InferenceSuccess(solution);
-      Map<Element, ConstraintSolver.InferredNullability> typeVarNullability =
-          successResult.typeVarNullabilityForSite(callTree);
       if (okToCacheInferenceResult(calledFromDataflow)) {
-        for (Tree inferredCall : allCalls) {
+        inferenceCacheState.methodReferences.forEach(
+            methodReference ->
+                inferredResultsForGenericMethodReferences.put(methodReference, successResult));
+        for (Tree inferredCall : inferenceCacheState.cacheableCalls) {
           inferredTypeVarNullabilityForGenericCalls.put(inferredCall, successResult);
         }
-        // Store inferred types for lambda or method reference arguments
-        new InvocationArguments(callTree, executableType)
-            .forEach(
-                (argument, argPos, formalParamType, unused) -> {
-                  if (argument instanceof LambdaExpressionTree
-                      || argument instanceof MemberReferenceTree) {
-                    Type polyExprTreeType = ASTHelpers.getType(argument);
-                    if (polyExprTreeType != null) {
-                      Type formalParamGroundTargetType =
-                          GenericsUtils.groundTargetType(formalParamType, state, config, handler);
-                      Type typeWithInferredNullability =
-                          TypeSubstitutionUtils.updateTypeWithInferredNullability(
-                              polyExprTreeType,
-                              formalParamGroundTargetType,
-                              typeVarNullability,
-                              state,
-                              config);
-                      inferredPolyExpressionTypes.put(argument, typeWithInferredNullability);
-                    }
-                  }
-                });
+        // Store inferred types for lambda or method reference arguments of the participating
+        // calls, including nested calls, each using the types inferred for that call
+        callTypesForPolyArgs.forEach(
+            (call, callExecutableType) ->
+                storeInferredPolyArgumentTypes(
+                    call, callExecutableType, successResult.inferredTypesForSite(call), state));
       }
       return successResult;
+    } catch (NestedUpperBoundViolationException e) {
+      String message =
+          errorMessageForIncompatibleTypesAtPseudoAssignment(
+              e.getUpperBound(), e.getLowerBound(), state);
+      ErrorMessage errorMessage =
+          new ErrorMessage(ErrorMessage.MessageTypes.PASS_NULLABLE_GENERIC, message);
+      if (reportedNestedUpperBoundViolations.add(e.getSite())) {
+        state.reportMatch(
+            analysis
+                .getErrorBuilder()
+                .createErrorDescription(
+                    errorMessage, analysis.buildDescription(e.getSite()), state, null));
+      }
+      InferenceFailure failureResult = new InferenceFailure(message);
+      if (okToCacheInferenceResult(calledFromDataflow)) {
+        for (Tree inferredCall : inferenceCacheState.cacheableCalls) {
+          inferredTypeVarNullabilityForGenericCalls.put(inferredCall, failureResult);
+        }
+      }
+      return failureResult;
     } catch (UnsatisfiableConstraintsException e) {
       String inferenceFailureMessage = inferenceFailureMessage(e);
       if (config.warnOnGenericInferenceFailure()
@@ -1458,7 +1525,7 @@ public final class GenericsChecks {
       }
       InferenceFailure failureResult = new InferenceFailure(inferenceFailureMessage);
       if (okToCacheInferenceResult(calledFromDataflow)) {
-        for (Tree inferredCall : allCalls) {
+        for (Tree inferredCall : inferenceCacheState.cacheableCalls) {
           inferredTypeVarNullabilityForGenericCalls.put(inferredCall, failureResult);
         }
       }
@@ -1466,6 +1533,44 @@ public final class GenericsChecks {
     }
   }
 
+  /**
+   * Stores the types of lambda and method reference arguments of a generic call in {@link
+   * #inferredPolyExpressionTypes}, applying the types inferred for the call's type variables to
+   * the javac types of the arguments.
+   *
+   * @param call the generic method invocation or diamond constructor call
+   * @param executableType the executable type of {@code call}, as computed by {@link
+   *     #getExecutableTypeForInference}
+   * @param inferredTypes the types inferred for the type variables of {@code call}
+   * @param state the visitor state
+   */
+  private void storeInferredPolyArgumentTypes(
+      Tree call,
+      Type.MethodType executableType,
+      Map<Element, Type> inferredTypes,
+      VisitorState state) {
+    new InvocationArguments(call, executableType)
+        .forEach(
+            (argument, argPos, formalParamType, unused) -> {
+              if (argument instanceof LambdaExpressionTree
+                  || argument instanceof MemberReferenceTree) {
+                Type polyExprTreeType = ASTHelpers.getType(argument);
+                if (polyExprTreeType != null) {
+                  Type formalParamGroundTargetType =
+                      GenericsUtils.groundTargetType(formalParamType, state, config, handler);
+                  Type typeWithInferredNullability =
+                      TypeSubstitutionUtils.updateTypeWithInferredNullability(
+                          polyExprTreeType,
+                          formalParamGroundTargetType,
+                          inferredTypes,
+                          state,
+                          config);
+                  inferredPolyExpressionTypes.put(argument, typeWithInferredNullability);
+                }
+              }
+            });
+  }
+
   private String inferenceFailureMessage(UnsatisfiableConstraintsException e) {
     if (e.isCausedByNonNullUpperBound()) {
       return String.format(
@@ -1492,6 +1597,42 @@ public final class GenericsChecks {
     return typeParameters;
   }
 
+  /** Returns variables whose owning declaration is in a nullness-marked context. */
+  private Set<Element> variablesWithNullnessMarkedBounds(
+      List<? extends Element> typeVariables, VisitorState state) {
+    Set<Element> result = new LinkedHashSet<>();
+    for (Element typeVariable : typeVariables) {
+      Symbol owner = ((Symbol) typeVariable).owner;
+      if (!CodeAnnotationInfo.instance(state.context)
+          .isSymbolUnannotated(owner, config, handler)) {
+        result.add(typeVariable);
+      }
+    }
+    return result;
+  }
+
+  /**
+   * Finds receiver/class-substituted upper bounds for inferred variables occurring in an executable
+   * type. Variables absent from the executable type use their declaration bounds in the solver.
+   */
+  private Map<Element, Type> getInstantiatedUpperBounds(
+      Type.MethodType executableType, List<Symbol.TypeVariableSymbol> typeVariables) {
+    Map<Element, Type> result = new LinkedHashMap<>();
+    for (Symbol.TypeVariableSymbol typeVariable : typeVariables) {
+      TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(typeVariable);
+      executableType.accept(collector, null);
+      if (!collector.getMatches().isEmpty()) {
+        Type.TypeVar instantiatedUse = collector.getMatches().iterator().next();
+        Type declaredUpperBound = ((Type.TypeVar) typeVariable.type).getUpperBound();
+        Type instantiatedUpperBound =
+            TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
+                declaredUpperBound, instantiatedUse.getUpperBound(), config);
+        result.put(typeVariable, instantiatedUpperBound);
+      }
+    }
+    return result;
+  }
+
   /**
    * Returns the executable type used to generate inference constraints for {@code callTree}, after
    * applying handler-provided models.
@@ -1552,8 +1693,7 @@ public final class GenericsChecks {
    * @param callTree the call tree representing the generic method call or diamond constructor call
    * @param methodType the executable type of {@code callTree}, as computed by {@link
    *     #getExecutableTypeForInference}
-   * @param allCalls a set of all calls that require inference, including nested ones. This is an
-   *     output parameter that gets mutated while generating the constraints to add nested calls.
+   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
    * @param calledFromDataflow whether this method is being called from dataflow analysis
    * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
    */
@@ -1565,15 +1705,31 @@ public final class GenericsChecks {
       ConstraintSolver solver,
       ExpressionTree callTree,
       Type.MethodType methodType,
-      Set<Tree> allCalls,
+      InferenceCacheState inferenceCacheState,
       boolean calledFromDataflow)
       throws UnsatisfiableConstraintsException {
     // Register all type variables whose nullability is inferred for this call, and use the
     // call-specific inference variables returned by the solver in place of the declared type
     // variables. This keeps the constraints for different calls to the same generic method
     // separate; see https://github.com/uber/NullAway/issues/1291
+    List<Symbol.TypeVariableSymbol> callTypeParameters = getCallTypeParameters(callTree);
+    Set<Element> variablesWithNullnessMarkedBounds =
+        variablesWithNullnessMarkedBounds(callTypeParameters, state);
+    Map<Element, Type> instantiatedUpperBounds =
+        getInstantiatedUpperBounds(methodType, callTypeParameters);
+    instantiatedUpperBounds.keySet().retainAll(variablesWithNullnessMarkedBounds);
     Map<Element, Type.TypeVar> inferenceVariables =
-        solver.registerInferenceVariables(callTree, getCallTypeParameters(callTree));
+        solver.registerInferenceVariables(
+            callTree,
+            callTypeParameters,
+            instantiatedUpperBounds,
+            variablesWithNullnessMarkedBounds);
+    Map<Tree, Type.MethodType> callTypesForPolyArgs = callTypesForPolyArguments;
+    boolean usesProvisionalLambdaTypes = !lambdaParameterTypesForInference.isEmpty();
+    inferenceCacheState.recordCall(callTree, usesProvisionalLambdaTypes);
+    if (!usesProvisionalLambdaTypes && callTypesForPolyArgs != null) {
+      callTypesForPolyArgs.put(callTree, methodType);
+    }
     Type.MethodType methodTypeForSite =
         (Type.MethodType)
             TypeSubstitutionUtils.substituteTypeVariables(
@@ -1599,7 +1755,7 @@ public final class GenericsChecks {
               generateConstraintsForPseudoAssignment(
                   state.withPath(pathToArgument),
                   solver,
-                  allCalls,
+                  inferenceCacheState,
                   argument,
                   formalParamType,
                   calledFromDataflow);
@@ -1612,8 +1768,7 @@ public final class GenericsChecks {
    *
    * @param state the visitor state
    * @param solver the constraint solver
-   * @param allCalls a set of all calls that require inference, including nested ones. This is an
-   *     output parameter that gets mutated while generating the constraints to add nested calls.
+   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
    * @param rhsExpr the right-hand side expression of the pseudo-assignment
    * @param lhsType the left-hand side type of the pseudo-assignment
    * @param calledFromDataflow whether this method is being called from dataflow analysis
@@ -1621,7 +1776,7 @@ public final class GenericsChecks {
   private void generateConstraintsForPseudoAssignment(
       VisitorState state,
       ConstraintSolver solver,
-      Set<Tree> allCalls,
+      InferenceCacheState inferenceCacheState,
       ExpressionTree rhsExpr,
       Type lhsType,
       boolean calledFromDataflow) {
@@ -1632,7 +1787,6 @@ public final class GenericsChecks {
     // if the parameter is itself a generic call requiring inference, generate constraints for
     // that call
     if (isCallNeedingInference(rhsExpr)) {
-      allCalls.add(rhsExpr);
       generateConstraintsForCall(
           state,
           state.getPath(),
@@ -1641,7 +1795,7 @@ public final class GenericsChecks {
           solver,
           rhsExpr,
           getExecutableTypeForInference(rhsExpr, state.getPath(), state, calledFromDataflow),
-          allCalls,
+          inferenceCacheState,
           calledFromDataflow);
     } else if (rhsExpr instanceof ConditionalExpressionTree conditionalExpressionTree) {
       // generate constraints for both the true and false sub-expressions of the conditional
@@ -1651,7 +1805,7 @@ public final class GenericsChecks {
       generateConstraintsForPseudoAssignment(
           state.withPath(pathToTrueExpression),
           solver,
-          allCalls,
+          inferenceCacheState,
           trueExpression,
           lhsType,
           calledFromDataflow);
@@ -1660,15 +1814,22 @@ public final class GenericsChecks {
       generateConstraintsForPseudoAssignment(
           state.withPath(pathToFalseExpression),
           solver,
-          allCalls,
+          inferenceCacheState,
           falseExpression,
           lhsType,
           calledFromDataflow);
     } else if (rhsExpr instanceof LambdaExpressionTree lambda) {
       handleLambdaInGenericMethodInference(
-          state, state.getPath(), solver, allCalls, lhsType, lambda, calledFromDataflow);
+          state,
+          state.getPath(),
+          solver,
+          inferenceCacheState,
+          lhsType,
+          lambda,
+          calledFromDataflow);
     } else if (rhsExpr instanceof MemberReferenceTree memberReferenceTree) {
-      handleMethodRefInGenericMethodInference(state, solver, lhsType, memberReferenceTree);
+      handleMethodRefInGenericMethodInference(
+          state, solver, inferenceCacheState, lhsType, memberReferenceTree);
     } else { // all other cases
       Type argumentType = getTreeType(rhsExpr, state, calledFromDataflow);
       if (argumentType == null) {
@@ -1693,8 +1854,7 @@ public final class GenericsChecks {
    * @param path the tree path to the enclosing call if available and possibly distinct from {@code
    *     state.getPath()}
    * @param solver the constraint solver
-   * @param allCalls a set of all calls that require inference, including nested ones. This is an
-   *     output parameter that gets mutated while generating the constraints to add nested calls.
+   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
    * @param lhsType the type to which the lambda is being assigned
    * @param lambda The lambda argument
    * @param calledFromDataflow whether this method is being called from dataflow analysis
@@ -1703,7 +1863,7 @@ public final class GenericsChecks {
       VisitorState state,
       @Nullable TreePath path,
       ConstraintSolver solver,
-      Set<Tree> allCalls,
+      InferenceCacheState inferenceCacheState,
       Type lhsType,
       LambdaExpressionTree lambda,
       boolean calledFromDataflow) {
@@ -1744,7 +1904,7 @@ public final class GenericsChecks {
         generateConstraintsForPseudoAssignment(
             state.withPath(returnedExpressionPath),
             solver,
-            allCalls,
+            inferenceCacheState,
             returnedExpression,
             fiReturnType,
             calledFromDataflow);
@@ -1760,7 +1920,7 @@ public final class GenericsChecks {
           generateConstraintsForPseudoAssignment(
               state.withPath(returnExprPath),
               solver,
-              allCalls,
+              inferenceCacheState,
               returnExpr,
               fiReturnType,
               calledFromDataflow);
@@ -1778,28 +1938,45 @@ public final class GenericsChecks {
    *
    * @param state the visitor state
    * @param solver the constraint solver
+   * @param inferenceCacheState cache and method-reference sites for the enclosing inference problem
    * @param lhsType the type to which the method reference is being assigned
    * @param memberReferenceTree the method reference argument
    */
   private void handleMethodRefInGenericMethodInference(
       VisitorState state,
       ConstraintSolver solver,
+      InferenceCacheState inferenceCacheState,
       Type lhsType,
       MemberReferenceTree memberReferenceTree) {
+    inferenceCacheState.methodReferences.add(memberReferenceTree);
+    inferredResultsForGenericMethodReferences.remove(memberReferenceTree);
     // if we have a reference to a generic method, and the call site does not pass explicit type
     // arguments, register the referenced method's type variables as inference variables
     Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(memberReferenceTree);
     List<? extends ExpressionTree> explicitTypeArguments = memberReferenceTree.getTypeArguments();
+    Type groundTargetType = GenericsUtils.groundTargetType(lhsType, state, config, handler);
     Map<Element, Type.TypeVar> inferenceVariables = Map.of();
     if (referencedMethod != null
         && !referencedMethod.getTypeParameters().isEmpty()
         && (explicitTypeArguments == null || explicitTypeArguments.isEmpty())) {
+      ResolvedMethodReference resolvedReference =
+          resolveMemberReference(memberReferenceTree, referencedMethod, groundTargetType, state);
+      Set<Element> variablesWithNullnessMarkedBounds =
+          variablesWithNullnessMarkedBounds(referencedMethod.getTypeParameters(), state);
+      Map<Element, Type> instantiatedUpperBounds =
+          resolvedReference != null
+              ? getInstantiatedUpperBounds(
+                  resolvedReference.methodType(), referencedMethod.getTypeParameters())
+              : new LinkedHashMap<>();
+      instantiatedUpperBounds.keySet().retainAll(variablesWithNullnessMarkedBounds);
       inferenceVariables =
           solver.registerInferenceVariables(
-              memberReferenceTree, referencedMethod.getTypeParameters());
+              memberReferenceTree,
+              referencedMethod.getTypeParameters(),
+              instantiatedUpperBounds,
+              variablesWithNullnessMarkedBounds);
     }
     Map<Element, Type.TypeVar> referenceInferenceVariables = inferenceVariables;
-    Type groundTargetType = GenericsUtils.groundTargetType(lhsType, state, config, handler);
     GenericsUtils.processMethodRefTypeRelations(
         this,
         groundTargetType,
@@ -1812,7 +1989,7 @@ public final class GenericsChecks {
           // from the functional interface type, which may mention the same type variables as
           // fixed types (e.g., in a recursive reference to an enclosing generic method).
           boolean referencedMethodTypeIsSubtype =
-              relationKind == GenericsUtils.MethodRefTypeRelationKind.RETURN;
+              relationKind == MethodRefTypeRelationKind.RETURN;
           Type subtypeForReference =
               referencedMethodTypeIsSubtype
                   ? TypeSubstitutionUtils.substituteTypeVariables(
@@ -2757,6 +2934,19 @@ public final class GenericsChecks {
    */
   private Type.MethodType getInferredMethodTypeForGenericMethodReference(
       Type.MethodType methodType, VisitorState state) {
+    Tree referenceTree = state.getPath().getLeaf();
+    if (referenceTree instanceof MemberReferenceTree memberReferenceTree) {
+      InferenceSuccess siteResult =
+          inferredResultsForGenericMethodReferences.get(memberReferenceTree);
+      if (siteResult != null) {
+        return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
+            methodType,
+            methodType,
+            siteResult.inferredTypesForSite(memberReferenceTree),
+            state,
+            config);
+      }
+    }
     TreePath parentPath = state.getPath().getParentPath();
     while (parentPath != null && parentPath.getLeaf() instanceof ParenthesizedTree) {
       parentPath = parentPath.getParentPath();
@@ -2772,7 +2962,7 @@ public final class GenericsChecks {
         return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
             methodType,
             methodType,
-            successResult.typeVarNullabilityForSite(memberReferenceTree),
+            successResult.inferredTypesForSite(memberReferenceTree),
             state,
             config);
       }
@@ -3243,7 +3433,7 @@ public final class GenericsChecks {
         return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
             methodTypeAtCallSite,
             methodType,
-            successResult.typeVarNullabilityForSite(invocationTree),
+            successResult.inferredTypesForSite(invocationTree),
             state,
             config);
       } else {
@@ -3931,7 +4121,9 @@ public final class GenericsChecks {
    */
   public void clearCache() {
     inferredTypeVarNullabilityForGenericCalls.clear();
+    inferredResultsForGenericMethodReferences.clear();
     callsWithReportedInferenceFailures.clear();
+    reportedNestedUpperBoundViolations.clear();
     inferredPolyExpressionTypes.clear();
     inferredVarLocalTypes.clear();
     varLocalDeclarations.clear();
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java b/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java
index 74c665f8..e4580e30 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java
@@ -1,7 +1,6 @@
 package com.uber.nullaway.generics;
 
 import static com.uber.nullaway.generics.ClassDeclarationNullnessAnnotUtils.getAnnotatedSupertype;
-import static com.uber.nullaway.generics.ConstraintSolver.InferredNullability.NULLABLE;
 import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;
 
 import com.google.common.base.Verify;
@@ -181,12 +180,12 @@ public class TypeSubstitutionUtils {
   }
 
   /**
-   * Updates a type {@code typeToUpdate} by applying inferred nullability for type variables. The
-   * update proceeds in three steps:
+   * Updates a type {@code typeToUpdate} by applying inferred types for type variables. The update
+   * proceeds in four steps:
    *
-   * <p>1. Substitute inferred nullability for type variables in the original type {@code origType}.
-   * So, if the {@code origType} is {@code List<T>}, and we inferred T to be nullable, the result
-   * will be {@code List<@Nullable T>}.
+   * <p>1. Substitute inferred top-level nullability for type variables in the original type {@code
+   * origType}. So, if the {@code origType} is {@code List<T>}, and we inferred T to be nullable,
+   * the result will be {@code List<@Nullable T>}.
    *
    * <p>2. Restore any explicit nullability annotations that were present on {@code origType} to the
    * result of 1. So, if {@code origType} was {@code List<@NonNull T>}, the result will be {@code
@@ -196,10 +195,18 @@ public class TypeSubstitutionUtils {
    * {@code typeToUpdate} is {@code List<String>}, and the result of 2 is {@code List<@Nullable T>},
    * the final result will be {@code List<@Nullable String>}.
    *
+   * <p>4. For type variables whose inferred type has known structure, apply the nested nullability
+   * annotations of the inferred type to the corresponding position in the result of 3. So, if
+   * {@code origType} is {@code Box<R>}, we inferred R to be {@code Box<@Nullable String>}, and the
+   * result of 3 is {@code Box<Box<String>>}, the final result is {@code Box<Box<@Nullable
+   * String>>}. This step corrects nested annotations that javac drops or misplaces in its inferred
+   * type arguments.
+   *
    * @param typeToUpdate the type to update
    * @param origType the original type with type variables and possibly explicit nullability
    *     annotations
-   * @param typeVarNullability a map from type variable elements to their inferred nullability
+   * @param inferredTypes a map from type variable elements to their inferred types, as described in
+   *     {@link ConstraintSolver#solve()}
    * @param state the visitor state
    * @param config the NullAway config
    * @return the updated type with inferred nullability applied
@@ -207,35 +214,38 @@ public class TypeSubstitutionUtils {
   static Type updateTypeWithInferredNullability(
       Type typeToUpdate,
       Type origType,
-      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
+      @Nullable Map<Element, Type> inferredTypes,
       VisitorState state,
       Config config) {
-    if (typeVarNullability == null) {
+    if (inferredTypes == null) {
       // no updates to perform
       return typeToUpdate;
     }
     // step 1
     Type inferredNullabilitySubstituted =
-        substituteInferredNullabilityForTypeVariables(origType, typeVarNullability, state, config);
+        substituteInferredNullabilityForTypeVariables(origType, inferredTypes, state, config);
     // step 2
     Type origExplicitAnnotationsRestored =
         restoreExplicitNullabilityAnnotations(origType, inferredNullabilitySubstituted, config);
     // step 3
     // TODO optimize these steps to avoid doing so many substitutions in the future, if needed
-    return restoreExplicitNullabilityAnnotations(
-        origExplicitAnnotationsRestored, typeToUpdate, config);
+    Type updated =
+        restoreExplicitNullabilityAnnotations(
+            origExplicitAnnotationsRestored, typeToUpdate, config);
+    // step 4
+    return applyNestedAnnotationsOfInferredTypes(
+        origType, updated, inferredTypes, state.getTypes(), config);
   }
 
   /**
-   * Updates a method type {@code typeToUpdate} by applying inferred nullability for type variables.
-   * The update is applied to the argument types, return type, and thrown types of the method type,
-   * using {@link #updateMethodTypeWithInferredNullability(Type.MethodType, Type.MethodType, Map,
-   * VisitorState, Config)}
+   * Updates a method type {@code typeToUpdate} by applying inferred types for type variables. The
+   * update is applied to the argument types, return type, and thrown types of the method type,
+   * using {@link #updateTypeWithInferredNullability(Type, Type, Map, VisitorState, Config)}
    *
    * @param methodTypeToUpdate method type to update
    * @param origMethodType original method type, with type variables and possibly explicit
    *     nullability annotations
-   * @param typeVarNullability a map from type variable elements to their inferred nullability
+   * @param inferredTypes a map from type variable elements to their inferred types
    * @param state the visitor state
    * @param config the NullAway config
    * @return the updated method type with inferred nullability applied
@@ -244,20 +254,19 @@ public class TypeSubstitutionUtils {
   public static Type.MethodType updateMethodTypeWithInferredNullability(
       Type.MethodType methodTypeToUpdate,
       Type.MethodType origMethodType,
-      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
+      @Nullable Map<Element, Type> inferredTypes,
       VisitorState state,
       Config config) {
     List<Type> argtypes = methodTypeToUpdate.argtypes;
     Type restype = methodTypeToUpdate.restype;
     List<Type> thrown = methodTypeToUpdate.thrown;
     List<Type> argtypes1 =
-        updateTypeListNullability(
-            argtypes, origMethodType.argtypes, typeVarNullability, state, config);
+        updateTypeListNullability(argtypes, origMethodType.argtypes, inferredTypes, state, config);
     Type restype1 =
         updateTypeWithInferredNullability(
-            restype, origMethodType.restype, typeVarNullability, state, config);
+            restype, origMethodType.restype, inferredTypes, state, config);
     List<Type> thrown1 =
-        updateTypeListNullability(thrown, origMethodType.thrown, typeVarNullability, state, config);
+        updateTypeListNullability(thrown, origMethodType.thrown, inferredTypes, state, config);
     if (argtypes1 == argtypes && restype1 == restype && thrown1 == thrown) {
       return methodTypeToUpdate;
     } else {
@@ -269,7 +278,7 @@ public class TypeSubstitutionUtils {
   private static List<Type> updateTypeListNullability(
       List<Type> typesToUpdate,
       List<Type> origTypes,
-      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
+      @Nullable Map<Element, Type> inferredTypes,
       VisitorState state,
       Config config) {
     ListBuffer<Type> buf = new ListBuffer<>();
@@ -277,8 +286,7 @@ public class TypeSubstitutionUtils {
     for (List<Type> l = typesToUpdate, l1 = origTypes; l.nonEmpty(); l = l.tail, l1 = l1.tail) {
       Type toUpdate = l.head;
       Type orig = l1.head;
-      Type t2 =
-          updateTypeWithInferredNullability(toUpdate, orig, typeVarNullability, state, config);
+      Type t2 = updateTypeWithInferredNullability(toUpdate, orig, inferredTypes, state, config);
       buf.append(t2);
       if (t2 != toUpdate) {
         changed = true;
@@ -288,47 +296,206 @@ public class TypeSubstitutionUtils {
   }
 
   /**
-   * Substitutes inferred nullability for type variables in the given target type.
+   * Substitutes inferred top-level nullability for type variables in the given target type.
    *
    * @param targetType type to which to apply substitutions
-   * @param typeVarNullability a map from type variable elements to their inferred nullability
+   * @param inferredTypes a map from type variable elements to their inferred types
    * @param state the visitor state
    * @param config the NullAway config
    * @return the type resulting from applying inferred nullability substitutions
    */
   private static Type substituteInferredNullabilityForTypeVariables(
-      Type targetType,
-      Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
-      VisitorState state,
-      Config config) {
+      Type targetType, Map<Element, Type> inferredTypes, VisitorState state, Config config) {
     ListBuffer<Type> typeVars = new ListBuffer<>();
-    ListBuffer<Type> inferredTypes = new ListBuffer<>();
-    for (Map.Entry<Element, ConstraintSolver.InferredNullability> entry :
-        typeVarNullability.entrySet()) {
+    ListBuffer<Type> inferredNullabilityTypes = new ListBuffer<>();
+    for (Map.Entry<Element, Type> entry : inferredTypes.entrySet()) {
       // find all TypeVars occurring in targetType with the same symbol and substitute for those.
       // we can have multiple such TypeVars due to previous substitutions that modified the type
       // in some way, e.g., by changing its bounds
       Element symbol = entry.getKey();
+      Type nullnessAnnotType =
+          Nullness.hasNullableAnnotation(entry.getValue().getAnnotationMirrors().stream(), config)
+              ? GenericsChecks.getSyntheticNullableAnnotType(state)
+              : GenericsChecks.getSyntheticNonNullAnnotType(state);
       TypeVarWithSymbolCollector tvc = new TypeVarWithSymbolCollector(symbol);
       targetType.accept(tvc, null);
       for (Type.TypeVar tv : tvc.getMatches()) {
         typeVars.append(tv);
-        inferredTypes.append(
-            typeWithAnnot(
-                tv,
-                entry.getValue() == NULLABLE
-                    ? GenericsChecks.getSyntheticNullableAnnotType(state)
-                    : GenericsChecks.getSyntheticNonNullAnnotType(state)));
+        inferredNullabilityTypes.append(typeWithAnnot(tv, nullnessAnnotType));
       }
     }
     List<Type> typeVarsToReplace = typeVars.toList();
     if (!typeVarsToReplace.isEmpty()) {
-      return subst(state.getTypes(), targetType, typeVarsToReplace, inferredTypes.toList(), config);
+      return subst(
+          state.getTypes(),
+          targetType,
+          typeVarsToReplace,
+          inferredNullabilityTypes.toList(),
+          config);
     } else {
       return targetType;
     }
   }
 
+  /**
+   * Walks {@code origType} and {@code target} in parallel. At each position where {@code origType}
+   * has a type variable whose inferred type has known structure (i.e., is not just the type
+   * variable itself; see {@link ConstraintSolver#solve()}), applies the nested nullability
+   * annotations of the inferred type to the corresponding position in {@code target}, keeping the
+   * top-level annotations of that position. Positions where the two types do not line up are left
+   * unchanged.
+   *
+   * @param origType the original type with type variables
+   * @param target the type to update, with the same shape as {@code origType} after substitution
+   * @param inferredTypes a map from type variable elements to their inferred types
+   * @param types the javac types instance
+   * @param config the NullAway config
+   * @return the updated type, or {@code target} itself if no updates were made
+   */
+  @SuppressWarnings("ReferenceEquality")
+  private static Type applyNestedAnnotationsOfInferredTypes(
+      Type origType,
+      Type target,
+      Map<Element, Type> inferredTypes,
+      Types types,
+      Config config) {
+    if (origType instanceof Type.TypeVar origTypeVar
+        && !(origType instanceof Type.CapturedType)) {
+      Type inferredType = inferredTypes.get(origTypeVar.tsym);
+      if (inferredType == null
+          || (inferredType instanceof Type.TypeVar && inferredType.tsym == origTypeVar.tsym)) {
+        // no structure inferred for this type variable
+        return target;
+      }
+      return applyNestedAnnotations(inferredType, target, types, config);
+    }
+    if (origType instanceof Type.ClassType origClassType
+        && target instanceof Type.ClassType targetClassType) {
+      if (origClassType.tsym != targetClassType.tsym
+          || origClassType.isRaw()
+          || targetClassType.isRaw()
+          || origClassType.getTypeArguments().size() != targetClassType.getTypeArguments().size()) {
+        return target;
+      }
+      ListBuffer<Type> newTypeArgs = new ListBuffer<>();
+      boolean changed = false;
+      for (List<Type> o = origClassType.getTypeArguments(), t = targetClassType.getTypeArguments();
+          t.nonEmpty();
+          o = o.tail, t = t.tail) {
+        Type newTypeArg =
+            applyNestedAnnotationsOfInferredTypes(o.head, t.head, inferredTypes, types, config);
+        changed |= newTypeArg != t.head;
+        newTypeArgs.append(newTypeArg);
+      }
+      Type enclosingType = targetClassType.getEnclosingType();
+      Type newEnclosingType =
+          applyNestedAnnotationsOfInferredTypes(
+              origClassType.getEnclosingType(), enclosingType, inferredTypes, types, config);
+      changed |= newEnclosingType != enclosingType;
+      return changed
+          ? TYPE_METADATA_BUILDER.createClassType(
+              targetClassType, newEnclosingType, newTypeArgs.toList())
+          : target;
+    }
+    if (origType instanceof Type.ArrayType origArrayType
+        && target instanceof Type.ArrayType targetArrayType) {
+      Type elemType = targetArrayType.getComponentType();
+      Type newElemType =
+          applyNestedAnnotationsOfInferredTypes(
+              origArrayType.getComponentType(), elemType, inferredTypes, types, config);
+      return newElemType == elemType
+          ? target
+          : TYPE_METADATA_BUILDER.createArrayType(targetArrayType, newElemType);
+    }
+    if (origType instanceof Type.WildcardType origWildcard && origWildcard.type != null) {
+      if (target instanceof Type.WildcardType targetWildcard) {
+        if (origWildcard.kind != targetWildcard.kind || targetWildcard.type == null) {
+          return target;
+        }
+        Type newBound =
+            applyNestedAnnotationsOfInferredTypes(
+                origWildcard.type, targetWildcard.type, inferredTypes, types, config);
+        return newBound == targetWildcard.type
+            ? target
+            : TYPE_METADATA_BUILDER.createWildcardType(targetWildcard, newBound);
+      }
+      if (origWildcard.kind == BoundKind.EXTENDS && !(target instanceof Type.CapturedType)) {
+        // e.g., the ground type of a functional interface type replaces ? extends S with S
+        return applyNestedAnnotationsOfInferredTypes(
+            origWildcard.type, target, inferredTypes, types, config);
+      }
+    }
+    return target;
+  }
+
+  /**
+   * Applies the nested nullability annotations of {@code inferredType} (annotations on its type
+   * arguments, enclosing type, or array component type, recursively) to {@code target}, keeping the
+   * top-level annotations of {@code target}. If {@code inferredType} is a class type for a
+   * different class than {@code target} (e.g., a subtype), it is first viewed as an instance of
+   * the class of {@code target}. If the two types cannot be aligned, returns {@code target}.
+   *
+   * @param inferredType the inferred type, whose nested annotations to apply
+   * @param target the type to update
+   * @param types the javac types instance
+   * @param config the NullAway config
+   * @return the updated type, or {@code target} itself if no updates were made
+   */
+  @SuppressWarnings("ReferenceEquality")
+  private static Type applyNestedAnnotations(
+      Type inferredType, Type target, Types types, Config config) {
+    if (target instanceof Type.CapturedType) {
+      return target;
+    }
+    if (inferredType instanceof Type.ArrayType inferredArrayType
+        && target instanceof Type.ArrayType targetArrayType) {
+      Type elemType = targetArrayType.getComponentType();
+      Type newElemType =
+          restoreExplicitNullabilityAnnotations(
+              inferredArrayType.getComponentType(), elemType, config);
+      return newElemType == elemType
+          ? target
+          : TYPE_METADATA_BUILDER.createArrayType(targetArrayType, newElemType);
+    }
+    if (!(inferredType instanceof Type.ClassType)
+        || !(target instanceof Type.ClassType targetClassType)
+        || targetClassType.isRaw()) {
+      return target;
+    }
+    Type aligned = inferredType;
+    if (inferredType.tsym != targetClassType.tsym) {
+      aligned = asSuper(types, inferredType, (Symbol.ClassSymbol) targetClassType.tsym, config);
+    }
+    if (!(aligned instanceof Type.ClassType alignedClassType)
+        || alignedClassType.isRaw()
+        || alignedClassType.tsym != targetClassType.tsym
+        || alignedClassType.getTypeArguments().size()
+            != targetClassType.getTypeArguments().size()) {
+      return target;
+    }
+    ListBuffer<Type> newTypeArgs = new ListBuffer<>();
+    boolean changed = false;
+    for (List<Type> a = alignedClassType.getTypeArguments(),
+            t = targetClassType.getTypeArguments();
+        t.nonEmpty();
+        a = a.tail, t = t.tail) {
+      Type newTypeArg = restoreExplicitNullabilityAnnotations(a.head, t.head, config);
+      changed |= newTypeArg != t.head;
+      newTypeArgs.append(newTypeArg);
+    }
+    Type enclosingType = targetClassType.getEnclosingType();
+    Type newEnclosingType = enclosingType;
+    if (enclosingType instanceof Type.ClassType
+        && alignedClassType.getEnclosingType() instanceof Type.ClassType alignedEnclosingType) {
+      newEnclosingType = applyNestedAnnotations(alignedEnclosingType, enclosingType, types, config);
+      changed |= newEnclosingType != enclosingType;
+    }
+    return changed
+        ? TYPE_METADATA_BUILDER.createClassType(
+            targetClassType, newEnclosingType, newTypeArgs.toList())
+        : target;
+  }
+
   /**
    * Replaces every occurrence of the given type variables in {@code targetType}, preserving
    * explicit nullability annotations on the replaced occurrences. So, if {@code targetType} is
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java
index 1a7fbb11..2174e57d 100644
--- a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java
@@ -307,6 +307,65 @@ public class GenericInferenceErrorReportingTests extends NullAwayTestsBase {
                 + " required");
   }
 
+  /** Nested declaration-bound violations are reported once as ordinary incompatibilities. */
+  @Test
+  public void nestedDeclarationBoundViolationReportedOnce() {
+    JavaFileObject callerSource =
+        FileObjects.forSourceLines(
+            "Caller.java",
+            """
+            package com.uber;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Caller {
+              static class Box<T extends @Nullable Object> {}
+              static class Bad extends Box<@Nullable String> {}
+              static <T extends Box<String>> T boundedId(T value) { return value; }
+              static void test(Box<@Nullable String> direct, Bad bad) {
+                boundedId(direct);
+                boundedId(bad);
+              }
+            }
+            """);
+
+    assertThat(compileAndReportDiagnostics(List.of(callerSource)))
+        .containsExactly(
+            "Caller.java:10: [NullAway] incompatible types: Box<@Nullable String> cannot be"
+                + " converted to Box<String>",
+            "Caller.java:11: [NullAway] incompatible types: Bad cannot be converted to Box<String>"
+                + " (Bad is a subtype of Box<@Nullable String>)");
+  }
+
+  /** Mixed invariant lower evidence reports ordinary incompatibilities, not inference failures. */
+  @Test
+  public void mixedInvariantLowerBoundsDoNotReportInferenceFailure() {
+    JavaFileObject callerSource =
+        FileObjects.forSourceLines(
+            "Caller.java",
+            """
+            package com.uber;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Caller {
+              static class Box<T extends @Nullable Object> {}
+              static <T extends @Nullable Object> void consume(T first, T second) {}
+              static void test(Box<@Nullable String> nullable, Box<String> nonNull) {
+                consume(nullable, nonNull);
+                consume(nonNull, nullable);
+              }
+            }
+            """);
+
+    assertThat(compileAndReportDiagnostics(List.of(callerSource)))
+        .containsExactly(
+            "Caller.java:9: [NullAway] incompatible types: Box<String> cannot be converted to"
+                + " Box<@Nullable String>",
+            "Caller.java:10: [NullAway] incompatible types: Box<@Nullable String> cannot be"
+                + " converted to Box<String>");
+  }
+
   /**
    * Fails when {@link #compileAndReportDiagnostics} reports a diagnostic that names no line in the
    * compiled source. javac emits a summary note against a file rather than against no file at all,
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodLambdaOrMethodRefArgTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodLambdaOrMethodRefArgTests.java
index 4f743845..fdd104d2 100644
--- a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodLambdaOrMethodRefArgTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodLambdaOrMethodRefArgTests.java
@@ -80,6 +80,94 @@ public class GenericMethodLambdaOrMethodRefArgTests extends NullAwayTestsBase {
         .doTest();
   }
 
+  @Test
+  public void nestedGenericLambdaCallPublishesInnerPolyType() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            package com.uber;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<T extends @Nullable Object> {
+                <R extends @Nullable Object> Box<R> flatMap(
+                    Mapper<? super T, ? extends Box<R>> mapper) {
+                  throw new AssertionError();
+                }
+              }
+              interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
+                R apply(P value);
+              }
+              interface Factory<R extends @Nullable Object> {
+                R get();
+              }
+              static <T extends @Nullable Object, R extends @Nullable Object> R map(
+                  T value, Mapper<T, R> mapper) {
+                throw new AssertionError();
+              }
+              static <T extends @Nullable Object, R extends @Nullable Object> R mapOrElse(
+                  T value, Mapper<T, R> mapper, R fallback) {
+                throw new AssertionError();
+              }
+              static <T extends @Nullable Object, R extends @Nullable Object> R mapOrSupply(
+                  T value, Mapper<T, R> mapper, Factory<R> fallback) {
+                throw new AssertionError();
+              }
+              static <U extends @Nullable Object> U id(U value) {
+                return value;
+              }
+              static <U extends @Nullable Object> U make() {
+                throw new AssertionError();
+              }
+              static void acceptsNested(Box<Box<@Nullable String>> value) {}
+              static void test(Box<Box<Box<@Nullable String>>> nested) {
+                acceptsNested(map(nested, n -> n.flatMap(box -> box)));
+              }
+              static void laterSiblingCall(
+                  Box<Box<Box<@Nullable String>>> nested,
+                  Box<Box<@Nullable String>> fallback) {
+                acceptsNested(
+                    mapOrElse(nested, n -> n.flatMap(box -> box), id(fallback)));
+              }
+              static void laterMethodReference(
+                  Box<Box<Box<@Nullable String>>> nested) {
+                acceptsNested(
+                    mapOrSupply(nested, n -> n.flatMap(box -> box), Test::make));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void receiverInstantiatedMethodReferenceBound() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              interface Fn<T extends @Nullable Object> {
+                T apply(T value);
+              }
+              static class Parent<X extends @Nullable Object> {
+                <T extends X> T id(T value) { return value; }
+              }
+              static class Child extends Parent<String> {}
+              static <T extends @Nullable Object> void use(T value, Fn<T> fn) {}
+              static void test(Child child, @Nullable String value) {
+                // BUG: Diagnostic contains: inference failure: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
+                use(value, child::id);
+              }
+            }
+            """)
+        .doTest();
+  }
+
   @Test
   public void genericCallInLambdaVarInitializerPreservesNestedNullness() {
     makeHelper()
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
index 653c4623..228ad997 100644
--- a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
@@ -1835,6 +1835,8 @@ public class GenericMethodTests extends NullAwayTestsBase {
               void test2() {
                 // BUG: Diagnostic contains: incompatible types: Supplier<OuterT> cannot be converted to Supplier<@Nullable OuterT>
                 acceptTwoSup(sup, sup2);
+                // BUG: Diagnostic contains: incompatible types: Supplier<@Nullable OuterT> cannot be converted to Supplier<OuterT>
+                acceptTwoSup(sup2, sup);
               }
             }
             """)
@@ -2483,6 +2485,294 @@ public class GenericMethodTests extends NullAwayTestsBase {
         .doTest();
   }
 
+  @Test
+  public void issue1585NestedNullnessInSubstitution() {
+    makeHelper()
+        .addSourceLines(
+            "Example.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            public final class Example {
+              public static final class Box<T extends @Nullable Object> {
+                public <R extends @Nullable Object> Box<R> flatMap(
+                    Mapper<? super T, ? extends Box<R>> mapper) {
+                  throw new UnsupportedOperationException();
+                }
+              }
+              @FunctionalInterface
+              public interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
+                R apply(P value);
+              }
+              static void acceptsNested(Box<Box<@Nullable String>> box) {}
+              static void reproduce(Box<Box<Box<@Nullable String>>> nested) {
+                // R should be inferred as Box<@Nullable String>, which is @NonNull at the top level
+                // but has a nested @Nullable type argument
+                var flat = nested.flatMap(box -> box);
+                acceptsNested(flat);
+              }
+              static void blockBody(Box<Box<Box<@Nullable String>>> nested) {
+                var flat = nested.flatMap(box -> { return box; });
+                acceptsNested(flat);
+              }
+              static void explicitDeclaration(Box<Box<Box<@Nullable String>>> nested) {
+                Box<Box<@Nullable String>> flat = nested.flatMap(box -> box);
+                acceptsNested(flat);
+              }
+              static void nestedNonNull(Box<Box<Box<String>>> nested) {
+                var flat = nested.flatMap(box -> box);
+                // BUG: Diagnostic contains: incompatible types: Box<Box<String>> cannot be converted to Box<Box<@Nullable String>>
+                acceptsNested(flat);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1585LambdaArgumentOfNestedCall() {
+    makeHelper()
+        .addSourceLines(
+            "Example.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            public final class Example {
+              public static final class Box<T extends @Nullable Object> {
+                public <R extends @Nullable Object> Box<R> flatMap(
+                    Mapper<? super T, ? extends Box<R>> mapper) {
+                  throw new UnsupportedOperationException();
+                }
+              }
+              @FunctionalInterface
+              public interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
+                R apply(P value);
+              }
+              static <T extends @Nullable Object> T id(T t) {
+                return t;
+              }
+              static void acceptsNested(Box<Box<@Nullable String>> box) {}
+              static void nestedCall(Box<Box<Box<@Nullable String>>> nested) {
+                // flatMap is a nested call in the inference problem for id; the type of its lambda
+                // argument must also reflect R = Box<@Nullable String>
+                acceptsNested(id(nested.flatMap(box -> box)));
+              }
+              static void nestedCallBlockBody(Box<Box<Box<@Nullable String>>> nested) {
+                acceptsNested(id(nested.flatMap(box -> { return box; })));
+              }
+              static void nestedCallNonNull(Box<Box<Box<String>>> nested) {
+                // BUG: Diagnostic contains: incompatible types: Box<Box<String>> cannot be converted to Box<Box<@Nullable String>>
+                acceptsNested(id(nested.flatMap(box -> box)));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void inferredNestedNullnessIsPerCall() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            public class Test {
+              static class Box<T extends @Nullable Object> {}
+              static <T extends @Nullable Object> T id(T t) {
+                return t;
+              }
+              static void acceptsNullableBox(Box<@Nullable String> box) {}
+              static void acceptsNonNullBox(Box<String> box) {}
+              static void test(Box<@Nullable String> nullableBox, Box<String> nonNullBox) {
+                // each call to id gets its own type argument, including nested nullability
+                acceptsNullableBox(id(nullableBox));
+                acceptsNonNullBox(id(nonNullBox));
+                var a = id(nullableBox);
+                var b = id(nonNullBox);
+                acceptsNullableBox(a);
+                acceptsNonNullBox(b);
+                // BUG: Diagnostic contains: incompatible types: Box<String> cannot be converted to Box<@Nullable String>
+                acceptsNullableBox(b);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void fullTypeInferenceRespectsNestedDeclarationBound() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<T extends @Nullable Object> {}
+              static class Bad extends Box<@Nullable String> {}
+              static class Good extends Box<String> {}
+              static <T extends Box<String>> T boundedId(T value) {
+                return value;
+              }
+              static <S extends Box<@Nullable String>> void badTypeVariable(S value) {
+                // BUG: Diagnostic contains: incompatible types
+                boundedId(value);
+              }
+              static <S extends Box<String>> void goodTypeVariable(S value) {
+                boundedId(value);
+              }
+              static void test(Box<@Nullable String> nullableBox, Bad bad, Good good) {
+                // BUG: Diagnostic contains: incompatible types
+                boundedId(nullableBox);
+                // BUG: Diagnostic contains: incompatible types
+                boundedId(bad);
+                boundedId(good);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void fullTypeInferenceMergesCovariantArrayBoundsInEitherOrder() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static <T extends @Nullable Object> T pick(T first, T second) {
+                return first;
+              }
+              static void acceptsNonNullElements(String[] value) {}
+              static void acceptsNullableElements(@Nullable String[] value) {}
+              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
+                // BUG: Diagnostic contains: incompatible types
+                acceptsNonNullElements(pick(nonNullElements, nullableElements));
+                // BUG: Diagnostic contains: incompatible types
+                acceptsNonNullElements(pick(nullableElements, nonNullElements));
+                acceptsNullableElements(pick(nonNullElements, nullableElements));
+                acceptsNullableElements(pick(nullableElements, nonNullElements));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void receiverInstantiatedMethodVariableBounds() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<T extends @Nullable Object> {}
+              static class Parent<X extends @Nullable Object> {
+                <T extends X> T id(T value) { return value; }
+                <T extends Box<X>> T boxId(T value) { return value; }
+              }
+              static class Child extends Parent<String> {}
+              static void test(Child child, Box<@Nullable String> nullableBox) {
+                child.id("value");
+                // BUG: Diagnostic contains: inference failure: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
+                child.id(null);
+                // BUG: Diagnostic contains: incompatible types
+                child.boxId(nullableBox);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void inheritedStructuredEvidenceReachesFixedPoint() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<T extends @Nullable Object> {}
+              static class Base<T extends @Nullable Object> {}
+              static class Nested extends Base<Box<@Nullable String>> {}
+              static class GenericNested<S extends @Nullable Object> extends Base<@Nullable S> {}
+              static <U extends @Nullable Object, T extends Base<U>> void consume(T value) {}
+              static void test(Nested nested) {
+                consume(nested);
+              }
+              static <S extends @Nullable Object> void genericTest(GenericNested<S> nested) {
+                consume(nested);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void wildcardDeclarationBoundUsesContainmentFallback() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<T extends @Nullable Object> {}
+              static <T extends Box<? super String>> T id(T value) { return value; }
+              static void test(Box<@Nullable Object> box) {
+                id(box);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void unmarkedDeclarationBoundIsNotNullnessEvidence() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NonNull;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            import org.jspecify.annotations.NullUnmarked;
+            @NullMarked
+            class Test {
+              static class Box<T extends @Nullable Object> {}
+              @NullUnmarked
+              static <T extends Box<@Nullable String>> @NonNull T id(T value) {
+                return value;
+              }
+              @NullUnmarked
+              static class C<T extends Box<@Nullable String>> {
+                @NullMarked
+                C(T value) {}
+              }
+              static void takes(Box<String> value) {}
+              static void test(Box<String> value) {
+                var inferred = id(value);
+                takes(inferred);
+                new C<>(value);
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

Validation:

- Independent final agent review — APPROVED
- `./gradlew :nullaway:test` — passed
- `./gradlew :nullaway:buildWithNullAway` — passed
