# Issue #1947: fixed nullable bounds survive nested wildcard inference

## Verdict and delivery

**Resolved for the reported case and the added regression controls by patch 007.** This replaces the previous gap document; #1947 is no longer listed as an unresolved acceptance case.

References:
- https://github.com/uber/NullAway/issues/1947
- https://github.com/uber/NullAway/issues/1932#issuecomment-6080536601
- Related open PR: https://github.com/uber/NullAway/pull/1834

Apply `0007-preserve-fixed-bounds-through-wildcard-inference.md` after 006:

```text
01 → 02 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006 → 007
```

Or replace 01+02 with combined 03. Earlier patches are unchanged. 007 contains the complete incremental implementation and tests: five files, 745 additions and seven deletions, mostly regression coverage.

## Root cause and chosen solution

A bare fixed `T extends @Nullable Object` is parametric: it might be instantiated non-null or nullable. It must remain `T`, not globally become `@Nullable T` or its upper bound. Nevertheless, a `Box<T>` cannot safely satisfy a non-null-bounded `Box<? extends U>` requirement for every permitted instantiation of `T`.

The previous scalar graph recorded neither that direct wildcard obligation nor the fixed-bound proof arriving through another call's inference variables. Full-type result preservation alone did not fill that constraint-generation gap. Open PR #1834 supplies related direct-call semantics, but its whole scalar-result/marker implementation is not part of our baseline and was not imported.

007 adds a general deferred obligation for `? extends U` when `U` is an unprojected inference variable whose effective bound excludes null. After structural preparation finishes, the solver follows root-inference subtype edges and dedicated root-compatible fixed lower evidence, looking for explicitly nullable declared/model bounds. A proven conflict throws an owning-site exception before any successful result can be published.

Important boundaries:
- Nullable-accepting variables retain symbolic fixed types and receive no forced nullable root from this proof.
- `@NonNull` source projections stop the bound proof, and annotated destination occurrences do not contribute root evidence.
- Structural evidence remains available for nested constraints but is not confused with root provenance.
- Nullable bounds can be reached through fixed-variable chains; intersections admit null only when every component does. Unmarked bare defaults do not count as explicit nullable evidence.
- Captured actuals are excluded from this specialized proof, consistent with the bounded existing behavior; this does not claim capture completeness.
- `? super` and unbounded-wildcard handling are not converted into `? extends` obligations.
- Deferred failures report and cache at their owning call site. Existing contextual scalar failures retain their established root-site reporting/cache policy.

There is no special case for `List.copyOf`, `ArrayList`, `id`, or the issue's AST shape, and no repair visitor was restored.

## Permanent integration tests

Added to `nullaway/src/test/java/com/uber/nullaway/jspecify/WildcardTests.java`:

| Method | Contract |
|---|---|
| `issue1947NullableBoundThroughNestedGenericCalls` | Reported source, including nullable-String `main`; all four direct/nested diagnostics require the named `U`/`E` upper-bound inference failure |
| `issue1947NonNullBoundAcceptedDirectAndNested` | Non-null-bounded fixed variables remain accepted through direct, identity, and diamond calls |
| `issue1947NullableAcceptingWildcardPreservesParametricType` | Nullable-accepting APIs preserve `Box<T>` / `List<T>` rather than substituting nullable-T or its bound |
| `issue1947ExplicitTypeVariableProjections` | Explicit non-null sources accepted; explicitly nullable sources rejected |
| `issue1947NullableBoundChainThroughWildcard` | Nullable bound reached through another fixed variable, with a non-null-chain control |
| `issue1947IntersectionBoundNullabilityThroughWildcard` | All-nullable intersections rejected; either non-null component excludes null |
| `issue1947UnmarkedBareBoundRemainsOptimistic` | Unmarked bare defaults accepted, explicit nullable bounds still meaningful |
| `issue1947NullableBoundThroughMultipleNestedCalls` | Repeated identity/diamond calls and mixed nesting cannot hide the obligation |
| `issue1947LowerWildcardKeepsParametricDirection` | Same-T lower wildcard accepted without gaining permission to write null into `Box<T>` |
| `issue1947NullableFormalProjectionDoesNotConstrainResult` | Review-discovered false-positive regression: nullable input formal does not contaminate an independent non-null result, including deep nesting |
| `issue1947NestedInferenceFailureReportedAtInnerCall` | Review-discovered attribution regression: multiline inner call, both independent siblings, and suppressed/unsuppressed local initializers |

