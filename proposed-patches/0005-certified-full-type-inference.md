# Patch 005: Certify call-specific full-type inference results

Apply after 03 + 004 + the optional CODE-QUALITY-FOLLOWUP, or after 01 + 02 + 004 + that cleanup. The cleanup is the tested baseline for this exported diff.

Actual implementation patch: attributed shape recovery, explicit complete/incomplete/inconsistent outcomes, structured bound certification, complete invocation/reference consumers, stable final targets, and compiler-backed direct solver tests. 006 is a separate removal patch on top.

```diff
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
index ff144cd5..bae4fefa 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
@@ -2,6 +2,9 @@ package com.uber.nullaway.generics;
 
 import com.sun.source.tree.Tree;
 import com.sun.tools.javac.code.Type;
+import java.util.Collections;
+import java.util.LinkedHashMap;
+import java.util.LinkedHashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Set;
@@ -27,6 +30,36 @@ public interface ConstraintSolver {
    */
   record InferenceVariable(Element typeVariable, Tree site) {}
 
+  /**
+   * Inferred annotation sources and their certification status. Sources for incomplete or
+   * inconsistent variables are retained for ordinary compatibility diagnostics, not for caching as
+   * successful inference. All collections are immutable snapshots in insertion order.
+   */
+  record Solution(
+      Map<InferenceVariable, Type> inferredTypes,
+      Set<InferenceVariable> incompleteVariables,
+      Set<InferenceVariable> inconsistentVariables) {
+    /** Takes immutable, insertion-ordered snapshots of the supplied inference result. */
+    public Solution {
+      inferredTypes = Collections.unmodifiableMap(new LinkedHashMap<>(inferredTypes));
+      incompleteVariables =
+          Collections.unmodifiableSet(new LinkedHashSet<>(incompleteVariables));
+      inconsistentVariables =
+          Collections.unmodifiableSet(new LinkedHashSet<>(inconsistentVariables));
+    }
+
+    /** Returns whether every registered variable has a certified, consistent inferred type. */
+    public boolean isComplete() {
+      return incompleteVariables.isEmpty() && inconsistentVariables.isEmpty();
+    }
+
+    /** Returns whether every variable registered at {@code site} is complete and consistent. */
+    public boolean isCompleteForSite(Tree site) {
+      return incompleteVariables.stream().noneMatch(variable -> variable.site().equals(site))
+          && inconsistentVariables.stream().noneMatch(variable -> variable.site().equals(site));
+    }
+  }
+
   /**
    * Registers the type variables whose nullability is inferred at {@code site}. Must be called
    * before adding constraints involving these variables; type variables not registered via this
@@ -48,11 +81,33 @@ public interface ConstraintSolver {
    *     are an authoritative nullness contract
    * @return a map from each declared type variable to its fresh type variable for {@code site}
    */
+  default Map<Element, Type.TypeVar> registerInferenceVariables(
+      Tree site,
+      List<? extends Element> typeVariables,
+      Map<? extends Element, ? extends Type> instantiatedUpperBounds,
+      Set<? extends Element> variablesWithNullnessMarkedBounds) {
+    return registerInferenceVariables(
+        site, typeVariables, instantiatedUpperBounds, variablesWithNullnessMarkedBounds, Map.of());
+  }
+
+  /**
+   * Registers inference variables with javac's authoritative Java instantiations. Nullness evidence
+   * is projected onto these shapes; the solver never replaces their Java structure. Missing shapes
+   * yield incomplete results, even if an annotation source is available.
+   *
+   * @param site the inference site
+   * @param typeVariables the declared variables inferred at the site
+   * @param instantiatedUpperBounds receiver/class-substituted declaration upper bounds
+   * @param variablesWithNullnessMarkedBounds declarations with authoritative nullness bounds
+   * @param javacInstantiations attributed Java type arguments, keyed by declared variable element
+   * @return the fresh variables, as in the four-argument overload
+   */
   Map<Element, Type.TypeVar> registerInferenceVariables(
       Tree site,
       List<? extends Element> typeVariables,
       Map<? extends Element, ? extends Type> instantiatedUpperBounds,
-      Set<? extends Element> variablesWithNullnessMarkedBounds);
+      Set<? extends Element> variablesWithNullnessMarkedBounds,
+      Map<? extends Element, ? extends Type> javacInstantiations);
 
   /**
    * Exception thrown when the constraints added to the solver are determined to be unsatisfiable.
@@ -151,13 +206,13 @@ public interface ConstraintSolver {
       throws UnsatisfiableConstraintsException;
 
   /**
-   * Solve the constraints, returning a map from inference variables to their inferred
-   * nullness-annotated types. The map only contains inference variables that appear in
-   * constraints.
+   * Solves constraints for every registered variable, including unconstrained variables, returning
+   * nullness-annotated types together with explicit completion and consistency status.
    *
-   * <p>The top-level annotation of each inferred type is a synthetic {@code @Nullable} or {@code
-   * @NonNull} annotation (see {@link GenericsChecks#getSyntheticNullableAnnotType}) giving the
-   * variable's inferred top-level nullability. When the constraints determine the structure of the
+   * <p>Concrete inferred types carry a synthetic {@code @Nullable} or {@code @NonNull} root
+   * annotation (see {@link GenericsChecks#getSyntheticNullableAnnotType}) giving their inferred
+   * nullability. An unconstrained symbolic caller type variable retains its original root instead
+   * of receiving an inferred default. When the constraints determine the structure of the
    * type argument, e.g., {@code R = Box<@Nullable String>} for a constraint {@code
    * Box<Box<@Nullable String>> <: Box<R>}, the inferred type is that type, including its nested
    * nullability annotations. Covariant array components may be merged, while invariant generic
@@ -168,14 +223,16 @@ public interface ConstraintSolver {
    * with a contextual constraint, the lower-bound
    * type can be returned as annotation evidence so the existing ordinary argument or
    * method-reference check reports the incompatibility at its established source location. If no
-   * safe annotation source can be established, the inferred type is the declared type variable
-   * itself, meaning only top-level nullability is inferred and the rest of the type argument comes
-   * from javac's inference and the repair fallback.
+   * safe annotation source can be established, scalar evidence is retained but is not certified as
+   * complete. Known javac instantiations supply the Java shape for annotation projection. Every
+   * relevant substituted structured bound is checked: unknown relationships make the result
+   * incomplete, and violations make it inconsistent. Fixed caller type variables remain symbolic.
+   * Diagnostic sources in either status must not be cached as successful inference.
    *
-   * @return a map from inference variables to their inferred types
+   * @return inferred types and explicit incomplete/inconsistent variable sets
    * @throws NestedUpperBoundViolationException if a declaration-bound violation is proven but no
    *     recursively shape-compatible annotation source can preserve ordinary diagnostics
    * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
    */
-  Map<InferenceVariable, Type> solve() throws UnsatisfiableConstraintsException;
+  Solution solve() throws UnsatisfiableConstraintsException;
 }
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
index b925eefd..c590b75f 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
@@ -13,6 +13,7 @@ import com.sun.tools.javac.code.Type.CapturedType;
 import com.sun.tools.javac.code.Type.ClassType;
 import com.sun.tools.javac.code.Type.TypeVar;
 import com.sun.tools.javac.code.Type.WildcardType;
+import com.sun.tools.javac.code.TypeTag;
 import com.sun.tools.javac.code.Types;
 import com.uber.nullaway.CodeAnnotationInfo;
 import com.uber.nullaway.Config;
@@ -60,6 +61,10 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   private final Map<InferenceVariable, Type.TypeVar> freshTypeVariableForInferenceVariable =
       new LinkedHashMap<>();
 
+  /** Authoritative Java shapes supplied by attribution, never inferred from nullness bounds. */
+  private final Map<InferenceVariable, Type> javacInstantiations = new LinkedHashMap<>();
+
+
   /** Effective upper-bound nullability after receiver/class substitution. */
   private final Map<InferenceVariable, Boolean> nullableAllowedForInferenceVariable =
       new LinkedHashMap<>();
@@ -106,7 +111,7 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
 
     /**
      * Structured evidence {@code S} with a constraint {@code S <: var}, including arrays, non-raw
-     * classes, and annotated upper bounds of fixed type variables. Non-generic subclasses are kept
+     * classes, and symbolic fixed type variables. Non-generic subclasses are kept
      * because alignment to a generic supertype can expose nested nullability.
      */
     final List<Type> lowerBoundTypes = new ArrayList<>();
@@ -114,7 +119,7 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     /** Structural fingerprints used to deduplicate {@link #lowerBoundTypes}. */
     final Set<String> lowerBoundKeys = new LinkedHashSet<>();
 
-    /** Fixed type-variable uses whose upper bounds supplied entries in {@link #lowerBoundTypes}. */
+    /** Fixed type-variable lower uses retained as declaration-diagnostic provenance. */
     final List<Type> fixedTypeVariableLowerBounds = new ArrayList<>();
 
     /** Structural fingerprints used to deduplicate fixed type-variable provenance. */
@@ -164,9 +169,11 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
       Tree site,
       List<? extends Element> typeVariables,
       Map<? extends Element, ? extends Type> instantiatedUpperBounds,
-      Set<? extends Element> variablesWithNullnessMarkedBounds) {
+      Set<? extends Element> variablesWithNullnessMarkedBounds,
+      Map<? extends Element, ? extends Type> javacInstantiations) {
     Map<Element, Type.TypeVar> existing = freshTypeVariablesForSite.get(site);
     if (existing != null) {
+      recordJavacInstantiations(site, existing.keySet(), javacInstantiations);
       return existing;
     }
     // First create all the fresh type variables, since the upper bound of one type variable can
@@ -236,9 +243,26 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
       }
     }
     freshTypeVariablesForSite.put(site, fresh);
+    recordJavacInstantiations(site, fresh.keySet(), javacInstantiations);
+    for (Element variable : fresh.keySet()) {
+      getState(new InferenceVariable(variable, site));
+    }
     return fresh;
   }
 
+  /** Records supplied shapes without discarding earlier shapes on repeated registration. */
+  private void recordJavacInstantiations(
+      Tree site,
+      Set<Element> variables,
+      Map<? extends Element, ? extends Type> instantiations) {
+    for (Element variable : variables) {
+      Type shape = instantiations.get(variable);
+      if (shape != null) {
+        javacInstantiations.put(new InferenceVariable(variable, site), shape);
+      }
+    }
+  }
+
   @Override
   public void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType)
       throws UnsatisfiableConstraintsException {
@@ -299,6 +323,8 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
           Type subtypeTypeArg = subtypeTypeArguments.get(i);
           constrainTypeArgumentContainment(subtypeTypeArg, supertypeTypeArg);
         }
+        // Non-static inner types can carry inference variables only in their enclosing type.
+        subtypeAsSuper.getEnclosingType().accept(this, supertype.getEnclosingType());
       }
       // if supertype is not a ClassType, we still call visitType to handle the case where
       // supertype is a TypeVar or a wildcard
@@ -324,6 +350,18 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     public @Nullable Void visitTypeVar(TypeVar subtype, Type supertype) {
       if (!localVariableType) {
         directlyConstrainTypePair(subtype, supertype);
+        if (inferenceVariableForStructure(subtype) == null
+            && !(subtype instanceof CapturedType)
+            && isStructuredType(supertype)
+            && beginBoundComparison("fixed-constraints", subtype, supertype)) {
+          try {
+            // A fixed caller variable can constrain nested inference through its declaration
+            // contract, without ever using that contract as its replacement shape.
+            subtype.getUpperBound().accept(this, supertype);
+          } finally {
+            endBoundComparison("fixed-constraints", subtype, supertype);
+          }
+        }
       }
       return visitType(subtype, supertype);
     }
@@ -453,7 +491,7 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   }
 
   @Override
-  public Map<InferenceVariable, Type> solve() throws UnsatisfiableConstraintsException {
+  public Solution solve() throws UnsatisfiableConstraintsException {
     prepareStructuredConstraints();
 
     /* ---------- work-list propagation of nullability ---------- */
@@ -507,81 +545,138 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     // evidence during validation of a dependent variable.
     declarationBoundFallbacks.putAll(validatedFallbacks);
 
-    /* ---------- build final solution map ---------- */
+    /* ---------- build and certify the final solution ---------- */
     Map<InferenceVariable, Type> result = new LinkedHashMap<>();
     Map<InferenceVariable, Type> inferredTypes = new LinkedHashMap<>();
+    Set<InferenceVariable> incomplete = new LinkedHashSet<>();
+    Set<InferenceVariable> inconsistent = new LinkedHashSet<>();
     for (InferenceVariable inferenceVar : vars.keySet()) {
-      result.put(inferenceVar, inferredType(inferenceVar, inferredTypes, new LinkedHashSet<>()));
+      result.put(
+          inferenceVar,
+          inferredType(inferenceVar, inferredTypes, new LinkedHashSet<>(), incomplete));
+      if (declarationBoundFallbacks.containsKey(inferenceVar)) {
+        inconsistent.add(inferenceVar);
+      }
     }
-    return result;
+    validateSolution(result, incomplete, inconsistent);
+    propagateUncertifiedDependencies(incomplete, inconsistent);
+    return new Solution(result, incomplete, inconsistent);
   }
 
   /**
    * Computes the nullness-annotated type inferred for {@code inferenceVar}, after nullability
    * propagation has reached a fixed point.
    *
-   * <p>The top-level annotation of the result is a synthetic {@code @Nullable} or {@code @NonNull}
-   * annotation giving the variable's inferred top-level nullability. (If the nullness state is
-   * UNKNOWN, we infer NONNULL arbitrarily; TODO does this matter? should we use NULLABLE instead?)
-   *
-   * <p>If a structured bound for the variable is available (see {@link #structuredBound}), the
-   * result is that bound, with nested nullability annotations preserved, inference variables of
-   * this problem within it replaced by their own inferred types, and unannotated nested class and
-   * array types marked as {@code @NonNull}. Marking those types matters since javac's inferred type
-   * argument may contain misplaced {@code @Nullable} annotations, and consumers overlay the nested
-   * annotations of the result onto javac's type. Otherwise, the result is the declared type
-   * variable itself, indicating that only top-level nullability was inferred and the rest of the
-   * type should come from javac's inferred type argument.
+   * <p>With an attributed Java shape, each resolved lower-bound source (or upper-bound source when
+   * there are no lowers, with declaration bounds preceding contextual bounds) is projected onto
+   * that shape before merging annotations. This permits
+   * distinct nominal lower types to share javac's chosen supertype without inventing a Java shape.
+   * Covariant array components merge nullness; invariant conflicts retain diagnostic evidence and
+   * are rejected by independent validation. Without a shape, the legacy annotation source remains
+   * available, but the variable is incomplete. Unconstrained fixed caller variables stay symbolic.
    *
    * @param inferenceVar the inference variable
    * @param memo already computed inferred types
    * @param inProgress variables whose inferred types are currently being computed, to guard against
    *     cycles, e.g., from a constraint {@code List<T> <: T}
-   * @return the inferred type
+   * @param incomplete variables whose structure cannot be certified
+   * @return the inferred type or diagnostic annotation source
    */
   private Type inferredType(
       InferenceVariable inferenceVar,
       Map<InferenceVariable, Type> memo,
-      Set<InferenceVariable> inProgress) {
+      Set<InferenceVariable> inProgress,
+      Set<InferenceVariable> incomplete) {
     Type memoized = memo.get(inferenceVar);
     if (memoized != null) {
       return memoized;
     }
-    VarState st = vars.get(inferenceVar);
-    Type nullnessAnnotType =
-        st != null && st.nullness == NullnessState.NULLABLE
-            ? GenericsChecks.getSyntheticNullableAnnotType(state)
-            : GenericsChecks.getSyntheticNonNullAnnotType(state);
+    VarState st = castToNonNull(vars.get(inferenceVar));
     Type declaredTypeVariable = (Type) inferenceVar.typeVariable().asType();
-    Type bound =
-        hasStructuredDependencyCycle(inferenceVar)
-            ? null
-            : structuredBound(inferenceVar);
-    if (bound == null || !inProgress.add(inferenceVar)) {
-      // only top-level nullability is known; don't memoize a result computed due to a cycle
-      Type result = TypeSubstitutionUtils.typeWithAnnot(declaredTypeVariable, nullnessAnnotType);
-      if (bound == null) {
-        memo.put(inferenceVar, result);
-      }
-      return result;
+    Type shape = javacInstantiations.get(inferenceVar);
+    if (shape == null || !hasCertifiedJavaStructure(shape, new IdentityHashMap<>())) {
+      incomplete.add(inferenceVar);
+    }
+    Type scalarSource =
+        shape == null
+            ? TypeSubstitutionUtils.typeWithAnnot(
+                declaredTypeVariable,
+                st.nullness == NullnessState.NULLABLE
+                    ? GenericsChecks.getSyntheticNullableAnnotType(state)
+                    : GenericsChecks.getSyntheticNonNullAnnotType(state))
+            : inferredRoot(shape, st);
+    if (shape == null && hasStructuredDependencyCycle(inferenceVar)) {
+      incomplete.add(inferenceVar);
+      return scalarSource;
+    }
+    if (!inProgress.add(inferenceVar)) {
+      // Attributed shapes can break structural recursion. The seed is not certification: every
+      // substituted bound is still checked against the final solution below.
+      return scalarSource;
     }
     try {
-      // replace inference variables of this problem occurring within the bound with their inferred
-      // types; only their annotations matter, since consumers copy annotations, not types, from
-      // the result
-      Map<Element, Type> replacements = new LinkedHashMap<>();
-      for (Map.Entry<Element, InferenceVariable> entry : inferenceVariables.entrySet()) {
-        TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(entry.getKey());
-        bound.accept(collector, null);
-        if (!collector.getMatches().isEmpty()) {
-          replacements.put(entry.getKey(), inferredType(entry.getValue(), memo, inProgress));
+      StructuredBounds bounds = structuredBounds(inferenceVar);
+      List<Type> sources;
+      Type declarationFallback = declarationBoundFallbacks.get(inferenceVar);
+      if (declarationFallback != null) {
+        sources = List.of(declarationFallback);
+      } else if (shape == null) {
+        Type legacySource = structuredBound(inferenceVar);
+        sources = legacySource == null ? List.of() : List.of(legacySource);
+      } else if (bounds.lower().isEmpty()) {
+        // Preserve the declaration contract as the diagnostic source when a contextual upper
+        // conflicts with it. Every upper still participates in independent solution validation.
+        sources = new ArrayList<>(bounds.declaredUpper());
+        for (Type upper : bounds.upper()) {
+          addIfNotIdentical(sources, upper);
+        }
+      } else {
+        sources = bounds.lower();
+      }
+      Type candidate = null;
+      for (Type source : sources) {
+        Map<Element, Type> replacements = new LinkedHashMap<>();
+        for (Map.Entry<Element, InferenceVariable> entry : inferenceVariables.entrySet()) {
+          TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(entry.getKey());
+          source.accept(collector, null);
+          if (!collector.getMatches().isEmpty()) {
+            replacements.put(
+                entry.getKey(), inferredType(entry.getValue(), memo, inProgress, incomplete));
+          }
+        }
+        Type resolved =
+            TypeSubstitutionUtils.substituteTypeVariables(
+                source, replacements, state.getTypes(), config);
+        Type evidence = inferredRoot(resolved, st);
+        Type projected;
+        if (shape == null) {
+          projected = evidence;
+        } else if (shape instanceof TypeVar && st.nullness == NullnessState.UNKNOWN) {
+          // The projection helper interprets absent root annotations as non-null. Preserve a
+          // symbolic caller variable instead; it has no nested positions to overlay.
+          projected = shape;
+        } else {
+          projected =
+              TypeSubstitutionUtils.updateTypeWithInferredNullability(
+                  markNestedTypesNonNull(shape),
+                  declaredTypeVariable,
+                  Map.of(inferenceVar.typeVariable(), evidence),
+                  state,
+                  config);
+        }
+        if (candidate == null) {
+          candidate = projected;
+        } else {
+          Type merged = mergeCovariantLowerBounds(candidate, projected, false);
+          if (merged == null) {
+            // Keep the first diagnostic source; validation visits all sources independently.
+            incomplete.add(inferenceVar);
+          } else {
+            candidate = merged;
+          }
         }
       }
-      Type resolved =
-          TypeSubstitutionUtils.substituteTypeVariables(
-              bound, replacements, state.getTypes(), config);
-      Type result =
-          TypeSubstitutionUtils.typeWithAnnot(markNestedTypesNonNull(resolved), nullnessAnnotType);
+      Type result = candidate == null ? scalarSource : inferredRoot(candidate, st);
       memo.put(inferenceVar, result);
       return result;
     } finally {
@@ -589,10 +684,164 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     }
   }
 
+  /** Applies solved root nullness without imposing a default on symbolic fixed variables. */
+  private Type inferredRoot(Type source, VarState st) {
+    Type annotated = markNestedTypesNonNull(source);
+    if (source instanceof TypeVar
+        && inferenceVariableForStructure(source) == null
+        && st.nullness == NullnessState.UNKNOWN) {
+      return annotated;
+    }
+    return TypeSubstitutionUtils.typeWithAnnot(
+        annotated,
+        st.nullness == NullnessState.NULLABLE
+            ? GenericsChecks.getSyntheticNullableAnnotType(state)
+            : GenericsChecks.getSyntheticNonNullAnnotType(state));
+  }
+
+  /**
+   * Checks whether a supplied Java shape contains only resolved structure. Fixed type variables
+   * are symbolic leaves: their upper bounds are contracts, not replacement shapes. Raw types,
+   * captures, errors, fresh inference variables, and recursive structural objects are uncertified.
+   */
+  private boolean hasCertifiedJavaStructure(Type type, IdentityHashMap<Type, Boolean> visiting) {
+    if (type.isErroneous() || type instanceof CapturedType || visiting.put(type, true) != null) {
+      return false;
+    }
+    try {
+      if (type instanceof TypeVar) {
+        return inferenceVariableForStructure(type) == null;
+      }
+      if (type instanceof Type.ArrayType array) {
+        return hasCertifiedJavaStructure(array.elemtype, visiting);
+      }
+      if (type instanceof WildcardType wildcard) {
+        if (!config.handleWildcardGenerics()) {
+          return false;
+        }
+        Type bound =
+            wildcard.kind == BoundKind.UNBOUND
+                ? GenericsUtils.wildcardUpperBound(wildcard, state, config, handler)
+                : wildcard.type;
+        return bound != null && hasCertifiedJavaStructure(bound, visiting);
+      }
+      if (type instanceof ClassType classType) {
+        if (classType.isRaw()) {
+          return false;
+        }
+        if (classType instanceof Type.IntersectionClassType intersection) {
+          for (TypeMirror bound : intersection.getBounds()) {
+            if (!hasCertifiedJavaStructure((Type) bound, visiting)) {
+              return false;
+            }
+          }
+        }
+        for (Type argument : classType.getTypeArguments()) {
+          if (!hasCertifiedJavaStructure(argument, visiting)) {
+            return false;
+          }
+        }
+        return hasCertifiedJavaStructure(classType.getEnclosingType(), visiting);
+      }
+      return type.isPrimitive() || type.hasTag(TypeTag.NONE);
+    } finally {
+      visiting.remove(type);
+    }
+  }
+
+  /**
+   * Validates every structured lower, upper, and declaration bound after substitution. Pairwise
+   * validation remains independent of candidate construction, so a failed merge or unknown sibling
+   * cannot hide a concrete conflict. Root occurrence checks stay with ordinary compatibility checks.
+   */
+  private void validateSolution(
+      Map<InferenceVariable, Type> result,
+      Set<InferenceVariable> incomplete,
+      Set<InferenceVariable> inconsistent) {
+    Map<Element, Type> substitutions = new LinkedHashMap<>();
+    inferenceVariables.forEach(
+        (symbol, variable) -> substitutions.put(symbol, castToNonNull(result.get(variable))));
+    for (InferenceVariable variable : result.keySet()) {
+      StructuredBounds bounds = structuredBounds(variable);
+      Type candidate = result.get(variable);
+      List<Type> lowerBounds = substituteBounds(bounds.lower(), substitutions);
+      List<Type> upperBounds = substituteBounds(bounds.upper(), substitutions);
+      for (Type lower : lowerBounds) {
+        recordBoundRelation(
+            variable, nullabilitySubtype(lower, candidate, false), incomplete, inconsistent);
+        for (Type upper : upperBounds) {
+          recordBoundRelation(
+              variable, nullabilitySubtype(lower, upper, false), incomplete, inconsistent);
+        }
+      }
+      for (Type upper : upperBounds) {
+        recordBoundRelation(
+            variable, nullabilitySubtype(candidate, upper, false), incomplete, inconsistent);
+      }
+      for (Type upper : substituteBounds(bounds.declaredUpper(), substitutions)) {
+        recordBoundRelation(
+            variable, nullabilitySubtype(candidate, upper, false), incomplete, inconsistent);
+      }
+      for (InferenceVariable supertype : castToNonNull(vars.get(variable)).structuralSupertypes) {
+        recordBoundRelation(
+            variable,
+            nullabilitySubtype(candidate, castToNonNull(result.get(supertype)), false),
+            incomplete,
+            inconsistent);
+      }
+    }
+  }
+
+  /** Substitutes solved fresh variables while preserving explicit occurrence annotations. */
+  private List<Type> substituteBounds(List<Type> bounds, Map<Element, Type> substitutions) {
+    List<Type> result = new ArrayList<>();
+    for (Type bound : bounds) {
+      result.add(
+          TypeSubstitutionUtils.substituteTypeVariables(
+              bound, substitutions, state.getTypes(), config));
+    }
+    return result;
+  }
+
+  /** Records uncertified relations without discarding annotation evidence used for diagnostics. */
+  private static void recordBoundRelation(
+      InferenceVariable variable,
+      BoundRelation relation,
+      Set<InferenceVariable> incomplete,
+      Set<InferenceVariable> inconsistent) {
+    if (relation == BoundRelation.UNKNOWN) {
+      incomplete.add(variable);
+    } else if (relation == BoundRelation.VIOLATED) {
+      inconsistent.add(variable);
+    }
+  }
+
+  /** Propagates uncertified evidence through structural dependencies to a fixed point. */
+  private void propagateUncertifiedDependencies(
+      Set<InferenceVariable> incomplete, Set<InferenceVariable> inconsistent) {
+    boolean changed;
+    do {
+      changed = false;
+      for (Map.Entry<InferenceVariable, VarState> entry : vars.entrySet()) {
+        Set<InferenceVariable> dependencies = directStructuredDependencies(entry.getKey());
+        dependencies.addAll(entry.getValue().structuralSubtypes);
+        dependencies.addAll(entry.getValue().structuralSupertypes);
+        for (InferenceVariable dependency : dependencies) {
+          if (incomplete.contains(dependency)) {
+            changed |= incomplete.add(entry.getKey());
+          }
+          if (inconsistent.contains(dependency)) {
+            changed |= inconsistent.add(entry.getKey());
+          }
+        }
+      }
+    } while (changed);
+  }
+
   /**
    * Returns whether structured evidence reachable from {@code start} contains a dependency cycle.
-   * Cyclic components use top-level-only inference so no traversal-order-dependent partial unfolding
-   * is published.
+   * Without authoritative Java shapes, cyclic components cannot supply a certified unfolding.
+   * Independent bound validation still visits concrete positions within cyclic declarations.
    */
   private boolean hasStructuredDependencyCycle(InferenceVariable start) {
     return hasStructuredDependencyCycle(start, new LinkedHashSet<>(), new LinkedHashSet<>());
@@ -730,13 +979,13 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   }
 
   /**
-   * Returns a deterministic structured annotation source for {@code inferenceVar}, or {@code null}
-   * to leave nested nullability to javac and the existing repair visitor.
+   * Returns a deterministic structured annotation source for comparison and legacy diagnostics,
+   * or {@code null} when the evidence cannot be reconciled. This is not a completion certificate.
    *
    * <p>All reachable lower and upper bounds participate. Lower bounds are merged only through
    * covariant positions; generic type arguments remain invariant. If there are no lower bounds, the
-   * most specific compatible upper bound is used. Unknown structure falls back to javac and the
-   * repair visitor. A contextual upper-bound mismatch is retained as lower-bound annotation evidence
+   * most specific compatible upper bound is used. Unknown structure remains uncertified. A
+   * contextual upper-bound mismatch is retained as lower-bound annotation evidence
    * so the established ordinary argument or method-reference check reports the incompatibility.
    */
   private @Nullable Type structuredBound(InferenceVariable inferenceVar) {
@@ -871,7 +1120,23 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
       }
       return canOverlayBound(targetClass.getEnclosingType(), sourceClass.getEnclosingType());
     }
-    // Wildcard bounds and unresolved variables need more than positional annotation copying.
+    if (target instanceof TypeVar targetVariable && source instanceof TypeVar sourceVariable) {
+      return inferenceVariableForStructure(target) == null
+          && inferenceVariableForStructure(source) == null
+          && !(target instanceof CapturedType)
+          && !(source instanceof CapturedType)
+          && targetVariable.tsym.equals(sourceVariable.tsym);
+    }
+    if (target instanceof WildcardType targetWildcard
+        && source instanceof WildcardType sourceWildcard) {
+      return config.handleWildcardGenerics()
+          && targetWildcard.kind == sourceWildcard.kind
+          && (targetWildcard.kind == BoundKind.UNBOUND
+              || (targetWildcard.type != null
+                  && sourceWildcard.type != null
+                  && canOverlayBound(targetWildcard.type, sourceWildcard.type)));
+    }
+    // Unresolved variables and differing wildcard forms cannot be copied positionally.
     if (target instanceof TypeVar
         || source instanceof TypeVar
         || target instanceof WildcardType
@@ -993,7 +1258,8 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
       for (TypeMirror component : intersectionType.getBounds()) {
         addStructuredDeclaredUpperBounds(result, (Type) component);
       }
-    } else if (isStructuredType(upperBound)) {
+    } else if (isStructuredType(upperBound)
+        || (upperBound instanceof TypeVar && inferenceVariableForStructure(upperBound) == null)) {
       addIfNotIdentical(result, upperBound);
     }
   }
@@ -1137,14 +1403,25 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
           : BoundRelation.UNKNOWN;
     }
     if (subtype instanceof TypeVar || supertype instanceof TypeVar) {
-      InferenceVariable subtypeVariable = inferenceVariableForUse(subtype);
-      InferenceVariable supertypeVariable = inferenceVariableForUse(supertype);
+      InferenceVariable subtypeVariable = inferenceVariableForStructure(subtype);
+      InferenceVariable supertypeVariable = inferenceVariableForStructure(supertype);
       if (subtypeVariable != null && subtypeVariable.equals(supertypeVariable)) {
         return BoundRelation.SATISFIED;
       }
-      Type resolvedSubtype = resolveInvariantVariable(subtypeVariable);
-      Type resolvedSupertype = resolveInvariantVariable(supertypeVariable);
+      Type resolvedSubtype = resolveInvariantOccurrence(subtype);
+      Type resolvedSupertype = resolveInvariantOccurrence(supertype);
       if (resolvedSubtype == null && resolvedSupertype == null) {
+        if (subtype instanceof TypeVar fixedSubtype
+            && inferenceVariableForStructure(subtype) == null
+            && !(subtype instanceof CapturedType)) {
+          if (supertype instanceof TypeVar) {
+            return state.getTypes().isSubtype(subtype, supertype)
+                ? BoundRelation.SATISFIED
+                : BoundRelation.UNKNOWN;
+          }
+          // Inspect a fixed variable's contract only for checking, never as an inferred shape.
+          return nullabilitySubtype(fixedSubtype.getUpperBound(), supertype, false);
+        }
         return BoundRelation.UNKNOWN;
       }
       return nullabilitySubtype(
@@ -1285,7 +1562,11 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
       BoundNullness firstNullness = boundNullness(first);
       BoundNullness secondNullness = boundNullness(second);
       if (firstNullness == BoundNullness.UNKNOWN || secondNullness == BoundNullness.UNKNOWN) {
-        if (!sameInferenceVariable(first, second)) {
+        if (!sameInferenceVariable(first, second)
+            && !(first instanceof TypeVar firstVariable
+                && second instanceof TypeVar secondVariable
+                && firstVariable.tsym.equals(secondVariable.tsym)
+                && firstNullness == secondNullness)) {
           result = BoundRelation.UNKNOWN;
         }
       } else if (firstNullness != secondNullness) {
@@ -1339,13 +1620,13 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
    */
   private BoundRelation compareTypeVariableStructure(
       Type first, Type second, boolean compareTopLevel) {
-    InferenceVariable firstVariable = inferenceVariableForUse(first);
-    InferenceVariable secondVariable = inferenceVariableForUse(second);
+    InferenceVariable firstVariable = inferenceVariableForStructure(first);
+    InferenceVariable secondVariable = inferenceVariableForStructure(second);
     if (firstVariable != null && firstVariable.equals(secondVariable)) {
       return BoundRelation.SATISFIED;
     }
-    Type resolvedFirst = resolveInvariantVariable(firstVariable);
-    Type resolvedSecond = resolveInvariantVariable(secondVariable);
+    Type resolvedFirst = resolveInvariantOccurrence(first);
+    Type resolvedSecond = resolveInvariantOccurrence(second);
     if (resolvedFirst == null && resolvedSecond == null) {
       return first instanceof TypeVar firstTypeVar
               && second instanceof TypeVar secondTypeVar
@@ -1359,6 +1640,16 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
         compareTopLevel);
   }
 
+  /** Resolves structural ownership while retaining explicit root annotations on the occurrence. */
+  private @Nullable Type resolveInvariantOccurrence(Type occurrence) {
+    InferenceVariable variable = inferenceVariableForStructure(occurrence);
+    Type resolved = resolveInvariantVariable(variable);
+    return resolved == null
+        ? null
+        : TypeSubstitutionUtils.substituteTypeVariables(
+            occurrence, Map.of(occurrence.tsym, resolved), state.getTypes(), config);
+  }
+
   /** Returns reconciled acyclic structure for {@code variable}, or {@code null} if unavailable. */
   private @Nullable Type resolveInvariantVariable(@Nullable InferenceVariable variable) {
     if (variable == null
@@ -1377,6 +1668,9 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   private BoundRelation topLevelNullabilitySubtype(Type subtype, Type supertype) {
     BoundNullness subtypeNullness = boundNullness(subtype);
     BoundNullness supertypeNullness = boundNullness(supertype);
+    if (supertypeNullness == BoundNullness.NULLABLE) {
+      return BoundRelation.SATISFIED;
+    }
     if (subtypeNullness == BoundNullness.UNKNOWN || supertypeNullness == BoundNullness.UNKNOWN) {
       return sameInferenceVariable(subtype, supertype)
           ? BoundRelation.SATISFIED
@@ -1406,7 +1700,9 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
           ? BoundNullness.NULLABLE
           : BoundNullness.NONNULL;
     }
-    return type instanceof TypeVar ? BoundNullness.UNKNOWN : BoundNullness.NONNULL;
+    return type instanceof TypeVar && !isKnownNonNull(type)
+        ? BoundNullness.UNKNOWN
+        : BoundNullness.NONNULL;
   }
 
   /** Returns whether both types denote the same unannotated inference variable. */
@@ -1427,31 +1723,31 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   }
 
   /**
-   * Records structured evidence for a lower or upper bound. A fixed type variable contributes its
-   * annotated upper bound while the original use is retained as provenance for declaration-bound
-   * diagnostics. Arrays and all non-raw classes are retained; alignment can expose nested
-   * nullability even when a concrete subclass has no direct type arguments.
+   * Records structured evidence for a lower or upper bound. Fixed variables remain symbolic;
+   * checking may inspect their bounds, but candidate construction must not replace them with those
+   * bounds. Their original uses also provide provenance for declaration-bound diagnostics. Arrays
+   * and non-raw classes retain nested annotations and supertype-alignment evidence.
    */
   private void recordStructuredBound(
       InferenceVariable inferenceVar, Type boundType, boolean lower) {
-    Type evidence = boundType;
     VarState st = getState(inferenceVar);
     if (lower
-        && boundType instanceof TypeVar typeVariable
-        && inferenceVariableForUse(boundType) == null) {
-      evidence = typeVariable.getUpperBound();
+        && boundType instanceof TypeVar
+        && inferenceVariableForStructure(boundType) == null) {
       if (st.fixedTypeVariableLowerBoundKeys.add(structuredTypeKey(boundType))) {
         st.fixedTypeVariableLowerBounds.add(boundType);
         structuredConstraintVersion++;
       }
     }
-    if (!isStructuredType(evidence)) {
+    if (!isStructuredType(boundType)
+        && !(boundType instanceof TypeVar)
+        && !(boundType instanceof ClassType)) {
       return;
     }
     List<Type> bounds = lower ? st.lowerBoundTypes : st.upperBoundTypes;
     Set<String> keys = lower ? st.lowerBoundKeys : st.upperBoundKeys;
-    if (keys.add(structuredTypeKey(evidence))) {
-      bounds.add(evidence);
+    if (keys.add(structuredTypeKey(boundType))) {
+      bounds.add(boundType);
       structuredConstraintVersion++;
     }
   }
@@ -1549,6 +1845,17 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
    */
   @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
   private Type markNestedTypesNonNull(Type type) {
+    if (type instanceof WildcardType wildcard
+        && wildcard.kind != BoundKind.UNBOUND
+        && wildcard.type != null) {
+      Type bound = markNonNullIfUnannotated(wildcard.type);
+      if (bound == wildcard.type) {
+        return type;
+      }
+      WildcardType result = TYPE_METADATA_BUILDER.createWildcardType(wildcard, bound);
+      result.bound = wildcard.bound;
+      return result;
+    }
     if (type instanceof Type.ArrayType arrayType) {
       Type elemType = arrayType.getComponentType();
       Type newElemType = markNonNullIfUnannotated(elemType);
@@ -1564,9 +1871,11 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
         changed |= newTypeArg != typeArg;
         newTypeArgs.add(newTypeArg);
       }
+      Type enclosingType = classType.getEnclosingType();
+      Type newEnclosingType = markNonNullIfUnannotated(enclosingType);
+      changed |= newEnclosingType != enclosingType;
       return changed
-          ? TYPE_METADATA_BUILDER.createClassType(
-              classType, classType.getEnclosingType(), newTypeArgs)
+          ? TYPE_METADATA_BUILDER.createClassType(classType, newEnclosingType, newTypeArgs)
           : type;
     }
     return type;
@@ -1577,6 +1886,9 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
    * unannotated class or array type.
    */
   private Type markNonNullIfUnannotated(Type type) {
+    if (type instanceof WildcardType) {
+      return markNestedTypesNonNull(type);
+    }
     if (!(type instanceof ClassType) && !(type instanceof Type.ArrayType)) {
       return type;
     }
@@ -1685,6 +1997,7 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
         && GenericsUtils.upperBoundIsNullable(typeVariable.asElement(), config, handler, state);
   }
 
+  /** Creates scalar state once, including for registered variables without constraints. */
   private VarState getState(InferenceVariable inferenceVar) {
     VarState existing = vars.get(inferenceVar);
     if (existing != null) {
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
index 5d2302b2..71a4dbea 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
@@ -60,11 +60,13 @@ import com.uber.nullaway.dataflow.EnclosingEnvironmentNullness;
 import com.uber.nullaway.dataflow.NullnessStore;
 import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
 import com.uber.nullaway.generics.ConstraintSolver.NestedUpperBoundViolationException;
+import com.uber.nullaway.generics.ConstraintSolver.Solution;
 import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
 import com.uber.nullaway.generics.GenericsUtils.MethodRefTypeRelationKind;
 import com.uber.nullaway.handlers.Handler;
 import java.util.ArrayList;
 import java.util.HashMap;
+import java.util.IdentityHashMap;
 import java.util.LinkedHashMap;
 import java.util.LinkedHashSet;
 import java.util.List;
@@ -77,6 +79,7 @@ import javax.lang.model.element.ElementKind;
 import javax.lang.model.type.ExecutableType;
 import javax.lang.model.type.NullType;
 import javax.lang.model.type.TypeKind;
+import javax.lang.model.type.TypeMirror;
 import javax.lang.model.type.TypeVariable;
 import org.jspecify.annotations.Nullable;
 
@@ -91,36 +94,29 @@ public final class GenericsChecks {
   /** Marker interface for results of attempting to infer nullability of type variables at a call */
   private interface CallInferenceResult {}
 
-  /**
-   * Indicates successful inference of type arguments at a call. Stores the inferred type of every
-   * inference variable in the inference problem, which may span several calls (nested calls,
-   * generic method references, etc.). See {@link ConstraintSolver#solve()} for the form of the
-   * inferred types.
-   */
-  private record InferenceSuccess(
-      Map<InferenceVariable, Type> inferenceVariableTypes)
-      implements CallInferenceResult {
+  /** Complete solutions and diagnostic fallback evidence share only their substitution view. */
+  private interface InferenceWithTypes extends CallInferenceResult {
+    Solution solution();
 
-    /**
-     * Returns the inferred types of the type variables inferred at {@code site}. The result
-     * excludes type variables inferred at other sites in the same inference problem, including
-     * other calls to the same generic method.
-     *
-     * @param site a call or method reference participating in the inference problem
-     * @return a map from declared type variables to their inferred types at {@code site}
-     */
-    Map<Element, Type> inferredTypesForSite(Tree site) {
+    /** Selects substitutions belonging to one call or reference, never a sibling's variables. */
+    default Map<Element, Type> inferredTypesForSite(Tree site) {
       Map<Element, Type> result = new LinkedHashMap<>();
-      inferenceVariableTypes.forEach(
-          (inferenceVar, inferredType) -> {
-            if (inferenceVar.site().equals(site)) {
-              result.put(inferenceVar.typeVariable(), inferredType);
+      solution().inferredTypes().forEach(
+          (variable, type) -> {
+            if (variable.site().equals(site)) {
+              result.put(variable.typeVariable(), type);
             }
           });
       return result;
     }
   }
 
+  /** A completed, consistent solution eligible for publication subject to lifecycle guards. */
+  private record InferenceSuccess(Solution solution) implements InferenceWithTypes {}
+
+  /** Uncertified or contradictory evidence used for ordinary checking, never success caching. */
+  private record InferencePartial(Solution solution) implements InferenceWithTypes {}
+
   /**
    * Tracks which participating calls can safely share a persisted inference result. Once any call
    * is analyzed with provisional lambda parameter types, no result from that inference problem is
@@ -164,6 +160,9 @@ public final class GenericsChecks {
   private final Map<Tree, CallInferenceResult> inferredTypeVarNullabilityForGenericCalls =
       new LinkedHashMap<>();
 
+  /** Final root targets retained when provisional lambda participation prevents result caching. */
+  private final Map<Tree, CallAndContext> completedCallContexts = new LinkedHashMap<>();
+
   /**
    * Completed enclosing or independent inference results indexed by generic method references. This
    * site-scoped cache remains valid when reusable call caching is disabled because provisional
@@ -196,6 +195,9 @@ public final class GenericsChecks {
    */
   private Map<Symbol, Type> lambdaParameterTypesForInference = Map.of();
 
+  /** Ground lambda targets scoped to constraint generation, including parameterless lambdas. */
+  private Map<LambdaExpressionTree, Type> lambdaTargetTypesForInference = Map.of();
+
   /** Scoped array-type recovery from a transfer must not recursively start another analysis. */
   private int arrayTypeRecoveryFromDataflowDepth = 0;
 
@@ -1064,6 +1066,36 @@ public final class GenericsChecks {
             newClassTree.getClassBody() == null ? newClassTree : newClassTree.getIdentifier()));
   }
 
+  /** Resolves a lambda descriptor as a member of its ground functional target. */
+  private Type.MethodType getLambdaDescriptorType(
+      LambdaExpressionTree lambda, Type targetType, VisitorState state) {
+    Type groundTarget = GenericsUtils.groundTargetType(targetType, state, config, handler);
+    return TypeSubstitutionUtils.memberType(
+            state.getTypes(),
+            groundTarget,
+            NullabilityUtil.getFunctionalInterfaceMethod(lambda, state.getTypes()),
+            config)
+        .asMethodType();
+  }
+
+  /**
+   * Returns the lambda body's target from its owning inference problem or completed functional
+   * target. Reentrant inference must not replace either with javac's annotation-erased target.
+   */
+  private @Nullable Type getLambdaReturnTargetType(
+      LambdaExpressionTree lambda, VisitorState state) {
+    Type targetType = lambdaTargetTypesForInference.get(lambda);
+    if (targetType == null) {
+      targetType = inferredPolyExpressionTypes.get(lambda);
+    }
+    if (targetType == null) {
+      targetType = ASTHelpers.getType(lambda);
+    }
+    return targetType == null || targetType.isRaw()
+        ? null
+        : getLambdaDescriptorType(lambda, targetType, state).getReturnType();
+  }
+
   /**
    * Gets the temporary type of a lambda parameter during constraint generation (see {@link
    * #lambdaParameterTypesForInference}), or its inferred type if the lambda was passed to a generic
@@ -1079,33 +1111,20 @@ public final class GenericsChecks {
     if (provisionalType != null) {
       return provisionalType;
     }
-    if (symbol.owner != null && symbol.owner.getKind() == ElementKind.METHOD) {
-      Symbol.MethodSymbol containingMethodSymbol = (Symbol.MethodSymbol) symbol.owner;
-      if (!containingMethodSymbol.getParameters().contains(symbol)) {
-        // we have a lambda parameter
-        LambdaExpressionTree lambdaTree =
-            ASTHelpers.findEnclosingNode(state.getPath(), LambdaExpressionTree.class);
-        if (lambdaTree != null) {
-          Type inferredLambdaType = inferredPolyExpressionTypes.get(lambdaTree);
-          if (inferredLambdaType != null) {
-            // type of lambda was inferred
-            var params = lambdaTree.getParameters();
-            for (int i = 0; i < params.size(); i++) {
-              VariableTree param = params.get(i);
-              Symbol paramSymbol = ASTHelpers.getSymbol(param);
-              if (paramSymbol != null && paramSymbol.equals(symbol)) {
-                // get the type of the functional interface method as a member of the inferred type
-                // of the lambda
-                Types types = state.getTypes();
-                var fiMethodType =
-                    TypeSubstitutionUtils.memberType(
-                        types,
-                        inferredLambdaType,
-                        NullabilityUtil.getFunctionalInterfaceMethod(lambdaTree, types),
-                        config);
-                return fiMethodType.getParameterTypes().get(i);
-              }
+    // A parameter captured by a nested lambda belongs to an outer lambda, not necessarily the
+    // nearest one. Match its declaration before selecting the completed functional target.
+    for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
+      if (path.getLeaf() instanceof LambdaExpressionTree lambdaTree) {
+        var params = lambdaTree.getParameters();
+        for (int i = 0; i < params.size(); i++) {
+          if (symbol.equals(ASTHelpers.getSymbol(params.get(i)))) {
+            Type inferredLambdaType = inferredPolyExpressionTypes.get(lambdaTree);
+            if (inferredLambdaType != null) {
+              return getLambdaDescriptorType(lambdaTree, inferredLambdaType, state)
+                  .getParameterTypes()
+                  .get(i);
             }
+            return null;
           }
         }
       }
@@ -1201,15 +1220,17 @@ public final class GenericsChecks {
    * Returns whether a computed type or generic-call inference result can be cached.
    *
    * <p>Results computed during dataflow may depend on incomplete analysis results. Results computed
-   * while provisional lambda parameter types are available may depend on unsolved outer inference
-   * variables. Skip caching in either context so subsequent checks can recompute the result after
+   * while provisional lambda parameter or target types are available may depend on unsolved outer
+   * inference variables. Skip caching in either context so subsequent checks can recompute the result after
    * dataflow or the enclosing generic inference completes.
    *
    * @param calledFromDataflow whether the result was computed as part of dataflow analysis
    * @return whether the inference result can be cached in the current context
    */
   private boolean okToCacheInferenceResult(boolean calledFromDataflow) {
-    return !calledFromDataflow && lambdaParameterTypesForInference.isEmpty();
+    return !calledFromDataflow
+        && lambdaParameterTypesForInference.isEmpty()
+        && lambdaTargetTypesForInference.isEmpty();
   }
 
   /**
@@ -1449,16 +1470,37 @@ public final class GenericsChecks {
               assignedToLocal,
               calledFromDataflow);
     }
-    if (result instanceof InferenceSuccess successResult) {
+    if (result instanceof InferenceWithTypes successResult) {
       typeVarNullability = successResult.inferredTypesForSite(callTree);
     }
     Type typeAtCallSite = castToNonNull(ASTHelpers.getType(callTree));
     if (callTree instanceof MethodInvocationTree) {
+      if (result instanceof InferenceSuccess complete) {
+        return TypeSubstitutionUtils.substituteTypeVariables(
+            executableType.getReturnType(),
+            complete.inferredTypesForSite(callTree),
+            state.getTypes(),
+            config);
+      }
       return TypeSubstitutionUtils.updateTypeWithInferredNullability(
           typeAtCallSite, executableType.getReturnType(), typeVarNullability, state, config);
     }
     Verify.verify(callTree instanceof NewClassTree);
     Type constructedTypeAtCallSite = getConstructedTypeAtCallSite((NewClassTree) callTree);
+    if (result instanceof InferenceSuccess complete
+        && constructedTypeAtCallSite instanceof Type.ClassType classType) {
+      Map<Element, Type> inferred = complete.inferredTypesForSite(callTree);
+      List<Symbol.TypeVariableSymbol> parameters = classType.tsym.getTypeParameters();
+      if (parameters.size() == classType.getTypeArguments().size()) {
+        List<Type> arguments = new ArrayList<>();
+        for (int i = 0; i < parameters.size(); i++) {
+          arguments.add(
+              inferred.getOrDefault(parameters.get(i), classType.getTypeArguments().get(i)));
+        }
+        return TYPE_METADATA_BUILDER.createClassType(
+            classType, classType.getEnclosingType(), arguments);
+      }
+    }
     Type constructedTypeWithTypeVars = constructedTypeAtCallSite.tsym.type;
     return TypeSubstitutionUtils.updateTypeWithInferredNullability(
         constructedTypeAtCallSite, constructedTypeWithTypeVars, typeVarNullability, state, config);
@@ -1510,18 +1552,18 @@ public final class GenericsChecks {
       } finally {
         callTypesForPolyArguments = enclosingCallTypesForPolyArgs;
       }
-      Map<InferenceVariable, Type> solution = new LinkedHashMap<>(solver.solve());
-      // The solver only computes a solution for variables that appear in constraints. For
-      // unconstrained variables of the top-level call, treat them as NONNULL, consistent with
-      // solver behavior for unconstrained variables that do appear in the constraint graph.
-      for (Symbol.TypeVariableSymbol typeVar : getCallTypeParameters(callTree)) {
-        solution.putIfAbsent(
-            new InferenceVariable(typeVar, callTree),
-            TypeSubstitutionUtils.typeWithAnnot(typeVar.type, getSyntheticNonNullAnnotType(state)));
-      }
-
-      InferenceSuccess successResult = new InferenceSuccess(solution);
-      if (okToCacheInferenceResult(calledFromDataflow)) {
+      Solution solution = solver.solve();
+
+      InferenceWithTypes result =
+          solution.isComplete()
+              ? new InferenceSuccess(solution)
+              : new InferencePartial(solution);
+      if (result instanceof InferenceSuccess successResult
+          && okToCacheInferenceResult(calledFromDataflow)) {
+        if (typeFromAssignmentContext != null) {
+          completedCallContexts.put(
+              callTree, new CallAndContext(callTree, typeFromAssignmentContext, assignedToLocal));
+        }
         inferenceCacheState.methodReferences.forEach(
             methodReference ->
                 inferredResultsForGenericMethodReferences.put(methodReference, successResult));
@@ -1534,8 +1576,25 @@ public final class GenericsChecks {
             (call, callExecutableType) ->
                 storeInferredPolyArgumentTypes(
                     call, callExecutableType, successResult.inferredTypesForSite(call), state));
+      } else if (result instanceof InferencePartial partial
+          && solution.inconsistentVariables().isEmpty()
+          && okToCacheInferenceResult(calledFromDataflow)) {
+        // Certify each target independently: an unrelated unused variable may remain opaque.
+        callTypesForPolyArgs.forEach(
+            (call, callExecutableType) ->
+                storeCertifiedPolyArgumentTypes(call, callExecutableType, partial, state));
+      } else if (result instanceof InferencePartial
+          && path != null
+          && !solution.inconsistentVariables().isEmpty()
+          && solution.inconsistentVariables().containsAll(solution.incompleteVariables())
+          && hasShapedDiagnosticTypes(callTree, executableType, result, state)
+          && !inferenceCacheState.provisionalLambdaTypesSeen
+          && okToCacheInferenceResult(calledFromDataflow)) {
+        // Preserve the final root's target-derived evidence for ordinary parameter diagnostics.
+        // This is not a successful result and must not certify nested calls or poly expressions.
+        inferredTypeVarNullabilityForGenericCalls.put(callTree, result);
       }
-      return successResult;
+      return result;
     } catch (NestedUpperBoundViolationException e) {
       String message =
           errorMessageForIncompatibleTypesAtPseudoAssignment(
@@ -1581,6 +1640,31 @@ public final class GenericsChecks {
     }
   }
 
+  /**
+   * Checks that contradictory root substitutions retain javac's complete Java instantiations.
+   * Contradictions can also be marked incomplete; that must not admit unshaped fallback sources.
+   */
+  private boolean hasShapedDiagnosticTypes(
+      ExpressionTree call,
+      Type.MethodType executableType,
+      InferenceWithTypes result,
+      VisitorState state) {
+    List<Symbol.TypeVariableSymbol> variables = getCallTypeParameters(call);
+    Map<Element, Type> shapes =
+        getJavacInstantiationsForCall(call, executableType, variables, state);
+    Map<Element, Type> diagnosticTypes = result.inferredTypesForSite(call);
+    for (Symbol.TypeVariableSymbol variable : variables) {
+      Type shape = shapes.get(variable);
+      Type diagnosticType = diagnosticTypes.get(variable);
+      if (shape == null
+          || diagnosticType == null
+          || !state.getTypes().isSameType(shape, diagnosticType)) {
+        return false;
+      }
+    }
+    return true;
+  }
+
   /** Recovers the diagnostic site's real ancestors, including local-variable suppressions. */
   private VisitorState stateForInferenceDiagnostic(Tree site, VisitorState state) {
     TreePath sitePath = TreePath.getPath(state.getPath().getCompilationUnit(), site);
@@ -1595,6 +1679,93 @@ public final class GenericsChecks {
     }
   }
 
+  /** Publishes a functional target only when every inferred variable it uses is certified. */
+  private void storeCertifiedPolyArgumentTypes(
+      Tree call, Type.MethodType executableType, InferencePartial partial, VisitorState state) {
+    Set<Element> uncertified = new LinkedHashSet<>();
+    partial.solution().incompleteVariables().forEach(
+        variable -> {
+          if (variable.site().equals(call)) {
+            uncertified.add(variable.typeVariable());
+          }
+        });
+    Map<Element, Type> inferred = partial.inferredTypesForSite(call);
+    new InvocationArguments(call, executableType)
+        .forEach(
+            (argument, argPos, formalParamType, unused) -> {
+              if (!(argument instanceof LambdaExpressionTree
+                  || argument instanceof MemberReferenceTree)) {
+                return;
+              }
+              for (Element variable : uncertified) {
+                TypeVarWithSymbolCollector occurrences = new TypeVarWithSymbolCollector(variable);
+                formalParamType.accept(occurrences, null);
+                if (!occurrences.getMatches().isEmpty()) {
+                  return;
+                }
+              }
+              Type attributed = ASTHelpers.getType(argument);
+              if (attributed != null) {
+                Type groundTarget =
+                    GenericsUtils.groundTargetType(formalParamType, state, config, handler);
+                Type completedTarget =
+                    TypeSubstitutionUtils.updateTypeWithInferredNullability(
+                        attributed, groundTarget, inferred, state, config);
+                if (!containsPendingInferenceVariables(completedTarget, new IdentityHashMap<>())) {
+                  inferredPolyExpressionTypes.put(argument, completedTarget);
+                }
+              }
+            });
+  }
+
+  /** Rejects solver-private fresh symbols in a target while retaining genuine fixed variables. */
+  private boolean containsPendingInferenceVariables(
+      Type type, IdentityHashMap<Type, Boolean> visiting) {
+    if (visiting.put(type, Boolean.TRUE) != null) {
+      return false;
+    }
+    try {
+      if (type instanceof Type.CapturedType capture) {
+        return containsPendingInferenceVariables(capture.getUpperBound(), visiting)
+            || containsPendingInferenceVariables(capture.wildcard, visiting);
+      }
+      if (type instanceof Type.TypeVar variable) {
+        Symbol owner = variable.tsym.owner;
+        if (owner instanceof Symbol.MethodSymbol method) {
+          return !method.getTypeParameters().contains(variable.tsym);
+        }
+        if (owner instanceof Symbol.ClassSymbol clazz) {
+          return !clazz.getTypeParameters().contains(variable.tsym);
+        }
+        return false;
+      }
+      if (type instanceof Type.ArrayType array) {
+        return containsPendingInferenceVariables(array.elemtype, visiting);
+      }
+      if (type instanceof Type.WildcardType wildcard) {
+        return wildcard.type != null && containsPendingInferenceVariables(wildcard.type, visiting);
+      }
+      if (type instanceof Type.ClassType clazz) {
+        if (clazz instanceof Type.IntersectionClassType intersection) {
+          for (TypeMirror bound : intersection.getBounds()) {
+            if (containsPendingInferenceVariables((Type) bound, visiting)) {
+              return true;
+            }
+          }
+        }
+        for (Type argument : clazz.getTypeArguments()) {
+          if (containsPendingInferenceVariables(argument, visiting)) {
+            return true;
+          }
+        }
+        return containsPendingInferenceVariables(clazz.getEnclosingType(), visiting);
+      }
+      return false;
+    } finally {
+      visiting.remove(type);
+    }
+  }
+
   /**
    * Stores the types of lambda and method reference arguments of a generic call in {@link
    * #inferredPolyExpressionTypes}, applying the types inferred for the call's type variables to
@@ -1673,6 +1844,64 @@ public final class GenericsChecks {
     return result;
   }
 
+  /**
+   * Recovers Java type shapes from javac attribution independently of NullAway's annotated evidence.
+   * Diamond class variables also occur in the constructed type, even when absent from parameters.
+   */
+  private Map<Element, Type> getJavacInstantiationsForCall(
+      ExpressionTree call,
+      Type.MethodType declaration,
+      List<Symbol.TypeVariableSymbol> variables,
+      VisitorState state) {
+    Map<Element, Type> result = new LinkedHashMap<>();
+    if (call instanceof MethodInvocationTree invocation) {
+      Type attributed = ASTHelpers.getType(invocation.getMethodSelect());
+      if (attributed != null) {
+        result.putAll(
+            InferenceTypeShapes.inferInstantiations(
+                declaration, attributed.asMethodType(), variables, state, config));
+      }
+    } else if (call instanceof NewClassTree construction) {
+      Type constructed = getConstructedTypeAtCallSite(construction);
+      result.putAll(
+          InferenceTypeShapes.inferInstantiations(
+              constructed.tsym.type, constructed, variables, state, config));
+      Type constructorType = ((JCTree.JCNewClass) construction).constructorType;
+      if (constructorType != null) {
+        Map<Element, Type> constructorShapes =
+            InferenceTypeShapes.inferInstantiations(
+                declaration, constructorType.asMethodType(), variables, state, config);
+        constructorShapes.forEach(result::putIfAbsent);
+      }
+    }
+    for (Symbol.TypeVariableSymbol variable : variables) {
+      if (result.containsKey(variable)) {
+        continue;
+      }
+      TypeVarWithSymbolCollector occurrences = new TypeVarWithSymbolCollector(variable);
+      declaration.accept(occurrences, null);
+      if (!occurrences.getMatches().isEmpty()) {
+        continue;
+      }
+      Type bound = ((Type.TypeVar) variable.type).getUpperBound();
+      Type substitutedBound =
+          TypeSubstitutionUtils.substituteTypeVariables(bound, result, state.getTypes(), config);
+      boolean unresolved = false;
+      for (Symbol.TypeVariableSymbol peer : variables) {
+        TypeVarWithSymbolCollector references = new TypeVarWithSymbolCollector(peer);
+        substitutedBound.accept(references, null);
+        unresolved |= !references.getMatches().isEmpty();
+      }
+      if (!unresolved && !(substitutedBound instanceof Type.TypeVar)) {
+        // A variable absent from the executable signature cannot affect observable call types.
+        // Its non-recursive bound is a valid representative instantiation; root defaults remain
+        // the solver's responsibility. Recursive or unresolved bounds remain explicitly incomplete.
+        result.put(variable, substitutedBound);
+      }
+    }
+    return result;
+  }
+
   /**
    * Finds receiver/class-substituted upper bounds for inferred variables occurring in an executable
    * type. Variables absent from the executable type use their declaration bounds in the solver.
@@ -1785,9 +2014,11 @@ public final class GenericsChecks {
             callTree,
             callTypeParameters,
             instantiatedUpperBounds,
-            variablesWithNullnessMarkedBounds);
+            variablesWithNullnessMarkedBounds,
+            getJavacInstantiationsForCall(callTree, methodType, callTypeParameters, state));
     Map<Tree, Type.MethodType> callTypesForPolyArgs = callTypesForPolyArguments;
-    boolean usesProvisionalLambdaTypes = !lambdaParameterTypesForInference.isEmpty();
+    boolean usesProvisionalLambdaTypes =
+        !lambdaParameterTypesForInference.isEmpty() || !lambdaTargetTypesForInference.isEmpty();
     inferenceCacheState.recordCall(callTree, usesProvisionalLambdaTypes);
     if (!usesProvisionalLambdaTypes && callTypesForPolyArgs != null) {
       callTypesForPolyArgs.put(callTree, methodType);
@@ -1944,6 +2175,10 @@ public final class GenericsChecks {
     // save the previous lambdaParameterTypesForInference map so we can restore it after handling
     // the lambda body
     Map<Symbol, Type> previousParameterTypes = lambdaParameterTypesForInference;
+    Map<LambdaExpressionTree, Type> previousTargetTypes = lambdaTargetTypesForInference;
+    Map<LambdaExpressionTree, Type> targetTypes = new LinkedHashMap<>(previousTargetTypes);
+    targetTypes.put(lambda, groundTargetType);
+    lambdaTargetTypesForInference = targetTypes;
     try {
       if (((JCTree.JCLambda) lambda).paramKind == JCTree.JCLambda.ParameterKind.IMPLICIT) {
         // If we have implicitly-typed lambda parameters, update the
@@ -1993,7 +2228,46 @@ public final class GenericsChecks {
     } finally {
       // Restore even if constraint generation fails or re-enters inference for a nested lambda.
       lambdaParameterTypesForInference = previousParameterTypes;
+      lambdaTargetTypesForInference = previousTargetTypes;
+    }
+  }
+
+  /** Recovers a generic reference's Java instantiations from its attributed functional target. */
+  private Map<Element, Type> getJavacInstantiationsForReference(
+      MemberReferenceTree reference, Symbol.MethodSymbol method, VisitorState state) {
+    Map<Element, Type> result = new LinkedHashMap<>();
+    Set<Element> conflicting = new LinkedHashSet<>();
+    Type attributedTarget = ASTHelpers.getType(reference);
+    if (attributedTarget == null || attributedTarget.isRaw()) {
+      return result;
     }
+    Type groundTarget = GenericsUtils.groundTargetType(attributedTarget, state, config, handler);
+    GenericsUtils.processMethodRefTypeRelations(
+        this,
+        groundTarget,
+        reference,
+        state,
+        (subtype, supertype, relationKind) -> {
+          boolean returned = relationKind == MethodRefTypeRelationKind.RETURN;
+          Map<Element, Type> relationShapes =
+              InferenceTypeShapes.inferInstantiations(
+                  returned ? subtype : supertype,
+                  returned ? supertype : subtype,
+                  method.getTypeParameters(),
+                  state,
+                  config);
+          relationShapes.forEach(
+              (variable, shape) -> {
+                Type previous = result.get(variable);
+                if (previous != null && !state.getTypes().isSameType(previous, shape)) {
+                  conflicting.add(variable);
+                } else {
+                  result.put(variable, shape);
+                }
+              });
+        });
+    conflicting.forEach(result::remove);
+    return result;
   }
 
   /**
@@ -2037,7 +2311,8 @@ public final class GenericsChecks {
               memberReferenceTree,
               referencedMethod.getTypeParameters(),
               instantiatedUpperBounds,
-              variablesWithNullnessMarkedBounds);
+              variablesWithNullnessMarkedBounds,
+              getJavacInstantiationsForReference(memberReferenceTree, referencedMethod, state));
     }
     Map<Element, Type.TypeVar> referenceInferenceVariables = inferenceVariables;
     GenericsUtils.processMethodRefTypeRelations(
@@ -2797,10 +3072,17 @@ public final class GenericsChecks {
     if (parent instanceof AssignmentTree || parent instanceof VariableTree) {
       return getTargetTypeForAssignmentContext(parent, parentState, calledFromDataflow);
     }
-    // 2b. `return [expr];` => target is containing method's formal return type
+    if (parent instanceof LambdaExpressionTree lambda) {
+      return new TargetTypeAndAssignmentKind(getLambdaReturnTargetType(lambda, state), false);
+    }
+    // 2b. `return [expr];` => target is containing method's or lambda's formal return type
     if (parent instanceof ReturnTree) {
       TreePath enclosingMethodOrLambda =
           NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(parentPath);
+      if (enclosingMethodOrLambda != null
+          && enclosingMethodOrLambda.getLeaf() instanceof LambdaExpressionTree lambda) {
+        return new TargetTypeAndAssignmentKind(getLambdaReturnTargetType(lambda, state), false);
+      }
       if (enclosingMethodOrLambda != null
           && enclosingMethodOrLambda.getLeaf() instanceof MethodTree enclosingMethod) {
         Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
@@ -2812,9 +3094,20 @@ public final class GenericsChecks {
     // 2c. `foo(..., [expr]);` => target is called method's formal argument type
     if (parent instanceof MethodInvocationTree parentInvocation) {
       if (isCallNeedingInference(parentInvocation)) {
-        // The parent invocation's formal parameter type is still part of the inference problem, not
-        // a solved target type. Generic method inference will handle this expression from the
-        // parent call side.
+        if (inferredTypeVarNullabilityForGenericCalls.get(parentInvocation)
+            instanceof InferenceWithTypes) {
+          Type.MethodType methodType =
+              getInvokedMethodTypeAtCall(
+                  ASTHelpers.getSymbol(parentInvocation),
+                  parentInvocation,
+                  parentPath,
+                  parentState,
+                  calledFromDataflow);
+          return new TargetTypeAndAssignmentKind(
+              getFormalParameterTypeForArgument(parentInvocation, methodType, expressionTree),
+              false);
+        }
+        // An unsolved parent supplies inference variables, not an independent final target.
         return new TargetTypeAndAssignmentKind(null, false);
       }
       Type methodType = ASTHelpers.getType(parentInvocation.getMethodSelect());
@@ -3020,7 +3313,7 @@ public final class GenericsChecks {
           || methodReferenceInferenceInProgress.contains(memberReferenceTree)) {
         return methodType;
       }
-      InferenceSuccess siteResult =
+      InferenceWithTypes siteResult =
           inferredResultsForGenericMethodReferences.get(memberReferenceTree);
       if (siteResult != null) {
         return TypeSubstitutionUtils.substituteInferredTypesForGenericMethodReference(
@@ -3046,7 +3339,8 @@ public final class GenericsChecks {
       }
     }
     if (referenceTree instanceof MemberReferenceTree memberReferenceTree) {
-      InferenceSuccess result = inferGenericMethodReferenceIndependently(memberReferenceTree, state);
+      InferenceWithTypes result =
+          inferGenericMethodReferenceIndependently(memberReferenceTree, state);
       if (result != null) {
         return TypeSubstitutionUtils.substituteInferredTypesForGenericMethodReference(
             methodType, result.inferredTypesForSite(memberReferenceTree), state, config);
@@ -3064,7 +3358,7 @@ public final class GenericsChecks {
    * @param state visitor state whose path ends at the reference
    * @return the completed solution, or {@code null} if no final target is available or inference fails
    */
-  private @Nullable InferenceSuccess inferGenericMethodReferenceIndependently(
+  private @Nullable InferenceWithTypes inferGenericMethodReferenceIndependently(
       MemberReferenceTree reference, VisitorState state) {
     if (callTypesForPolyArguments != null || !methodReferenceInferenceInProgress.add(reference)) {
       return null;
@@ -3082,9 +3376,13 @@ public final class GenericsChecks {
       InferenceCacheState inferenceCacheState = new InferenceCacheState();
       handleMethodRefInGenericMethodInference(
           state, solver, inferenceCacheState, targetType, reference);
-      InferenceSuccess result = new InferenceSuccess(new LinkedHashMap<>(solver.solve()));
-      if (okToCacheInferenceResult(false)) {
-        inferredResultsForGenericMethodReferences.put(reference, result);
+      Solution solution = solver.solve();
+      InferenceWithTypes result =
+          solution.isComplete()
+              ? new InferenceSuccess(solution)
+              : new InferencePartial(solution);
+      if (result instanceof InferenceSuccess successResult && okToCacheInferenceResult(false)) {
+        inferredResultsForGenericMethodReferences.put(reference, successResult);
       }
       return result;
     } catch (NestedUpperBoundViolationException e) {
@@ -3546,22 +3844,37 @@ public final class GenericsChecks {
         // have not yet attempted inference for this call
         CallAndContext invocationAndType =
             path == null
-                ? new CallAndContext(invocationTree, null, false)
+                ? completedCallContexts.getOrDefault(
+                    invocationTree, new CallAndContext(invocationTree, null, false))
                 : getCallAndContextForInference(path, state, calledFromDataflow);
+        // The selected root may be an ancestor of the original invocation. Constraint generation
+        // and lambda ownership must use that root's source path, not the nested call's path.
+        TreePath inferencePath = path;
+        while (inferencePath != null
+            && !inferencePath.getLeaf().equals(invocationAndType.call)) {
+          inferencePath = inferencePath.getParentPath();
+        }
+        VisitorState inferenceState =
+            inferencePath != null ? state.withPath(inferencePath) : state;
         result =
             runInferenceForCall(
-                state,
-                path,
+                inferenceState,
+                inferencePath,
                 invocationAndType.call,
                 getExecutableTypeForInference(
-                    invocationAndType.call, path, state, calledFromDataflow),
+                    invocationAndType.call, inferencePath, inferenceState, calledFromDataflow),
                 invocationAndType.typeFromAssignmentContext,
                 invocationAndType.assignedToLocal,
                 calledFromDataflow);
       }
       Type.MethodType methodTypeAtCallSite =
           castToNonNull(ASTHelpers.getType(invocationTree.getMethodSelect())).asMethodType();
-      if (result instanceof InferenceSuccess successResult) {
+      if (result instanceof InferenceSuccess complete) {
+        return (Type.MethodType)
+            TypeSubstitutionUtils.substituteTypeVariables(
+                methodType, complete.inferredTypesForSite(invocationTree), state.getTypes(), config);
+      }
+      if (result instanceof InferenceWithTypes successResult) {
         // Repairing dropped nested nullability annotations can itself inspect actual argument
         // types. For diamond constructor arguments, that can re-enter method-type computation for
         // this same invocation while we are still repairing it. In that case, use the already
@@ -3659,6 +3972,10 @@ public final class GenericsChecks {
    */
   private CallAndContext getCallAndContextForInference(
       TreePath path, VisitorState state, boolean calledFromDataflow) {
+    CallAndContext completedContext = completedCallContexts.get(path.getLeaf());
+    if (completedContext != null) {
+      return completedContext;
+    }
     TreePath parentPath = path.getParentPath();
     Tree parent = parentPath.getLeaf();
     while (parent instanceof ParenthesizedTree) {
@@ -3699,7 +4016,10 @@ public final class GenericsChecks {
       // find the enclosing method and return its return type
       TreePath enclosingMethodOrLambda =
           NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(parentPath);
-      // TODO handle lambdas; https://github.com/uber/NullAway/issues/1288
+      if (enclosingMethodOrLambda != null
+          && enclosingMethodOrLambda.getLeaf() instanceof LambdaExpressionTree lambda) {
+        return new CallAndContext(call, getLambdaReturnTargetType(lambda, state), false);
+      }
       if (enclosingMethodOrLambda != null
           && enclosingMethodOrLambda.getLeaf() instanceof MethodTree enclosingMethod) {
         Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
@@ -3707,6 +4027,8 @@ public final class GenericsChecks {
           return new CallAndContext(call, methodSymbol.getReturnType(), false);
         }
       }
+    } else if (parent instanceof LambdaExpressionTree lambda) {
+      return new CallAndContext(call, getLambdaReturnTargetType(lambda, state), false);
     } else if (parent instanceof ExpressionTree exprParent) {
       // could be a parameter to another method call, or part of a conditional expression, etc.
       // in any case, just return the type of the parent expression
@@ -4272,6 +4594,7 @@ public final class GenericsChecks {
    */
   public void clearCache() {
     inferredTypeVarNullabilityForGenericCalls.clear();
+    completedCallContexts.clear();
     inferredResultsForGenericMethodReferences.clear();
     methodReferenceInferenceInProgress.clear();
     callsWithReportedInferenceFailures.clear();
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/InferenceTypeShapes.java b/nullaway/src/main/java/com/uber/nullaway/generics/InferenceTypeShapes.java
new file mode 100644
index 00000000..48e35db2
--- /dev/null
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/InferenceTypeShapes.java
@@ -0,0 +1,307 @@
+package com.uber.nullaway.generics;
+
+import com.google.errorprone.VisitorState;
+import com.sun.tools.javac.code.Symbol;
+import com.sun.tools.javac.code.Type;
+import com.sun.tools.javac.code.Type.ArrayType;
+import com.sun.tools.javac.code.Type.CapturedType;
+import com.sun.tools.javac.code.Type.ClassType;
+import com.sun.tools.javac.code.Type.ForAll;
+import com.sun.tools.javac.code.Type.MethodType;
+import com.sun.tools.javac.code.Type.TypeVar;
+import com.sun.tools.javac.code.Type.WildcardType;
+import com.sun.tools.javac.code.Types;
+import com.uber.nullaway.Config;
+import java.util.Collections;
+import java.util.IdentityHashMap;
+import java.util.LinkedHashMap;
+import java.util.LinkedHashSet;
+import java.util.List;
+import java.util.Map;
+import java.util.Set;
+import javax.lang.model.element.Element;
+import javax.lang.model.type.TypeVariable;
+import org.jspecify.annotations.Nullable;
+
+/** Recovers javac's nominal instantiations without inferring their nullness. */
+final class InferenceTypeShapes {
+
+  private InferenceTypeShapes() {}
+
+  /**
+   * Matches a declaration against its javac-attributed shape, recovering only variables in {@code
+   * vars}.
+   *
+   * <p>For calls, the declaration should be the receiver-substituted executable type and the
+   * attributed type should be the invocation's method-select type (or attributed constructor
+   * signature). Class instantiations can instead be recovered by matching the constructed class's
+   * declaration against its attributed constructed type. For anonymous classes, callers must supply
+   * their existing view of the constructed class, not the anonymous class declaration.
+   *
+   * <p>Returned types retain javac's metadata, but their annotations are provisional, not nullness
+   * evidence. Repeated occurrences must have the same nominal shape, ignoring annotations; a
+   * conflicting or unresolved variable is omitted. Variables absent from the compared shapes are
+   * not defaulted to their declaration bounds. Fixed, non-listed type variables remain fixed.
+   */
+  static Map<Element, Type> inferInstantiations(
+      Type declaration,
+      Type attributed,
+      List<? extends Element> vars,
+      VisitorState state,
+      Config config) {
+    Matcher matcher = new Matcher(vars, state.getTypes(), config);
+    matcher.match(declaration, attributed);
+    return matcher.instantiations;
+  }
+
+  /** Stateful matching for one pair of attributed and declaration shapes. */
+  private static final class Matcher {
+    private final Set<Element> variables;
+    private final Types types;
+    private final Config config;
+    private final Map<Element, Type> instantiations = new LinkedHashMap<>();
+    private final Set<Element> rejected = new LinkedHashSet<>();
+    private final IdentityHashMap<Type, Set<Type>> seen = new IdentityHashMap<>();
+
+    private Matcher(List<? extends Element> vars, Types types, Config config) {
+      this.variables = new LinkedHashSet<>(vars);
+      this.types = types;
+      this.config = config;
+    }
+
+    /** Traverses corresponding shapes, never replacing a fixed variable with its bound. */
+    private void match(@Nullable Type declaration, @Nullable Type attributed) {
+      if (declaration == null || attributed == null) {
+        rejectVariables(declaration);
+        return;
+      }
+      if (!enter(seen, declaration, attributed)) {
+        return;
+      }
+      if (declaration instanceof ForAll quantified) {
+        match(quantified.qtype, attributed);
+        return;
+      }
+      if (attributed instanceof ForAll quantified) {
+        match(declaration, quantified.qtype);
+        return;
+      }
+      if (declaration.isErroneous() || attributed.isErroneous()) {
+        rejectVariables(declaration);
+        return;
+      }
+      // A capture's originating wildcard is explicit shape evidence; its synthesized bounds are not.
+      if (declaration instanceof CapturedType capture) {
+        match(capture.wildcard, attributed);
+        return;
+      }
+      if (declaration instanceof TypeVar variable) {
+        Element symbol = variableSymbol(variable);
+        if (variables.contains(symbol)) {
+          record(symbol, attributed);
+        }
+        return;
+      }
+      if (declaration instanceof WildcardType wildcard) {
+        matchWildcard(wildcard, attributed);
+        return;
+      }
+      if (declaration instanceof MethodType method && attributed instanceof MethodType actual) {
+        matchLists(method.getParameterTypes(), actual.getParameterTypes());
+        match(method.getReturnType(), actual.getReturnType());
+        matchLists(method.getThrownTypes(), actual.getThrownTypes());
+        return;
+      }
+      if (declaration instanceof ArrayType array && attributed instanceof ArrayType actual) {
+        match(array.elemtype, actual.elemtype);
+        return;
+      }
+      if (declaration instanceof ClassType clazz && attributed instanceof ClassType actual) {
+        Type aligned = actual;
+        if (!clazz.tsym.equals(actual.tsym) && clazz.tsym instanceof Symbol.ClassSymbol symbol) {
+          aligned = TypeSubstitutionUtils.asSuper(types, actual, symbol, config);
+        }
+        if (!(aligned instanceof ClassType alignedClass)
+            || !clazz.tsym.equals(alignedClass.tsym)) {
+          rejectVariables(declaration);
+          return;
+        }
+        match(clazz.getEnclosingType(), alignedClass.getEnclosingType());
+        matchLists(clazz.getTypeArguments(), alignedClass.getTypeArguments());
+        return;
+      }
+      rejectVariables(declaration);
+    }
+
+    /** Matches only explicit wildcard bounds, including a capture's original wildcard. */
+    private void matchWildcard(WildcardType declaration, Type attributed) {
+      if (attributed instanceof CapturedType capture) {
+        matchWildcard(declaration, capture.wildcard);
+        return;
+      }
+      if (declaration.isUnbound()) {
+        return;
+      }
+      if (attributed instanceof WildcardType wildcard) {
+        if (declaration.kind != wildcard.kind) {
+          rejectVariables(declaration);
+        } else {
+          match(declaration.type, wildcard.type);
+        }
+        return;
+      }
+      // A concrete adapted bound is useful, but a fixed type variable's upper bound is not evidence.
+      if (attributed instanceof TypeVar) {
+        rejectVariables(declaration);
+        return;
+      }
+      match(declaration.type, attributed);
+    }
+
+    /** Pairs lists only when their arities agree; truncation would hide contradictory evidence. */
+    private void matchLists(List<Type> declaration, List<Type> attributed) {
+      if (declaration.size() != attributed.size()) {
+        for (Type type : declaration) {
+          rejectVariables(type);
+        }
+        return;
+      }
+      for (int i = 0; i < declaration.size(); i++) {
+        match(declaration.get(i), attributed.get(i));
+      }
+    }
+
+    /** Records attributed evidence unless it is unresolved or conflicts with an earlier occurrence. */
+    private void record(Element variable, Type attributed) {
+      if (rejected.contains(variable)) {
+        return;
+      }
+      Set<Element> unresolved = new LinkedHashSet<>();
+      collectVariables(attributed, unresolved, Collections.newSetFromMap(new IdentityHashMap<>()));
+      if (!unresolved.isEmpty() || attributed.isErroneous()) {
+        reject(variable);
+        return;
+      }
+      Type previous = instantiations.get(variable);
+      if (previous == null) {
+        instantiations.put(variable, attributed);
+      } else if (!sameShape(previous, attributed, new IdentityHashMap<>())) {
+        reject(variable);
+      }
+    }
+
+    /** Permanently removes all listed variables occurring in a mismatched declaration subtree. */
+    private void rejectVariables(@Nullable Type declaration) {
+      Set<Element> affected = new LinkedHashSet<>();
+      collectVariables(declaration, affected, Collections.newSetFromMap(new IdentityHashMap<>()));
+      for (Element variable : affected) {
+        reject(variable);
+      }
+    }
+
+    /** Removes an inconsistent variable so that later occurrences cannot reinstate it. */
+    private void reject(Element variable) {
+      rejected.add(variable);
+      instantiations.remove(variable);
+    }
+
+    /** Collects syntactic occurrences, not variables merely mentioned by declaration bounds. */
+    private void collectVariables(@Nullable Type type, Set<Element> result, Set<Type> visited) {
+      if (type == null || !visited.add(type)) {
+        return;
+      }
+      if (type instanceof CapturedType capture) {
+        collectVariables(capture.wildcard, result, visited);
+      } else if (type instanceof TypeVar variable) {
+        Element symbol = variableSymbol(variable);
+        if (variables.contains(symbol)) {
+          result.add(symbol);
+        }
+      } else if (type instanceof ForAll quantified) {
+        collectVariables(quantified.qtype, result, visited);
+      } else if (type instanceof MethodType method) {
+        for (Type parameter : method.getParameterTypes()) {
+          collectVariables(parameter, result, visited);
+        }
+        collectVariables(method.getReturnType(), result, visited);
+        for (Type thrown : method.getThrownTypes()) {
+          collectVariables(thrown, result, visited);
+        }
+      } else if (type instanceof ArrayType array) {
+        collectVariables(array.elemtype, result, visited);
+      } else if (type instanceof WildcardType wildcard) {
+        if (!wildcard.isUnbound()) {
+          collectVariables(wildcard.type, result, visited);
+        }
+      } else if (type instanceof ClassType clazz) {
+        collectVariables(clazz.getEnclosingType(), result, visited);
+        for (Type argument : clazz.getTypeArguments()) {
+          collectVariables(argument, result, visited);
+        }
+      }
+    }
+
+    /** Compares exact nominal shapes, without subtype alignment or annotation comparisons. */
+    private boolean sameShape(Type left, Type right, Map<Type, Set<Type>> compared) {
+      if (!enter(compared, left, right)) {
+        return true;
+      }
+      if (left instanceof CapturedType || right instanceof CapturedType) {
+        // Distinct captures cannot be equated just because their bounds happen to agree.
+        return left instanceof CapturedType
+            && right instanceof CapturedType
+            && variableSymbol((TypeVar) left).equals(variableSymbol((TypeVar) right));
+      }
+      if (left instanceof TypeVar || right instanceof TypeVar) {
+        return left instanceof TypeVar leftVariable
+            && right instanceof TypeVar rightVariable
+            && variableSymbol(leftVariable).equals(variableSymbol(rightVariable));
+      }
+      if (left instanceof WildcardType || right instanceof WildcardType) {
+        return left instanceof WildcardType leftWildcard
+            && right instanceof WildcardType rightWildcard
+            && leftWildcard.kind == rightWildcard.kind
+            && (leftWildcard.isUnbound()
+                || sameShape(leftWildcard.type, rightWildcard.type, compared));
+      }
+      if (left instanceof ArrayType || right instanceof ArrayType) {
+        return left instanceof ArrayType leftArray
+            && right instanceof ArrayType rightArray
+            && sameShape(leftArray.elemtype, rightArray.elemtype, compared);
+      }
+      if (left instanceof ClassType || right instanceof ClassType) {
+        return left instanceof ClassType leftClass
+            && right instanceof ClassType rightClass
+            && leftClass.tsym.equals(rightClass.tsym)
+            && sameShape(leftClass.getEnclosingType(), rightClass.getEnclosingType(), compared)
+            && sameShapes(leftClass.getTypeArguments(), rightClass.getTypeArguments(), compared);
+      }
+      return types.isSameType(left, right);
+    }
+
+    /** Checks corresponding type lists for exact, annotation-independent shape agreement. */
+    private boolean sameShapes(List<Type> left, List<Type> right, Map<Type, Set<Type>> compared) {
+      if (left.size() != right.size()) {
+        return false;
+      }
+      for (int i = 0; i < left.size(); i++) {
+        if (!sameShape(left.get(i), right.get(i), compared)) {
+          return false;
+        }
+      }
+      return true;
+    }
+  }
+
+  /** Uses the language-model variable symbol rather than comparing TypeVar wrapper identities. */
+  private static Element variableSymbol(TypeVar variable) {
+    return ((TypeVariable) variable).asElement();
+  }
+
+  /** Registers an identity pair, returning false for an already visited recursive edge. */
+  private static boolean enter(Map<Type, Set<Type>> pairs, Type declaration, Type attributed) {
+    return pairs
+        .computeIfAbsent(declaration, unused -> Collections.newSetFromMap(new IdentityHashMap<>()))
+        .add(attributed);
+  }
+}
diff --git a/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java b/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java
new file mode 100644
index 00000000..af95de64
--- /dev/null
+++ b/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java
@@ -0,0 +1,713 @@
+package com.uber.nullaway.generics;
+
+import static com.google.common.truth.Truth.assertThat;
+import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
+import static com.google.errorprone.matchers.Description.NO_MATCH;
+import static org.junit.jupiter.api.Assertions.assertThrows;
+import static org.mockito.Mockito.mock;
+import static org.mockito.Mockito.when;
+
+import com.google.errorprone.BugPattern;
+import com.google.errorprone.CompilationTestHelper;
+import com.google.errorprone.ErrorProneFlags;
+import com.google.errorprone.VisitorState;
+import com.google.errorprone.bugpatterns.BugChecker;
+import com.google.errorprone.matchers.Description;
+import com.google.errorprone.util.ASTHelpers;
+import com.sun.source.tree.ClassTree;
+import com.sun.source.tree.MethodInvocationTree;
+import com.sun.source.tree.MethodTree;
+import com.sun.source.tree.Tree;
+import com.sun.source.tree.VariableTree;
+import com.sun.source.util.TreePath;
+import com.sun.tools.javac.code.Symbol;
+import com.sun.tools.javac.code.Type;
+import com.uber.nullaway.Config;
+import com.uber.nullaway.NullAway;
+import com.uber.nullaway.Nullness;
+import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
+import com.uber.nullaway.generics.ConstraintSolver.Solution;
+import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
+import com.uber.nullaway.handlers.Handler;
+import java.util.ArrayList;
+import java.util.IdentityHashMap;
+
+import java.util.List;
+import java.util.Map;
+import java.util.Set;
+import javax.lang.model.element.Element;
+import org.junit.Test;
+import org.junit.runner.RunWith;
+import org.junit.runners.JUnit4;
+
+/** Direct solver assertions on attributed javac types, executed during an active compilation. */
+@RunWith(JUnit4.class)
+public class ConstraintSolverImplTests {
+
+  private static final ThreadLocal<List<String>> EXECUTED_MARKERS =
+      ThreadLocal.withInitial(ArrayList::new);
+
+  @Test
+  public void callsToSameDeclarationHaveSeparateInferenceVariables() {
+    runFixture(
+        "callSeparation",
+        "<T extends @Nullable Object> void inference(String shape) {}");
+  }
+
+  @Test
+  public void completeSupplierSubstitutionRetainsNullableFixedOuterSymbol() {
+    runFixture(
+        "nestedSupplier",
+        """
+        <R extends Supplier<?>> void inference(
+            Supplier<@Nullable OuterT> lower, Supplier<OuterT> shape) {}
+        """);
+  }
+
+  @Test
+  public void enclosingTypeArgumentsConstrainInnerClassSubtyping() {
+    runFixture(
+        "enclosingType",
+        """
+        static class Outer<E extends @Nullable Object> {
+          class Inner {}
+        }
+        <T extends @Nullable Object> void inference(
+            Outer<@Nullable String>.Inner lower, Outer<T>.Inner template, String shape) {}
+        """);
+  }
+
+  @Test
+  public void covariantArrayMergeIsIndependentOfLowerBoundOrder() {
+    runFixture(
+        "arrayMerge",
+        """
+        <R extends @Nullable Object> void inference(
+            String[] nonNullElements, @Nullable String[] nullableElements) {}
+        """);
+  }
+
+  @Test
+  public void contradictoryInvariantBoundsAreTaggedInconsistent() {
+    runFixture(
+        "invariantContradiction",
+        """
+        <R extends @Nullable Object, U extends @Nullable Object> void inference(
+            Box<String> nonNullArgument, Box<@Nullable String> nullableArgument,
+            String unrelatedShape) {}
+        """);
+  }
+
+  @Test
+  public void unusedRegisteredVariablesHaveConcreteShapesAndNonNullRootDefaults() {
+    runFixture(
+        "unusedVariables",
+        "<T, U extends @Nullable Object> void inference(String tShape, Object uShape) {}");
+  }
+
+  @Test
+  public void registrationAndSolvingDoNotMutateSourceOrDeclaredTypes() {
+    runFixture(
+        "sourceIsolation",
+        """
+        <T extends @Nullable Object, R extends Supplier<T>> void inference(
+            Supplier<T> template, @Nullable String nullableT, Supplier<String> rShape,
+            String tShape, Test<?> wildcard, String[] array,
+            @Nullable String[] nullableElements) {}
+        """);
+  }
+
+  @Test
+  public void dependentVariablesReceiveLateNullableEvidence() {
+    runFixture(
+        "lateEvidence",
+        """
+        <T extends @Nullable Object, R extends Supplier<T>> void inference(
+            Supplier<T> template, @Nullable String nullableT, Supplier<String> rShape,
+            String tShape) {}
+        """);
+  }
+
+  @Test
+  public void nullableHandlerModelOverridesNonNullReceiverInstantiatedBound() {
+    runFixture(
+        "modeledBound",
+        "<T extends OuterT> void inference(String receiverBound, String shape) {}");
+  }
+
+  @Test
+  public void fourArgumentRegistrationWithoutJavaShapesIsIncomplete() {
+    runFixture(
+        "missingShapes", "<T extends @Nullable Object> void inference(String evidence) {}");
+  }
+
+  /** Compiles one marker-field scenario and verifies its assertions ran exactly once. */
+  private void runFixture(String marker, String members) {
+    EXECUTED_MARKERS.get().clear();
+    try {
+      CompilationTestHelper.newInstance(SolverAssertionsChecker.class, getClass())
+          .setArgs(
+              JSpecifyJavacConfig.withJSpecifyModeArgs(
+                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
+          .addSourceLines(
+              "Test.java",
+              """
+              package com.uber;
+              import java.util.function.Supplier;
+              import org.jspecify.annotations.Nullable;
+              class Test<OuterT extends @Nullable Object> {
+                static class Box<E extends @Nullable Object> {}
+                static <V extends @Nullable Object> V id(V value) { return value; }
+                Object siteA = id(null);
+                Object siteB = id(null);
+              """
+                  + members
+                  + "\nObject "
+                  + marker
+                  + ";\n}\n")
+          .doTest();
+      assertThat(EXECUTED_MARKERS.get()).containsExactly(marker);
+    } finally {
+      EXECUTED_MARKERS.remove();
+    }
+  }
+
+  /** Runs direct solver assertions at a marker field while compiler types are available. */
+  @BugPattern(summary = "Checks compiler-backed constraint solver results", severity = SUGGESTION)
+  public static final class SolverAssertionsChecker extends BugChecker
+      implements BugChecker.VariableTreeMatcher {
+
+    private static final Set<String> MARKERS =
+        Set.of(
+            "callSeparation",
+            "nestedSupplier",
+            "enclosingType",
+            "arrayMerge",
+            "invariantContradiction",
+            "unusedVariables",
+            "sourceIsolation",
+            "lateEvidence",
+            "modeledBound",
+            "missingShapes");
+
+    @Override
+    public Description matchVariable(VariableTree tree, VisitorState state) {
+      String marker = tree.getName().toString();
+      if (!MARKERS.contains(marker)) {
+        return NO_MATCH;
+      }
+      TestContext context = createContext(state);
+      switch (marker) {
+        case "callSeparation" -> checkCallSeparation(context);
+        case "nestedSupplier" -> checkNestedSupplier(context);
+        case "enclosingType" -> checkEnclosingType(context);
+        case "arrayMerge" -> checkArrayMerge(context);
+        case "invariantContradiction" -> checkInvariantContradiction(context);
+        case "unusedVariables" -> checkUnusedVariables(context);
+        case "sourceIsolation" -> checkSourceIsolation(context);
+        case "lateEvidence" -> checkLateEvidence(context);
+        case "modeledBound" -> checkModeledBound(context);
+        case "missingShapes" -> checkMissingShapes(context);
+        default -> throw new AssertionError("Unhandled marker: " + marker);
+      }
+      EXECUTED_MARKERS.get().add(marker);
+      return NO_MATCH;
+    }
+
+    /** Finds the fixture declarations and invocation sites without fabricating compiler symbols. */
+    private static TestContext createContext(VisitorState state) {
+      TreePath path = state.getPath();
+      while (!(path.getLeaf() instanceof ClassTree)) {
+        path = java.util.Objects.requireNonNull(path.getParentPath());
+      }
+      ClassTree classTree = (ClassTree) path.getLeaf();
+      MethodTree declaration = null;
+      MethodInvocationTree siteA = null;
+      MethodInvocationTree siteB = null;
+      for (Tree member : classTree.getMembers()) {
+        if (member instanceof MethodTree method
+            && method.getName().contentEquals("inference")) {
+          declaration = method;
+        } else if (member instanceof VariableTree field) {
+          if (field.getName().contentEquals("siteA")) {
+            siteA = (MethodInvocationTree) field.getInitializer();
+          } else if (field.getName().contentEquals("siteB")) {
+            siteB = (MethodInvocationTree) field.getInitializer();
+          }
+        }
+      }
+      Config config =
+          new NullAway(
+                  ErrorProneFlags.fromMap(
+                      Map.of(
+                          "NullAway:AnnotatedPackages", "com.uber",
+                          "NullAway:JSpecifyMode", "true",
+                          "NullAway:JSpecifyExperimental", "true")))
+              .getConfig();
+      return new TestContext(
+          state,
+          config,
+          ASTHelpers.getSymbol(classTree),
+          ASTHelpers.getSymbol(java.util.Objects.requireNonNull(declaration)),
+          java.util.Objects.requireNonNull(siteA),
+          java.util.Objects.requireNonNull(siteB));
+    }
+
+    /** Supplies only the handler dependency required by the solver's NullAway constructor input. */
+    private static ConstraintSolver newSolver(TestContext context, Handler handler) {
+      NullAway analysis = mock(NullAway.class);
+      when(analysis.getHandler()).thenReturn(handler);
+      return new ConstraintSolverImpl(context.config(), context.state(), analysis);
+    }
+
+    /** Creates an isolated solver with the interface's real no-op default handler methods. */
+    private static ConstraintSolver newSolver(TestContext context) {
+      return newSolver(context, new Handler() {});
+    }
+
+    /** Registers all method variables with marked declaration bounds and attributed Java shapes. */
+    private static Map<Element, Type.TypeVar> register(
+        TestContext context, ConstraintSolver solver, Tree site, Map<Element, Type> shapes) {
+      return solver.registerInferenceVariables(
+          site,
+          context.declaration().getTypeParameters(),
+          Map.of(),
+          Set.copyOf(context.declaration().getTypeParameters()),
+          shapes);
+    }
+
+    /** Checks freshness, idempotent registration, and independent nullness at two call sites. */
+    private static void checkCallSeparation(TestContext context) {
+      ConstraintSolver solver = newSolver(context);
+      Element declared = context.variable(0);
+      Type shape = context.parameter(0);
+      Map<Element, Type> shapes = Map.of(declared, shape);
+      Type.TypeVar first = register(context, solver, context.siteA(), shapes).get(declared);
+      Type.TypeVar second = register(context, solver, context.siteB(), shapes).get(declared);
+      assertThat(first.tsym).isNotSameInstanceAs(second.tsym);
+      assertThat(first.tsym).isNotSameInstanceAs(declared);
+      assertThat(register(context, solver, context.siteA(), shapes).get(declared))
+          .isSameInstanceAs(first);
+      solver.addSubtypeConstraint(context.state().getSymtab().botType, first, false);
+      solver.addSubtypeConstraint(second, shape, false);
+      Solution solution = solver.solve();
+      InferenceVariable firstKey = new InferenceVariable(declared, context.siteA());
+      InferenceVariable secondKey = new InferenceVariable(declared, context.siteB());
+      assertComplete(solution, firstKey, secondKey);
+      assertNullness(solution.inferredTypes().get(firstKey), true, context);
+      assertNullness(solution.inferredTypes().get(secondKey), false, context);
+      assertThat(solution.inferredTypes().get(firstKey).tsym).isSameInstanceAs(shape.tsym);
+      assertThat(solution.inferredTypes().get(secondKey).tsym).isSameInstanceAs(shape.tsym);
+    }
+
+    /** Rejects wildcard upper-bound fallback and preserves the fixed outer variable's symbol. */
+    private static void checkNestedSupplier(TestContext context) {
+      ConstraintSolver solver = newSolver(context);
+      Element declared = context.variable(0);
+      Type lower = context.parameter(0);
+      Type shape = context.parameter(1);
+      Type.TypeVar fresh =
+          register(context, solver, context.siteA(), Map.of(declared, shape)).get(declared);
+      solver.addSubtypeConstraint(lower, fresh, false);
+      Solution solution = solver.solve();
+      InferenceVariable key = new InferenceVariable(declared, context.siteA());
+      assertComplete(solution, key);
+      Type result = solution.inferredTypes().get(key);
+      assertThat(result).isInstanceOf(Type.ClassType.class);
+      assertThat(result.tsym).isSameInstanceAs(shape.tsym);
+      assertNullness(result, false, context);
+      assertThat(result.getTypeArguments()).hasSize(1);
+      Type argument = result.getTypeArguments().head;
+      assertThat(argument).isInstanceOf(Type.TypeVar.class);
+      assertThat(argument).isNotInstanceOf(Type.CapturedType.class);
+      assertThat(argument.tsym).isSameInstanceAs(context.owner().getTypeParameters().head);
+      assertNullness(argument, true, context);
+      assertThat(((Type.TypeVar) argument).getUpperBound().tsym)
+          .isSameInstanceAs(
+              ((Type.TypeVar) context.owner().getTypeParameters().head.type).getUpperBound().tsym);
+      assertNoFreshVariables(result, List.of(fresh));
+    }
+
+    /** Infers nullness from enclosing arguments without mutating the original inner type graphs. */
+    private static void checkEnclosingType(TestContext context) {
+      ConstraintSolver solver = newSolver(context);
+      Element declared = context.variable(0);
+      Type lower = context.parameter(0);
+      Type template = context.parameter(1);
+      Type shape = context.parameter(2);
+      SourceSnapshot snapshot = new SourceSnapshot();
+      snapshot.capture(lower);
+      snapshot.capture(template);
+      snapshot.capture((Type) declared.asType());
+      Type.TypeVar fresh =
+          register(context, solver, context.siteA(), Map.of(declared, shape)).get(declared);
+      Type supertype =
+          TypeSubstitutionUtils.substituteTypeVariables(
+              template, Map.of(declared, fresh), context.state().getTypes(), context.config());
+      assertThat(lower.getTypeArguments()).isEmpty();
+      assertThat(supertype.getTypeArguments()).isEmpty();
+      assertThat(supertype.getEnclosingType().getTypeArguments().head.tsym)
+          .isSameInstanceAs(fresh.tsym);
+      solver.addSubtypeConstraint(lower, supertype, false);
+      Solution solution = solver.solve();
+      InferenceVariable key = new InferenceVariable(declared, context.siteA());
+      assertComplete(solution, key);
+      Type result = solution.inferredTypes().get(key);
+      assertThat(result.tsym).isSameInstanceAs(shape.tsym);
+      assertNullness(result, true, context);
+      assertNoFreshVariables(result, List.of(fresh));
+      snapshot.assertUnchanged();
+      assertThat(template.getEnclosingType().getTypeArguments().head.tsym)
+          .isSameInstanceAs(declared);
+      assertNullness(lower.getEnclosingType().getTypeArguments().head, true, context);
+    }
+
+    /** Requires nullable array components, but non-null array roots, in both insertion orders. */
+    private static void checkArrayMerge(TestContext context) {
+      for (boolean nullableFirst : List.of(false, true)) {
+        ConstraintSolver solver = newSolver(context);
+        Element declared = context.variable(0);
+        Type nonNullElements = context.parameter(0);
+        Type nullableElements = context.parameter(1);
+        assertNullness(((Type.ArrayType) nonNullElements).elemtype, false, context, false);
+        assertNullness(((Type.ArrayType) nullableElements).elemtype, true, context);
+        Type.TypeVar fresh =
+            register(context, solver, context.siteA(), Map.of(declared, nonNullElements))
+                .get(declared);
+        solver.addSubtypeConstraint(
+            nullableFirst ? nullableElements : nonNullElements, fresh, false);
+        solver.addSubtypeConstraint(
+            nullableFirst ? nonNullElements : nullableElements, fresh, false);
+        Solution solution = solver.solve();
+        InferenceVariable key = new InferenceVariable(declared, context.siteA());
+        assertComplete(solution, key);
+        Type result = solution.inferredTypes().get(key);
+        assertThat(result).isInstanceOf(Type.ArrayType.class);
+        assertNullness(result, false, context);
+        Type component = ((Type.ArrayType) result).elemtype;
+        assertThat(component.tsym).isSameInstanceAs(((Type.ArrayType) nonNullElements).elemtype.tsym);
+        assertNullness(component, true, context);
+        assertNoFreshVariables(result, List.of(fresh));
+      }
+    }
+
+    /** Requires contradictory invariant evidence to taint only the affected inference variable. */
+    private static void checkInvariantContradiction(TestContext context) {
+      for (boolean nullableFirst : List.of(false, true)) {
+        ConstraintSolver solver = newSolver(context);
+        Element declared = context.variable(0);
+        Element unrelated = context.variable(1);
+        Map<Element, Type.TypeVar> fresh =
+            register(
+                context,
+                solver,
+                context.siteA(),
+                Map.of(declared, context.parameter(0), unrelated, context.parameter(2)));
+        solver.addSubtypeConstraint(context.parameter(nullableFirst ? 1 : 0), fresh.get(declared), false);
+        solver.addSubtypeConstraint(context.parameter(nullableFirst ? 0 : 1), fresh.get(declared), false);
+        Solution solution = solver.solve();
+        InferenceVariable key = new InferenceVariable(declared, context.siteA());
+        InferenceVariable unrelatedKey = new InferenceVariable(unrelated, context.siteA());
+        assertThat(solution.inferredTypes().keySet()).containsExactly(key, unrelatedKey);
+        assertThat(solution.inconsistentVariables()).containsExactly(key);
+        assertThat(solution.incompleteVariables()).doesNotContain(unrelatedKey);
+        assertThat(solution.isComplete()).isFalse();
+        assertThat(solution.isCompleteForSite(context.siteA())).isFalse();
+        assertNullness(solution.inferredTypes().get(unrelatedKey), false, context);
+      }
+    }
+
+    /** Checks that registration alone creates complete concrete results with default root nullness. */
+    private static void checkUnusedVariables(TestContext context) {
+      ConstraintSolver solver = newSolver(context);
+      Element first = context.variable(0);
+      Element second = context.variable(1);
+      register(
+          context,
+          solver,
+          context.siteA(),
+          Map.of(first, context.parameter(0), second, context.parameter(1)));
+      Solution solution = solver.solve();
+      InferenceVariable firstKey = new InferenceVariable(first, context.siteA());
+      InferenceVariable secondKey = new InferenceVariable(second, context.siteA());
+      assertComplete(solution, firstKey, secondKey);
+      for (int index = 0; index < 2; index++) {
+        Type result =
+            solution.inferredTypes().get(new InferenceVariable(context.variable(index), context.siteA()));
+        assertThat(result).isInstanceOf(Type.ClassType.class);
+        assertThat(result.tsym).isSameInstanceAs(context.parameter(index).tsym);
+        assertNullness(result, false, context);
+      }
+    }
+
+    /** Snapshots recursive source graphs before registration, substitution, merging, and solving. */
+    private static void checkSourceIsolation(TestContext context) {
+      SourceSnapshot snapshot = new SourceSnapshot();
+      snapshot.capture(context.owner().type);
+      for (Symbol.TypeVariableSymbol variable : context.declaration().getTypeParameters()) {
+        snapshot.capture(variable.type);
+      }
+      for (Symbol.VarSymbol parameter : context.declaration().getParameters()) {
+        snapshot.capture(parameter.type);
+      }
+      solveDependent(context, false);
+      snapshot.assertUnchanged();
+
+      ConstraintSolver arrays = newSolver(context);
+      Element variable = context.variable(0);
+      Type.TypeVar fresh =
+          arrays
+              .registerInferenceVariables(
+                  context.siteB(),
+                  List.of(variable),
+                  Map.of(),
+                  Set.of(variable),
+                  Map.of(variable, context.parameter(5)))
+              .get(variable);
+      arrays.addSubtypeConstraint(context.parameter(5), fresh, false);
+      arrays.addSubtypeConstraint(context.parameter(6), fresh, false);
+      Solution solution = arrays.solve();
+      InferenceVariable key = new InferenceVariable(variable, context.siteB());
+      assertComplete(solution, key);
+      assertNullness(((Type.ArrayType) solution.inferredTypes().get(key)).elemtype, true, context);
+      snapshot.assertUnchanged();
+    }
+
+    /** Requires dependent substitution to see nullable evidence regardless of constraint order. */
+    private static void checkLateEvidence(TestContext context) {
+      solveDependent(context, false);
+      solveDependent(context, true);
+    }
+
+    /** Solves Supplier<T> evidence and verifies the final substitution contains nullable String. */
+    private static void solveDependent(TestContext context, boolean nullableFirst) {
+      ConstraintSolver solver = newSolver(context);
+      Element t = context.variable(0);
+      Element r = context.variable(1);
+      Map<Element, Type.TypeVar> fresh =
+          register(
+              context,
+              solver,
+              context.siteA(),
+              Map.of(t, context.parameter(3), r, context.parameter(2)));
+      Type template =
+          TypeSubstitutionUtils.substituteTypeVariables(
+              context.parameter(0), fresh, context.state().getTypes(), context.config());
+      if (nullableFirst) {
+        solver.addSubtypeConstraint(context.parameter(1), fresh.get(t), false);
+      }
+      solver.addSubtypeConstraint(template, fresh.get(r), false);
+      if (!nullableFirst) {
+        solver.addSubtypeConstraint(context.parameter(1), fresh.get(t), false);
+      }
+      Solution solution = solver.solve();
+      InferenceVariable tKey = new InferenceVariable(t, context.siteA());
+      InferenceVariable rKey = new InferenceVariable(r, context.siteA());
+      assertComplete(solution, tKey, rKey);
+      Type tResult = solution.inferredTypes().get(tKey);
+      assertThat(tResult.tsym).isSameInstanceAs(context.parameter(3).tsym);
+      assertNullness(tResult, true, context);
+      Type rResult = solution.inferredTypes().get(rKey);
+      assertThat(rResult).isInstanceOf(Type.ClassType.class);
+      assertThat(rResult.tsym).isSameInstanceAs(context.parameter(2).tsym);
+      assertNullness(rResult, false, context);
+      assertThat(rResult.getTypeArguments()).hasSize(1);
+      Type argument = rResult.getTypeArguments().head;
+      assertThat(argument).isInstanceOf(Type.ClassType.class);
+      assertThat(argument.tsym).isSameInstanceAs(context.parameter(3).tsym);
+      assertNullness(argument, true, context);
+      assertNoFreshVariables(rResult, List.copyOf(fresh.values()));
+    }
+
+    /** Uses a real method owner/index to test model precedence over a substituted receiver bound. */
+    private static void checkModeledBound(TestContext context) {
+      Element declared = context.variable(0);
+      Type.TypeVar source = (Type.TypeVar) declared.asType();
+      Type originalUpperBound = source.getUpperBound();
+      assertThat(originalUpperBound.tsym)
+          .isSameInstanceAs(context.owner().getTypeParameters().head);
+      Type receiverBound = context.parameter(0);
+      Type shape = context.parameter(1);
+      Handler modeledHandler =
+          new Handler() {
+            @Override
+            public boolean onOverrideMethodTypeVariableUpperBound(
+                Symbol.MethodSymbol methodSymbol, int index, VisitorState state) {
+              return methodSymbol.equals(context.declaration()) && index == 0;
+            }
+          };
+      ConstraintSolver modeled = newSolver(context, modeledHandler);
+      Type.TypeVar fresh =
+          modeled
+              .registerInferenceVariables(
+                  context.siteA(),
+                  List.of(declared),
+                  Map.of(declared, receiverBound),
+                  Set.of(declared),
+                  Map.of(declared, shape))
+              .get(declared);
+      assertNullness(fresh.getUpperBound(), true, context);
+      modeled.addSubtypeConstraint(context.state().getSymtab().botType, fresh, false);
+      Solution solution = modeled.solve();
+      InferenceVariable key = new InferenceVariable(declared, context.siteA());
+      assertComplete(solution, key);
+      assertThat(solution.inferredTypes().get(key).tsym).isSameInstanceAs(shape.tsym);
+      assertNullness(solution.inferredTypes().get(key), true, context);
+      assertThat(source.getUpperBound()).isSameInstanceAs(originalUpperBound);
+      assertThat(receiverBound.getAnnotationMirrors()).isEmpty();
+
+      ConstraintSolver control = newSolver(context);
+      Type.TypeVar controlFresh =
+          control
+              .registerInferenceVariables(
+                  context.siteB(),
+                  List.of(declared),
+                  Map.of(declared, receiverBound),
+                  Set.of(declared),
+                  Map.of(declared, shape))
+              .get(declared);
+      UnsatisfiableConstraintsException exception =
+          assertThrows(
+              UnsatisfiableConstraintsException.class,
+              () ->
+                  control.addSubtypeConstraint(
+                      context.state().getSymtab().botType, controlFresh, false));
+      assertThat(exception.getTypeVariable()).isSameInstanceAs(declared);
+      assertThat(exception.getInferenceSite()).isSameInstanceAs(context.siteB());
+      assertThat(exception.isCausedByNonNullUpperBound()).isTrue();
+      assertThat(source.getUpperBound()).isSameInstanceAs(originalUpperBound);
+    }
+
+    /** Verifies the compatibility overload cannot certify a variable without an attributed shape. */
+    private static void checkMissingShapes(TestContext context) {
+      ConstraintSolver solver = newSolver(context);
+      Element declared = context.variable(0);
+      Map<Element, Type.TypeVar> fresh =
+          solver.registerInferenceVariables(
+              context.siteA(), List.of(declared), Map.of(), Set.of(declared));
+      solver.addSubtypeConstraint(context.parameter(0), fresh.get(declared), false);
+      Solution solution = solver.solve();
+      InferenceVariable key = new InferenceVariable(declared, context.siteA());
+      assertThat(solution.inferredTypes().keySet()).containsExactly(key);
+      assertThat(solution.incompleteVariables()).containsExactly(key);
+      assertThat(solution.inconsistentVariables()).isEmpty();
+      assertThat(solution.isComplete()).isFalse();
+      assertThat(solution.isCompleteForSite(context.siteA())).isFalse();
+    }
+
+    /** Checks exact result coverage and both independent certification status sets. */
+    private static void assertComplete(Solution solution, InferenceVariable... variables) {
+      assertThat(solution.inferredTypes().keySet()).containsExactlyElementsIn(List.of(variables));
+      assertThat(solution.incompleteVariables()).isEmpty();
+      assertThat(solution.inconsistentVariables()).isEmpty();
+      assertThat(solution.isComplete()).isTrue();
+      for (InferenceVariable variable : variables) {
+        assertThat(solution.isCompleteForSite(variable.site())).isTrue();
+      }
+    }
+
+    /** Requires explicit solved nullness, including synthetic non-null metadata on concrete roots. */
+    private static void assertNullness(Type type, boolean nullable, TestContext context) {
+      assertNullness(type, nullable, context, true);
+    }
+
+    /** Distinguishes source default non-nullness from the explicit annotations on solver results. */
+    private static void assertNullness(
+        Type type, boolean nullable, TestContext context, boolean requireExplicitNonNull) {
+      assertThat(Nullness.hasNullableAnnotation(type.getAnnotationMirrors().stream(), context.config()))
+          .isEqualTo(nullable);
+      if (nullable || requireExplicitNonNull) {
+        assertThat(Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), context.config()))
+            .isEqualTo(!nullable);
+      }
+    }
+
+    /** Rejects unexpanded inference symbols at every position of an inferred annotation source. */
+    private static void assertNoFreshVariables(Type result, List<Type.TypeVar> freshVariables) {
+      for (Type.TypeVar fresh : freshVariables) {
+        TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(fresh.tsym);
+        result.accept(collector, null);
+        assertThat(collector.getMatches()).isEmpty();
+      }
+    }
+  }
+
+  /** Holds compiler-owned declarations and types for exactly one marker callback. */
+  private record TestContext(
+      VisitorState state,
+      Config config,
+      Symbol.ClassSymbol owner,
+      Symbol.MethodSymbol declaration,
+      MethodInvocationTree siteA,
+      MethodInvocationTree siteB) {
+
+    /** Returns a genuine declaration variable, never a synthesized stand-in symbol. */
+    Element variable(int index) {
+      return declaration.getTypeParameters().get(index);
+    }
+
+    /** Returns an attributed parameter type, preserving its source type-use annotations. */
+    Type parameter(int index) {
+      return declaration.getParameters().get(index).type;
+    }
+  }
+
+  /** Captures identities and annotations throughout recursive source type graphs. */
+  private static final class SourceSnapshot {
+    private final IdentityHashMap<Type, Boolean> seen = new IdentityHashMap<>();
+    private final List<Runnable> checks = new ArrayList<>();
+
+    /** Saves source metadata and mutable child links, guarding recursive declaration bounds. */
+    void capture(Type source) {
+      if (source == null || seen.put(source, true) != null) {
+        return;
+      }
+      Symbol symbol = source.tsym;
+      var annotations = List.copyOf(source.getAnnotationMirrors());
+      checks.add(() -> assertThat(source.tsym).isSameInstanceAs(symbol));
+      checks.add(
+          () ->
+              assertThat(source.getAnnotationMirrors())
+                  .containsExactlyElementsIn(annotations)
+                  .inOrder());
+      List<Type> arguments = List.copyOf(source.getTypeArguments());
+      checks.add(() -> assertThat(source.getTypeArguments()).hasSize(arguments.size()));
+      for (int index = 0; index < arguments.size(); index++) {
+        int argumentIndex = index;
+        Type argument = arguments.get(index);
+        checks.add(
+            () ->
+                assertThat(source.getTypeArguments().get(argumentIndex)).isSameInstanceAs(argument));
+        capture(argument);
+      }
+      if (source instanceof Type.TypeVar variable) {
+        Type upper = variable.getUpperBound();
+        Type lower = variable.getLowerBound();
+        checks.add(() -> assertThat(variable.getUpperBound()).isSameInstanceAs(upper));
+        checks.add(() -> assertThat(variable.getLowerBound()).isSameInstanceAs(lower));
+        capture(upper);
+        capture(lower);
+      } else if (source instanceof Type.ArrayType array) {
+        Type component = array.elemtype;
+        checks.add(() -> assertThat(array.elemtype).isSameInstanceAs(component));
+        capture(component);
+      } else if (source instanceof Type.WildcardType wildcard) {
+        Type boundType = wildcard.type;
+        Type.TypeVar formalBound = wildcard.bound;
+        checks.add(() -> assertThat(wildcard.type).isSameInstanceAs(boundType));
+        checks.add(() -> assertThat(wildcard.bound).isSameInstanceAs(formalBound));
+        capture(boundType);
+        capture(formalBound);
+      } else if (source instanceof Type.ClassType classType) {
+        Type enclosing = classType.getEnclosingType();
+        checks.add(() -> assertThat(classType.getEnclosingType()).isSameInstanceAs(enclosing));
+        capture(enclosing);
+      }
+    }
+
+    /** Rechecks all saved links and metadata after the solver has consumed these types. */
+    void assertUnchanged() {
+      checks.forEach(Runnable::run);
+    }
+  }
+}
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
index 52eb7502..864fdf08 100644
--- a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
@@ -2603,6 +2603,42 @@ public class GenericMethodTests extends NullAwayTestsBase {
         .doTest();
   }
 
+  @Test
+  public void inferredEnclosingTypeNullnessSurvivesInnerClassIdentity() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Outer<E extends @Nullable Object> {
+                final E value;
+                Outer(E value) { this.value = value; }
+                class Inner {
+                  E get() { return value; }
+                }
+              }
+              static <T extends @Nullable Object> Outer<T>.Inner identity(Outer<T>.Inner value) {
+                return value;
+              }
+              static void test(
+                  Outer<@Nullable String>.Inner nullableInner, Outer<String>.Inner nonNullInner) {
+                // BUG: Diagnostic contains: dereferenced expression
+                identity(nullableInner).get().length();
+                identity(nonNullInner).get().length();
+                var extracted = identity(nullableInner).get();
+                // BUG: Diagnostic contains: dereferenced expression
+                extracted.length();
+                var nonNullExtracted = identity(nonNullInner).get();
+                nonNullExtracted.length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
   @Test
   public void fullTypeInferenceRespectsNestedDeclarationBound() {
     makeHelper()
@@ -3414,6 +3450,42 @@ public class GenericMethodTests extends NullAwayTestsBase {
         .doTest();
   }
 
+  @Test
+  public void unusedTypeVariablesDoNotEraseCertifiedLambdaTargets() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.function.Supplier;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <U, R extends @Nullable Object> R plain(Supplier<R> factory) {
+                return factory.get();
+              }
+              static <U extends Comparable<U>, R extends @Nullable Object> R recursive(
+                  Supplier<R> factory) {
+                return factory.get();
+              }
+              static void acceptsNullable(Box<@Nullable String> value) {}
+              static void acceptsNonNull(Box<String> value) {}
+              static void test(Box<@Nullable String> box) {
+                var first = plain(() -> box);
+                var second = recursive(() -> box);
+                acceptsNullable(first);
+                acceptsNullable(second);
+                // BUG: Diagnostic contains: incompatible types
+                acceptsNonNull(first);
+                // BUG: Diagnostic contains: incompatible types
+                acceptsNonNull(second);
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

Validation: full main-module test and self-check passed uncached before cleanup; 1185 tests recorded, 0 failures, 0 errors, 21 skipped. See COMPLETION-005-006.md for certification semantics, acceptance evidence, and limitations.
