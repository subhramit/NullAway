# Repair pass results

## Scope

The implementation was repaired against the findings in `FRESH-REVIEW.md`, including the additional blockers discovered by independent review during this pass. This report supersedes the earlier rejection for the newly generated patch files; it does not erase the historical review.

The implementation and tests were applied temporarily to `b8e88803a2bb2287c08d9f85361c9317222387d4`. No commits or branches were created.

## Repaired defects

| Finding | Repair | Acceptance coverage |
|---|---|---|
| Array overlay crashes on mismatched component class shapes | Recursively align nominal types; never positionally overlay unmatched classes; diagnose declaration violations that cannot be represented on javac's shape | Pair/Box arrays, non-generic Bad/Good component subclasses, accepted non-null controls |
| Cyclic declaration-bound checking loses concrete mismatches | Validate declarations independently of cycle-safe candidate construction; concrete violations dominate unknown sibling positions | Bad and Good F-bounded Node examples |
| Wildcard declarations fall back without enforcing nested bounds | Check wildcard containment with recursion guards and feature gates | Rejected nullable nested bound and accepted non-null control |
| Structured method-reference results lose nested structure | Substitute complete inferred types into reference signatures while preserving explicit root overrides | Invalid identity adaptation, valid non-null adaptation, accepted nullable target |
| One declaration failure suppresses another participating call | Cache only the completed failing site, not every call in the graph | Exact two-diagnostic test on distinct nested call lines |
| Failed enclosing inference loses independent method-reference errors | Independently solve unresolved references against final targets with guarded re-entry | Invocation/reference, reversed order, reference/reference, and valid controls |
| Provisional-lambda inference duplicates scalar warnings | Retain failure-site provenance separately from root-problem caching | Exactly one inference warning plus ordinary nullable-parameter checking |
| Temporary non-publishing inference evicts completed reference results | Do not destructively invalidate persistent results during constraint generation; publish on completion, invalidate on publishable failure | Existing/new reference and nested-lambda suites |
| Local suppression ancestors are lost during lazy inference | Recover the actual diagnostic TreePath from the compilation unit | Exact zero-diagnostic local-suppression test inside a lazy lambda initializer |
| Explicit @Nullable/@NonNull occurrences lose nested contracts | Separate structural ownership/adjacency from scalar-nullness eligibility | Direct and nested `id(bad)` negative controls and good/null controls; existing annotation-override diagnostics unchanged |
| Receiver-instantiated bounds override explicit nullable library models | Apply explicit nullable handler overrides after receiver substitution | Existing library/model suite passes; no dedicated external custom-model integration test added |

## Test changes

The six former temporary review probes are now permanent regression tests, supplemented by positive controls and exact diagnostic-count checks. No pre-existing master expectation was removed or relaxed.

The mixed-invariant consumer test was renamed and documented as **characterization of a pre-existing false positive**, not as evidence of correct full inference:

`knownLimitationMixedInvariantLowerBoundsReportFalsePositiveIncompatibilities`

For unconstrained `<T> void consume(T first, T second)`, a common supertype can accept different invariant box instantiations. The retained repair visitor still reports incompatibilities for that shape, as unpatched master does. Fixing that broader Java-shape/LUB limitation is not claimed by this repair pass.

## Validation

Final full runs, sequentially:

```text
./gradlew :nullaway:test --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 1m 16s

./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 24s
```

JUnit reports: **62 suites, 1,159 tests, 0 failures, 0 errors, 21 skipped**.

Focused runs covered every reproduced blocker and the generic-method, method-reference/lambda, diamond, inference-reporting, and wildcard suites. `git diff --check` passed.

Independent read-only reviews checked solver/substitution soundness and lifecycle/test integrity. They identified further annotated-use and sibling-reference gaps, which were repaired and tested. The final focused re-reviews reported no remaining blockers in those repaired paths. This is evidence of improvement, not a proof that all possible javac attribution shapes are correct.

## Delivery

- `0001-call-specific-inference-variables.md`: unchanged first stage.
- `0002-reconcile-full-inferred-types.md`: regenerated second stage, including all repairs and acceptance tests.
- `0003-complete-solution.md`: complete combined diff from the stated master baseline.

Use either the staged pair or the combined patch, not both. Each Markdown file contains a complete fenced diff payload.

The pre-repair versions are preserved in `archive-before-repair/`. `FRESH-REVIEW.md` and `REVIEW-REPRODUCERS.md` are historical audit material.

## Remaining limitations

- The existing repair visitor remains a fallback; this is not a complete replacement of all Java generic-shape inference.
- The mixed-invariant/common-supertype false positive described above remains pre-existing behavior.
- Direct array-element dereference propagation beyond the parameter-compatibility tests is not claimed solved.
- Existing ignored tests remain ignored; this pass did not silently enable, disable, or rewrite them.
- No blanket assertion of zero false positives/false negatives is justified by these finite tests and reviews.
