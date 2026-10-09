# Certify call-specific full-type inference

Apply directly to baseline b8e88803a2bb2287c08d9f85361c9317222387d4. This contains the final inference API, ownership, certification, diagnostic/lambda lifecycle, fixed-bound wildcard obligations, readability cleanup, and their tests in one review unit. The pre-existing repair visitor remains until03; no intermediate scalar-only or uncertified-result API is introduced.

```diff
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
index 5b74181b706500986756ed6a784c89e07459bebd..e33900deaf3839abe6e8d93e8ca2306fa7560855 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolver.java
@@ -1,8 +1,15 @@
 package com.uber.nullaway.generics;
 
+import com.sun.source.tree.Tree;
 import com.sun.tools.javac.code.Type;
+import java.util.Collections;
+import java.util.LinkedHashMap;
+import java.util.LinkedHashSet;
+import java.util.List;
 import java.util.Map;
+import java.util.Set;
 import javax.lang.model.element.Element;
+import org.jspecify.annotations.Nullable;
 
 /**
  * An interface for solving constraints on type variables, such as subtype relationships between
@@ -12,10 +19,94 @@ import javax.lang.model.element.Element;
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
+   * Inferred annotation sources and their certification status. Sources for incomplete or
+   * inconsistent variables are retained for ordinary compatibility diagnostics, not for caching as
+   * successful inference. All collections are immutable snapshots in insertion order.
    */
-  void registerInferenceVariable(Element typeVariable);
+  record Solution(
+      Map<InferenceVariable, Type> inferredTypes,
+      Set<InferenceVariable> incompleteVariables,
+      Set<InferenceVariable> inconsistentVariables) {
+    /** Takes immutable, insertion-ordered snapshots of the supplied inference result. */
+    public Solution {
+      inferredTypes = Collections.unmodifiableMap(new LinkedHashMap<>(inferredTypes));
+      incompleteVariables = Collections.unmodifiableSet(new LinkedHashSet<>(incompleteVariables));
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
+  /**
+   * Registers the type variables whose nullability is inferred at {@code site}. Must be called
+   * before adding constraints involving these variables; type variables not registered via this
+   * method are treated as fixed types.
+   *
+   * <p>Returns a fresh type variable for each declared type variable. Callers must substitute these
+   * fresh type variables for the declared ones in all types used to generate constraints for {@code
+   * site}, so that each occurrence of a type variable in a constraint identifies the site it
+   * belongs to. The fresh type variables have the same name, owner, and upper bound nullability as
+   * the declared type variables. Calling this method again for the same site returns the same fresh
+   * type variables.
+   *
+   * @param site the tree whose type arguments are inferred
+   * @param typeVariables the declared type variables inferred at {@code site}
+   * @param instantiatedUpperBounds upper bounds after receiver/class substitutions, keyed by the
+   *     corresponding declared type variable; an explicit nullable library-model override takes
+   *     precedence over the substituted bound's top-level nullness
+   * @param variablesWithNullnessMarkedBounds variables whose declaration upper-bound annotations
+   *     are an authoritative nullness contract
+   * @return a map from each declared type variable to its fresh type variable for {@code site}
+   */
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
+  Map<Element, Type.TypeVar> registerInferenceVariables(
+      Tree site,
+      List<? extends Element> typeVariables,
+      Map<? extends Element, ? extends Type> instantiatedUpperBounds,
+      Set<? extends Element> variablesWithNullnessMarkedBounds,
+      Map<? extends Element, ? extends Type> javacInstantiations);
 
   /**
    * Exception thrown when the constraints added to the solver are determined to be unsatisfiable.
@@ -25,20 +116,34 @@ public interface ConstraintSolver {
    * exceptions.
    */
   class UnsatisfiableConstraintsException extends RuntimeException {
-    /** Type variable on which the contradiction was detected */
+    /** Declared type variable on which the contradiction was detected */
     private final Element typeVariable;
 
     /** Whether a {@code @Nullable} constraint conflicts with the type variable's upper bound. */
     private final boolean causedByNonNullUpperBound;
 
+    /** Site owning the conflicting inference variable, when available. */
+    private final @Nullable Tree inferenceSite;
+
     public UnsatisfiableConstraintsException(Element typeVariable) {
       this(typeVariable, false);
     }
 
     public UnsatisfiableConstraintsException(
         Element typeVariable, boolean causedByNonNullUpperBound) {
+      this(typeVariable, causedByNonNullUpperBound, null);
+    }
+
+    /** Records the declaration, root-bound cause, and owning inference site of a contradiction. */
+    public UnsatisfiableConstraintsException(
+        Element typeVariable, boolean causedByNonNullUpperBound, @Nullable Tree inferenceSite) {
       this.typeVariable = typeVariable;
       this.causedByNonNullUpperBound = causedByNonNullUpperBound;
+      this.inferenceSite = inferenceSite;
+    }
+
+    public @Nullable Tree getInferenceSite() {
+      return inferenceSite;
     }
 
     public Element getTypeVariable() {
@@ -50,9 +155,55 @@ public interface ConstraintSolver {
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
+  /**
+   * Indicates a proven nested-nullness violation of a declaration upper bound for which no safe
+   * annotation source can preserve ordinary compatibility diagnostics. This includes cyclic
+   * inference, non-generic subclasses of annotated generic bounds, and incompatible nominal shapes
+   * nested inside arrays. The caller reports the violation; the solver does not emit diagnostics.
+   */
+  class NestedUpperBoundViolationException extends UnsatisfiableConstraintsException {
+    private final Tree site;
+    private final Type lowerBound;
+    private final Type upperBound;
+
+    /** Records the inference site and the lower/declaration-bound pair proving the violation. */
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
-   * generic type parameters of the two types must have identical nullability).
+   * generic type parameters of the two types must have identical nullability). Inference variables
+   * must appear in the types as the fresh type variables returned by {@link
+   * #registerInferenceVariables(Tree, List, Map, Set)}.
    *
    * @param subtype the subtype
    * @param supertype the supertype
@@ -64,16 +215,34 @@ public interface ConstraintSolver {
   void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType)
       throws UnsatisfiableConstraintsException;
 
-  enum InferredNullability {
-    NONNULL,
-    NULLABLE
-  }
-
   /**
-   * Solve the constraints, returning a map from type variables to their inferred nullability.
+   * Solves constraints for every registered variable, including unconstrained variables, returning
+   * nullness-annotated types together with explicit completion and consistency status.
+   *
+   * <p>Concrete inferred types carry a synthetic {@code @Nullable} or {@code @NonNull} root
+   * annotation (see {@link GenericsChecks#getSyntheticNullableAnnotType}) giving their inferred
+   * nullability. An unconstrained symbolic caller type variable retains its original root instead
+   * of receiving an inferred default. When the constraints determine the structure of the type
+   * argument, e.g., {@code R = Box<@Nullable String>} for a constraint {@code Box<Box<@Nullable
+   * String>> <: Box<R>}, the inferred type is that type, including its nested nullability
+   * annotations. Covariant array components may be merged, while invariant generic arguments must
+   * remain consistent. Every known lower bound is validated against declaration upper bounds
+   * independently of candidate selection, including when cycles or unknown structure require
+   * fallback. Unknown positions do not suppress a violation proven elsewhere. Wildcard containment
+   * follows the wildcard-generics feature gate. When lower-bound evidence conflicts with a
+   * contextual constraint, the lower-bound type can be returned as annotation evidence so the
+   * existing ordinary argument or method-reference check reports the incompatibility at its
+   * established source location. If no safe annotation source can be established, scalar evidence
+   * is retained but is not certified as complete. Known javac instantiations supply the Java shape
+   * for annotation projection. Every relevant substituted structured bound is checked: unknown
+   * relationships make the result incomplete, and violations make it inconsistent. Fixed caller
+   * type variables remain symbolic. Diagnostic sources in either status must not be cached as
+   * successful inference.
    *
-   * @return a map from type variables to their inferred nullability
+   * @return inferred types and explicit incomplete/inconsistent variable sets
+   * @throws NestedUpperBoundViolationException if a declaration-bound violation is proven but no
+   *     recursively shape-compatible annotation source can preserve ordinary diagnostics
    * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
    */
-  Map<Element, InferredNullability> solve() throws UnsatisfiableConstraintsException;
+  Solution solve() throws UnsatisfiableConstraintsException;
 }
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
index f1f9d3b51d863e022d303326f448b69eae454a9c..f59f64c672f73196baac02ae23ba7ddc0ab07b13 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
@@ -1,9 +1,11 @@
 package com.uber.nullaway.generics;
 
 import static com.uber.nullaway.NullabilityUtil.castToNonNull;
+import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;
 
 import com.google.common.base.Verify;
 import com.google.errorprone.VisitorState;
+import com.sun.source.tree.Tree;
 import com.sun.tools.javac.code.BoundKind;
 import com.sun.tools.javac.code.Symbol;
 import com.sun.tools.javac.code.Type;
@@ -11,21 +13,27 @@ import com.sun.tools.javac.code.Type.CapturedType;
 import com.sun.tools.javac.code.Type.ClassType;
 import com.sun.tools.javac.code.Type.TypeVar;
 import com.sun.tools.javac.code.Type.WildcardType;
+import com.sun.tools.javac.code.TypeTag;
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
 import java.util.LinkedHashMap;
 import java.util.LinkedHashSet;
+import java.util.List;
 import java.util.Map;
 import java.util.Set;
+import javax.lang.model.element.AnnotationMirror;
 import javax.lang.model.element.Element;
 import javax.lang.model.type.NullType;
+import javax.lang.model.type.TypeMirror;
 import javax.lang.model.type.TypeVariable;
 import org.jspecify.annotations.Nullable;
 
@@ -38,8 +46,30 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   private final Handler handler;
   private final VisitorState state;
 
-  /** Type variables belonging to the calls participating in this inference problem. */
-  private final Set<Element> inferenceVariables = new LinkedHashSet<>();
+  /**
+   * Maps the symbol of each fresh type variable created by {@link #registerInferenceVariables(Tree,
+   * List, Map, Set)} to the inference variable it represents. Only type variables with these
+   * symbols are treated as inference variables; all other type variables are fixed.
+   */
+  private final Map<Element, InferenceVariable> inferenceVariables = new LinkedHashMap<>();
+
+  /** Fresh type variables created for each site, keyed by declared type variable. */
+  private final Map<Tree, Map<Element, Type.TypeVar>> freshTypeVariablesForSite =
+      new LinkedHashMap<>();
+
+  /** Fresh type variable corresponding to each inference variable. */
+  private final Map<InferenceVariable, Type.TypeVar> freshTypeVariableForInferenceVariable =
+      new LinkedHashMap<>();
+
+  /** Authoritative Java shapes supplied by attribution, never inferred from nullness bounds. */
+  private final Map<InferenceVariable, Type> javacInstantiations = new LinkedHashMap<>();
+
+  /** Effective upper-bound nullability after receiver/class substitution. */
+  private final Map<InferenceVariable, Boolean> nullableAllowedForInferenceVariable =
+      new LinkedHashMap<>();
+
+  /** Variables whose declaration upper bounds are authoritative nullness contracts. */
+  private final Set<InferenceVariable> nullnessMarkedDeclarationBounds = new LinkedHashSet<>();
 
   public ConstraintSolverImpl(Config config, VisitorState state, NullAway analysis) {
     this.config = config;
@@ -68,10 +98,46 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     NullnessState nullness = NullnessState.UNKNOWN;
 
     /** Important to use a LinkedHashSet here for determinism in error messages. */
-    final Set<Element> supertypes = new LinkedHashSet<>();
+    final Set<InferenceVariable> supertypes = new LinkedHashSet<>();
 
     /** Important to use a LinkedHashSet here for determinism in error messages. */
-    final Set<Element> subtypes = new LinkedHashSet<>();
+    final Set<InferenceVariable> subtypes = new LinkedHashSet<>();
+
+    /** Structural relationships survive explicit occurrence-level root nullness overrides. */
+    final Set<InferenceVariable> structuralSupertypes = new LinkedHashSet<>();
+
+    final Set<InferenceVariable> structuralSubtypes = new LinkedHashSet<>();
+
+    /**
+     * Structured evidence {@code S} with a constraint {@code S <: var}, including arrays, non-raw
+     * classes, and symbolic fixed type variables. Non-generic subclasses are kept because alignment
+     * to a generic supertype can expose nested nullability.
+     */
+    final List<Type> lowerBoundTypes = new ArrayList<>();
+
+    /** Structural fingerprints used to deduplicate {@link #lowerBoundTypes}. */
+    final Set<String> lowerBoundKeys = new LinkedHashSet<>();
+
+    /** Fixed lower uses that constrain this variable's root, not an annotated projection of it. */
+    final List<Type> rootFixedLowerBounds = new ArrayList<>();
+
+    final Set<String> rootFixedLowerBoundKeys = new LinkedHashSet<>();
+
+    /** Fixed type-variable lower uses retained as declaration-diagnostic provenance. */
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
 
     VarState(boolean nullableAllowed) {
       this.nullableAllowed = nullableAllowed;
@@ -82,13 +148,127 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
    * All variables seen so far. Important to use a LinkedHashMap here for determinism in error
    * messages.
    */
-  private final Map<Element, VarState> vars = new LinkedHashMap<>();
+  private final Map<InferenceVariable, VarState> vars = new LinkedHashMap<>();
+
+  /** Deferred containment obligations, checked after every call's lower bounds are available. */
+  private final Set<NonNullWildcardRequirement> nonNullWildcardRequirements = new LinkedHashSet<>();
+
+  /** A wildcard's actual upper bound must fit an inferred variable whose bound excludes null. */
+  private record NonNullWildcardRequirement(Type actual, InferenceVariable required) {}
+
+  /** Incremented whenever a variable, structured bound, or variable edge is added. */
+  private int structuredConstraintVersion = 0;
+
+  /** Variables currently being structurally resolved for invariant comparison. */
+  private final Set<InferenceVariable> invariantResolutionInProgress = new LinkedHashSet<>();
+
+  /** Diagnostic annotation sources selected independently of structured candidate construction. */
+  private final Map<InferenceVariable, Type> declarationBoundFallbacks = new LinkedHashMap<>();
+
+  /** Active directed type pairs, separated by comparison mode and compared by identity. */
+  private final Map<String, IdentityHashMap<Type, Set<Type>>> activeBoundComparisons =
+      new LinkedHashMap<>();
+
+  /** Stable per-run IDs for symbols appearing in structural fingerprints. */
+  private final IdentityHashMap<Symbol, Integer> fingerprintSymbolIds = new IdentityHashMap<>();
 
   /* ───────────────────── public API ───────────────────── */
 
   @Override
-  public void registerInferenceVariable(Element typeVariable) {
-    inferenceVariables.add(typeVariable);
+  public Map<Element, Type.TypeVar> registerInferenceVariables(
+      Tree site,
+      List<? extends Element> typeVariables,
+      Map<? extends Element, ? extends Type> instantiatedUpperBounds,
+      Set<? extends Element> variablesWithNullnessMarkedBounds,
+      Map<? extends Element, ? extends Type> javacInstantiations) {
+    Map<Element, Type.TypeVar> existing = freshTypeVariablesForSite.get(site);
+    if (existing != null) {
+      recordJavacInstantiations(site, existing.keySet(), javacInstantiations);
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
+    Set<Element> receiverInstantiatedBounds = new LinkedHashSet<>();
+    for (Map.Entry<Element, Type.TypeVar> entry : fresh.entrySet()) {
+      Type.TypeVar declared = (Type.TypeVar) ((Symbol) entry.getKey()).type;
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
+      entry
+          .getValue()
+          .setUpperBound(
+              TypeSubstitutionUtils.substituteTypeVariables(upperBound, fresh, types, config));
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
+      boolean modeledNullable = hasNullableUpperBoundOverride(entry.getKey());
+      if ((modeledNullable
+              || (!receiverInstantiatedBounds.contains(entry.getKey())
+                  && declaredNullable
+                      != GenericsUtils.upperBoundIsNullable(freshVar.tsym, config, handler, state)))
+          && !upperBound.isCompound()) {
+        freshVar.setUpperBound(
+            TypeSubstitutionUtils.typeWithAnnot(
+                upperBound,
+                declaredNullable
+                    ? GenericsChecks.getSyntheticNullableAnnotType(state)
+                    : GenericsChecks.getSyntheticNonNullAnnotType(state)));
+      }
+      InferenceVariable inferenceVariable = new InferenceVariable(entry.getKey(), site);
+      inferenceVariables.put(freshVar.tsym, inferenceVariable);
+      freshTypeVariableForInferenceVariable.put(inferenceVariable, freshVar);
+      if (variablesWithNullnessMarkedBounds.contains(entry.getKey())) {
+        nullnessMarkedDeclarationBounds.add(inferenceVariable);
+      }
+      if (variablesWithNullnessMarkedBounds.contains(entry.getKey())
+          && receiverInstantiatedBounds.contains(entry.getKey())) {
+        nullableAllowedForInferenceVariable.put(
+            inferenceVariable,
+            modeledNullable || upperBoundAllowsNullable(freshVar.getUpperBound()));
+      }
+    }
+    freshTypeVariablesForSite.put(site, fresh);
+    recordJavacInstantiations(site, fresh.keySet(), javacInstantiations);
+    for (Element variable : fresh.keySet()) {
+      getState(new InferenceVariable(variable, site));
+    }
+    return fresh;
+  }
+
+  /** Records supplied shapes without discarding earlier shapes on repeated registration. */
+  private void recordJavacInstantiations(
+      Tree site, Set<Element> variables, Map<? extends Element, ? extends Type> instantiations) {
+    for (Element variable : variables) {
+      Type shape = instantiations.get(variable);
+      if (shape != null) {
+        javacInstantiations.put(new InferenceVariable(variable, site), shape);
+      }
+    }
   }
 
   @Override
@@ -151,6 +331,8 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
           Type subtypeTypeArg = subtypeTypeArguments.get(i);
           constrainTypeArgumentContainment(subtypeTypeArg, supertypeTypeArg);
         }
+        // Non-static inner types can carry inference variables only in their enclosing type.
+        subtypeAsSuper.getEnclosingType().accept(this, supertype.getEnclosingType());
       }
       // if supertype is not a ClassType, we still call visitType to handle the case where
       // supertype is a TypeVar or a wildcard
@@ -176,6 +358,18 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
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
@@ -251,8 +445,17 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
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
@@ -305,24 +508,27 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
   }
 
   @Override
-  public Map<Element, InferredNullability> solve() throws UnsatisfiableConstraintsException {
+  public Solution solve() throws UnsatisfiableConstraintsException {
+    prepareStructuredConstraints();
+    constrainNonNullWildcardRequirements();
+
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
@@ -330,7 +536,7 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
         }
         case NULLABLE -> {
           /* tv <: T  &  tv NULLABLE  ⇒  T NULLABLE */
-          for (Element sup : st.supertypes) {
+          for (InferenceVariable sup : st.supertypes) {
             if (updateNullness(sup, NullnessState.NULLABLE)) {
               work.add(sup);
             }
@@ -339,25 +545,1471 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
         default ->
             // UNKNOWN
             throw new RuntimeException(
-                "Unexpected nullness state: " + st.nullness + " for " + typeVarElement);
+                "Unexpected nullness state: " + st.nullness + " for " + inferenceVar);
       }
     }
 
-    /* ---------- build final solution map ---------- */
-    Map<Element, InferredNullability> result = new LinkedHashMap<>();
-    vars.forEach(
-        (tv, st) -> {
-          // Note: if the nullness state is UNKNOWN, we infer NONNULL arbitrarily
-          // TODO does this matter?  should we use NULLABLE instead?
-          result.put(
-              tv,
-              st.nullness == NullnessState.NULLABLE
-                  ? InferredNullability.NULLABLE
-                  : InferredNullability.NONNULL);
-        });
+    // Validation must not depend on whether candidate construction can unfold a cycle or merge
+    // the lower bounds into a single annotation source.
+    declarationBoundFallbacks.clear();
+    Map<InferenceVariable, Type> validatedFallbacks = new LinkedHashMap<>();
+    for (InferenceVariable inferenceVar : vars.keySet()) {
+      Type fallback = validateDeclarationBounds(inferenceVar);
+      if (fallback != null) {
+        validatedFallbacks.put(inferenceVar, fallback);
+      }
+    }
+    // Publish only after validating every variable, so diagnostic fallbacks cannot hide lower
+    // evidence during validation of a dependent variable.
+    declarationBoundFallbacks.putAll(validatedFallbacks);
+
+    /* ---------- build and certify the final solution ---------- */
+    Map<InferenceVariable, Type> result = new LinkedHashMap<>();
+    Map<InferenceVariable, Type> inferredTypes = new LinkedHashMap<>();
+    Set<InferenceVariable> incomplete = new LinkedHashSet<>();
+    Set<InferenceVariable> inconsistent = new LinkedHashSet<>();
+    for (InferenceVariable inferenceVar : vars.keySet()) {
+      result.put(
+          inferenceVar,
+          inferredType(inferenceVar, inferredTypes, new LinkedHashSet<>(), incomplete));
+      if (declarationBoundFallbacks.containsKey(inferenceVar)) {
+        inconsistent.add(inferenceVar);
+      }
+    }
+    validateSolution(result, incomplete, inconsistent);
+    propagateUncertifiedDependencies(incomplete, inconsistent);
+    return new Solution(result, incomplete, inconsistent);
+  }
+
+  /**
+   * Computes the nullness-annotated type inferred for {@code inferenceVar}, after nullability
+   * propagation has reached a fixed point.
+   *
+   * <p>With an attributed Java shape, each resolved lower-bound source (or upper-bound source when
+   * there are no lowers, with declaration bounds preceding contextual bounds) is projected onto
+   * that shape before merging annotations. This permits distinct nominal lower types to share
+   * javac's chosen supertype without inventing a Java shape. Covariant array components merge
+   * nullness; invariant conflicts retain diagnostic evidence and are rejected by independent
+   * validation. Without a shape, the legacy annotation source remains available, but the variable
+   * is incomplete. Unconstrained fixed caller variables stay symbolic.
+   *
+   * @param inferenceVar the inference variable
+   * @param memo already computed inferred types
+   * @param inProgress variables whose inferred types are currently being computed, to guard against
+   *     cycles, e.g., from a constraint {@code List<T> <: T}
+   * @param incomplete variables whose structure cannot be certified
+   * @return the inferred type or diagnostic annotation source
+   */
+  private Type inferredType(
+      InferenceVariable inferenceVar,
+      Map<InferenceVariable, Type> memo,
+      Set<InferenceVariable> inProgress,
+      Set<InferenceVariable> incomplete) {
+    Type memoized = memo.get(inferenceVar);
+    if (memoized != null) {
+      return memoized;
+    }
+    VarState st = castToNonNull(vars.get(inferenceVar));
+    Type declaredTypeVariable = (Type) inferenceVar.typeVariable().asType();
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
+    }
+    try {
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
+        }
+      }
+      Type result = candidate == null ? scalarSource : inferredRoot(candidate, st);
+      memo.put(inferenceVar, result);
+      return result;
+    } finally {
+      inProgress.remove(inferenceVar);
+    }
+  }
+
+  /**
+   * Applies wildcard containment obligations after nested calls have contributed their evidence. A
+   * fixed variable with a nullable bound remains symbolic for nullable-accepting calls, but no
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
+   * Checks whether a supplied Java shape contains only resolved structure. Fixed type variables are
+   * symbolic leaves: their upper bounds are contracts, not replacement shapes. Raw types, captures,
+   * errors, fresh inference variables, and recursive structural objects are uncertified.
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
+   * cannot hide a concrete conflict. Root occurrence checks stay with ordinary compatibility
+   * checks.
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
+  /**
+   * Returns whether structured evidence reachable from {@code start} contains a dependency cycle.
+   * Without authoritative Java shapes, cyclic components cannot supply a certified unfolding.
+   * Independent bound validation still visits concrete positions within cyclic declarations.
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
+  private record StructuredBounds(List<Type> lower, List<Type> upper, List<Type> declaredUpper) {}
+
+  /**
+   * Adds constraints implied by declared upper bounds and by every structured lower/upper pair.
+   *
+   * <p>The latter constraints are transitive consequences of {@code lower <: variable <: upper}.
+   * Generating them before nullness propagation lets dependent bounds such as {@code U extends
+   * Box<T>} constrain {@code T}, rather than defaulting {@code T} before the structured candidate
+   * is checked.
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
+    Type upperBound = freshTypeVariable.getUpperBound();
+    InferenceVariable structuralUpper = inferenceVariableForStructure(upperBound);
+    if (structuralUpper != null) {
+      addStructuralVariableEdge(inferenceVar, structuralUpper);
+    }
+    InferenceVariable upperVariable = inferenceVariableForUse(upperBound);
+    if (upperVariable != null) {
+      addInferenceVariableEdge(inferenceVar, upperVariable);
+    }
+  }
+
+  /** Adds {@code subtype <: supertype}, recording whether the structural graph changed. */
+  private void addInferenceVariableEdge(InferenceVariable subtype, InferenceVariable supertype) {
+    addStructuralVariableEdge(subtype, supertype);
+    boolean changed = getState(subtype).supertypes.add(supertype);
+    changed |= getState(supertype).subtypes.add(subtype);
+    if (changed) {
+      structuredConstraintVersion++;
+    }
+  }
+
+  /** Adds structural subtype evidence without constraining occurrence-overridden root nullness. */
+  private void addStructuralVariableEdge(InferenceVariable subtype, InferenceVariable supertype) {
+    boolean changed = getState(subtype).structuralSupertypes.add(supertype);
+    changed |= getState(supertype).structuralSubtypes.add(subtype);
+    if (changed) {
+      structuredConstraintVersion++;
+    }
+  }
+
+  /**
+   * Returns a deterministic structured annotation source for comparison and legacy diagnostics, or
+   * {@code null} when the evidence cannot be reconciled. This is not a completion certificate.
+   *
+   * <p>All reachable lower and upper bounds participate. Lower bounds are merged only through
+   * covariant positions; generic type arguments remain invariant. If there are no lower bounds, the
+   * most specific compatible upper bound is used. Unknown structure remains uncertified. A
+   * contextual upper-bound mismatch is retained as lower-bound annotation evidence so the
+   * established ordinary argument or method-reference check reports the incompatibility.
+   */
+  private @Nullable Type structuredBound(InferenceVariable inferenceVar) {
+    Type declarationFallback = declarationBoundFallbacks.get(inferenceVar);
+    if (declarationFallback != null) {
+      return declarationFallback;
+    }
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
+        // Declaration violations are handled independently before constructing any candidate.
+        // Keep lower evidence for contextual mismatches so ordinary compatibility checks report it.
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
+   * Validates every known lower against every declaration bound, independently of candidate merging
+   * and cycle fallback. A representable upper-bound source preserves ordinary argument diagnostics;
+   * otherwise the existing exception lets GenericsChecks report the proven violation. Returns the
+   * diagnostic annotation source, or {@code null} when no violation is proven.
+   */
+  private @Nullable Type validateDeclarationBounds(InferenceVariable inferenceVar) {
+    StructuredBounds bounds = structuredBounds(inferenceVar);
+    Type offendingLower = null;
+    Type offendingUpper = null;
+    for (Type lower : bounds.lower()) {
+      for (Type upper : bounds.declaredUpper()) {
+        if (nullabilitySubtype(lower, upper, false) == BoundRelation.VIOLATED
+            && offendingLower == null) {
+          offendingLower = lower;
+          offendingUpper = upper;
+        }
+      }
+    }
+    if (offendingLower == null) {
+      return null;
+    }
+    Type fallback = validatedUpperBoundFallback(bounds.upper());
+    VarState st = vars.get(inferenceVar);
+    boolean representable =
+        fallback != null
+            && !hasStructuredDependencyCycle(inferenceVar)
+            && (st == null || st.fixedTypeVariableLowerBounds.isEmpty());
+    if (fallback != null) {
+      for (Type lower : bounds.lower()) {
+        representable &= canOverlayBound(lower, fallback);
+      }
+    }
+    if (!representable) {
+      throw new NestedUpperBoundViolationException(
+          inferenceVar.typeVariable(),
+          inferenceVar.site(),
+          declarationViolationSource(inferenceVar, offendingLower),
+          castToNonNull(offendingUpper));
+    }
+    return castToNonNull(fallback);
+  }
+
+  /**
+   * Checks recursively whether annotation overlay preserves corresponding nominal positions. In
+   * particular, array covariance must not permit positional copying between unrelated generic
+   * component shapes, even when their argument counts happen to match.
+   */
+  private boolean canOverlayBound(Type target, Type source) {
+    if (!beginBoundComparison("overlay", target, source)) {
+      return false;
+    }
+    try {
+      return hasCorrespondingOverlayStructure(target, source);
+    } finally {
+      endBoundComparison("overlay", target, source);
+    }
+  }
+
+  /** Checks array components, class arguments, and enclosing types for safe positional overlay. */
+  private boolean hasCorrespondingOverlayStructure(Type target, Type source) {
+    if (target instanceof Type.ArrayType targetArray
+        && source instanceof Type.ArrayType sourceArray) {
+      return canOverlayBound(targetArray.elemtype, sourceArray.elemtype);
+    }
+    if (target instanceof ClassType targetClass && source instanceof ClassType sourceClass) {
+      if (!targetClass.tsym.equals(sourceClass.tsym)
+          || targetClass.isRaw()
+          || sourceClass.isRaw()
+          || targetClass.getTypeArguments().size() != sourceClass.getTypeArguments().size()) {
+        return false;
+      }
+      for (int i = 0; i < targetClass.getTypeArguments().size(); i++) {
+        if (!canOverlayBound(
+            targetClass.getTypeArguments().get(i), sourceClass.getTypeArguments().get(i))) {
+          return false;
+        }
+      }
+      return canOverlayBound(targetClass.getEnclosingType(), sourceClass.getEnclosingType());
+    }
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
+    if (target instanceof TypeVar
+        || source instanceof TypeVar
+        || target instanceof WildcardType
+        || source instanceof WildcardType) {
+      return false;
+    }
+    return !(target instanceof ClassType)
+        && !(source instanceof ClassType)
+        && !(target instanceof Type.ArrayType)
+        && !(source instanceof Type.ArrayType)
+        && state.getTypes().isSameType(target, source);
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
+  /**
+   * Collects all structured bounds flowing to and from {@code inferenceVar} in breadth-first order.
+   */
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
+        for (InferenceVariable supertype : st.structuralSupertypes) {
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
+      for (InferenceVariable next : lower ? st.structuralSubtypes : st.structuralSupertypes) {
+        if (visited.add(next)) {
+          worklist.add(next);
+        }
+      }
+    }
     return result;
   }
 
+  /** Adds the structured components of a declared upper bound to {@code result}. */
+  private void addStructuredDeclaredUpperBounds(List<Type> result, Type upperBound) {
+    if (upperBound instanceof Type.IntersectionClassType intersectionType) {
+      for (TypeMirror component : intersectionType.getBounds()) {
+        addStructuredDeclaredUpperBounds(result, (Type) component);
+      }
+    } else if (isStructuredType(upperBound)
+        || (upperBound instanceof TypeVar && inferenceVariableForStructure(upperBound) == null)) {
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
+    if (!canOverlayBound(first, chosen)
+        || !canOverlayBound(second, chosen)
+        || sameInvariantStructure(chosen, otherAligned, false) != BoundRelation.SATISFIED) {
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
+        return TypeSubstitutionUtils.typeWithAnnot(base, (Type) annotation.getAnnotationType());
+      }
+    }
+    Type syntheticAnnotation =
+        nullable
+            ? GenericsChecks.getSyntheticNullableAnnotType(state)
+            : GenericsChecks.getSyntheticNonNullAnnotType(state);
+    return TypeSubstitutionUtils.typeWithAnnot(base, syntheticAnnotation);
+  }
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
+    String mode = compareTopLevel ? "subtype-top" : "subtype-nested";
+    if (!beginBoundComparison(mode, subtype, supertype)) {
+      return BoundRelation.UNKNOWN;
+    }
+    try {
+      BoundRelation topLevel =
+          compareTopLevel
+              ? topLevelNullabilitySubtype(subtype, supertype)
+              : BoundRelation.SATISFIED;
+      return combineBoundRelations(
+          topLevel, nestedNullabilitySubtype(subtype, supertype, compareTopLevel));
+    } finally {
+      endBoundComparison(mode, subtype, supertype);
+    }
+  }
+
+  /**
+   * Checks nested subtype structure without allowing unknown top-level nullness to hide a
+   * violation.
+   */
+  private BoundRelation nestedNullabilitySubtype(
+      Type subtype, Type supertype, boolean compareTopLevel) {
+    WildcardType formalWildcard = GenericsUtils.asWildcard(supertype);
+    WildcardType actualWildcard = GenericsUtils.asWildcard(subtype);
+    if (formalWildcard != null || actualWildcard != null) {
+      return config.handleWildcardGenerics()
+          ? typeArgumentContainment(subtype, supertype)
+          : BoundRelation.UNKNOWN;
+    }
+    if (subtype instanceof TypeVar || supertype instanceof TypeVar) {
+      InferenceVariable subtypeVariable = inferenceVariableForStructure(subtype);
+      InferenceVariable supertypeVariable = inferenceVariableForStructure(supertype);
+      if (subtypeVariable != null && subtypeVariable.equals(supertypeVariable)) {
+        return BoundRelation.SATISFIED;
+      }
+      Type resolvedSubtype = resolveInvariantOccurrence(subtype);
+      Type resolvedSupertype = resolveInvariantOccurrence(supertype);
+      if (resolvedSubtype == null && resolvedSupertype == null) {
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
+        return BoundRelation.UNKNOWN;
+      }
+      return nullabilitySubtype(
+          resolvedSubtype != null ? resolvedSubtype : subtype,
+          resolvedSupertype != null ? resolvedSupertype : supertype,
+          compareTopLevel);
+    }
+    if (subtype instanceof Type.ArrayType subtypeArray
+        && supertype instanceof Type.ArrayType supertypeArray) {
+      return nullabilitySubtype(subtypeArray.elemtype, supertypeArray.elemtype, true);
+    }
+    if (subtype instanceof ClassType && supertype instanceof ClassType supertypeClass) {
+      if (!(supertypeClass.tsym instanceof Symbol.ClassSymbol superSymbol)) {
+        return BoundRelation.UNKNOWN;
+      }
+      Type aligned = TypeSubstitutionUtils.asSuper(state.getTypes(), subtype, superSymbol, config);
+      if (!(aligned instanceof ClassType alignedClass)) {
+        return BoundRelation.VIOLATED;
+      }
+      if (alignedClass.isRaw() || supertypeClass.isRaw()) {
+        return BoundRelation.UNKNOWN;
+      }
+      if (alignedClass.getTypeArguments().size() != supertypeClass.getTypeArguments().size()) {
+        return BoundRelation.VIOLATED;
+      }
+      BoundRelation result = BoundRelation.SATISFIED;
+      for (int i = 0; i < alignedClass.getTypeArguments().size(); i++) {
+        result =
+            combineBoundRelations(
+                result,
+                typeArgumentContainment(
+                    alignedClass.getTypeArguments().get(i),
+                    supertypeClass.getTypeArguments().get(i)));
+      }
+      return combineBoundRelations(
+          result,
+          sameInvariantStructure(
+              alignedClass.getEnclosingType(), supertypeClass.getEnclosingType(), true));
+    }
+    return state.getTypes().isSubtype(subtype, supertype)
+        ? BoundRelation.SATISFIED
+        : BoundRelation.VIOLATED;
+  }
+
+  /** Combines independent positions; a concrete violation dominates any unknown sibling. */
+  private static BoundRelation combineBoundRelations(BoundRelation first, BoundRelation second) {
+    if (first == BoundRelation.VIOLATED || second == BoundRelation.VIOLATED) {
+      return BoundRelation.VIOLATED;
+    }
+    return first == BoundRelation.UNKNOWN || second == BoundRelation.UNKNOWN
+        ? BoundRelation.UNKNOWN
+        : BoundRelation.SATISFIED;
+  }
+
+  /** Starts a comparison unless the same directed identity pair and mode is already active. */
+  private boolean beginBoundComparison(String mode, Type first, Type second) {
+    IdentityHashMap<Type, Set<Type>> pairs =
+        activeBoundComparisons.computeIfAbsent(mode, unused -> new IdentityHashMap<>());
+    Set<Type> seconds =
+        pairs.computeIfAbsent(first, unused -> Collections.newSetFromMap(new IdentityHashMap<>()));
+    return seconds.add(second);
+  }
+
+  /** Removes an active comparison after all its independent positions have been visited. */
+  private void endBoundComparison(String mode, Type first, Type second) {
+    IdentityHashMap<Type, Set<Type>> pairs = castToNonNull(activeBoundComparisons.get(mode));
+    Set<Type> seconds = castToNonNull(pairs.get(first));
+    seconds.remove(second);
+    if (seconds.isEmpty()) {
+      pairs.remove(first);
+    }
+    if (pairs.isEmpty()) {
+      activeBoundComparisons.remove(mode);
+    }
+  }
+
+  /**
+   * Checks directed type-argument containment using the same effective wildcard bounds as
+   * constraint generation. Extends bounds are covariant and super bounds contravariant; concrete
+   * arguments remain invariant. Wildcard checking is unknown when its feature gate is disabled.
+   */
+  private BoundRelation typeArgumentContainment(Type actual, Type formal) {
+    WildcardType formalWildcard = GenericsUtils.asWildcard(formal);
+    WildcardType actualWildcard = GenericsUtils.asWildcard(actual);
+    if (formalWildcard == null && actualWildcard == null) {
+      return sameInvariantStructure(actual, formal, true);
+    }
+    if (!config.handleWildcardGenerics() || !beginBoundComparison("containment", actual, formal)) {
+      return BoundRelation.UNKNOWN;
+    }
+    try {
+      if (formalWildcard == null) {
+        return BoundRelation.UNKNOWN;
+      }
+      if (formalWildcard.kind == BoundKind.SUPER) {
+        Type formalLower = castToNonNull(formalWildcard.getSuperBound());
+        if (actualWildcard == null) {
+          return nullabilitySubtype(formalLower, actual, true);
+        }
+        return actualWildcard.kind == BoundKind.SUPER
+            ? nullabilitySubtype(formalLower, castToNonNull(actualWildcard.getSuperBound()), true)
+            : BoundRelation.UNKNOWN;
+      }
+      return nullabilitySubtype(
+          GenericsUtils.effectiveWildcardUpperBound(actual, state, config, handler),
+          GenericsUtils.wildcardUpperBound(formalWildcard, state, config, handler),
+          true);
+    } finally {
+      endBoundComparison("containment", actual, formal);
+    }
+  }
+
+  /** Checks identical nullability and nested structure for an invariant generic position. */
+  private BoundRelation sameInvariantStructure(Type first, Type second, boolean compareTopLevel) {
+    String mode = compareTopLevel ? "invariant-top" : "invariant-nested";
+    if (!beginBoundComparison(mode, first, second)) {
+      return BoundRelation.UNKNOWN;
+    }
+    try {
+      return compareInvariantStructure(first, second, compareTopLevel);
+    } finally {
+      endBoundComparison(mode, first, second);
+    }
+  }
+
+  /**
+   * Compares all invariant positions, retaining concrete violations alongside unresolved positions.
+   */
+  private BoundRelation compareInvariantStructure(
+      Type first, Type second, boolean compareTopLevel) {
+    if (GenericsUtils.asWildcard(first) != null || GenericsUtils.asWildcard(second) != null) {
+      return combineBoundRelations(
+          typeArgumentContainment(first, second), typeArgumentContainment(second, first));
+    }
+    BoundRelation result = BoundRelation.SATISFIED;
+    if (compareTopLevel) {
+      BoundNullness firstNullness = boundNullness(first);
+      BoundNullness secondNullness = boundNullness(second);
+      if (firstNullness == BoundNullness.UNKNOWN || secondNullness == BoundNullness.UNKNOWN) {
+        if (!sameInferenceVariable(first, second)
+            && !(first instanceof TypeVar firstVariable
+                && second instanceof TypeVar secondVariable
+                && firstVariable.tsym.equals(secondVariable.tsym)
+                && firstNullness == secondNullness)) {
+          result = BoundRelation.UNKNOWN;
+        }
+      } else if (firstNullness != secondNullness) {
+        result = BoundRelation.VIOLATED;
+      }
+    }
+    if (first instanceof TypeVar || second instanceof TypeVar) {
+      return combineBoundRelations(
+          result, compareTypeVariableStructure(first, second, compareTopLevel));
+    }
+    if (first instanceof Type.ArrayType firstArray
+        && second instanceof Type.ArrayType secondArray) {
+      return combineBoundRelations(
+          result,
+          sameInvariantStructure(
+              firstArray.getComponentType(), secondArray.getComponentType(), true));
+    }
+    if (first instanceof ClassType firstClass && second instanceof ClassType secondClass) {
+      if (firstClass.isRaw() || secondClass.isRaw()) {
+        return combineBoundRelations(result, BoundRelation.UNKNOWN);
+      }
+      if (!firstClass.tsym.equals(secondClass.tsym)
+          || firstClass.getTypeArguments().size() != secondClass.getTypeArguments().size()) {
+        return BoundRelation.VIOLATED;
+      }
+      for (int i = 0; i < firstClass.getTypeArguments().size(); i++) {
+        result =
+            combineBoundRelations(
+                result,
+                sameInvariantStructure(
+                    firstClass.getTypeArguments().get(i),
+                    secondClass.getTypeArguments().get(i),
+                    true));
+      }
+      return combineBoundRelations(
+          result,
+          sameInvariantStructure(
+              firstClass.getEnclosingType(), secondClass.getEnclosingType(), true));
+    }
+    return combineBoundRelations(
+        result,
+        state.getTypes().isSameType(first, second)
+            ? BoundRelation.SATISFIED
+            : BoundRelation.VIOLATED);
+  }
+
+  /**
+   * Compares invariant structure involving type variables. Acyclic inference variables are replaced
+   * by their reconciled structured evidence; unresolved, distinct, or cyclic variables remain
+   * unknown rather than being treated as equal.
+   */
+  private BoundRelation compareTypeVariableStructure(
+      Type first, Type second, boolean compareTopLevel) {
+    InferenceVariable firstVariable = inferenceVariableForStructure(first);
+    InferenceVariable secondVariable = inferenceVariableForStructure(second);
+    if (firstVariable != null && firstVariable.equals(secondVariable)) {
+      return BoundRelation.SATISFIED;
+    }
+    Type resolvedFirst = resolveInvariantOccurrence(first);
+    Type resolvedSecond = resolveInvariantOccurrence(second);
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
+  /**
+   * Returns whether the top-level nullness of {@code subtype} is a subtype of {@code supertype}.
+   */
+  private BoundRelation topLevelNullabilitySubtype(Type subtype, Type supertype) {
+    BoundNullness subtypeNullness = boundNullness(subtype);
+    BoundNullness supertypeNullness = boundNullness(supertype);
+    if (supertypeNullness == BoundNullness.NULLABLE) {
+      return BoundRelation.SATISFIED;
+    }
+    if (subtypeNullness == BoundNullness.UNKNOWN || supertypeNullness == BoundNullness.UNKNOWN) {
+      return sameInferenceVariable(subtype, supertype)
+          ? BoundRelation.SATISFIED
+          : BoundRelation.UNKNOWN;
+    }
+    return subtypeNullness == BoundNullness.NULLABLE && supertypeNullness == BoundNullness.NONNULL
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
+      return st.nullness == NullnessState.NULLABLE ? BoundNullness.NULLABLE : BoundNullness.NONNULL;
+    }
+    return type instanceof TypeVar && !isKnownNonNull(type)
+        ? BoundNullness.UNKNOWN
+        : BoundNullness.NONNULL;
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
+   * Records structured evidence for a lower or upper bound. Fixed variables remain symbolic;
+   * checking may inspect their bounds, but candidate construction must not replace them with those
+   * bounds. Their original uses also provide provenance for declaration-bound diagnostics. Arrays
+   * and non-raw classes retain nested annotations and supertype-alignment evidence.
+   */
+  private void recordStructuredBound(
+      InferenceVariable inferenceVar, Type boundType, boolean lower) {
+    VarState st = getState(inferenceVar);
+    if (lower && boundType instanceof TypeVar && inferenceVariableForStructure(boundType) == null) {
+      if (st.fixedTypeVariableLowerBoundKeys.add(structuredTypeKey(boundType))) {
+        st.fixedTypeVariableLowerBounds.add(boundType);
+        structuredConstraintVersion++;
+      }
+    }
+    if (!isStructuredType(boundType)
+        && !(boundType instanceof TypeVar)
+        && !(boundType instanceof ClassType)) {
+      return;
+    }
+    List<Type> bounds = lower ? st.lowerBoundTypes : st.upperBoundTypes;
+    Set<String> keys = lower ? st.lowerBoundKeys : st.upperBoundKeys;
+    if (keys.add(structuredTypeKey(boundType))) {
+      bounds.add(boundType);
+      structuredConstraintVersion++;
+    }
+  }
+
+  /**
+   * Returns a deterministic fingerprint including nested nullness and inference-variable identity.
+   */
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
+      Type enclosingType = classType.getEnclosingType();
+      Type newEnclosingType = markNonNullIfUnannotated(enclosingType);
+      changed |= newEnclosingType != enclosingType;
+      return changed
+          ? TYPE_METADATA_BUILDER.createClassType(classType, newEnclosingType, newTypeArgs)
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
+    if (type instanceof WildcardType) {
+      return markNestedTypesNonNull(type);
+    }
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
+  /**
+   * Adds scalar and structural constraints separately. Explicit occurrence annotations can disable
+   * scalar inference without changing structural ownership or the nested declaration contract.
+   */
   private void directlyConstrainTypePair(Type s, Type t) throws UnsatisfiableConstraintsException {
     Verify.verify(
         s instanceof TypeVariable || t instanceof TypeVariable,
@@ -365,95 +2017,150 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
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
+      addInferenceVariableEdge(sVar, tVar);
+    }
+
+    // Root use-site annotations affect scalar constraints, not the variable's nested contract.
+    InferenceVariable sStructure = inferenceVariableForStructure(s);
+    InferenceVariable tStructure = inferenceVariableForStructure(t);
+    if (tStructure != null) {
+      if (sStructure != null) {
+        addStructuralVariableEdge(sStructure, tStructure);
+      } else {
+        recordStructuredBound(tStructure, s, true);
+      }
+    } else if (sStructure != null) {
+      recordStructuredBound(sStructure, t, false);
+    }
+
+    if (tVar != null
+        && sStructure == null
+        && s instanceof TypeVar
+        && !(s instanceof CapturedType)) {
+      VarState target = getState(tVar);
+      if (target.rootFixedLowerBoundKeys.add(structuredTypeKey(s))) {
+        target.rootFixedLowerBounds.add(s);
+        structuredConstraintVersion++;
+      }
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
+      throw new UnsatisfiableConstraintsException(
+          inferenceVar.typeVariable(), true, inferenceVar.site());
     }
     if (st.nullness != NullnessState.UNKNOWN) {
-      throw new UnsatisfiableConstraintsException(typeVarElement);
+      throw new UnsatisfiableConstraintsException(
+          inferenceVar.typeVariable(), false, inferenceVar.site());
     }
     st.nullness = n;
     return true;
   }
 
+  /* ───────────────────── helpers & stubs ───────────────────── */
+
   /**
-   * Records that {@code t} must be {@code @Nullable}.
-   *
-   * <p>Only a registered inference variable with no explicit nullness annotation takes a
-   * constraint. For other types, including enclosing type parameters and explicitly annotated
-   * type-variable uses, this method does not introduce any constraint, and the normal type
-   * compatibility checks report any incompatibility.
-   *
-   * @param t the type to constrain
-   * @throws UnsatisfiableConstraintsException if the constraint leads to a contradiction
+   * Returns whether a handler explicitly models this declared type variable's bound as nullable.
    */
-  private void constrainAsNullable(Type t) throws UnsatisfiableConstraintsException {
-    if (treatAsTypeVariableForInference(t)) {
-      updateNullness(t.asElement(), NullnessState.NULLABLE);
+  private boolean hasNullableUpperBoundOverride(Element typeVariable) {
+    Symbol.TypeVariableSymbol symbol = (Symbol.TypeVariableSymbol) typeVariable;
+    if (symbol.owner instanceof Symbol.MethodSymbol method) {
+      int index = method.getTypeParameters().indexOf(symbol);
+      return index >= 0 && handler.onOverrideMethodTypeVariableUpperBound(method, index, state);
+    }
+    if (symbol.owner instanceof Symbol.ClassSymbol clazz) {
+      int index = clazz.getTypeParameters().indexOf(symbol);
+      return index >= 0 && handler.onOverrideClassTypeVariableUpperBound(clazz.toString(), index);
     }
+    return false;
   }
 
-  /**
-   * Records that {@code t} must be {@code @NonNull}.
-   *
-   * <p>Only a registered inference variable with no explicit nullness annotation takes a
-   * constraint. For other types, including enclosing type parameters and explicitly annotated
-   * type-variable uses, this method does not introduce any constraint, and the normal type
-   * compatibility checks report any incompatibility.
-   *
-   * @param t the type to constrain
-   * @throws UnsatisfiableConstraintsException if the constraint leads to a contradiction
-   */
-  private void constrainAsNonNull(Type t) throws UnsatisfiableConstraintsException {
-    if (treatAsTypeVariableForInference(t)) {
-      updateNullness(t.asElement(), NullnessState.NONNULL);
+  /** Returns whether an instantiated upper bound permits a nullable inference result. */
+  private boolean upperBoundAllowsNullable(Type upperBound) {
+    if (Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config)) {
+      return true;
     }
+    return upperBound instanceof TypeVar typeVariable
+        && GenericsUtils.upperBoundIsNullable(typeVariable.asElement(), config, handler, state);
   }
 
-  /* ───────────────────── helpers & stubs ───────────────────── */
+  /** Creates scalar state once, including for registered variables without constraints. */
+  private VarState getState(InferenceVariable inferenceVar) {
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
+  }
 
-  private VarState getState(Element typeVarElement) {
-    return vars.computeIfAbsent(
-        typeVarElement,
-        v -> new VarState(GenericsUtils.upperBoundIsNullable(v, config, handler, state)));
+  /** Returns structural variable ownership independently of an occurrence's root annotation. */
+  private @Nullable InferenceVariable inferenceVariableForStructure(Type type) {
+    return type instanceof TypeVar variable && !(type instanceof CapturedType)
+        ? inferenceVariables.get(variable.asElement())
+        : null;
+  }
+
+  /**
+   * Returns an inference variable only when its use has no explicit root nullness override.
+   * Annotated occurrences still retain structural ownership through {@link
+   * #inferenceVariableForStructure(Type)}.
+   */
+  private @Nullable InferenceVariable inferenceVariableForUse(Type t) {
+    if (!(t instanceof TypeVar tv)) {
+      return null;
+    }
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
index 790e8e1ee4ed3598a03c9ff6188cb784a4f31f8d..a4c0f9f5aef40fef7b3be28f7938dc81d59d89ac 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
@@ -42,7 +42,6 @@ import com.sun.tools.javac.code.BoundKind;
 import com.sun.tools.javac.code.Symbol;
 import com.sun.tools.javac.code.Symtab;
 import com.sun.tools.javac.code.Type;
-import com.sun.tools.javac.code.Types;
 import com.sun.tools.javac.tree.JCTree;
 import com.sun.tools.javac.tree.TreeInfo;
 import com.sun.tools.javac.util.Name;
@@ -58,11 +57,15 @@ import com.uber.nullaway.Nullness;
 import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
 import com.uber.nullaway.dataflow.EnclosingEnvironmentNullness;
 import com.uber.nullaway.dataflow.NullnessStore;
+import com.uber.nullaway.generics.ConstraintSolver.NestedUpperBoundViolationException;
+import com.uber.nullaway.generics.ConstraintSolver.NonNullWildcardBoundViolationException;
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
@@ -75,6 +78,7 @@ import javax.lang.model.element.ElementKind;
 import javax.lang.model.type.ExecutableType;
 import javax.lang.model.type.NullType;
 import javax.lang.model.type.TypeKind;
+import javax.lang.model.type.TypeMirror;
 import javax.lang.model.type.TypeVariable;
 import org.jspecify.annotations.Nullable;
 
@@ -89,13 +93,57 @@ public final class GenericsChecks {
   /** Marker interface for results of attempting to infer nullability of type variables at a call */
   private interface CallInferenceResult {}
 
+  /** Complete solutions and diagnostic fallback evidence share only their substitution view. */
+  private interface InferenceWithTypes extends CallInferenceResult {
+    Solution solution();
+
+    /** Selects substitutions belonging to one call or reference, never a sibling's variables. */
+    default Map<Element, Type> inferredTypesForSite(Tree site) {
+      Map<Element, Type> result = new LinkedHashMap<>();
+      solution()
+          .inferredTypes()
+          .forEach(
+              (variable, type) -> {
+                if (variable.site().equals(site)) {
+                  result.put(variable.typeVariable(), type);
+                }
+              });
+      return result;
+    }
+  }
+
+  /** A completed, consistent solution eligible for publication subject to lifecycle guards. */
+  private record InferenceSuccess(Solution solution) implements InferenceWithTypes {}
+
+  /** Uncertified or contradictory evidence used for ordinary checking, never success caching. */
+  private record InferencePartial(Solution solution) implements InferenceWithTypes {}
+
   /**
-   * Indicates successful inference of nullability of type variables at a call. Stores the inferred
-   * type variable nullability.
+   * Tracks which participating calls can safely share a persisted inference result. Once any call
+   * is analyzed with provisional lambda parameter types, no result from that inference problem is
+   * persisted, since all participating calls can be connected through the shared constraint graph.
    */
-  private record InferenceSuccess(
-      Map<Element, ConstraintSolver.InferredNullability> typeVarNullability)
-      implements CallInferenceResult {}
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
 
   /** Indicates failed inference of nullability of type variables at a call */
   private record InferenceFailure(@SuppressWarnings("UnusedVariable") @Nullable String errorMessage)
@@ -113,9 +161,27 @@ public final class GenericsChecks {
   private final Map<Tree, CallInferenceResult> inferredTypeVarNullabilityForGenericCalls =
       new LinkedHashMap<>();
 
-  /** Calls for which a generic inference failure diagnostic has already been reported. */
+  /** Final root targets retained when provisional lambda participation prevents result caching. */
+  private final Map<Tree, CallAndContext> completedCallContexts = new LinkedHashMap<>();
+
+  /**
+   * Completed enclosing or independent inference results indexed by generic method references. This
+   * site-scoped cache remains valid when reusable call caching is disabled because provisional
+   * lambda parameter types participated: the enclosing problem has reached a complete solution
+   * before entries are published.
+   */
+  private final Map<MemberReferenceTree, InferenceSuccess>
+      inferredResultsForGenericMethodReferences = new LinkedHashMap<>();
+
+  /** References currently being solved independently, guarding declaration-type resolution. */
+  private final Set<MemberReferenceTree> methodReferenceInferenceInProgress = new LinkedHashSet<>();
+
+  /** Calls or references for which a generic inference failure has already been reported. */
   private final Set<Tree> callsWithReportedInferenceFailures = new LinkedHashSet<>();
 
+  /** Sites for which a nested declaration-upper-bound violation has already been reported. */
+  private final Set<Tree> reportedNestedUpperBoundViolations = new LinkedHashSet<>();
+
   /**
    * Maps poly expressions for which we have computed a context-derived type to that type, if
    * inference succeeded.
@@ -130,6 +196,22 @@ public final class GenericsChecks {
    */
   private Map<Symbol, Type> lambdaParameterTypesForInference = Map.of();
 
+  /** Ground lambda targets scoped to constraint generation, including parameterless lambdas. */
+  private Map<LambdaExpressionTree, Type> lambdaTargetTypesForInference = Map.of();
+
+  /**
+   * While generating constraints for an inference problem, maps each participating call whose
+   * lambda and method reference arguments get their types published after successful inference (see
+   * {@link #inferredPolyExpressionTypes}) to its executable type, as computed by {@link
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
 
@@ -941,6 +1023,36 @@ public final class GenericsChecks {
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
@@ -956,33 +1068,20 @@ public final class GenericsChecks {
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
@@ -1078,15 +1177,17 @@ public final class GenericsChecks {
    * Returns whether a computed type or generic-call inference result can be cached.
    *
    * <p>Results computed during dataflow may depend on incomplete analysis results. Results computed
-   * while provisional lambda parameter types are available may depend on unsolved outer inference
-   * variables. Skip caching in either context so subsequent checks can recompute the result after
-   * dataflow or the enclosing generic inference completes.
+   * while provisional lambda parameter or target types are available may depend on unsolved outer
+   * inference variables. Skip caching in either context so subsequent checks can recompute the
+   * result after dataflow or the enclosing generic inference completes.
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
@@ -1313,7 +1414,7 @@ public final class GenericsChecks {
     // which may itself require inference, so compute it only once
     Type.MethodType executableType =
         getExecutableTypeForInference(callTree, path, state, calledFromDataflow);
-    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability = null;
+    Map<Element, Type> typeVarNullability = null;
     CallInferenceResult result = inferredTypeVarNullabilityForGenericCalls.get(callTree);
     if (result == null) { // have not yet attempted inference for this call
       result =
@@ -1326,16 +1427,37 @@ public final class GenericsChecks {
               assignedToLocal,
               calledFromDataflow);
     }
-    if (result instanceof InferenceSuccess) {
-      typeVarNullability = ((InferenceSuccess) result).typeVarNullability;
+    if (result instanceof InferenceWithTypes successResult) {
+      typeVarNullability = successResult.inferredTypesForSite(callTree);
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
@@ -1355,7 +1477,7 @@ public final class GenericsChecks {
    *     {@code null} if the type is unavailable or the method result is not assigned anywhere
    * @param assignedToLocal true if the call result is assigned to a local variable, false otherwise
    * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
-   * @return the inference result, either success with inferred type variable nullability or failure
+   * @return the inference result, either success with inferred nullness-annotated types or failure
    *     with an error message
    */
   private CallInferenceResult runInferenceForCall(
@@ -1367,79 +1489,284 @@ public final class GenericsChecks {
       boolean assignedToLocal,
       boolean calledFromDataflow) {
     ConstraintSolver solver = makeSolver(state, analysis);
-    // allCalls tracks the top-level call and any nested calls that also require inference
-    Set<Tree> allCalls = new LinkedHashSet<>();
-    allCalls.add(callTree);
-    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability;
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
-      typeVarNullability = new LinkedHashMap<>(solver.solve());
-      // The solver only computes a solution for variables that appear in constraints. For
-      // unconstrained variables, treat them as NONNULL, consistent with solver behavior for
-      // unconstrained variables that do appear in the constraint graph.
-      for (Symbol.TypeVariableSymbol typeVar : getCallTypeParameters(callTree)) {
-        typeVarNullability.putIfAbsent(typeVar, ConstraintSolver.InferredNullability.NONNULL);
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
       }
-
-      InferenceSuccess successResult = new InferenceSuccess(typeVarNullability);
-      if (okToCacheInferenceResult(calledFromDataflow)) {
-        for (Tree inferredCall : allCalls) {
+      Solution solution = solver.solve();
+
+      InferenceWithTypes result =
+          solution.isComplete() ? new InferenceSuccess(solution) : new InferencePartial(solution);
+      if (result instanceof InferenceSuccess successResult
+          && okToCacheInferenceResult(calledFromDataflow)) {
+        if (typeFromAssignmentContext != null) {
+          completedCallContexts.put(
+              callTree, new CallAndContext(callTree, typeFromAssignmentContext, assignedToLocal));
+        }
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
-      }
-      return successResult;
+        // Store inferred types for lambda or method reference arguments of the participating
+        // calls, including nested calls, each using the types inferred for that call
+        callTypesForPolyArgs.forEach(
+            (call, callExecutableType) ->
+                storeInferredPolyArgumentTypes(
+                    call, callExecutableType, successResult.inferredTypesForSite(call), state));
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
+      }
+      return result;
+    } catch (NestedUpperBoundViolationException e) {
+      String message =
+          errorMessageForIncompatibleTypesAtPseudoAssignment(
+              e.getUpperBound(), e.getLowerBound(), state);
+      ErrorMessage errorMessage =
+          new ErrorMessage(ErrorMessage.MessageTypes.PASS_NULLABLE_GENERIC, message);
+      if (reportedNestedUpperBoundViolations.add(e.getSite())) {
+        VisitorState reportingState = stateForInferenceDiagnostic(e.getSite(), state);
+        state.reportMatch(
+            analysis
+                .getErrorBuilder()
+                .createErrorDescription(
+                    errorMessage, analysis.buildDescription(e.getSite()), reportingState, null));
+      }
+      InferenceFailure failureResult = new InferenceFailure(message);
+      if (okToCacheInferenceResult(calledFromDataflow)) {
+        invalidateMethodReferenceResults(inferenceCacheState);
+        // Solving stopped at this site. Other participating calls still need independent checking.
+        inferredTypeVarNullabilityForGenericCalls.put(e.getSite(), failureResult);
+      }
+      return failureResult;
     } catch (UnsatisfiableConstraintsException e) {
       String inferenceFailureMessage = inferenceFailureMessage(e);
+      Tree reportingSite =
+          e instanceof NonNullWildcardBoundViolationException
+              ? castToNonNull(e.getInferenceSite())
+              : callTree;
+      VisitorState reportingState = stateForInferenceDiagnostic(reportingSite, state);
       if (config.warnOnGenericInferenceFailure()
-          && callsWithReportedInferenceFailures.add(callTree)) {
+          && callsWithReportedInferenceFailures.add(
+              e.getInferenceSite() != null ? e.getInferenceSite() : callTree)) {
         ErrorBuilder errorBuilder = analysis.getErrorBuilder();
         ErrorMessage errorMessage =
             new ErrorMessage(
                 ErrorMessage.MessageTypes.GENERIC_INFERENCE_FAILURE, inferenceFailureMessage);
         state.reportMatch(
             errorBuilder.createErrorDescription(
-                errorMessage, analysis.buildDescription(callTree), state, null));
+                errorMessage, analysis.buildDescription(reportingSite), reportingState, null));
       }
       InferenceFailure failureResult = new InferenceFailure(inferenceFailureMessage);
       if (okToCacheInferenceResult(calledFromDataflow)) {
-        for (Tree inferredCall : allCalls) {
-          inferredTypeVarNullabilityForGenericCalls.put(inferredCall, failureResult);
-        }
+        invalidateMethodReferenceResults(inferenceCacheState);
+        // A deferred wildcard failure completes only its owning site; contextual scalar failures
+        // retain the established root cache policy. Independent nested calls must still be checked.
+        inferredTypeVarNullabilityForGenericCalls.put(reportingSite, failureResult);
       }
       return failureResult;
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
+  /** Recovers the diagnostic site's real ancestors, including local-variable suppressions. */
+  private VisitorState stateForInferenceDiagnostic(Tree site, VisitorState state) {
+    TreePath sitePath = TreePath.getPath(state.getPath().getCompilationUnit(), site);
+    return state.withPath(sitePath != null ? sitePath : pathWithLeaf(state.getPath(), site));
+  }
+
+  /** Invalidates completed reference results only when a publishable inference problem fails. */
+  private void invalidateMethodReferenceResults(InferenceCacheState inferenceCacheState) {
+    for (MemberReferenceTree reference : inferenceCacheState.methodReferences) {
+      inferredResultsForGenericMethodReferences.remove(reference);
+      inferredPolyExpressionTypes.remove(reference);
+    }
+  }
+
+  /** Publishes a functional target only when every inferred variable it uses is certified. */
+  private void storeCertifiedPolyArgumentTypes(
+      Tree call, Type.MethodType executableType, InferencePartial partial, VisitorState state) {
+    Set<Element> uncertified = new LinkedHashSet<>();
+    partial
+        .solution()
+        .incompleteVariables()
+        .forEach(
+            variable -> {
+              if (variable.site().equals(call)) {
+                uncertified.add(variable.typeVariable());
+              }
+            });
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
+  /**
+   * Stores the types of lambda and method reference arguments of a generic call in {@link
+   * #inferredPolyExpressionTypes}, applying the types inferred for the call's type variables to the
+   * javac types of the arguments.
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
@@ -1466,6 +1793,100 @@ public final class GenericsChecks {
     return typeParameters;
   }
 
+  /** Returns variables whose owning declaration is in a nullness-marked context. */
+  private Set<Element> variablesWithNullnessMarkedBounds(
+      List<? extends Element> typeVariables, VisitorState state) {
+    Set<Element> result = new LinkedHashSet<>();
+    for (Element typeVariable : typeVariables) {
+      Symbol owner = ((Symbol) typeVariable).owner;
+      if (!CodeAnnotationInfo.instance(state.context).isSymbolUnannotated(owner, config, handler)) {
+        result.add(typeVariable);
+      }
+    }
+    return result;
+  }
+
+  /**
+   * Recovers Java type shapes from javac attribution independently of NullAway's annotated
+   * evidence. Diamond class variables also occur in the constructed type, even when absent from
+   * parameters.
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
@@ -1526,8 +1947,7 @@ public final class GenericsChecks {
    * @param callTree the call tree representing the generic method call or diamond constructor call
    * @param methodType the executable type of {@code callTree}, as computed by {@link
    *     #getExecutableTypeForInference}
-   * @param allCalls a set of all calls that require inference, including nested ones. This is an
-   *     output parameter that gets mutated while generating the constraints to add nested calls.
+   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
    * @param calledFromDataflow whether this method is being called from dataflow analysis
    * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
    */
@@ -1539,31 +1959,59 @@ public final class GenericsChecks {
       ConstraintSolver solver,
       ExpressionTree callTree,
       Type.MethodType methodType,
-      Set<Tree> allCalls,
+      InferenceCacheState inferenceCacheState,
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
+    List<Symbol.TypeVariableSymbol> callTypeParameters = getCallTypeParameters(callTree);
+    Set<Element> variablesWithNullnessMarkedBounds =
+        variablesWithNullnessMarkedBounds(callTypeParameters, state);
+    Map<Element, Type> instantiatedUpperBounds =
+        getInstantiatedUpperBounds(methodType, callTypeParameters);
+    instantiatedUpperBounds.keySet().retainAll(variablesWithNullnessMarkedBounds);
+    Map<Element, Type.TypeVar> inferenceVariables =
+        solver.registerInferenceVariables(
+            callTree,
+            callTypeParameters,
+            instantiatedUpperBounds,
+            variablesWithNullnessMarkedBounds,
+            getJavacInstantiationsForCall(callTree, methodType, callTypeParameters, state));
+    Map<Tree, Type.MethodType> callTypesForPolyArgs = callTypesForPolyArguments;
+    boolean usesProvisionalLambdaTypes =
+        !lambdaParameterTypesForInference.isEmpty() || !lambdaTargetTypesForInference.isEmpty();
+    inferenceCacheState.recordCall(callTree, usesProvisionalLambdaTypes);
+    if (!usesProvisionalLambdaTypes && callTypesForPolyArgs != null) {
+      callTypesForPolyArgs.put(callTree, methodType);
+    }
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
               generateConstraintsForPseudoAssignment(
                   state.withPath(pathToArgument),
                   solver,
-                  allCalls,
+                  inferenceCacheState,
                   argument,
                   formalParamType,
                   calledFromDataflow);
@@ -1576,8 +2024,7 @@ public final class GenericsChecks {
    *
    * @param state the visitor state
    * @param solver the constraint solver
-   * @param allCalls a set of all calls that require inference, including nested ones. This is an
-   *     output parameter that gets mutated while generating the constraints to add nested calls.
+   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
    * @param rhsExpr the right-hand side expression of the pseudo-assignment
    * @param lhsType the left-hand side type of the pseudo-assignment
    * @param calledFromDataflow whether this method is being called from dataflow analysis
@@ -1585,7 +2032,7 @@ public final class GenericsChecks {
   private void generateConstraintsForPseudoAssignment(
       VisitorState state,
       ConstraintSolver solver,
-      Set<Tree> allCalls,
+      InferenceCacheState inferenceCacheState,
       ExpressionTree rhsExpr,
       Type lhsType,
       boolean calledFromDataflow) {
@@ -1596,7 +2043,6 @@ public final class GenericsChecks {
     // if the parameter is itself a generic call requiring inference, generate constraints for
     // that call
     if (isCallNeedingInference(rhsExpr)) {
-      allCalls.add(rhsExpr);
       generateConstraintsForCall(
           state,
           state.getPath(),
@@ -1605,7 +2051,7 @@ public final class GenericsChecks {
           solver,
           rhsExpr,
           getExecutableTypeForInference(rhsExpr, state.getPath(), state, calledFromDataflow),
-          allCalls,
+          inferenceCacheState,
           calledFromDataflow);
     } else if (rhsExpr instanceof ConditionalExpressionTree conditionalExpressionTree) {
       // generate constraints for both the true and false sub-expressions of the conditional
@@ -1615,7 +2061,7 @@ public final class GenericsChecks {
       generateConstraintsForPseudoAssignment(
           state.withPath(pathToTrueExpression),
           solver,
-          allCalls,
+          inferenceCacheState,
           trueExpression,
           lhsType,
           calledFromDataflow);
@@ -1624,15 +2070,16 @@ public final class GenericsChecks {
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
+          state, state.getPath(), solver, inferenceCacheState, lhsType, lambda, calledFromDataflow);
     } else if (rhsExpr instanceof MemberReferenceTree memberReferenceTree) {
-      handleMethodRefInGenericMethodInference(state, solver, lhsType, memberReferenceTree);
+      handleMethodRefInGenericMethodInference(
+          state, solver, inferenceCacheState, lhsType, memberReferenceTree);
     } else { // all other cases
       Type argumentType = getTreeType(rhsExpr, state, calledFromDataflow);
       if (argumentType == null) {
@@ -1657,8 +2104,7 @@ public final class GenericsChecks {
    * @param path the tree path to the enclosing call if available and possibly distinct from {@code
    *     state.getPath()}
    * @param solver the constraint solver
-   * @param allCalls a set of all calls that require inference, including nested ones. This is an
-   *     output parameter that gets mutated while generating the constraints to add nested calls.
+   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
    * @param lhsType the type to which the lambda is being assigned
    * @param lambda The lambda argument
    * @param calledFromDataflow whether this method is being called from dataflow analysis
@@ -1667,7 +2113,7 @@ public final class GenericsChecks {
       VisitorState state,
       @Nullable TreePath path,
       ConstraintSolver solver,
-      Set<Tree> allCalls,
+      InferenceCacheState inferenceCacheState,
       Type lhsType,
       LambdaExpressionTree lambda,
       boolean calledFromDataflow) {
@@ -1684,6 +2130,10 @@ public final class GenericsChecks {
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
@@ -1708,7 +2158,7 @@ public final class GenericsChecks {
         generateConstraintsForPseudoAssignment(
             state.withPath(returnedExpressionPath),
             solver,
-            allCalls,
+            inferenceCacheState,
             returnedExpression,
             fiReturnType,
             calledFromDataflow);
@@ -1724,7 +2174,7 @@ public final class GenericsChecks {
           generateConstraintsForPseudoAssignment(
               state.withPath(returnExprPath),
               solver,
-              allCalls,
+              inferenceCacheState,
               returnExpr,
               fiReturnType,
               calledFromDataflow);
@@ -1733,41 +2183,116 @@ public final class GenericsChecks {
     } finally {
       // Restore even if constraint generation fails or re-enters inference for a nested lambda.
       lambdaParameterTypesForInference = previousParameterTypes;
+      lambdaTargetTypesForInference = previousTargetTypes;
     }
   }
 
+  /** Recovers a generic reference's Java instantiations from its attributed functional target. */
+  private Map<Element, Type> getJavacInstantiationsForReference(
+      MemberReferenceTree reference, Symbol.MethodSymbol method, VisitorState state) {
+    Map<Element, Type> result = new LinkedHashMap<>();
+    Set<Element> conflicting = new LinkedHashSet<>();
+    Type attributedTarget = ASTHelpers.getType(reference);
+    if (attributedTarget == null || attributedTarget.isRaw()) {
+      return result;
+    }
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
+  }
+
   /**
    * Generate constraints for a method reference argument by comparing functional interface method
    * parameter and return types against the referenced method.
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
     // if we have a reference to a generic method, and the call site does not pass explicit type
     // arguments, register the referenced method's type variables as inference variables
     Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(memberReferenceTree);
     List<? extends ExpressionTree> explicitTypeArguments = memberReferenceTree.getTypeArguments();
+    Type groundTargetType = GenericsUtils.groundTargetType(lhsType, state, config, handler);
+    Map<Element, Type.TypeVar> inferenceVariables = Map.of();
     if (referencedMethod != null
+        && !referencedMethod.getTypeParameters().isEmpty()
         && (explicitTypeArguments == null || explicitTypeArguments.isEmpty())) {
-      for (Symbol.TypeVariableSymbol typeVariable : referencedMethod.getTypeParameters()) {
-        solver.registerInferenceVariable(typeVariable);
-      }
-    }
-    Type groundTargetType = GenericsUtils.groundTargetType(lhsType, state, config, handler);
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
+      inferenceVariables =
+          solver.registerInferenceVariables(
+              memberReferenceTree,
+              referencedMethod.getTypeParameters(),
+              instantiatedUpperBounds,
+              variablesWithNullnessMarkedBounds,
+              getJavacInstantiationsForReference(memberReferenceTree, referencedMethod, state));
+    }
+    Map<Element, Type.TypeVar> referenceInferenceVariables = inferenceVariables;
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
+          boolean referencedMethodTypeIsSubtype = relationKind == MethodRefTypeRelationKind.RETURN;
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
 
@@ -2493,10 +3018,17 @@ public final class GenericsChecks {
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
@@ -2508,9 +3040,20 @@ public final class GenericsChecks {
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
@@ -2617,6 +3160,8 @@ public final class GenericsChecks {
               ExpressionTree actualParameterWithoutParentheses = actualParameterAndState.expr();
               if (actualParameterWithoutParentheses
                   instanceof MemberReferenceTree memberReferenceTree) {
+                maybeStorePolyExpressionTypeFromTarget(
+                    actualParameterWithoutParentheses, formalParameter, state);
                 Type groundFormalParameter =
                     GenericsUtils.groundTargetType(formalParameter, state, config, handler);
                 // the type of the method reference tree provided by javac may not capture
@@ -2638,8 +3183,6 @@ public final class GenericsChecks {
                         }
                       }
                     });
-                maybeStorePolyExpressionTypeFromTarget(
-                    actualParameterWithoutParentheses, formalParameter, state);
                 return;
               }
 
@@ -2689,9 +3232,10 @@ public final class GenericsChecks {
   }
 
   /**
-   * For a generic method reference, if it is being called in a context that requires type argument
-   * nullability inference, return the method type with inferred nullability for type parameters.
-   * Otherwise, return the original method type.
+   * Returns a generic method reference's method type with inferred type arguments. Reuses a
+   * completed enclosing solution when available; otherwise solves the reference independently
+   * against its final target type. During constraint generation or re-entrant reference resolution,
+   * returns the declaration type.
    *
    * @param methodType the original method type
    * @param state the visitor state (generic method reference should be leaf of {@code
@@ -2701,6 +3245,27 @@ public final class GenericsChecks {
    */
   private Type.MethodType getInferredMethodTypeForGenericMethodReference(
       Type.MethodType methodType, VisitorState state) {
+    if (callTypesForPolyArguments != null) {
+      // Constraint generation must use the declaration, not a previous completed solution.
+      return methodType;
+    }
+    Tree referenceTree = state.getPath().getLeaf();
+    if (referenceTree instanceof MemberReferenceTree memberReferenceTree) {
+      Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(memberReferenceTree);
+      List<? extends ExpressionTree> explicitTypeArguments = memberReferenceTree.getTypeArguments();
+      if (referencedMethod == null
+          || referencedMethod.isConstructor()
+          || (explicitTypeArguments != null && !explicitTypeArguments.isEmpty())
+          || methodReferenceInferenceInProgress.contains(memberReferenceTree)) {
+        return methodType;
+      }
+      InferenceWithTypes siteResult =
+          inferredResultsForGenericMethodReferences.get(memberReferenceTree);
+      if (siteResult != null) {
+        return TypeSubstitutionUtils.substituteInferredTypesForGenericMethodReference(
+            methodType, siteResult.inferredTypesForSite(memberReferenceTree), state, config);
+      }
+    }
     TreePath parentPath = state.getPath().getParentPath();
     while (parentPath != null && parentPath.getLeaf() instanceof ParenthesizedTree) {
       parentPath = parentPath.getParentPath();
@@ -2711,13 +3276,94 @@ public final class GenericsChecks {
       CallInferenceResult inferenceResult =
           inferredTypeVarNullabilityForGenericCalls.get(methodInvocationTree);
       if (inferenceResult instanceof InferenceSuccess successResult) {
-        return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
-            methodType, methodType, successResult.typeVarNullability, state, config);
+        // Only reuse a parent solution if the reference actually participated in its constraints.
+        Map<Element, Type> inferredTypes = successResult.inferredTypesForSite(referenceTree);
+        if (!inferredTypes.isEmpty()) {
+          return TypeSubstitutionUtils.substituteInferredTypesForGenericMethodReference(
+              methodType, inferredTypes, state, config);
+        }
+      }
+    }
+    if (referenceTree instanceof MemberReferenceTree memberReferenceTree) {
+      InferenceWithTypes result =
+          inferGenericMethodReferenceIndependently(memberReferenceTree, state);
+      if (result != null) {
+        return TypeSubstitutionUtils.substituteInferredTypesForGenericMethodReference(
+            methodType, result.inferredTypesForSite(memberReferenceTree), state, config);
       }
     }
     return methodType;
   }
 
+  /**
+   * Solves an unresolved generic method reference without publishing or invalidating call results.
+   * This allows siblings of a failed enclosing inference problem to check their declaration bounds
+   * independently. Only completed reference solutions from a cacheable context are persisted.
+   *
+   * @param reference the generic method reference without explicit type arguments
+   * @param state visitor state whose path ends at the reference
+   * @return the completed solution, or {@code null} if no final target is available or inference
+   *     fails
+   */
+  private @Nullable InferenceWithTypes inferGenericMethodReferenceIndependently(
+      MemberReferenceTree reference, VisitorState state) {
+    if (callTypesForPolyArguments != null || !methodReferenceInferenceInProgress.add(reference)) {
+      return null;
+    }
+    try {
+      Type targetType =
+          okToCacheInferenceResult(false) ? inferredPolyExpressionTypes.get(reference) : null;
+      if (targetType == null) {
+        targetType = ASTHelpers.getType(reference);
+      }
+      if (targetType == null || targetType.isRaw()) {
+        return null;
+      }
+      ConstraintSolver solver = makeSolver(state, analysis);
+      InferenceCacheState inferenceCacheState = new InferenceCacheState();
+      handleMethodRefInGenericMethodInference(
+          state, solver, inferenceCacheState, targetType, reference);
+      Solution solution = solver.solve();
+      InferenceWithTypes result =
+          solution.isComplete() ? new InferenceSuccess(solution) : new InferencePartial(solution);
+      if (result instanceof InferenceSuccess successResult && okToCacheInferenceResult(false)) {
+        inferredResultsForGenericMethodReferences.put(reference, successResult);
+      }
+      return result;
+    } catch (NestedUpperBoundViolationException e) {
+      if (reportedNestedUpperBoundViolations.add(e.getSite())) {
+        VisitorState reportingState = stateForInferenceDiagnostic(e.getSite(), state);
+        ErrorMessage errorMessage =
+            new ErrorMessage(
+                ErrorMessage.MessageTypes.PASS_NULLABLE_GENERIC,
+                errorMessageForIncompatibleTypesAtPseudoAssignment(
+                    e.getUpperBound(), e.getLowerBound(), reportingState));
+        state.reportMatch(
+            analysis
+                .getErrorBuilder()
+                .createErrorDescription(
+                    errorMessage, analysis.buildDescription(e.getSite()), reportingState, null));
+      }
+      return null;
+    } catch (UnsatisfiableConstraintsException e) {
+      Tree site = e.getInferenceSite() != null ? e.getInferenceSite() : reference;
+      if (config.warnOnGenericInferenceFailure() && callsWithReportedInferenceFailures.add(site)) {
+        VisitorState reportingState = stateForInferenceDiagnostic(site, state);
+        ErrorMessage errorMessage =
+            new ErrorMessage(
+                ErrorMessage.MessageTypes.GENERIC_INFERENCE_FAILURE, inferenceFailureMessage(e));
+        state.reportMatch(
+            analysis
+                .getErrorBuilder()
+                .createErrorDescription(
+                    errorMessage, analysis.buildDescription(site), reportingState, null));
+      }
+      return null;
+    } finally {
+      methodReferenceInferenceInProgress.remove(reference);
+    }
+  }
+
   /**
    * Checks that type parameter nullability is consistent between an overriding method and the
    * corresponding overridden method.
@@ -3143,22 +3789,38 @@ public final class GenericsChecks {
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
+        while (inferencePath != null && !inferencePath.getLeaf().equals(invocationAndType.call)) {
+          inferencePath = inferencePath.getParentPath();
+        }
+        VisitorState inferenceState = inferencePath != null ? state.withPath(inferencePath) : state;
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
+                methodType,
+                complete.inferredTypesForSite(invocationTree),
+                state.getTypes(),
+                config);
+      }
+      if (result instanceof InferenceWithTypes successResult) {
         // Repairing dropped nested nullability annotations can itself inspect actual argument
         // types. For diamond constructor arguments, that can re-enter method-type computation for
         // this same invocation while we are still repairing it. In that case, use the already
@@ -3179,7 +3841,11 @@ public final class GenericsChecks {
           }
         }
         return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
-            methodTypeAtCallSite, methodType, successResult.typeVarNullability, state, config);
+            methodTypeAtCallSite,
+            methodType,
+            successResult.inferredTypesForSite(invocationTree),
+            state,
+            config);
       } else {
         // inference failed; just return the method type at the call site with no substitutions
         return methodTypeAtCallSite;
@@ -3252,6 +3918,10 @@ public final class GenericsChecks {
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
@@ -3292,7 +3962,10 @@ public final class GenericsChecks {
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
@@ -3300,6 +3973,8 @@ public final class GenericsChecks {
           return new CallAndContext(call, methodSymbol.getReturnType(), false);
         }
       }
+    } else if (parent instanceof LambdaExpressionTree lambda) {
+      return new CallAndContext(call, getLambdaReturnTargetType(lambda, state), false);
     } else if (parent instanceof ExpressionTree exprParent) {
       // could be a parameter to another method call, or part of a conditional expression, etc.
       // in any case, just return the type of the parent expression
@@ -3865,7 +4540,11 @@ public final class GenericsChecks {
    */
   public void clearCache() {
     inferredTypeVarNullabilityForGenericCalls.clear();
+    completedCallContexts.clear();
+    inferredResultsForGenericMethodReferences.clear();
+    methodReferenceInferenceInProgress.clear();
     callsWithReportedInferenceFailures.clear();
+    reportedNestedUpperBoundViolations.clear();
     inferredPolyExpressionTypes.clear();
     inferredVarLocalTypes.clear();
     varLocalDeclarations.clear();
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/InferenceTypeShapes.java b/nullaway/src/main/java/com/uber/nullaway/generics/InferenceTypeShapes.java
new file mode 100644
index 0000000000000000000000000000000000000000..b1532a39993f80e2caa2626de47c596d29e07c31
--- /dev/null
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/InferenceTypeShapes.java
@@ -0,0 +1,310 @@
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
+      // A capture's originating wildcard is explicit shape evidence; its synthesized bounds are
+      // not.
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
+        if (!(aligned instanceof ClassType alignedClass) || !clazz.tsym.equals(alignedClass.tsym)) {
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
+      // A concrete adapted bound is useful, but a fixed type variable's upper bound is not
+      // evidence.
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
+    /**
+     * Records attributed evidence unless it is unresolved or conflicts with an earlier occurrence.
+     */
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
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java b/nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java
index 4435521a5a7a8378caa29f506341164835179856..f31b2936f6fb2d7de74cee2e54708c3a4cd671bb 100644
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
@@ -265,11 +274,37 @@ public class TypeSubstitutionUtils {
     }
   }
 
+  /**
+   * Substitutes complete inferred types into a generic method reference's still-symbolic method
+   * type, including nested type arguments and array components.
+   *
+   * <p>Unlike {@link #updateMethodTypeWithInferredNullability}, this replaces type-variable
+   * occurrences with the inferred types themselves rather than only overlaying their annotations.
+   * Explicit nullability annotations on each original occurrence take precedence over the inferred
+   * root annotation; nested annotations of the replacement are retained. Solver fallback values
+   * that are annotated declared type variables remain symbolic: their upper bounds are not used as
+   * replacement types. Variables absent from the inference map are left unchanged.
+   *
+   * @param methodType the original method-reference type, which may still contain type variables
+   * @param inferredTypes complete inferred substitutions for this method-reference site
+   * @param state the visitor state
+   * @param config the NullAway config
+   * @return the method type with complete inferred substitutions applied
+   */
+  static Type.MethodType substituteInferredTypesForGenericMethodReference(
+      Type.MethodType methodType,
+      Map<Element, Type> inferredTypes,
+      VisitorState state,
+      Config config) {
+    return (Type.MethodType)
+        substituteTypeVariables(methodType, inferredTypes, state.getTypes(), config);
+  }
+
   @SuppressWarnings("ReferenceEquality")
   private static List<Type> updateTypeListNullability(
       List<Type> typesToUpdate,
       List<Type> origTypes,
-      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
+      @Nullable Map<Element, Type> inferredTypes,
       VisitorState state,
       Config config) {
     ListBuffer<Type> buf = new ListBuffer<>();
@@ -277,8 +312,7 @@ public class TypeSubstitutionUtils {
     for (List<Type> l = typesToUpdate, l1 = origTypes; l.nonEmpty(); l = l.tail, l1 = l1.tail) {
       Type toUpdate = l.head;
       Type orig = l1.head;
-      Type t2 =
-          updateTypeWithInferredNullability(toUpdate, orig, typeVarNullability, state, config);
+      Type t2 = updateTypeWithInferredNullability(toUpdate, orig, inferredTypes, state, config);
       buf.append(t2);
       if (t2 != toUpdate) {
         changed = true;
@@ -288,47 +322,323 @@ public class TypeSubstitutionUtils {
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
+      Type origType, Type target, Map<Element, Type> inferredTypes, Types types, Config config) {
+    if (origType instanceof Type.TypeVar origTypeVar && !(origType instanceof Type.CapturedType)) {
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
+   * different class than {@code target} (e.g., a subtype), it is first viewed as an instance of the
+   * class of {@code target}. If the two types cannot be aligned, returns {@code target}.
+   *
+   * @param inferredType the inferred type, whose nested annotations to apply
+   * @param target the type to update
+   * @param types the javac types instance
+   * @param config the NullAway config
+   * @return the updated type, or {@code target} itself if no updates were made
+   */
+  private static Type applyNestedAnnotations(
+      Type inferredType, Type target, Types types, Config config) {
+    return overlayInferredTypeAnnotations(inferredType, target, types, config, true);
+  }
+
+  /**
+   * Overlays inferred nullability only at recursively aligned positions. Class types must name the
+   * same class after viewing the source as the target's supertype; equal argument counts alone do
+   * not establish alignment. Arrays and wildcard bounds are checked recursively rather than passed
+   * to the positional annotation-restoration visitor.
+   *
+   * @param inferredType the annotation source
+   * @param target the type whose structure and unrelated metadata are preserved
+   * @param types the javac types instance
+   * @param config the NullAway config
+   * @param preserveRoot whether to preserve the target's root annotations, including explicit
+   *     occurrence overrides already restored before applying nested inferred annotations
+   * @return the updated target, or the original target if no aligned annotations changed
+   */
+  private static Type overlayInferredTypeAnnotations(
+      Type inferredType, Type target, Types types, Config config, boolean preserveRoot) {
+    if (inferredType instanceof Type.CapturedType || target instanceof Type.CapturedType) {
+      return target;
+    }
+    if (inferredType instanceof Type.ArrayType inferredArrayType
+        && target instanceof Type.ArrayType targetArrayType) {
+      Type.ArrayType updated =
+          preserveRoot
+              ? targetArrayType
+              : (Type.ArrayType) copyDirectNullabilityAnnotations(inferredType, target, config);
+      Type elemType = updated.getComponentType();
+      Type newElemType =
+          overlayInferredTypeAnnotations(
+              inferredArrayType.getComponentType(), elemType, types, config, false);
+      return newElemType == elemType
+          ? updated
+          : TYPE_METADATA_BUILDER.createArrayType(updated, newElemType);
+    }
+    if (inferredType instanceof Type.ClassType
+        && target instanceof Type.ClassType targetClassType) {
+      if (inferredType.isRaw() || targetClassType.isRaw()) {
+        return target;
+      }
+      Type aligned = inferredType;
+      if (inferredType.tsym != targetClassType.tsym) {
+        aligned = asSuper(types, inferredType, (Symbol.ClassSymbol) targetClassType.tsym, config);
+      }
+      if (!(aligned instanceof Type.ClassType alignedClassType)
+          || alignedClassType.isRaw()
+          || alignedClassType.tsym != targetClassType.tsym
+          || alignedClassType.getTypeArguments().size()
+              != targetClassType.getTypeArguments().size()) {
+        return target;
+      }
+      Type updated =
+          preserveRoot ? target : copyDirectNullabilityAnnotations(aligned, target, config);
+      ListBuffer<Type> newTypeArgs = new ListBuffer<>();
+      boolean changed = false;
+      for (List<Type> a = alignedClassType.getTypeArguments(),
+              t = targetClassType.getTypeArguments();
+          t.nonEmpty();
+          a = a.tail, t = t.tail) {
+        Type newTypeArg = overlayInferredTypeAnnotations(a.head, t.head, types, config, false);
+        changed |= newTypeArg != t.head;
+        newTypeArgs.append(newTypeArg);
+      }
+      Type enclosingType = targetClassType.getEnclosingType();
+      Type newEnclosingType =
+          overlayInferredTypeAnnotations(
+              alignedClassType.getEnclosingType(), enclosingType, types, config, false);
+      changed |= newEnclosingType != enclosingType;
+      return changed
+          ? TYPE_METADATA_BUILDER.createClassType(updated, newEnclosingType, newTypeArgs.toList())
+          : updated;
+    }
+    if (inferredType instanceof Type.WildcardType inferredWildcard
+        && target instanceof Type.WildcardType targetWildcard) {
+      if (inferredWildcard.kind != targetWildcard.kind) {
+        return target;
+      }
+      Type.WildcardType updated =
+          preserveRoot
+              ? targetWildcard
+              : (Type.WildcardType) copyDirectNullabilityAnnotations(inferredType, target, config);
+      if (inferredWildcard.type == null || targetWildcard.type == null) {
+        return updated;
+      }
+      Type newBound =
+          overlayInferredTypeAnnotations(
+              inferredWildcard.type, targetWildcard.type, types, config, false);
+      if (newBound == targetWildcard.type) {
+        return updated;
+      }
+      Type.WildcardType result = TYPE_METADATA_BUILDER.createWildcardType(updated, newBound);
+      result.bound = updated.bound;
+      return result;
+    }
+    if (!preserveRoot
+        && inferredType.tsym != null
+        && inferredType.tsym == target.tsym
+        && inferredType.getTag() == target.getTag()) {
+      return copyDirectNullabilityAnnotations(inferredType, target, config);
+    }
+    return target;
+  }
+
+  /**
+   * Copies only a source's direct nullability annotation, retaining the target's other annotations
+   * and using the metadata builder to avoid mutating shared javac types. If the source has no
+   * direct nullability annotation, the target is unchanged.
+   */
+  private static Type copyDirectNullabilityAnnotations(Type source, Type target, Config config) {
+    for (Attribute.TypeCompound annotation : source.getAnnotationMirrors()) {
+      if (annotation.type.tsym == null) {
+        continue;
+      }
+      String name = annotation.type.tsym.getQualifiedName().toString();
+      if (!Nullness.isNullableAnnotation(name, config)
+          && !Nullness.isNonNullAnnotation(name, config)) {
+        continue;
+      }
+      ListBuffer<Attribute.TypeCompound> annotations = new ListBuffer<>();
+      for (Attribute.TypeCompound targetAnnotation : target.getAnnotationMirrors()) {
+        if (targetAnnotation.type.tsym != null) {
+          String targetName = targetAnnotation.type.tsym.getQualifiedName().toString();
+          if (Nullness.isNullableAnnotation(targetName, config)
+              || Nullness.isNonNullAnnotation(targetName, config)) {
+            continue;
+          }
+        }
+        annotations.append(targetAnnotation);
+      }
+      annotations.append(annotation);
+      return TYPE_METADATA_BUILDER.cloneTypeWithMetadata(
+          target, TYPE_METADATA_BUILDER.create(annotations.toList()));
+    }
+    return target;
+  }
+
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
diff --git a/nullaway/src/test/java/com/uber/nullaway/JSpecifyJDKModelsTest.java b/nullaway/src/test/java/com/uber/nullaway/JSpecifyJDKModelsTest.java
index 83d3f6948c531a69e547be549952f0e3ac210c14..6cd2c4c3988b9cf9c7f8964674fc4043f19d7474 100644
--- a/nullaway/src/test/java/com/uber/nullaway/JSpecifyJDKModelsTest.java
+++ b/nullaway/src/test/java/com/uber/nullaway/JSpecifyJDKModelsTest.java
@@ -555,6 +555,84 @@ public class JSpecifyJDKModelsTest extends NullAwayTestsBase {
         .doTest();
   }
 
+  @Test
+  public void modeledNestedReturnAnnotationInGenericInference() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.Optional;
+            import java.util.concurrent.CompletableFuture;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static <T> T identity(T value) { return value; }
+              void test() {
+                CompletableFuture<@Nullable Void> direct = CompletableFuture.allOf();
+                CompletableFuture<@Nullable Void> inferred =
+                    Optional.of(CompletableFuture.allOf()).get();
+                CompletableFuture<@Nullable Void> identity = identity(CompletableFuture.allOf());
+                Optional<CompletableFuture<@Nullable Void>> optional =
+                    Optional.of(CompletableFuture.allOf());
+                CompletableFuture<@Nullable Void> nested =
+                    Optional.of(Optional.of(CompletableFuture.allOf())).get().get();
+                var future = Optional.of(CompletableFuture.allOf()).get();
+                CompletableFuture<@Nullable Void> fromVar = future;
+                // BUG: Diagnostic contains: dereferenced expression
+                future.join().toString();
+                // BUG: Diagnostic contains: incompatible types
+                CompletableFuture<Void> invalid = Optional.of(CompletableFuture.allOf()).get();
+                // BUG: Diagnostic contains: incompatible types
+                CompletableFuture<Void> invalidIdentity = identity(CompletableFuture.allOf());
+              }
+              CompletableFuture<@Nullable Void> returned() {
+                return identity(CompletableFuture.allOf());
+              }
+              CompletableFuture<Void> invalidReturn() {
+                // BUG: Diagnostic contains: incompatible types
+                return identity(CompletableFuture.allOf());
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void modeledNestedReturnInferencePreservesPayloadNullness() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.Optional;
+            import java.util.concurrent.CompletableFuture;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static <T> T identity(T value) { return value; }
+              void test() {
+                var nonNull = Optional.of(CompletableFuture.completedFuture("ok")).get();
+                CompletableFuture<String> valid = nonNull;
+                nonNull.join().length();
+                identity(CompletableFuture.completedFuture("ok")).join().length();
+                Optional.of(Optional.of(CompletableFuture.completedFuture("ok")))
+                    .get().get().join().length();
+                var nullable = Optional.of(CompletableFuture.allOf()).get();
+                // BUG: Diagnostic contains: incompatible types
+                CompletableFuture<Void> invalidVar = nullable;
+                var nested = Optional.of(Optional.of(CompletableFuture.allOf())).get().get();
+                CompletableFuture<@Nullable Void> validNested = nested;
+                // BUG: Diagnostic contains: incompatible types
+                CompletableFuture<Void> invalidNested = nested;
+                // BUG: Diagnostic contains: dereferenced expression
+                nested.join().toString();
+              }
+            }
+            """)
+        .doTest();
+  }
+
   private CompilationTestHelper makeHelper() {
     return makeTestHelperWithArgs(
         JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
diff --git a/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java b/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java
new file mode 100644
index 0000000000000000000000000000000000000000..1cfe0ce8c5bedb26d1f297861d8943433e485ead
--- /dev/null
+++ b/nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java
@@ -0,0 +1,955 @@
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
+import com.uber.nullaway.generics.ConstraintSolver.NonNullWildcardBoundViolationException;
+import com.uber.nullaway.generics.ConstraintSolver.Solution;
+import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
+import com.uber.nullaway.handlers.Handler;
+import java.util.ArrayList;
+import java.util.IdentityHashMap;
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
+    runFixture("callSeparation", "<T extends @Nullable Object> void inference(String shape) {}");
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
+        "modeledBound", "<T extends OuterT> void inference(String receiverBound, String shape) {}");
+  }
+
+  @Test
+  public void fourArgumentRegistrationWithoutJavaShapesIsIncomplete() {
+    runFixture("missingShapes", "<T extends @Nullable Object> void inference(String evidence) {}");
+  }
+
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
+              import org.jspecify.annotations.NonNull;
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
+            "missingShapes",
+            "wildcardEvidenceOrder",
+            "wildcardProjectionBarriers",
+            "wildcardModeledFixedSource");
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
+        case "wildcardEvidenceOrder" -> checkWildcardEvidenceOrder(context);
+        case "wildcardProjectionBarriers" -> checkWildcardProjectionBarriers(context);
+        case "wildcardModeledFixedSource" -> checkWildcardModeledFixedSource(context);
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
+        if (member instanceof MethodTree method && method.getName().contentEquals("inference")) {
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
+        assertThat(component.tsym)
+            .isSameInstanceAs(((Type.ArrayType) nonNullElements).elemtype.tsym);
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
+        solver.addSubtypeConstraint(
+            context.parameter(nullableFirst ? 1 : 0), fresh.get(declared), false);
+        solver.addSubtypeConstraint(
+            context.parameter(nullableFirst ? 0 : 1), fresh.get(declared), false);
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
+    /**
+     * Checks that registration alone creates complete concrete results with default root nullness.
+     */
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
+            solution
+                .inferredTypes()
+                .get(new InferenceVariable(context.variable(index), context.siteA()));
+        assertThat(result).isInstanceOf(Type.ClassType.class);
+        assertThat(result.tsym).isSameInstanceAs(context.parameter(index).tsym);
+        assertNullness(result, false, context);
+      }
+    }
+
+    /**
+     * Snapshots recursive source graphs before registration, substitution, merging, and solving.
+     */
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
+    /**
+     * Uses a real method owner/index to test model precedence over a substituted receiver bound.
+     */
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
+    /**
+     * Verifies the compatibility overload cannot certify a variable without an attributed shape.
+     */
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
+          solver
+              .registerInferenceVariables(
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
+            solver.addSubtypeConstraint(wildcardFixtureType(context, 1, fresh), formal, false);
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
+    /**
+     * Requires explicit solved nullness, including synthetic non-null metadata on concrete roots.
+     */
+    private static void assertNullness(Type type, boolean nullable, TestContext context) {
+      assertNullness(type, nullable, context, true);
+    }
+
+    /**
+     * Distinguishes source default non-nullness from the explicit annotations on solver results.
+     */
+    private static void assertNullness(
+        Type type, boolean nullable, TestContext context, boolean requireExplicitNonNull) {
+      assertThat(
+              Nullness.hasNullableAnnotation(
+                  type.getAnnotationMirrors().stream(), context.config()))
+          .isEqualTo(nullable);
+      if (nullable || requireExplicitNonNull) {
+        assertThat(
+                Nullness.hasNonNullAnnotation(
+                    type.getAnnotationMirrors().stream(), context.config()))
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
+                assertThat(source.getTypeArguments().get(argumentIndex))
+                    .isSameInstanceAs(argument));
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
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java
index 1a7fbb11227f2c48e7f88059f1db6bd22ad9ef05..da074c1a2ea6c15f48593dfcebf79d36bbb90f15 100644
--- a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java
@@ -244,6 +244,72 @@ public class GenericInferenceErrorReportingTests extends NullAwayTestsBase {
                 + "but its upper bound requires it to be @NonNull");
   }
 
+  @Test
+  public void scalarInferenceFailureInsideImplicitLambdaReportedOnce() {
+    JavaFileObject callerSource =
+        FileObjects.forSourceLines(
+            "Caller.java",
+            """
+            package com.uber;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Caller {
+              interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
+                R apply(P value);
+              }
+              static <P extends @Nullable Object, R extends @Nullable Object> R map(
+                  P value, Mapper<P, R> mapper) {
+                throw new UnsupportedOperationException();
+              }
+              static <T> T nonNullId(T value) { return value; }
+              static void test() {
+                map("value", x -> nonNullId(null));
+              }
+            }
+            """);
+
+    List<String> diagnostics = compileAndReportDiagnostics(List.of(callerSource));
+    assertThat(
+            diagnostics.stream()
+                .filter(diagnostic -> diagnostic.contains("inference failure:"))
+                .toList())
+        .hasSize(1);
+    assertThat(
+            diagnostics.stream()
+                .anyMatch(diagnostic -> diagnostic.contains("passing @Nullable parameter")))
+        .isTrue();
+  }
+
+  @Test
+  public void scalarInferenceFailureInsideImplicitLambdaSuppressed() {
+    JavaFileObject callerSource =
+        FileObjects.forSourceLines(
+            "Caller.java",
+            """
+            package com.uber;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Caller {
+              interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
+                R apply(P value);
+              }
+              static <P extends @Nullable Object, R extends @Nullable Object> R map(
+                  P value, Mapper<P, R> mapper) {
+                throw new UnsupportedOperationException();
+              }
+              static <T> T nonNullId(T value) { return value; }
+              @SuppressWarnings("NullAway")
+              static void test() {
+                map("value", x -> nonNullId(null));
+              }
+            }
+            """);
+
+    assertThat(compileAndReportDiagnostics(List.of(callerSource))).isEmpty();
+  }
+
   /**
    * Fails when an explicit {@code @Nullable} annotation on a type-variable use constrains the
    * underlying inference variable, which makes NullAway report one mismatch twice: as an inference
@@ -307,6 +373,70 @@ public class GenericInferenceErrorReportingTests extends NullAwayTestsBase {
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
+  /**
+   * Characterizes a pre-existing false-positive limitation for mixed invariant lower bounds. The
+   * unconstrained type variable T could be inferred as Object, making both calls valid. The current
+   * incompatibilities are not correct full inference; retain their exact diagnostics here without
+   * treating them as desired behavior or allowing additional inference-failure diagnostics.
+   */
+  @Test
+  public void knownLimitationMixedInvariantLowerBoundsReportFalsePositiveIncompatibilities() {
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
@@ -405,6 +535,74 @@ public class GenericInferenceErrorReportingTests extends NullAwayTestsBase {
     return fileName + ":" + diagnostic.getLineNumber() + ": " + message;
   }
 
+  /** Each independent nested bound violation gets exactly one diagnostic on its own call line. */
+  @Test
+  public void independentNestedDeclarationBoundViolationsReportedOnceOnDistinctLines() {
+    JavaFileObject callerSource =
+        FileObjects.forSourceLines(
+            "Caller.java",
+            """
+            package com.uber;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Caller {
+              static class Box<E extends @Nullable Object> {}
+              static class Bad extends Box<@Nullable String> {}
+              static class Good extends Box<String> {}
+              static <T extends Box<String>> T boundedId(T value) { return value; }
+              static <A extends @Nullable Object, B extends @Nullable Object> void pair(
+                  A first, B second) {}
+              static void test(Bad firstBad, Bad secondBad, Good good) {
+                pair(
+                    boundedId(firstBad),
+                    boundedId(secondBad));
+                pair(boundedId(good), boundedId(good));
+              }
+            }
+            """);
+
+    assertThat(compileAndReportDiagnostics(List.of(callerSource)))
+        .containsExactly(
+            "Caller.java:14: [NullAway] incompatible types: Bad cannot be converted to Box<String>"
+                + " (Bad is a subtype of Box<@Nullable String>)",
+            "Caller.java:15: [NullAway] incompatible types: Bad cannot be converted to Box<String>"
+                + " (Bad is a subtype of Box<@Nullable String>)");
+  }
+
+  @Test
+  public void nestedDeclarationBoundViolationInsideLazyInferenceLocallySuppressed() {
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
+              interface Factory<R extends @Nullable Object> {
+                R get();
+              }
+              static <R extends @Nullable Object> R supply(Factory<R> factory) {
+                return factory.get();
+              }
+              static <T extends Box<String>> T boundedId(T value) { return value; }
+              @NullMarked
+              static void test(Bad bad) {
+                supply(() -> {
+                  @SuppressWarnings("NullAway") var result = boundedId(bad);
+                  return result;
+                });
+              }
+            }
+            """);
+
+    assertThat(compileAndReportDiagnostics(List.of(callerSource))).isEmpty();
+  }
+
   private CompilationTestHelper makeHelper() {
     return makeTestHelperWithArgs(
         JSpecifyJavacConfig.withJSpecifyModeArgs(
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodLambdaOrMethodRefArgTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodLambdaOrMethodRefArgTests.java
index 4f743845b05e561d3055dbdf8fd540d1237c277e..fdd104d23cff5a860a1e1bb1a212f2c585fb0167 100644
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
index 8e63ee642d7f4b81fdf3bee65c54dd9da1cae8d3..20b708048eafd8b79828b019852913664e2007e5 100644
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
@@ -2412,6 +2414,660 @@ public class GenericMethodTests extends NullAwayTestsBase {
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
+  @Test
+  public void arrayDeclarationBoundPreservesNestedNullnessThroughSubtyping() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static class Pair<A extends @Nullable Object, B extends @Nullable Object>
+                  extends Box<B> {}
+              static class Bad extends Box<@Nullable String> {}
+              static class Good extends Box<String> {}
+              static class Parent<X extends @Nullable Object> {
+                <T extends X> T id(T value) { return value; }
+              }
+              static void test(
+                  Parent<Box<String>[]> parent,
+                  Pair<Integer, @Nullable String>[] nullablePairs,
+                  Pair<Integer, String>[] nonNullPairs,
+                  Bad[] bad,
+                  Good[] good) {
+                // BUG: Diagnostic contains: incompatible types
+                parent.id(nullablePairs);
+                parent.id(nonNullPairs);
+                // BUG: Diagnostic contains: incompatible types
+                parent.id(bad);
+                parent.id(good);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void recursiveDeclarationBoundPreservesNestedNullness() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Node<A extends @Nullable Object, B extends @Nullable Object> {}
+              static class Box<E extends @Nullable Object> {}
+              static class Bad extends Node<Bad, Box<@Nullable String>> {}
+              static class Good extends Node<Good, Box<String>> {}
+              static <T extends Node<T, Box<String>>> void require(T value) {}
+              static void test(Bad bad, Good good) {
+                // BUG: Diagnostic contains: incompatible types
+                require(bad);
+                require(good);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void structuredMethodReferenceChecksNestedNullnessAndNullableReturn() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              interface Fn<I extends @Nullable Object, O extends @Nullable Object> {
+                O apply(I value);
+              }
+              static <T extends @Nullable Object> T id(T value) { return value; }
+              static <T extends @Nullable Object> @Nullable T nullableId(T value) {
+                return null;
+              }
+              static <I extends @Nullable Object> void use(I value, Fn<I, Box<String>> fn) {}
+              static <I extends @Nullable Object> void useNullableReturn(
+                  I value, Fn<I, @Nullable Box<String>> fn) {}
+              static void test(Box<@Nullable String> nullableBox, Box<String> nonNullBox) {
+                // BUG: Diagnostic contains: mismatched type parameter nullability
+                use(nullableBox, Test::id);
+                use(nonNullBox, Test::id);
+                useNullableReturn(nonNullBox, Test::id);
+                useNullableReturn(nonNullBox, Test::nullableId);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void wildcardDeclarationBoundPreservesNestedNonNullRequirement() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static class Bad extends Box<Box<@Nullable String>> {}
+              static class Good extends Box<Box<String>> {}
+              static <T extends Box<? extends Box<String>>> void require(T value) {}
+              static void test(Bad bad, Good good) {
+                // BUG: Diagnostic contains: incompatible types
+                require(bad);
+                require(good);
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void upperOnlyFactoryDeclarationBoundConflictsWithNullableNestedTarget() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<E extends @Nullable Object> {}
+              static <T extends Box<String>> T make() { throw new AssertionError(); }
+              // BUG: Diagnostic contains: incompatible types
+              Box<@Nullable String> field = make();
+              Box<String> nonNullField = make();
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void annotatedOccurrencePreservesNestedDeclarationBound() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NonNull;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Box<T extends @Nullable Object> {}
+              static class Bad extends Box<@Nullable String> {}
+              static class Good extends Box<String> {}
+              static <U extends @Nullable Object> U id(U value) { return value; }
+              static <T extends Box<String>> void requireNullable(@Nullable T value) {}
+              static <T extends Box<String>> void requireNonNull(@NonNull T value) {}
+              static void test(Bad bad, Good good) {
+                // BUG: Diagnostic contains: incompatible types
+                requireNullable(bad);
+                // BUG: Diagnostic contains: incompatible types
+                requireNonNull(bad);
+                requireNullable(good);
+                requireNonNull(good);
+                requireNullable(null);
+                // BUG: Diagnostic contains: incompatible types
+                requireNullable(id(bad));
+                // BUG: Diagnostic contains: incompatible types
+                requireNonNull(id(bad));
+                requireNullable(id(good));
+                requireNonNull(id(good));
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void referenceSiblingViolationsSurviveEnclosingFailure() {
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
+              interface Fn<I extends @Nullable Object, O extends @Nullable Object> {
+                O apply(I value);
+              }
+              static <T extends Box<String>> T boundedId(T value) { return value; }
+              static <A extends @Nullable Object> void pair(A value, Fn<Bad, Bad> fn) {}
+              static <A extends @Nullable Object> void reversedPair(Fn<Bad, Bad> fn, A value) {}
+              static void refs(Fn<Bad, Bad> first, Fn<Bad, Bad> second) {}
+              static <A extends @Nullable Object> void goodPair(A value, Fn<Good, Good> fn) {}
+              static <A extends @Nullable Object> void goodReversedPair(Fn<Good, Good> fn, A value) {}
+              static void goodRefs(Fn<Good, Good> first, Fn<Good, Good> second) {}
+              static void test(Bad bad, Good good) {
+                pair(
+                    // BUG: Diagnostic contains: incompatible types
+                    boundedId(bad),
+                    // BUG: Diagnostic contains: incompatible types
+                    Test::boundedId);
+                reversedPair(
+                    // BUG: Diagnostic contains: incompatible types
+                    Test::boundedId,
+                    // BUG: Diagnostic contains: incompatible types
+                    boundedId(bad));
+                refs(
+                    // BUG: Diagnostic contains: incompatible types
+                    Test::boundedId,
+                    // BUG: Diagnostic contains: incompatible types
+                    Test::boundedId);
+                goodPair(boundedId(good), Test::boundedId);
+                goodReversedPair(Test::boundedId, boundedId(good));
+                goodRefs(Test::boundedId, Test::boundedId);
+              }
+            }
+            """)
+        .doTest();
+  }
+
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
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/WildcardTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/WildcardTests.java
index 16c06a820618ce33a527890e16c21c4ca16a0966..37050178e6f1de2602a97ccd43731ecfff494164 100644
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
