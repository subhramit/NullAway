# Implemented 005 and 006: certification and repair removal

## Delivery and order

Both files are real applicable implementation patches, not design sketches:

1. `0005-certified-full-type-inference.md`
2. `0006-remove-obsolete-substitution-repair.md`

Apply them after the repaired base and array follow-up:

```text
01 → 02 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006
```

or:

```text
03 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006
```

03 combines only 01+02. Do not apply it after that pair. The quality cleanup is included before 005 to match the exact tested/exported baseline; it is not an additional inference feature.

## What 005 implements

### Attributed shape recovery

New `InferenceTypeShapes` matches receiver-substituted declaration types to javac's attributed executable and constructed types. It traverses parameters, results, thrown types, class arguments, enclosing types, arrays, and supported wildcard/reference adaptations, and rejects inconsistent or unresolved shape evidence.

Ordinary invocations, diamonds, and generic references supply shape maps during registration. Known non-recursive unused bounds can provide representative instantiations when the variable does not occur in observable executable positions. Recursive or unresolved unused variables remain explicitly incomplete instead of being certified by default.

Javac annotations in these shapes are not taken as authoritative annotation evidence; NullAway's bounds and explicit use-site facts supply that evidence.

### Explicit result status

`solve()` returns:

```java
Solution(
    Map<InferenceVariable, Type> inferredTypes,
    Set<InferenceVariable> incompleteVariables,
    Set<InferenceVariable> inconsistentVariables)
```

The collections are immutable insertion-ordered snapshots. Their contained javac `Type` objects are not claimed to be deeply immutable.

- Complete, consistent bindings may be consumed and published as successful inference.
- Incomplete bindings remain explicit fallback evidence.
- Structurally contradictory evidence is not classified or cached as successful inference.
- Existing scalar contradictions retain the established exception/warning behavior.

`InferenceSuccess` and `InferencePartial` are different call-result types. Root diagnostic evidence may be retained as `InferencePartial` under final-context/lifecycle guards, but that is not a completed reusable solution for nested calls or references.

### Final substitution validation

After root propagation and independent declaration checking, the solver resolves structured evidence, projects it onto known Java shapes, and validates substituted lower, upper, declaration, and variable-edge relations. Unknown relations mark incompleteness; proved violations mark inconsistency. Uncertified dependency status is propagated.

Fixed caller variables remain symbolic leaves. Their bounds are used for checking, not silently substituted as their actual types. The exact fixed-variable/wildcard case `Supplier<@Nullable OuterT>` now produces complete nested evidence instead of falling back to the repair visitor.

### Consumer and context migration

Complete ordinary invocation signatures and results consume full substitutions; method references retain full substitution; complete diamond arguments are rebuilt while preserving the attributed enclosing type. General annotation-preserving utilities still handle explicit occurrence annotations and uncertified diagnostic/fallback paths.

Completed call targets and scoped owning-lambda return targets are retained separately from reusable result caching. This preserves context during lazy parameter checking and nested re-entry without caching a provisional-lambda-dependent call solution.

A functional target independent of an unrelated incomplete unused variable may be published when all of its own inferred bindings are certified and no private pending inference symbols remain. The incomplete root call itself is not promoted to success.

### Enclosing-type constraint repair

Review found that shape recovery traversed enclosing types while constraint generation did not. `AddSubtypeConstraintsVisitor.visitClassType` now constrains corresponding enclosing types after supertype alignment. A direct solver test and an end-to-end inner-class dereference test guard against false COMPLETE certification.

## Direct solver tests added

New `ConstraintSolverImplTests` runs assertions against real attributed javac types during compilation, with a callback-execution assertion:

