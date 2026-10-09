# Propagate inferred array nullness through flow

Apply after01. This contains enhanced array-component consumption, scoped dataflow recovery, precision/invalidation logic, and fourteen regression methods. It does not revise the solver API introduced by01.

```diff
diff --git a/nullaway/src/main/java/com/uber/nullaway/NullAway.java b/nullaway/src/main/java/com/uber/nullaway/NullAway.java
index 1ffef614f0fe20021fea82361ea25ce2882920db..23ab3faf1c25f1dfa094a9ef2e2e8f72d43ff290 100644
--- a/nullaway/src/main/java/com/uber/nullaway/NullAway.java
+++ b/nullaway/src/main/java/com/uber/nullaway/NullAway.java
@@ -2954,10 +2954,11 @@ public class NullAway extends BugChecker
           // In JSpecify mode, we check if the array element type is nullable
           ArrayAccessTree arrayAccess = (ArrayAccessTree) expr;
           ExpressionTree arrayExpr = arrayAccess.getExpression();
-          Symbol arraySymbol = ASTHelpers.getSymbol(arrayExpr);
-          if (arraySymbol != null) {
-            exprMayBeNull = NullabilityUtil.isArrayElementNullable(arraySymbol, config);
-          }
+          TreePath arrayExprPath =
+              pathWithLeaf(pathWithLeaf(state.getPath(), arrayAccess), arrayExpr);
+          exprMayBeNull =
+              genericsChecks.isArrayElementNullable(
+                  arrayExpr, state.withPath(arrayExprPath), /* calledFromDataflow= */ false);
         }
       }
       case MEMBER_SELECT -> {
diff --git a/nullaway/src/main/java/com/uber/nullaway/dataflow/AccessPathNullnessPropagation.java b/nullaway/src/main/java/com/uber/nullaway/dataflow/AccessPathNullnessPropagation.java
index 629fdf23785c60e0300685475fb21b69aee983ab..f75d7bff060b243fbd60d408ed6327e4cbdb6f31 100644
--- a/nullaway/src/main/java/com/uber/nullaway/dataflow/AccessPathNullnessPropagation.java
+++ b/nullaway/src/main/java/com/uber/nullaway/dataflow/AccessPathNullnessPropagation.java
@@ -31,13 +31,17 @@ import com.google.errorprone.VisitorState;
 import com.google.errorprone.suppliers.Supplier;
 import com.google.errorprone.suppliers.Suppliers;
 import com.google.errorprone.util.ASTHelpers;
+import com.sun.source.tree.ArrayAccessTree;
 import com.sun.source.tree.BindingPatternTree;
 import com.sun.source.tree.CaseTree;
 import com.sun.source.tree.CompilationUnitTree;
 import com.sun.source.tree.EnhancedForLoopTree;
 import com.sun.source.tree.ExpressionTree;
+import com.sun.source.tree.MemberSelectTree;
 import com.sun.source.tree.MethodInvocationTree;
+import com.sun.source.tree.ParenthesizedTree;
 import com.sun.source.tree.Tree;
+import com.sun.source.tree.TypeCastTree;
 import com.sun.source.tree.VariableTree;
 import com.sun.source.util.TreePath;
 import com.sun.tools.javac.code.Symbol;
@@ -58,6 +62,7 @@ import java.util.List;
 import java.util.Map;
 import java.util.function.Predicate;
 import javax.annotation.CheckReturnValue;
+import javax.lang.model.element.Element;
 import javax.lang.model.element.ElementKind;
 import javax.lang.model.element.VariableElement;
 import javax.lang.model.type.TypeKind;
@@ -167,6 +172,8 @@ public class AccessPathNullnessPropagation
 
   private VisitorState state;
 
+  private CompilationUnitTree compilationUnit;
+
   private final AccessPath.AccessPathContext apContext;
 
   private final Config config;
@@ -188,9 +195,8 @@ public class AccessPathNullnessPropagation
    * @param stateForNewCompilationUnit the new visitor state
    */
   public void updateForNewCompilationUnit(VisitorState stateForNewCompilationUnit) {
-    this.state =
-        stateForNewCompilationUnit.withPath(
-            new FailingTreePath(stateForNewCompilationUnit.getPath().getCompilationUnit()));
+    this.compilationUnit = stateForNewCompilationUnit.getPath().getCompilationUnit();
+    this.state = stateForNewCompilationUnit.withPath(new FailingTreePath(compilationUnit));
   }
 
   /**
@@ -241,7 +247,8 @@ public class AccessPathNullnessPropagation
     this.defaultAssumption = defaultAssumption;
     this.methodReturnsNonNull = analysis::isMethodUnannotated;
     // Overwrite the TreePath with a FailingTreePath to ensure it never gets used
-    this.state = state.withPath(new FailingTreePath(state.getPath().getCompilationUnit()));
+    this.compilationUnit = state.getPath().getCompilationUnit();
+    this.state = state.withPath(new FailingTreePath(compilationUnit));
     this.apContext = apContext;
     this.config = analysis.getConfig();
     this.handler = analysis.getHandler();
@@ -601,6 +608,7 @@ public class AccessPathNullnessPropagation
     Node rhs = node.getExpression();
     Nullness value = values(input).valueOfSubNode(rhs);
     Node target = node.getTarget();
+    invalidateArrayAccessPaths(input.getRegularStore(), updates, target);
 
     if (target instanceof LocalVariableNode localVariableNode
         && !castToNonNull(ASTHelpers.getType(target.getTree())).isPrimitive()) {
@@ -610,6 +618,17 @@ public class AccessPathNullnessPropagation
 
     if (target instanceof ArrayAccessNode arrayAccessNode) {
       setNonnullIfAnalyzeable(updates, arrayAccessNode.getArray());
+      if (config.isJSpecifyMode()
+          && !arrayAccessNode.getType().getKind().isPrimitive()
+          && isStableArrayAssignmentExpression(arrayAccessNode.getTree())
+          && isStableArrayAssignmentExpression(rhs.getTree())) {
+        // The CFG evaluates the location before the RHS. Only refine paths whose array and index
+        // cannot have changed during operand evaluation.
+        AccessPath elementPath = AccessPath.getAccessPathForNode(arrayAccessNode, state, apContext);
+        if (elementPath != null && hasFaithfullyRepresentedArrayIndices(elementPath)) {
+          updates.set(elementPath, value);
+        }
+      }
     }
 
     if (target instanceof FieldAccessNode fieldAccessNode) {
@@ -631,6 +650,44 @@ public class AccessPathNullnessPropagation
     return updateRegularStore(value, input, updates);
   }
 
+  /**
+   * Conservatively recognizes expressions without explicit calls or assignment side effects.
+   *
+   * <p>Only refine array assignments with stable operands: an RHS call or nested assignment can
+   * change the array/index after the CFG has evaluated the LHS location. Method-based paths can
+   * also refer to a different array on their next evaluation. Unhandled expressions conservatively
+   * receive no assignment refinement. This syntactic check is not complete alias or call-effect
+   * analysis; calls retain the existing NullAway refinement policy.
+   */
+  private static boolean isStableArrayAssignmentExpression(@Nullable Tree tree) {
+    if (tree == null) {
+      return false;
+    }
+    return switch (tree.getKind()) {
+      case IDENTIFIER,
+          NULL_LITERAL,
+          STRING_LITERAL,
+          BOOLEAN_LITERAL,
+          CHAR_LITERAL,
+          INT_LITERAL,
+          LONG_LITERAL,
+          FLOAT_LITERAL,
+          DOUBLE_LITERAL ->
+          true;
+      case MEMBER_SELECT ->
+          isStableArrayAssignmentExpression(((MemberSelectTree) tree).getExpression());
+      case PARENTHESIZED ->
+          isStableArrayAssignmentExpression(((ParenthesizedTree) tree).getExpression());
+      case TYPE_CAST -> isStableArrayAssignmentExpression(((TypeCastTree) tree).getExpression());
+      case ARRAY_ACCESS -> {
+        ArrayAccessTree access = (ArrayAccessTree) tree;
+        yield isStableArrayAssignmentExpression(access.getExpression())
+            && isStableArrayAssignmentExpression(access.getIndex());
+      }
+      default -> false;
+    };
+  }
+
   /**
    * Propagates access paths to track iteration over a map's key set using an enhanced-for loop,
    * i.e., code of the form {@code for (Object k: m.keySet()) ...}. For such code, we track access
@@ -891,28 +948,12 @@ public class AccessPathNullnessPropagation
     Nullness resultNullness;
     // Unsoundly assume @NonNull, except in JSpecify mode where we check the type
     if (config.isJSpecifyMode()) {
-      Symbol arraySymbol;
-      boolean isElementNullable = false;
-      // For enhanced-for-loops we get the symbol from the array expression as the node is desugared
-      ExpressionTree arrayExpr = node.getArrayExpression();
-      if (arrayExpr != null) {
-        arraySymbol = ASTHelpers.getSymbol(arrayExpr);
-      } else {
-        arraySymbol = ASTHelpers.getSymbol(node.getArray().getTree());
-      }
-      if (arraySymbol != null) {
-        isElementNullable = NullabilityUtil.isArrayElementNullable(arraySymbol, config);
-      }
+      boolean isElementNullable = arrayElementIsNullable(node);
       if (isElementNullable) {
         AccessPath arrayAccessPath = AccessPath.getAccessPathForNode(node, state, apContext);
         if (arrayAccessPath != null) {
-          Nullness accessPathNullness =
-              input.getRegularStore().getNullnessOfAccessPath(arrayAccessPath);
-          if (accessPathNullness == Nullness.NULLABLE) {
-            resultNullness = Nullness.NULLABLE;
-          } else {
-            resultNullness = Nullness.NONNULL;
-          }
+          // Assignments can store NULL, not just NULLABLE or NONNULL. Preserve the lattice value.
+          resultNullness = input.getRegularStore().getNullnessOfAccessPath(arrayAccessPath);
         } else {
           resultNullness = Nullness.NULLABLE;
         }
@@ -926,6 +967,26 @@ public class AccessPathNullnessPropagation
     return updateRegularStore(resultNullness, input, updates);
   }
 
+  /**
+   * Checks an array read's component type without using the transfer's deliberately invalid path.
+   *
+   * <p>For a desugared enhanced-for read, resolves the original loop expression rather than the
+   * synthetic array temporary. If no source path exists, retains the symbol-based behavior.
+   */
+  private boolean arrayElementIsNullable(ArrayAccessNode node) {
+    ExpressionTree arrayExpression = node.getArrayExpression();
+    Tree arrayTree = arrayExpression != null ? arrayExpression : node.getArray().getTree();
+    if (arrayTree instanceof ExpressionTree expression) {
+      TreePath expressionPath = TreePath.getPath(compilationUnit, expression);
+      if (expressionPath != null) {
+        return genericsChecks.isArrayElementNullable(
+            expression, state.withPath(expressionPath), /* calledFromDataflow= */ true);
+      }
+    }
+    Symbol arraySymbol = ASTHelpers.getSymbol(arrayTree);
+    return arraySymbol != null && NullabilityUtil.isArrayElementNullable(arraySymbol, config);
+  }
+
   @Override
   public TransferResult<Nullness, NullnessStore> visitImplicitThis(
       ImplicitThisNode implicitThisNode, TransferInput<Nullness, NullnessStore> input) {
@@ -1417,6 +1478,94 @@ public class AccessPathNullnessPropagation
     return noStoreChanges(NULLABLE, input);
   }
 
+  /**
+   * Invalidates array-dependent facts affected by an assignment in JSpecify mode.
+   *
+   * <p>Array writes may overlap elements reached through aliases, but distinct known integer
+   * indices do not overlap. Rebinding a local or field only invalidates paths referencing that
+   * symbol, including array indices and map keys. Field dependencies are conservatively matched by
+   * symbol because index paths do not encode field receivers. This is not complete alias or
+   * call-effect analysis. Callers add any known post-assignment element value after invalidation.
+   */
+  private void invalidateArrayAccessPaths(
+      NullnessStore store, ReadableUpdates updates, Node target) {
+    if (!config.isJSpecifyMode()) {
+      return;
+    }
+    Predicate<AccessPath> affected;
+    if (target instanceof ArrayAccessNode arrayAccess) {
+      Integer writtenIndex =
+          arrayAccess.getIndex() instanceof IntegerLiteralNode literal ? literal.getValue() : null;
+      affected = path -> mayOverlapArrayWrite(path, writtenIndex);
+    } else {
+      Element changedSymbol;
+      if (target instanceof LocalVariableNode local) {
+        changedSymbol = local.getElement();
+      } else if (target instanceof FieldAccessNode field) {
+        changedSymbol = field.getElement();
+      } else {
+        return;
+      }
+      affected = path -> containsArrayAccess(path) && referencesSymbol(path, changedSymbol);
+    }
+    for (Nullness nullness : Nullness.values()) {
+      for (AccessPath path : store.getAccessPathsWithValue(nullness)) {
+        if (affected.test(path)) {
+          updates.set(path, NULLABLE);
+        }
+      }
+    }
+  }
+
+  /** Checks index fidelity; field indices omit their receiver and cannot justify refinement. */
+  private static boolean hasFaithfullyRepresentedArrayIndices(AccessPath path) {
+    for (AccessPathElement element : path.getElements()) {
+      if (element instanceof ArrayIndexElement arrayIndex
+          && !(arrayIndex.getIndex() instanceof Integer)
+          && !(arrayIndex.getIndex() instanceof Element index && !index.getKind().isField())) {
+        return false;
+      }
+    }
+    return true;
+  }
+
+  /**
+   * Checks for possibly overlapping array elements without assuming distinct roots cannot alias.
+   */
+  private static boolean mayOverlapArrayWrite(AccessPath path, @Nullable Integer writtenIndex) {
+    for (AccessPathElement element : path.getElements()) {
+      if (element instanceof ArrayIndexElement arrayIndex
+          && (writtenIndex == null
+              || !(arrayIndex.getIndex() instanceof Integer storedIndex)
+              || writtenIndex.equals(storedIndex))) {
+        return true;
+      }
+    }
+    return path.getMapGetArg() instanceof AccessPath keyPath
+        && mayOverlapArrayWrite(keyPath, writtenIndex);
+  }
+
+  /** Checks roots, path elements, indices, and map-key paths for a rebound symbol dependency. */
+  private static boolean referencesSymbol(AccessPath path, Element symbol) {
+    if (symbol.equals(path.getRoot())) {
+      return true;
+    }
+    for (AccessPathElement element : path.getElements()) {
+      if (symbol.equals(element.getJavaElement())
+          || (element instanceof ArrayIndexElement arrayIndex
+              && symbol.equals(arrayIndex.getIndex()))) {
+        return true;
+      }
+    }
+    return path.getMapGetArg() instanceof AccessPath keyPath && referencesSymbol(keyPath, symbol);
+  }
+
+  /** Returns whether a path depends on an array element, including accesses below that element. */
+  private static boolean containsArrayAccess(AccessPath path) {
+    return path.getElements().stream().anyMatch(element -> element instanceof ArrayIndexElement)
+        || (path.getMapGetArg() instanceof AccessPath keyPath && containsArrayAccess(keyPath));
+  }
+
   @CheckReturnValue
   private ResultingStore updateStore(NullnessStore oldStore, ReadableUpdates... updates) {
     if (trackUnreachableStores && oldStore.isUnreachable()) {
diff --git a/nullaway/src/main/java/com/uber/nullaway/dataflow/ArrayIndexElement.java b/nullaway/src/main/java/com/uber/nullaway/dataflow/ArrayIndexElement.java
index 939d02e23be83a144677d5ba85ba1a259f04ef17..9d721cd4dbd5cf48fb59a9b5e8478033864c27f2 100644
--- a/nullaway/src/main/java/com/uber/nullaway/dataflow/ArrayIndexElement.java
+++ b/nullaway/src/main/java/com/uber/nullaway/dataflow/ArrayIndexElement.java
@@ -43,6 +43,11 @@ public class ArrayIndexElement implements AccessPathElement {
     return new ArrayIndexElement(javaElement, indexElement);
   }
 
+  /** Returns the represented index: an {@link Integer} constant or a variable {@link Element}. */
+  public Object getIndex() {
+    return index;
+  }
+
   @Override
   public Element getJavaElement() {
     return this.javaElement;
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
index a4c0f9f5aef40fef7b3be28f7938dc81d59d89ac..ed3ddb010de9cd91ef61aca04a9c034845d8d070 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java
@@ -199,6 +199,9 @@ public final class GenericsChecks {
   /** Ground lambda targets scoped to constraint generation, including parameterless lambdas. */
   private Map<LambdaExpressionTree, Type> lambdaTargetTypesForInference = Map.of();
 
+  /** Scoped array-type recovery from a transfer must not recursively start another analysis. */
+  private int arrayTypeRecoveryFromDataflowDepth = 0;
+
   /**
    * While generating constraints for an inference problem, maps each participating call whose
    * lambda and method reference arguments get their types published after successful inference (see
@@ -789,6 +792,35 @@ public final class GenericsChecks {
             errorMessage, analysis.buildDescription(formalParameterTree), state, null));
   }
 
+  /**
+   * Checks the immediate component nullability of an array expression using its enhanced type.
+   *
+   * <p>The symbol check preserves support for declaration annotations and older annotation
+   * encodings. Dataflow callers must pass {@code true} so incomplete inference results are not
+   * cached; type recovery uses the existing running-analysis queries rather than restarting it.
+   *
+   * @param expression the array expression
+   * @param state visitor state whose path points to {@code expression}
+   * @param calledFromDataflow whether this check is part of dataflow analysis
+   * @return whether the array's immediate elements may be null
+   */
+  public boolean isArrayElementNullable(
+      ExpressionTree expression, VisitorState state, boolean calledFromDataflow) {
+    if (calledFromDataflow) {
+      arrayTypeRecoveryFromDataflowDepth++;
+    }
+    try {
+      Type arrayType = getTreeType(expression, state, calledFromDataflow);
+      Symbol arraySymbol = ASTHelpers.getSymbol(expression);
+      return NullabilityUtil.isArrayElementNullable(arrayType, config)
+          || (arraySymbol != null && NullabilityUtil.isArrayElementNullable(arraySymbol, config));
+    } finally {
+      if (calledFromDataflow) {
+        arrayTypeRecoveryFromDataflowDepth--;
+      }
+    }
+  }
+
   /**
    * This method returns the type of the given tree, including any type use annotations.
    *
@@ -871,7 +903,19 @@ public final class GenericsChecks {
       return typeWithPreservedAnnotations(tree);
     } else {
       Type result;
-      if (tree instanceof VariableTree) {
+      if (tree instanceof ArrayAccessTree arrayAccess) {
+        ExpressionTree arrayExpression = arrayAccess.getExpression();
+        TreePath arrayExpressionPath =
+            pathWithLeaf(pathWithLeaf(state.getPath(), arrayAccess), arrayExpression);
+        Type arrayType =
+            getTreeType(arrayExpression, state.withPath(arrayExpressionPath), calledFromDataflow);
+        // Project one dimension at a time, including when this access is itself an array
+        // expression or an argument to another generic call.
+        result =
+            arrayType instanceof Type.ArrayType arrayTypeWithComponent
+                ? arrayTypeWithComponent.getComponentType()
+                : ASTHelpers.getType(tree);
+      } else if (tree instanceof VariableTree) {
         // type on the tree itself can be missing nested annotations for arrays; get the type from
         // the symbol for the variable instead
         result = castToNonNull(ASTHelpers.getSymbol(tree)).type;
@@ -2087,7 +2131,9 @@ public final class GenericsChecks {
         // cases it does not handle
         return;
       }
-      argumentType = refineArgumentTypeWithDataflow(argumentType, rhsExpr, state, state.getPath());
+      argumentType =
+          refineArgumentTypeWithDataflow(
+              argumentType, rhsExpr, state, state.getPath(), calledFromDataflow);
       solver.addSubtypeConstraint(argumentType, lhsType, false);
     }
   }
@@ -2510,10 +2556,15 @@ public final class GenericsChecks {
    * @param expr the expression tree
    * @param state the visitor state
    * @param path relevant tree path if available and possibly distinct from {@code state.getPath()}
+   * @param calledFromDataflow whether refinement must avoid starting a new analysis from a transfer
    * @return the refined type of the expression
    */
   private Type refineArgumentTypeWithDataflow(
-      Type exprType, ExpressionTree expr, VisitorState state, @Nullable TreePath path) {
+      Type exprType,
+      ExpressionTree expr,
+      VisitorState state,
+      @Nullable TreePath path,
+      boolean calledFromDataflow) {
     if (!shouldRunDataflowForExpression(exprType, expr)) {
       return exprType;
     }
@@ -2546,6 +2597,9 @@ public final class GenericsChecks {
       // dataflow analysis is already running, so just get the current dataflow value for the
       // argument
       refinedNullness = nullnessAnalysis.getNullnessFromRunning(exprPath, state.context);
+    } else if (calledFromDataflow && arrayTypeRecoveryFromDataflowDepth > 0) {
+      // Array type recovery inside a transfer must not start another dataflow instance.
+      return exprType;
     } else {
       refinedNullness = nullnessAnalysis.getNullness(exprPath, state.context);
     }
diff --git a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
index 20b708048eafd8b79828b019852913664e2007e5..864fdf081f55924c973f47b90852f0381c581e26 100644
--- a/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
+++ b/nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java
@@ -2702,6 +2702,424 @@ public class GenericMethodTests extends NullAwayTestsBase {
         .doTest();
   }
 
+  @Test
+  public void inferredArrayElementDereferenceInEitherOrder() {
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
+              static void direct(String[] nonNullElements, @Nullable String[] nullableElements) {
+                // BUG: Diagnostic contains: dereferenced expression 'pick(nonNullElements, nullableElements)[0]' is @Nullable
+                pick(nonNullElements, nullableElements)[0].length();
+                // BUG: Diagnostic contains: dereferenced expression 'pick(nullableElements, nonNullElements)[0]' is @Nullable
+                pick(nullableElements, nonNullElements)[0].length();
+                pick(nonNullElements, nonNullElements)[0].length();
+              }
+              static void inferredLocal(String[] nonNullElements, @Nullable String[] nullableElements) {
+                var forward = pick(nonNullElements, nullableElements);
+                var reverse = pick(nullableElements, nonNullElements);
+                var nonNull = pick(nonNullElements, nonNullElements);
+                // BUG: Diagnostic contains: dereferenced expression 'forward[0]' is @Nullable
+                forward[0].length();
+                // BUG: Diagnostic contains: dereferenced expression 'reverse[0]' is @Nullable
+                reverse[0].length();
+                nonNull[0].length();
+              }
+              static void explicit(@Nullable String[] elements) {
+                // BUG: Diagnostic contains: dereferenced expression 'elements[0]' is @Nullable
+                elements[0].length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void inferredArrayElementFlowRefinement() {
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
+              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
+                var forward = pick(nonNullElements, nullableElements);
+                var reverse = pick(nullableElements, nonNullElements);
+                if (forward[0] != null) {
+                  forward[0].length();
+                }
+                if (reverse[0] == null) {
+                  return;
+                }
+                reverse[0].length();
+                // BUG: Diagnostic contains: dereferenced expression 'forward[1]' is @Nullable
+                forward[1].length();
+                forward[2] = "safe";
+                forward[2].length();
+                forward[2] = null;
+                // BUG: Diagnostic contains: dereferenced expression 'forward[2]' is @Nullable
+                forward[2].length();
+              }
+              static void explicit(@Nullable String[] elements) {
+                if (elements[0] != null) {
+                  elements[0].length();
+                }
+              }
+              static void indexed(
+                  String[] nonNullElements, @Nullable String[] nullableElements, int index) {
+                var elements = pick(nullableElements, nonNullElements);
+                if (elements[index] != null) {
+                  elements[index].length();
+                  // BUG: Diagnostic contains: dereferenced expression 'elements[index + 1]' is @Nullable
+                  elements[index + 1].length();
+                }
+              }
+              static void extracted(String[] nonNullElements, @Nullable String[] nullableElements) {
+                var elements = pick(nonNullElements, nullableElements);
+                var element = elements[0];
+                // BUG: Diagnostic contains: dereferenced expression 'element' is @Nullable
+                element.length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void nullableArrayElementFieldIndicesHaveDistinctReceivers() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static class Index {
+                int i;
+              }
+              Index left = new Index();
+              Index right = new Index();
+              void test(@Nullable String[] a) {
+                a[left.i] = "safe";
+                // BUG: Diagnostic contains: dereferenced expression 'a[right.i]' is @Nullable
+                a[right.i].length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void nullableArrayElementRequireNonNullRefinesAccess() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.Objects;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static void test(@Nullable String[] a) {
+                Objects.requireNonNull(a[0]);
+                a[0].length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void nullableArrayElementNonNullGuardRefinesAccess() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import java.util.Objects;
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static void test(@Nullable String[] a) {
+                if (Objects.nonNull(a[0])) {
+                  a[0].length();
+                }
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void nullableArrayElementGuardSurvivesUnrelatedStatements() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static void test(@Nullable String[] a) {
+                if (a[0] != null) {
+                  int unrelated = 1;
+                  new Object();
+                  a[0].length();
+                }
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void nullableArrayElementGuardSurvivesDifferentElementWrite() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static void test(@Nullable String[] a) {
+                if (a[0] != null) {
+                  a[1] = "safe";
+                  a[0].length();
+                }
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void nullableArrayElementAliasWriteInvalidatesRefinement() {
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
+              static void explicit(@Nullable String[] a) {
+                if (a[0] != null) {
+                  var b = a;
+                  b[0] = null;
+                  // BUG: Diagnostic contains: dereferenced expression 'a[0]' is @Nullable
+                  a[0].length();
+                }
+              }
+              static void inferred(String[] nonNullElements, @Nullable String[] nullableElements) {
+                var a = pick(nonNullElements, nullableElements);
+                if (a[0] != null) {
+                  var b = a;
+                  b[0] = null;
+                  // BUG: Diagnostic contains: dereferenced expression 'a[0]' is @Nullable
+                  a[0].length();
+                }
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void nullableArrayElementIndexRebindingInvalidatesRefinement() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static void test(@Nullable String[] a) {
+                int index = 0;
+                a[index] = "safe";
+                a[index].length();
+                index = 1;
+                // BUG: Diagnostic contains: dereferenced expression 'a[index]' is @Nullable
+                a[index].length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void nullableArrayRebindingInvalidatesElementRefinement() {
+    makeHelper()
+        .addSourceLines(
+            "Test.java",
+            """
+            import org.jspecify.annotations.NullMarked;
+            import org.jspecify.annotations.Nullable;
+            @NullMarked
+            class Test {
+              static void test(@Nullable String[] a, @Nullable String[] nullableElements) {
+                a[0] = "safe";
+                a[0].length();
+                a = nullableElements;
+                // BUG: Diagnostic contains: dereferenced expression 'a[0]' is @Nullable
+                a[0].length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void inferredArrayAccessAsGenericArgument() {
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
+              static <T extends @Nullable Object> T id(T value) {
+                return value;
+              }
+              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
+                // BUG: Diagnostic contains: dereferenced expression 'id(pick(nonNullElements, nullableElements)[0])' is @Nullable
+                id(pick(nonNullElements, nullableElements)[0]).length();
+                // BUG: Diagnostic contains: dereferenced expression 'id(pick(nullableElements, nonNullElements)[0])' is @Nullable
+                id(pick(nullableElements, nonNullElements)[0]).length();
+                id(pick(nonNullElements, nonNullElements)[0]).length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void inferredMultidimensionalArrayElementNullness() {
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
+              static void acceptsNonNullElements(String[] elements) {}
+              static void acceptsNullableElements(@Nullable String[] elements) {}
+              static void leaves(String[][] nonNull, @Nullable String[][] nullableLeaves) {
+                int length = pick(nonNull, nullableLeaves)[0].length;
+                // BUG: Diagnostic contains: dereferenced expression 'pick(nonNull, nullableLeaves)[1][0]' is @Nullable
+                pick(nonNull, nullableLeaves)[1][0].length();
+                // BUG: Diagnostic contains: dereferenced expression 'pick(nullableLeaves, nonNull)[0][0]' is @Nullable
+                pick(nullableLeaves, nonNull)[0][0].length();
+                var elements = pick(nonNull, nullableLeaves);
+                var row = elements[0];
+                // BUG: Diagnostic contains: dereferenced expression 'row[0]' is @Nullable
+                row[0].length();
+                // BUG: Diagnostic contains: incompatible types
+                acceptsNonNullElements(pick(nonNull, nullableLeaves)[0]);
+                acceptsNullableElements(pick(nonNull, nullableLeaves)[0]);
+                pick(nonNull, nonNull)[0][0].length();
+              }
+              static void rows(String[][] nonNull, String[] @Nullable [] nullableRows) {
+                // BUG: Diagnostic contains: dereferenced expression 'pick(nonNull, nullableRows)[0]' is @Nullable
+                int length = pick(nonNull, nullableRows)[0].length;
+                var elements = pick(nullableRows, nonNull);
+                // BUG: Diagnostic contains: dereferenced expression 'elements[0]' is @Nullable
+                int otherLength = elements[0].length;
+                if (elements[1] != null) {
+                  elements[1][0].length();
+                }
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void inferredArrayElementsInEnhancedFor() {
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
+              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
+                for (var element : pick(nonNullElements, nullableElements)) {
+                  // BUG: Diagnostic contains: dereferenced expression 'element' is @Nullable
+                  element.length();
+                }
+                var elements = pick(nullableElements, nonNullElements);
+                for (var element : elements) {
+                  // BUG: Diagnostic contains: dereferenced expression 'element' is @Nullable
+                  element.length();
+                }
+                for (var element : pick(nonNullElements, nonNullElements)) {
+                  element.length();
+                }
+                for (var element : nullableElements) {
+                  if (element != null) {
+                    element.length();
+                  }
+                }
+              }
+            }
+            """)
+        .doTest();
+  }
+
+  @Test
+  public void inferredArrayElementsRemainNonNullInLegacyMode() {
+    makeTestHelperWithArgs(Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber"))
+        .addSourceLines(
+            "Test.java",
+            """
+            package com.uber;
+            import org.jspecify.annotations.Nullable;
+            class Test {
+              static <T extends @Nullable Object> T pick(T first, T second) {
+                return first;
+              }
+              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
+                pick(nonNullElements, nullableElements)[0].length();
+                pick(nullableElements, nonNullElements)[0].length();
+                var elements = pick(nonNullElements, nullableElements);
+                elements[0].length();
+                nullableElements[0].length();
+              }
+            }
+            """)
+        .doTest();
+  }
+
   @Test
   public void receiverInstantiatedMethodVariableBounds() {
     makeHelper()
```