No pre-existing diagnostic expectations were removed or weakened.

## Direct compiler-backed solver assertions

Added to `nullaway/src/test/java/com/uber/nullaway/generics/ConstraintSolverImplTests.java`:

| Method | Contract |
|---|---|
| `nonNullWildcardObligationIsIndependentOfEvidenceOrderAndRepeatedSolves` | Both obligation/evidence orders and lower/edge orders; exact exception class, declared variable, owning site and upper-bound cause; repeated successful/failing solves; late evidence rejects after prior success |
| `annotatedOccurrencesBlockNonNullWildcardRootEvidence` | Nullable destination and non-null source barriers; complete solutions, exact non-null Java shapes, all variable keys, and absence of private fresh symbols |
| `modeledUnregisteredFixedSourceProvesNonNullWildcardViolation` | Real method owner/index model on an unregistered fixed source; direct/transitive rejection with no-model and non-null-projection controls |

The new projection fixture initially assumed an incomplete result. Inspection established that structural certification intentionally excludes root-occurrence checks and both projected constraints permit the supplied `Object` shape. Its final assertion requires **complete** status and exact structure for both controls; no existing assertion was changed to accommodate the repair.

## Independent review and resolved findings

A separate agent reviewed the new implementation and tests, rather than relying only on passing issue examples. It found two concrete problems in the first repair:

1. The wildcard proof read structural lower evidence even when it came through a nullable formal projection. The `empty(@Nullable E)` case was a false positive. Dedicated root-fixed lower evidence, recorded only for unprojected destinations, repairs it.
2. A deferred inner failure was deduplicated by its owner but reported and cached at the outer expression. A distinct `NonNullWildcardBoundViolationException` now selects the owning description, actual ancestor path, and cache entry without rewriting ordinary scalar failure behavior.

Both findings were reproduced by executing their new regression tests before the fixes, and those tests now pass unchanged. Follow-up independent review found no remaining concrete blocker in the corrected paths and approved the direct-test contracts; that is a bounded source-review conclusion, not proof of universal soundness.

## Executed validation

The pre-007 complete-stack probe failed with **none** of the four expected diagnostics. The first repair passed the original nine integration methods and the full suite but failed both subsequently added review regressions; those failures were repaired before export.

Final commands, run sequentially:

```sh
./gradlew :nullaway:test --tests com.uber.nullaway.generics.ConstraintSolverImplTests --tests 'com.uber.nullaway.jspecify.WildcardTests.issue1947*' --rerun-tasks --no-build-cache
./gradlew :nullaway:test --rerun-tasks --no-build-cache
./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
```

- Final full module suite: **1,201 tests recorded, zero failures, zero errors, 21 skipped**; 1m 17s.
- Final NullAway self-check: **passes**; 31s.
- Source-tree `git diff --check`: **passes**.
- Revised exports reapplied in a fresh worktree using both 01+02 and combined 03 alternatives: **all 15 changed paths match the tested tree byte-for-byte**, including the old visitor deletion.

The final full-suite run includes the last repeated-success assertion added after the targeted direct-test run. No existing skipped tests were disabled/enabled and no production suppression was added. Builds ran in disposable worktrees; the user's branch and checkout implementation files were not changed, and no implementation commits were created.

## Bounded scope

This repairs the #1947 acceptance case without claiming the entire unrelated wildcard contract overhaul in #1834, other newly reported issues #1944/#1945/#1946, exhaustive captures, or Java least-upper-bound inference. Class-owner model callbacks lack a new dedicated direct test; method-owner callbacks are tested. The earlier supported-version/performance-matrix limitations remain, and finite tests/review cannot guarantee the absence of every possible false positive or false negative.
