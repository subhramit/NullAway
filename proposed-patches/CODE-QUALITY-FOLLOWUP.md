# Code-quality follow-up: Make structural cases mutually exclusive

Apply on top of 01 + 02, or on top of combined 03. This is separate from any behavioral follow-up 004.

The three structural cases are disjoint: variable-to-variable edge, concrete lower bound, or concrete upper bound. One outer check and an else-if make that exclusivity explicit without changing scalar propagation or annotated-use ownership.

```diff
diff --git a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
index 46715e3d..b925eefd 100644
--- a/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
+++ b/nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java
@@ -1590,6 +1590,10 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     return markNestedTypesNonNull(result);
   }
 
+  /**
+   * Adds scalar and structural constraints separately. Explicit occurrence annotations can disable
+   * scalar inference without changing structural ownership or the nested declaration contract.
+   */
   private void directlyConstrainTypePair(Type s, Type t) throws UnsatisfiableConstraintsException {
     Verify.verify(
         s instanceof TypeVariable || t instanceof TypeVariable,
@@ -1606,13 +1610,13 @@ public final class ConstraintSolverImpl implements ConstraintSolver {
     // Root use-site annotations affect scalar constraints, not the variable's nested contract.
     InferenceVariable sStructure = inferenceVariableForStructure(s);
     InferenceVariable tStructure = inferenceVariableForStructure(t);
-    if (sStructure != null && tStructure != null) {
-      addStructuralVariableEdge(sStructure, tStructure);
-    }
-    if (tStructure != null && sStructure == null) {
-      recordStructuredBound(tStructure, s, true);
-    }
-    if (sStructure != null && tStructure == null) {
+    if (tStructure != null) {
+      if (sStructure != null) {
+        addStructuralVariableEdge(sStructure, tStructure);
+      } else {
+        recordStructuredBound(tStructure, s, true);
+      }
+    } else if (sStructure != null) {
       recordStructuredBound(sStructure, t, false);
     }
 
```

PR title: Clarify structural constraint case selection

PR description: Express the mutually exclusive structural edge and bound cases with one branch tree. Keep scalar-nullness eligibility and structural ownership separate. Document the intent of direct pair constraint generation without changing behavior.

Beginner explanation: The checker has three possible relationships to record. Previously it tested three separate conditions even though only one could be true. The new branches show that one choice is made, while leaving all analysis rules unchanged.

Assisted-by: Zed (GPT-6.1 Sol)

Validation: the cleanup was included in the full uncached 01+02+quality+004 test and self-check runs, which passed. Its production write set is limited to `ConstraintSolverImpl.java`; 004 does not edit that file, so the two follow-ups are independently applicable.
