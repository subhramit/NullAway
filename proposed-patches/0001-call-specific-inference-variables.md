# Patch 1: Use call-specific inference variables

Status: recommended and fully validated on `master` at `b8e88803`.

This is the supplied stage-1 change with the required Error Prone fix: site projection uses `Tree.equals` rather than rejected reference equality. It fixes #1291 while retaining #1930 cache behavior.

```diff
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
index 5b74181b..790b681e 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
@@ -1,6 +1,8 @@
 package com.uber.nullaway.generics;
 
+import com.sun.source.tree.Tree;
 import com.sun.tools.javac.code.Type;
+import java.util.List;
 import java.util.Map;
 import javax.lang.model.element.Element;
 
@@ -12,10 +14,35 @@ import javax.lang.model.element.Element;
 public interface ConstraintSolver {
 
   /**
-   * Registers a type variable whose nullability is being inferred by this solver. Must be called
-   * before adding constraints involving that parameter; unregistered parameters are fixed types.
+   * An inference variable: a type variable whose type argument is being inferred at a particular
+   * site. The same declared type variable can be inferred at several sites within one inference
+   * problem, e.g., for {@code chooseFirst(id(t), id(s))}, where the two calls to {@code id} need
+   * separate inference variables for the type variable of {@code id}.
+   *
+   * @param typeVariable the declared type variable
+   * @param site the tree whose type argument is being inferred: a generic method invocation, a
+   *     diamond constructor call, or a method reference to a generic method
+   */
+  record InferenceVariable(Element typeVariable, Tree site) {}
+
+  /**
+   * Registers the type variables whose nullability is inferred at {@code site}. Must be called
+   * before adding constraints involving these variables; type variables not registered via this
+   * method are treated as fixed types.
+   *
+   * <p>Returns a fresh type variable for each declared type variable. Callers must substitute these
+   * fresh type variables for the declared ones in all types used to generate constraints for {@code
+   * site}, so that each occurrence of a type variable in a constraint identifies the site it
+   * belongs to. The fresh type variables have the same name, owner, and upper bound nullability as
+   * the declared type variables. Calling this method again for the same site returns the same
+   * fresh type variables.
+   *
+   * @param site the tree whose type arguments are inferred
+   * @param typeVariables the declared type variables inferred at {@code site}
+   * @return a map from each declared type variable to its fresh type variable for {@code site}
    */
-  void registerInferenceVariable(Element typeVariable);
+  Map<Element, Type.TypeVar> registerInferenceVariables(
+      Tree site, List<? extends Element> typeVariables);
 
   /**
    * Exception thrown when the constraints added to the solver are determined to be unsatisfiable.
@@ -25,7 +52,7 @@ public interface ConstraintSolver {
    * exceptions.
    */
   class UnsatisfiableConstraintsException extends RuntimeException {
-    /** Type variable on which the contradiction was detected */
+    /** Declared type variable on which the contradiction was detected */
     private final Element typeVariable;
 
     /** Whether a {@code @Nullable} constraint conflicts with the type variable's upper bound. */
@@ -52,7 +79,9 @@ public interface ConstraintSolver {
 
   /**
    * Add a subtype constraint between two types. Also constrains nested types appropriately (e.g.,
-   * generic type parameters of the two types must have identical nullability).
+   * generic type parameters of the two types must have identical nullability). Inference variables
+   * must appear in the types as the fresh type variables returned by {@link
+   * #registerInferenceVariables(Tree, List)}.
    *
    * @param subtype the subtype
    * @param supertype the supertype
@@ -70,10 +99,11 @@ public interface ConstraintSolver {
   }
 
   /**
-   * Solve the constraints, returning a map from type variables to their inferred nullability.
+   * Solve the constraints, returning a map from inference variables to their inferred nullability.
+   * The map only contains inference variables that appear in constraints.
    *
-   * @return a map from type variables to their inferred nullability
+   * @return a map from inference variables to their inferred nullability
    * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
    */
-  Map<Element, InferredNullability> solve() throws UnsatisfiableConstraintsException;
+  Map<InferenceVariable, InferredNullability> solve() throws UnsatisfiableConstraintsException;
 }
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
index f1f9d3b5..9cb268ea 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
@@ -4,6 +4,7 @@ import static com.uber.nullaway.NullabilityUtil.castToNonNull;
 
 import com.google.common.base.Verify;
 import com.google.errorprone.VisitorState;
+import com.sun.source.tree.Tree;
 import com.sun.tools.javac.code.BoundKind;
 import com.sun.tools.javac.code.Symbol;
 import com.sun.tools.javac.code.Type;
@@ -22,6 +23,7 @@ import java.util.Deque;
 import java.util.IdentityHashMap;
 import java.util.LinkedHashMap;
 import java.util.LinkedHashSet;
+import java.util.List;
 import java.util.Map;
 import java.util.Set;
 import javax.lang.model.element.Element;
@@ -38,8 +40,16 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   private final Handler handler;
   private final VisitorState state;
 
-  /** Type variables belonging to the calls participating in this inference problem. */
-  private final Set<Element> inferenceVariables = new LinkedHashSet<>();
+  /**
+   * Maps the symbol of each fresh type variable created by {@link #registerInferenceVariables(Tree,
+   * List)} to the inference variable it represents. Only type variables with these symbols are
+   * treated as inference variables; all other type variables are fixed.
+   */
+  private final Map<Element, InferenceVariable> inferenceVariables = new LinkedHashMap<>();
+
+  /** Fresh type variables created for each site, keyed by declared type variable. */
+  private final Map<Tree, Map<Element, Type.TypeVar>> freshTypeVariablesForSite =
+      new LinkedHashMap<>();
 
   public ConstraintSolverImpl(Config config, VisitorState state, NullAway analysis) {
     this.config = config;
@@ -68,10 +78,10 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     NullnessState nullness = NullnessState.UNKNOWN;
 
     /** Important to use a LinkedHashSet here for determinism in error messages. */
-    final Set<Element> supertypes = new LinkedHashSet<>();
+    final Set<InferenceVariable> supertypes = new LinkedHashSet<>();
 
     /** Important to use a LinkedHashSet here for determinism in error messages. */
-    final Set<Element> subtypes = new LinkedHashSet<>();
+    final Set<InferenceVariable> subtypes = new LinkedHashSet<>();
 
     VarState(boolean nullableAllowed) {
       this.nullableAllowed = nullableAllowed;
@@ -82,13 +92,59 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
    * All variables seen so far. Important to use a LinkedHashMap here for determinism in error
    * messages.
    */
-  private final Map<Element, VarState> vars = new LinkedHashMap<>();
+  private final Map<InferenceVariable, VarState> vars = new LinkedHashMap<>();
 
   /* ───────────────────── public API ───────────────────── */
 
   @Override
-  public void registerInferenceVariable(Element typeVariable) {
-    inferenceVariables.add(typeVariable);
+  public Map<Element, Type.TypeVar> registerInferenceVariables(
+      Tree site, List<? extends Element> typeVariables) {
+    Map<Element, Type.TypeVar> existing = freshTypeVariablesForSite.get(site);
+    if (existing != null) {
+      return existing;
+    }
+    // First create all the fresh type variables, since the upper bound of one type variable can
+    // refer to others, e.g., <T extends Comparable<T>> or <T, U extends T>.
+    Map<Element, Type.TypeVar> fresh = new LinkedHashMap<>();
+    for (Element typeVariable : typeVariables) {
+      Symbol.TypeVariableSymbol symbol = (Symbol.TypeVariableSymbol) typeVariable;
+      Type.TypeVar declared = (Type.TypeVar) symbol.type;
+      fresh.put(
+          typeVariable, new Type.TypeVar(symbol.name, symbol.owner, declared.getLowerBound()));
+    }
+    Types types = state.getTypes();
+    for (Map.Entry<Element, Type.TypeVar> entry : fresh.entrySet()) {
+      Type.TypeVar declared = (Type.TypeVar) ((Symbol) entry.getKey()).type;
+      entry
+          .getValue()
+          .setUpperBound(
+              TypeSubstitutionUtils.substituteTypeVariables(
+                  declared.getUpperBound(), fresh, types, config));
+    }
+    // The fresh type variables are not among their owner's type parameters, so upper bound
+    // nullability coming from library models (which are keyed on type parameter index) is not
+    // visible on them. When the declared and fresh upper bound nullability differ, make the fresh
+    // upper bound nullability explicit. This matters when a fresh type variable is later seen as a
+    // fixed type, e.g., by a separate inference problem for a call inside a lambda body.
+    for (Map.Entry<Element, Type.TypeVar> entry : fresh.entrySet()) {
+      Type.TypeVar freshVar = entry.getValue();
+      boolean declaredNullable =
+          GenericsUtils.upperBoundIsNullable(entry.getKey(), config, handler, state);
+      Type upperBound = freshVar.getUpperBound();
+      if (declaredNullable
+              != GenericsUtils.upperBoundIsNullable(freshVar.tsym, config, handler, state)
+          && !upperBound.isCompound()) {
+        freshVar.setUpperBound(
+            TypeSubstitutionUtils.typeWithAnnot(
+                upperBound,
+                declaredNullable
+                    ? GenericsChecks.getSyntheticNullableAnnotType(state)
+                    : GenericsChecks.getSyntheticNonNullAnnotType(state)));
+      }
+      inferenceVariables.put(freshVar.tsym, new InferenceVariable(entry.getKey(), site));
+    }
+    freshTypeVariablesForSite.put(site, fresh);
+    return fresh;
   }
 
   @Override
@@ -305,24 +361,25 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   }
 
   @Override
-  public Map<Element, InferredNullability> solve() throws UnsatisfiableConstraintsException {
+  public Map<InferenceVariable, InferredNullability> solve()
+      throws UnsatisfiableConstraintsException {
     /* ---------- work-list propagation of nullability ---------- */
-    Deque<Element> work = new ArrayDeque<>();
+    Deque<InferenceVariable> work = new ArrayDeque<>();
     vars.forEach(
-        (tv, st) -> {
+        (inferenceVar, st) -> {
           if (st.nullness != NullnessState.UNKNOWN) {
-            work.add(tv);
+            work.add(inferenceVar);
           }
         });
 
     while (!work.isEmpty()) {
-      Element typeVarElement = work.removeFirst();
-      VarState st = castToNonNull(vars.get(typeVarElement));
+      InferenceVariable inferenceVar = work.removeFirst();
+      VarState st = castToNonNull(vars.get(inferenceVar));
 
       switch (st.nullness) {
         case NONNULL -> {
           /* S <: tv  &  tv NONNULL  ⇒  S NONNULL */
-          for (Element sub : st.subtypes) {
+          for (InferenceVariable sub : st.subtypes) {
             if (updateNullness(sub, NullnessState.NONNULL)) {
               work.add(sub);
             }
@@ -330,7 +387,7 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
         }
         case NULLABLE -> {
           /* tv <: T  &  tv NULLABLE  ⇒  T NULLABLE */
-          for (Element sup : st.supertypes) {
+          for (InferenceVariable sup : st.supertypes) {
             if (updateNullness(sup, NullnessState.NULLABLE)) {
               work.add(sup);
             }
@@ -339,18 +396,18 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
         default ->
             // UNKNOWN
             throw new RuntimeException(
-                "Unexpected nullness state: " + st.nullness + " for " + typeVarElement);
+                "Unexpected nullness state: " + st.nullness + " for " + inferenceVar);
       }
     }
 
     /* ---------- build final solution map ---------- */
-    Map<Element, InferredNullability> result = new LinkedHashMap<>();
+    Map<InferenceVariable, InferredNullability> result = new LinkedHashMap<>();
     vars.forEach(
-        (tv, st) -> {
+        (inferenceVar, st) -> {
           // Note: if the nullness state is UNKNOWN, we infer NONNULL arbitrarily
           // TODO does this matter?  should we use NULLABLE instead?
           result.put(
-              tv,
+              inferenceVar,
               st.nullness == NullnessState.NULLABLE
                   ? InferredNullability.NULLABLE
                   : InferredNullability.NONNULL);
@@ -365,95 +422,87 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
         s,
         t);
     /* variable-to-variable edge */
-    if (treatAsTypeVariableForInference(s) && treatAsTypeVariableForInference(t)) {
-      TypeVariable sv = (TypeVariable) s;
-      TypeVariable tv = (TypeVariable) t;
-      getState(sv.asElement()).supertypes.add(tv.asElement());
-      getState(tv.asElement()).subtypes.add(sv.asElement());
+    InferenceVariable sVar = inferenceVariableForUse(s);
+    InferenceVariable tVar = inferenceVariableForUse(t);
+    if (sVar != null && tVar != null) {
+      getState(sVar).supertypes.add(tVar);
+      getState(tVar).subtypes.add(sVar);
     }
 
     /* top-level nullability rules */
-    if (isKnownNonNull(t)) {
-      constrainAsNonNull(s);
+    if (isKnownNonNull(t) && sVar != null) {
+      updateNullness(sVar, NullnessState.NONNULL);
     }
-    if (isKnownNullable(s)) {
-      constrainAsNullable(t);
+    if (isKnownNullable(s) && tVar != null) {
+      updateNullness(tVar, NullnessState.NULLABLE);
     }
   }
 
   /* ───────────────────── nullability bookkeeping ───────────────────── */
 
-  /** Force {@code tv} to {@code n}. Returns true if state changed. */
-  private boolean updateNullness(Element typeVarElement, NullnessState n)
+  /**
+   * Force {@code var} to {@code n}. Returns true if state changed.
+   *
+   * <p>Only a registered inference variable with no explicit nullness annotation takes a
+   * constraint; see {@link #inferenceVariableForUse(Type)}. For other types, including enclosing
+   * type parameters and explicitly annotated type-variable uses, no constraint is introduced, and
+   * the normal type compatibility checks report any incompatibility.
+   *
+   * @throws UnsatisfiableConstraintsException if the constraint leads to a contradiction
+   */
+  private boolean updateNullness(InferenceVariable inferenceVar, NullnessState n)
       throws UnsatisfiableConstraintsException {
-    VarState st = getState(typeVarElement);
+    VarState st = getState(inferenceVar);
 
     if (st.nullness == n) {
       return false;
     }
     if (n == NullnessState.NULLABLE && !st.nullableAllowed) {
-      throw new UnsatisfiableConstraintsException(typeVarElement, true);
+      throw new UnsatisfiableConstraintsException(inferenceVar.typeVariable(), true);
     }
     if (st.nullness != NullnessState.UNKNOWN) {
-      throw new UnsatisfiableConstraintsException(typeVarElement);
+      throw new UnsatisfiableConstraintsException(inferenceVar.typeVariable());
     }
     st.nullness = n;
     return true;
   }
 
-  /**
-   * Records that {@code t} must be {@code @Nullable}.
-   *
-   * <p>Only a registered inference variable with no explicit nullness annotation takes a
-   * constraint. For other types, including enclosing type parameters and explicitly annotated
-   * type-variable uses, this method does not introduce any constraint, and the normal type
-   * compatibility checks report any incompatibility.
-   *
-   * @param t the type to constrain
-   * @throws UnsatisfiableConstraintsException if the constraint leads to a contradiction
-   */
-  private void constrainAsNullable(Type t) throws UnsatisfiableConstraintsException {
-    if (treatAsTypeVariableForInference(t)) {
-      updateNullness(t.asElement(), NullnessState.NULLABLE);
-    }
+  /* ───────────────────── helpers & stubs ───────────────────── */
+
+  private VarState getState(InferenceVariable inferenceVar) {
+    return vars.computeIfAbsent(
+        inferenceVar,
+        v ->
+            new VarState(
+                GenericsUtils.upperBoundIsNullable(v.typeVariable(), config, handler, state)));
   }
 
   /**
-   * Records that {@code t} must be {@code @NonNull}.
-   *
-   * <p>Only a registered inference variable with no explicit nullness annotation takes a
-   * constraint. For other types, including enclosing type parameters and explicitly annotated
-   * type-variable uses, this method does not introduce any constraint, and the normal type
-   * compatibility checks report any incompatibility.
+   * If {@code t} is a use of an inference variable without a nullness override, returns that
+   * inference variable. Otherwise, returns {@code null}.
    *
-   * @param t the type to constrain
-   * @throws UnsatisfiableConstraintsException if the constraint leads to a contradiction
+   * @param t the type
+   * @return the inference variable used by {@code t}, or {@code null} if {@code t} should be
+   *     treated as a fixed type for inference
    */
-  private void constrainAsNonNull(Type t) throws UnsatisfiableConstraintsException {
-    if (treatAsTypeVariableForInference(t)) {
-      updateNullness(t.asElement(), NullnessState.NONNULL);
+  private @Nullable InferenceVariable inferenceVariableForUse(Type t) {
+    if (!(t instanceof TypeVar tv)) {
+      return null;
     }
-  }
-
-  /* ───────────────────── helpers & stubs ───────────────────── */
-
-  private VarState getState(Element typeVarElement) {
-    return vars.computeIfAbsent(
-        typeVarElement,
-        v -> new VarState(GenericsUtils.upperBoundIsNullable(v, config, handler, state)));
+    InferenceVariable inferenceVar = inferenceVariables.get(tv.asElement());
+    // Only treat as an inference variable if the use _doesn't_ have an explicit @Nullable or
+    // @NonNull annotation.
+    if (inferenceVar == null
+        || Nullness.hasNullableAnnotation(tv.getAnnotationMirrors().stream(), config)
+        || Nullness.hasNonNullAnnotation(tv.getAnnotationMirrors().stream(), config)) {
+      return null;
+    }
+    return inferenceVar;
   }
 
   /** Returns whether this use denotes an inference variable without a nullness override. */
   private boolean treatAsTypeVariableForInference(Type t) {
-    if (t instanceof TypeVar tv) {
-      // Only treat as a type variable if it _doesn't_ have an explicit @Nullable or @NonNull
-      // annotation.
-      return inferenceVariables.contains(tv.asElement())
-          && !Nullness.hasNullableAnnotation(tv.getAnnotationMirrors().stream(), config)
-          && !Nullness.hasNonNullAnnotation(tv.getAnnotationMirrors().stream(), config);
-    } else {
-      return false;
-    }
+    return inferenceVariableForUse(t) != null;
   }
 
   /** Returns whether a type is explicitly nullable or is the null type. */
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
index 790e8e1e..966b77bf 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
@@ -91,11 +91,33 @@ public final class GenericsChecks {
 
   /**
    * Indicates successful inference of nullability of type variables at a call. Stores the inferred
-   * type variable nullability.
+   * nullability of every inference variable in the inference problem, which may span several calls
+   * (nested calls, generic method references, etc.).
    */
   private record InferenceSuccess(
-      Map<Element, ConstraintSolver.InferredNullability> typeVarNullability)
-      implements CallInferenceResult {}
+      Map<ConstraintSolver.InferenceVariable, ConstraintSolver.InferredNullability>
+          inferenceVariableNullability)
+      implements CallInferenceResult {
+
+    /**
+     * Returns the inferred nullability of the type variables inferred at {@code site}. The result
+     * excludes type variables inferred at other sites in the same inference problem, including
+     * other calls to the same generic method.
+     *
+     * @param site a call or method reference participating in the inference problem
+     * @return a map from declared type variables to their inferred nullability at {@code site}
+     */
+    Map<Element, ConstraintSolver.InferredNullability> typeVarNullabilityForSite(Tree site) {
+      Map<Element, ConstraintSolver.InferredNullability> result = new LinkedHashMap<>();
+      inferenceVariableNullability.forEach(
+          (inferenceVar, nullability) -> {
+            if (inferenceVar.site().equals(site)) {
+              result.put(inferenceVar.typeVariable(), nullability);
+            }
+          });
+      return result;
+    }
+  }
 
   /** Indicates failed inference of nullability of type variables at a call */
   private record InferenceFailure(@SuppressWarnings("UnusedVariable") @Nullable String errorMessage)
@@ -1326,8 +1348,8 @@ public final class GenericsChecks {
               assignedToLocal,
               calledFromDataflow);
     }
-    if (result instanceof InferenceSuccess) {
-      typeVarNullability = ((InferenceSuccess) result).typeVarNullability;
+    if (result instanceof InferenceSuccess successResult) {
+      typeVarNullability = successResult.typeVarNullabilityForSite(callTree);
     }
     Type typeAtCallSite = castToNonNull(ASTHelpers.getType(callTree));
     if (callTree instanceof MethodInvocationTree) {
@@ -1370,7 +1392,6 @@ public final class GenericsChecks {
     // allCalls tracks the top-level call and any nested calls that also require inference
     Set<Tree> allCalls = new LinkedHashSet<>();
     allCalls.add(callTree);
-    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability;
     try {
       generateConstraintsForCall(
           state,
@@ -1382,15 +1403,20 @@ public final class GenericsChecks {
           executableType,
           allCalls,
           calledFromDataflow);
-      typeVarNullability = new LinkedHashMap<>(solver.solve());
+      Map<ConstraintSolver.InferenceVariable, ConstraintSolver.InferredNullability> solution =
+          new LinkedHashMap<>(solver.solve());
       // The solver only computes a solution for variables that appear in constraints. For
-      // unconstrained variables, treat them as NONNULL, consistent with solver behavior for
-      // unconstrained variables that do appear in the constraint graph.
+      // unconstrained variables of the top-level call, treat them as NONNULL, consistent with
+      // solver behavior for unconstrained variables that do appear in the constraint graph.
       for (Symbol.TypeVariableSymbol typeVar : getCallTypeParameters(callTree)) {
-        typeVarNullability.putIfAbsent(typeVar, ConstraintSolver.InferredNullability.NONNULL);
+        solution.putIfAbsent(
+            new ConstraintSolver.InferenceVariable(typeVar, callTree),
+            ConstraintSolver.InferredNullability.NONNULL);
       }
 
-      InferenceSuccess successResult = new InferenceSuccess(typeVarNullability);
+      InferenceSuccess successResult = new InferenceSuccess(solution);
+      Map<Element, ConstraintSolver.InferredNullability> typeVarNullability =
+          successResult.typeVarNullabilityForSite(callTree);
       if (okToCacheInferenceResult(calledFromDataflow)) {
         for (Tree inferredCall : allCalls) {
           inferredTypeVarNullabilityForGenericCalls.put(inferredCall, successResult);
@@ -1542,21 +1568,31 @@ public final class GenericsChecks {
       Set<Tree> allCalls,
       boolean calledFromDataflow)
       throws UnsatisfiableConstraintsException {
-    // Register all type variables whose nullability is inferred for this call.
-    for (Symbol.TypeVariableSymbol typeVariable : getCallTypeParameters(callTree)) {
-      solver.registerInferenceVariable(typeVariable);
-    }
+    // Register all type variables whose nullability is inferred for this call, and use the
+    // call-specific inference variables returned by the solver in place of the declared type
+    // variables. This keeps the constraints for different calls to the same generic method
+    // separate; see https://github.com/uber/NullAway/issues/1291
+    Map<Element, Type.TypeVar> inferenceVariables =
+        solver.registerInferenceVariables(callTree, getCallTypeParameters(callTree));
+    Type.MethodType methodTypeForSite =
+        (Type.MethodType)
+            TypeSubstitutionUtils.substituteTypeVariables(
+                methodType, inferenceVariables, state.getTypes(), config);
     // first, handle the call result flow
     if (typeFromAssignmentContext != null) {
       Type callResultType =
           (callTree instanceof MethodInvocationTree)
-              ? methodType.getReturnType()
-              : getConstructedTypeAtCallSite((NewClassTree) callTree).tsym.type;
+              ? methodTypeForSite.getReturnType()
+              : TypeSubstitutionUtils.substituteTypeVariables(
+                  getConstructedTypeAtCallSite((NewClassTree) callTree).tsym.type,
+                  inferenceVariables,
+                  state.getTypes(),
+                  config);
       solver.addSubtypeConstraint(callResultType, typeFromAssignmentContext, assignedToLocal);
     }
     // then, handle parameters
     TreePath pathToCall = path != null ? path : pathWithLeaf(state.getPath(), callTree);
-    new InvocationArguments(callTree, methodType)
+    new InvocationArguments(callTree, methodTypeForSite)
         .forEach(
             (argument, argPos, formalParamType, unused) -> {
               TreePath pathToArgument = new TreePath(pathToCall, argument);
@@ -1754,20 +1790,40 @@ public final class GenericsChecks {
     // arguments, register the referenced method's type variables as inference variables
     Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(memberReferenceTree);
     List<? extends ExpressionTree> explicitTypeArguments = memberReferenceTree.getTypeArguments();
+    Map<Element, Type.TypeVar> inferenceVariables = Map.of();
     if (referencedMethod != null
+        && !referencedMethod.getTypeParameters().isEmpty()
         && (explicitTypeArguments == null || explicitTypeArguments.isEmpty())) {
-      for (Symbol.TypeVariableSymbol typeVariable : referencedMethod.getTypeParameters()) {
-        solver.registerInferenceVariable(typeVariable);
-      }
+      inferenceVariables =
+          solver.registerInferenceVariables(
+              memberReferenceTree, referencedMethod.getTypeParameters());
     }
+    Map<Element, Type.TypeVar> referenceInferenceVariables = inferenceVariables;
     Type groundTargetType = GenericsUtils.groundTargetType(lhsType, state, config, handler);
     GenericsUtils.processMethodRefTypeRelations(
         this,
         groundTargetType,
         memberReferenceTree,
         state,
-        (subtype, supertype, unused) -> {
-          solver.addSubtypeConstraint(subtype, supertype, false);
+        (subtype, supertype, relationKind) -> {
+          // Use the reference-specific inference variables in place of the referenced method's
+          // declared type variables. Only the side of the relation coming from the referenced
+          // method can mention those type variables as inference variables; the other side comes
+          // from the functional interface type, which may mention the same type variables as
+          // fixed types (e.g., in a recursive reference to an enclosing generic method).
+          boolean referencedMethodTypeIsSubtype =
+              relationKind == GenericsUtils.MethodRefTypeRelationKind.RETURN;
+          Type subtypeForReference =
+              referencedMethodTypeIsSubtype
+                  ? TypeSubstitutionUtils.substituteTypeVariables(
+                      subtype, referenceInferenceVariables, state.getTypes(), config)
+                  : subtype;
+          Type supertypeForReference =
+              referencedMethodTypeIsSubtype
+                  ? supertype
+                  : TypeSubstitutionUtils.substituteTypeVariables(
+                      supertype, referenceInferenceVariables, state.getTypes(), config);
+          solver.addSubtypeConstraint(subtypeForReference, supertypeForReference, false);
         });
   }
 
@@ -2711,8 +2767,14 @@ public final class GenericsChecks {
       CallInferenceResult inferenceResult =
           inferredTypeVarNullabilityForGenericCalls.get(methodInvocationTree);
       if (inferenceResult instanceof InferenceSuccess successResult) {
+        // the referenced method's type variables are inferred at the method reference itself
+        Tree memberReferenceTree = state.getPath().getLeaf();
         return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
-            methodType, methodType, successResult.typeVarNullability, state, config);
+            methodType,
+            methodType,
+            successResult.typeVarNullabilityForSite(memberReferenceTree),
+            state,
+            config);
       }
     }
     return methodType;
@@ -3179,7 +3241,11 @@ public final class GenericsChecks {
           }
         }
         return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
-            methodTypeAtCallSite, methodType, successResult.typeVarNullability, state, config);
+            methodTypeAtCallSite,
+            methodType,
+            successResult.typeVarNullabilityForSite(invocationTree),
+            state,
+            config);
       } else {
         // inference failed; just return the method type at the call site with no substitutions
         return methodTypeAtCallSite;
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java b/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java
index 4435521a..74c665f8 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java
@@ -329,6 +329,44 @@ public class TypeSubstitutionUtils {
     }
   }
 
+  /**
+   * Replaces every occurrence of the given type variables in {@code targetType}, preserving
+   * explicit nullability annotations on the replaced occurrences. So, if {@code targetType} is
+   * {@code List<@Nullable T>} and {@code T} is replaced with {@code S}, the result is {@code
+   * List<@Nullable S>}.
+   *
+   * <p>Occurrences are found by symbol, since a type can contain several {@link Type.TypeVar}
+   * objects for the same symbol, e.g., due to annotations on type variable uses.
+   *
+   * @param targetType type in which to replace type variables
+   * @param replacements map from type variable symbols to their replacement types
+   * @param types the javac types instance
+   * @param config the NullAway config
+   * @return the type with replacements applied, or {@code targetType} itself if it contains none of
+   *     the type variables
+   */
+  static Type substituteTypeVariables(
+      Type targetType,
+      Map<? extends Element, ? extends Type> replacements,
+      Types types,
+      Config config) {
+    ListBuffer<Type> typeVars = new ListBuffer<>();
+    ListBuffer<Type> replacementTypes = new ListBuffer<>();
+    for (Map.Entry<? extends Element, ? extends Type> entry : replacements.entrySet()) {
+      TypeVarWithSymbolCollector tvc = new TypeVarWithSymbolCollector(entry.getKey());
+      targetType.accept(tvc, null);
+      for (Type.TypeVar tv : tvc.getMatches()) {
+        typeVars.append(tv);
+        replacementTypes.append(entry.getValue());
+      }
+    }
+    List<Type> typeVarsToReplace = typeVars.toList();
+    if (typeVarsToReplace.isEmpty()) {
+      return targetType;
+    }
+    return subst(types, targetType, typeVarsToReplace, replacementTypes.toList(), config);
+  }
+
   /**
    * A visitor that restores explicit nullability annotations on types nested within another type to
    * the corresponding positions in the visited type. If no annotations need to be restored, returns
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
index 8e63ee64..653c4623 100644
--- a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
@@ -2412,6 +2412,77 @@ public class GenericMethodTests extends NullAwayTestsBase {
         .doTest();
   }
 
+  @Test
+  public void issue1291() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            public class Test {
+              static <T extends @Nullable Object> T id(T t) {
+                return t;
+              }
+              static <T extends @Nullable Object, U extends @Nullable Object> T chooseFirst(T t, U u) {
+                return t;
+              }
+              static void takesNonNull(String s) {}
+              static void test(@Nullable String s, String t) {
+                String u = chooseFirst(id(t), id(s));
+                // this is safe since each call to id gets its own inference variable
+                u.hashCode();
+              }
+              static void reversed(@Nullable String s, String t) {
+                String u = chooseFirst(id(s), id(t));
+                // BUG: Diagnostic contains: dereferenced expression 'u' is @Nullable
+                u.hashCode();
+              }
+              static void deeperNesting(@Nullable String s, String t) {
+                String u = chooseFirst(id(id(t)), id(id(s)));
+                u.hashCode();
+              }
+              static void nonLocalContext(@Nullable String s, String t) {
+                takesNonNull(chooseFirst(id(t), id(s)));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void issue1291RepeatedDiamond() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            public class Test {
+              static class Box<T extends @Nullable Object> {
+                Box(T t) {}
+              }
+              static <A extends @Nullable Object, B extends @Nullable Object> A first(
+                  Box<A> a, Box<B> b) {
+                throw new UnsupportedOperationException();
+              }
+              static void test(@Nullable String s, String t) {
+                String u = first(new Box<>(t), new Box<>(s));
+                // safe, since each diamond gets its own inference variables
+                u.hashCode();
+              }
+              static void reversed(@Nullable String s, String t) {
+                String u = first(new Box<>(s), new Box<>(t));
+                // BUG: Diagnostic contains: dereferenced expression 'u' is @Nullable
+                u.hashCode();
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

- `./gradlew :nullaway:test` — passed
- `./gradlew :nullaway:buildWithNullAway` — passed