| Permanent test | Contract checked |
|---|---|
| `callsToSameDeclarationHaveSeparateInferenceVariables` | Separate site identities and stable repeated registration |
| `completeSupplierSubstitutionRetainsNullableFixedOuterSymbol` | Exact nested annotation and fixed outer symbol; complete status |
| `enclosingTypeArgumentsConstrainInnerClassSubtyping` | Nullable enclosing argument constrains the inferred variable |
| `covariantArrayMergeIsIndependentOfLowerBoundOrder` | Same component nullness in either bound order |
| `contradictoryInvariantBoundsAreTaggedInconsistent` | Contradiction is not a complete consistent solution |
| `unusedRegisteredVariablesHaveConcreteShapesAndNonNullRootDefaults` | All registrations are represented with supplied shapes and established defaults |
| `registrationAndSolvingDoNotMutateSourceOrDeclaredTypes` | Source type graphs and declaration-bound references remain unchanged by operations |
| `dependentVariablesReceiveLateNullableEvidence` | Late evidence reaches nested dependent bindings |
| `nullableHandlerModelOverridesNonNullReceiverInstantiatedBound` | Genuine symbol/index handler override wins, with no-override control |
| `fourArgumentRegistrationWithoutJavaShapesIsIncomplete` | Missing shape does not masquerade as complete inference |

New `GenericMethodTests` integration tests:

- `inferredEnclosingTypeNullnessSurvivesInnerClassIdentity`
- `unusedTypeVariablesDoNotEraseCertifiedLambdaTargets`

Existing expected diagnostic markers and exact diagnostic lists were not weakened.

## What 006 removes

- Entire `NestedTypeVarSubstitutionRepairVisitor.java` — 351 lines.
- `restoreNestedNullabilityForTypeVarArguments` wrapper.
- Visitor invocation and dedicated `nestedNullabilityRepairInProgress` state.
- Its compilation-unit cache-cleanup entry and stale repair comments.

It renames `nestedGenericMethodRepairPreservesTopLevelNullability` to `nestedGenericMethodSubstitutionPreservesTopLevelNullability`. Update external method-specific test filters if needed; assertions and expected diagnostics are unchanged.

006 changes three files, adding seven lines and deleting 422. It adds no `UnusedMethod` suppression and contains no experiment-only bypass switch.

General annotation-preserving substitution, metadata-copy, supertype/member, wildcard/capture, and fallback diagnostic utilities remain because they are not the obsolete #1473/#1574 visitor.

## Latest #1942 regression validation

02 and combined 03 now include PR #1943's unchanged `modeledNestedReturnAnnotationInGenericInference` regression and a separate non-null/`var`/nested-result control method. No new production changes were needed. The upstream test fails on the original baseline, passes on 01+02, and passes on this complete stack with the visitor deleted. The latest uncached full suite records **1,187 tests, zero failures/errors, 21 skipped**, and the self-check passes. Both revised export alternatives match the tested 14 changed paths byte-for-byte. See `ISSUE-1942-VALIDATION.md` for commands, independent review, and bounded conclusions.

## Original 005/006 validation

005 independently passed:

```text
./gradlew :nullaway:test --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 1m 15s

./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 30s
```

006 on top passed with the visitor physically deleted:

```text
./gradlew :nullaway:test --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 1m 31s

./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 25s
```

Reports: **1,185 tests recorded, zero failures/errors, 21 skipped**. This includes the unchanged #1291/#1585/#1919/#1455/Caffeine gates, direct array regressions, and new direct solver assertions.

Independent static reviews checked certification, lifecycle/consumers, unused-variable target publication, and cleanup. One missing enclosing-type constraint was found, repaired, and tested. Final reviews reported no concrete P0/P1 blocker in the reviewed paths. They did not rerun Gradle and are not a proof of universal soundness.

A temporary serialization-fixture failure was caused by its path extraction encountering lowercase `com` in the disposable worktree name `complete-inference`. The worktree was renamed, then compilation/tests were rerun uncached; no serialization implementation or expectation was changed.

## Remaining bounded limitations

- Raw, captured, unresolved, or feature-disabled structures can still be explicitly incomplete. They are not certified successful substitutions merely because a `Type` value exists.
- The pre-existing mixed-invariant/common-supertype false-positive characterization remains. This pass preserves javac's nominal shape and the plan's established diagnostic gates rather than implementing broader Java LUB inference.
- The handler/model collision now has a direct solver regression, but no new external model-provider service-loading integration was added.
- These runs cover the current local JDK/Error Prone environment; a supported-version matrix and systematic performance benchmarks were not performed.
- General fallback/annotation utilities are not all removed. The obsolete visitor and its specific repair lifecycle are removed.

The earlier `REMAINING-WORK.md` repair-disabled failure describes the pre-005 state. It no longer blocks deletion on this tested 005→006 stack. This report supersedes that removal-readiness conclusion without rewriting the historical evidence.
