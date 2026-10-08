# Issue completeness and permanent regression test map

> **Current-stage update:** 004 closes the observable array gap. 005 adds attributed shapes, explicit result certification, ten direct compiler-backed solver tests, an enclosing-type integration test, and an unused-variable lambda test; 006 physically removes the visitor/wrapper/guard after the entire repair-free suite and self-check pass. The old failed `issue1455` bypass experiment applies to the pre-005 stack. The detailed audit below remains a baseline snapshot of 01+02; see `COMPLETION-005-006.md` for current acceptance evidence.

## Scope and answers

Read-only audit dated 2026-10-08 of **repaired patches 01+02**, as present in `NullAway/.worktrees/final-scope`, against master `b8e88803a2bb2287c08d9f85361c9317222387d4`. The supplied `C:\Users\subhr\Downloads\issue-1932-plan.md`, current patch payloads, review reports, and GitHub API bodies/comments were inspected. This document is the only audit output; no production/test edits, patch application to disk, commits, branches, or Gradle commands were performed.

**Do all independent-review fixes have permanent tests? Not without qualification.** The five reproduced blockers in `FRESH-REVIEW.md` have permanent targeted regressions, and its sixth, passing upper-only probe is permanent too. Additional annotated-use, sibling-reference, warning-deduplication, and local-suppression repairs have permanent tests. However, explicit nullable library-model precedence over a receiver-instantiated bound has **no dedicated integration regression**. Non-publishing method-reference cache preservation has related end-to-end coverage, not a dedicated test inspecting the eviction/preservation lifecycle. The mixed-invariant case is a characterization of a known false positive, not a fix; direct inferred-array element dereference is not covered by the delivered acceptance test.

**Can `NestedTypeVarSubstitutionRepairVisitor` now be removed? Not on the evidence for 01+02.** It is still called on successful generic invocation inference; the solver deliberately falls back to scalar/symbolic results for unsupported or cyclic structures, and consumers still overlay annotations on javac types. No removal or repair-independent validation was performed here. The nominal `Map<InferenceVariable, Type>` API and passing motivating examples do not establish a complete replacement of the visitor.

These conclusions concern **01+02 only**. A parent may later add an optional `004` follow-up, but it was not among the inspected patch files and is neither assessed nor credited here. The filename `0003-complete-solution.md` describes combined packaging, not proof of complete issue/plan satisfaction.

## Delivery and baseline

| Artifact | Actual role and scope |
|---|---|
| [0001-call-specific-inference-variables.md](0001-call-specific-inference-variables.md) | Standalone first stage on the stated master baseline. **#1291 lives here**, including `issue1291` and `issue1291RepeatedDiamond`. Uses call/site-specific variables while retaining scalar results at this stage. Includes the reported compilation repair using `Tree.equals` for site projection. Does not independently deliver the full #1585 rewrite. |
| [0002-reconcile-full-inferred-types.md](0002-reconcile-full-inferred-types.md) | Dependent second stage: apply **after 01**. Adds structured bounds/type-valued results, consumer changes, and the review repairs/tests mapped below. Retains the repair visitor and conservative fallbacks. |
| [0003-complete-solution.md](0003-complete-solution.md) | Alternative combined diff from master, equivalent to 01+02. **Not a third incremental stage or a new commit**; do not apply it after the pair. |

Observed worktree `HEAD` remains `b8e88803` (the squash merge of #1930). The implementation is an uncommitted diff in seven files, plus an existing untracked `combined.patch`. This is not the old plan's proposed child branch from `issue-1919` at `2490d1e3`; the required #1919 groundwork is already in the current master baseline through #1930.

Artifact verification in this audit:

- Parsed and applied 01 then 02 **entirely in memory** to the seven baseline files read with `git show HEAD:<path>`. All context matched; resulting file lines matched the repaired worktree, with zero mismatches.
- With explicit UTF-8 decoding and normalized line endings, 03's fenced diff equals the worktree's `git diff`; the existing `combined.patch` equals it too.
- Test diff is additive: 198/0 added/deleted lines in error-reporting tests, 88/0 in lambda/reference tests, and 584/0 in generic-method tests: **870 additions, zero deletions**. The three files add 26 JUnit methods overall (two in 01, 24 in 02). The existing `issue1455` method gains reversed-order coverage. These counts alone do not prove semantic completeness.

## Upstream requirements, from API bodies and discussions

Fetched issue bodies and issue comments for #1291, #1585, and #1932; PR bodies, issue comments, review bodies, and inline review comments for #1930, #1473, and #1574. Relevant sources:

- [#1291](https://github.com/uber/NullAway/issues/1291): distinguish two calls of the same declared generic method in one inference problem. Exact positive example is `String u = chooseFirst(id(t), id(s)); u.hashCode()` with nullable `s` and non-null `t`. No issue comments added further requirements at inspection.
- [#1585](https://github.com/uber/NullAway/issues/1585): top-level nullability alone cannot preserve full substitutions; #1473/#1574 are motivating workarounds. Its [self-contained deeper example](https://github.com/uber/NullAway/issues/1585#issuecomment-6006334300) requires `R = Box<@Nullable String>` for `nested.flatMap(box -> box)` when `nested` is `Box<Box<Box<@Nullable String>>>`, then acceptance of the inferred `var flat` by `acceptsNested`.
- [#1932](https://github.com/uber/NullAway/issues/1932): change solver keys to declaration-plus-call identity, change values to fully nullness-annotated types, adapt constraint registration/internal state, keep existing tests passing, and make the new #1291/#1585 examples pass. It expresses a **hope** to remove some/all repairs, ideally the entire visitor. The two issue comments only concern notification/assignment; they do not relax those goals.
- [#1930](https://github.com/uber/NullAway/pull/1930): preserve target-derived implicit lambda parameter types, nested environment restoration, block return/`var` scanning, and no persistent inference caching while provisional parameter types are active. Its [cache review](https://github.com/uber/NullAway/pull/1930#discussion_r4189995080) explicitly covers both success and failure writes. The [negative-control discussion](https://github.com/uber/NullAway/pull/1930#discussion_r4198898169) asks for an invariant non-null/nullable mismatch control, subsequently added.
- [#1473](https://github.com/uber/NullAway/pull/1473): restore nested annotations dropped by javac for #1455, without inconsistent substitutions across parameters. A [review comment](https://github.com/uber/NullAway/pull/1473#discussion_r2809908489) raises return/thrown-type consistency as follow-up scope. Passing the original argument-only example does not alone establish this broader consistency.
- [#1574](https://github.com/uber/NullAway/pull/1574): recursively remove misplaced nullable annotations, notably the Caffeine example, not just add missing annotations. Its [per-occurrence annotation discussion](https://github.com/uber/NullAway/pull/1574#discussion_r3230846419) distinguishes shared nested substitutions from occurrence-specific root overrides. It is historical review evidence, not proof that the new replacement path is independent of the old repair.

### Requirement-to-code/test assessment

Test abbreviations below refer to permanent files in the inspected worktree:

- **GM**: [GenericMethodTests.java](../nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodTests.java)
- **LM**: [GenericMethodLambdaOrMethodRefArgTests.java](../nullaway/src/test/java/com/uber/nullaway/jspecify/GenericMethodLambdaOrMethodRefArgTests.java)
- **ER**: [GenericInferenceErrorReportingTests.java](../nullaway/src/test/java/com/uber/nullaway/jspecify/GenericInferenceErrorReportingTests.java)

Line numbers identify this snapshot, not a stable API.

| Requirement | Code and permanent test evidence | Bounded conclusion |
|---|---|---|
| #1291 exact reproducer and call isolation | `ConstraintSolver.InferenceVariable(Element typeVariable, Tree site)`; registration substitutes fresh symbols per site; solutions project by site. GM `issue1291` (2418), `issue1291RepeatedDiamond` (2457). | Permanent in **01**: exact positive, reversed nullable-selected dereference, deeper nested `id`, nonlocal sink, and repeated diamonds with reversed negative control. #1291 does not need 02 for its standalone fix. |
| #1585 deeper substitution | Type-valued `solve()`, structured bound retention/reconciliation, nested annotation application, and participating poly-type publication. GM `issue1585NestedNullnessInSubstitution` (2489), `issue1585LambdaArgumentOfNestedCall` (2534), `inferredNestedNullnessIsPerCall` (2575). | Exact deeper example, expression/block body, `var`/explicit result declaration, nested call, and invariant negative controls are permanent in 02. These are checker integration tests, not direct assertions of solver map values or evidence that every structure is solved. |
| Retain #1919/#1930 | `lambdaParameterTypesForInference`, restoration, return/`var` finder, `okToCacheInferenceResult`; participating nested calls publish poly types on completion. LM `issue1919ImplicitLambdaParameterPreservesNestedNullness` (13), `genericCallInLambdaVarInitializerPreservesNestedNullness` (172), plus new `nestedGenericLambdaCallPublishesInnerPolyType` (84). | Existing parent cases remain unchanged; the `copy.get().length()` unsafe dereference still has an expected diagnostic in source. New coverage includes a later sibling call and later method reference. No tests were run in this audit. |
| #1473 consistency and #1574 annotation correction | Annotation-aware super/member utilities and full-type overlays; `updateMethodTypeWithInferredNullability` updates arguments, return, and thrown types. GM `issue1455` (1803), `caffeineNestedArgToGenericMethod` (1943), `nestedGenericMethodRepairPreservesTopLevelNullability` (1976). | Existing regression gates remain, and #1455 gains reversed-order mismatch. **Visitor remains active during these tests**; they do not establish repair-free equivalence. No new focused thrown-type substitution regression was found in 01+02. |
| #1932 new API/internal state | Public `solve()` returns `Map<InferenceVariable, Type>`; internal `vars`, edges, fresh-symbol ownership, and structured bound collections are call-specific. | Key/value contract is changed. However, a returned `Type` can still be an annotated declaration `TypeVar` carrying only scalar evidence, or diagnostic evidence inconsistent with a contextual bound. The stronger claim that every entry is a complete satisfying substitution is not justified. |
| Remove visitor | Successful invocation path in `GenericsChecks.substituteTypeArgsInGenericMethodType` still runs `restoreNestedNullabilityForTypeVarArguments` under `nestedNullabilityRepairInProgress`. | **Not delivered in 01+02**; not safe to describe as a completed cleanup based on this audit. |

## Independent-review fix-to-test map

Historical `FRESH-REVIEW.md` rejected the pre-repair implementation. `REPAIR-REPORT.md` describes the regenerated artifacts. This map checks actual permanent test bodies rather than treating either report's verdict as sufficient evidence. All listed new repair methods are in 02 unless explicitly stated otherwise.

| Review finding / repair | Permanent method(s) | What is actually asserted; gap/status |
|---|---|---|
| Array declaration overlay crashed on mismatched nominal component shapes | GM `arrayDeclarationBoundPreservesNestedNullnessThroughSubtyping` (2777) | `Pair<Integer, @Nullable String>[]` and nongeneric `Bad[]` reject against receiver-bound `Box<String>[]`; corresponding non-null `Pair` and `Good[]` accept. A checker crash fails compilation. Covers original array nominal-shape regression and positive controls, not all possible multidimensional/capture shapes. |
| Recursive declaration-bound construction skipped concrete nested violations | GM `recursiveDeclarationBoundPreservesNestedNullness` (2813) | F-bounded `Node<T, Box<String>>` rejects `Bad extends Node<Bad, Box<@Nullable String>>`, accepts `Good`. `validateDeclarationBounds` runs independently of candidate construction; `combineBoundRelations` lets a concrete violation dominate an unknown sibling. Permanent original repro and positive control. |
| Wildcard fallback did not enforce nested declaration bounds | GM `wildcardDeclarationBoundPreservesNestedNonNullRequirement` (2871); `wildcardDeclarationBoundUsesContainmentFallback` (2724) | `Box<? extends Box<String>>` rejects nullable nested `Bad`, accepts `Good`; separate `? super String` acceptance avoids treating containment as equality. Permanent focused examples; not exhaustive wildcard/feature-gate coverage. |
| Structured method-reference result lost substituted nested structure | GM `structuredMethodReferenceChecksNestedNullnessAndNullableReturn` (2838) | Rejects `use(nullableBox, Test::id)` by method-reference nullability mismatch; accepts non-null input, nullable target return, and explicit nullable reference return. Uses complete substitution for references rather than overlay onto a symbolic target. Permanent original false-negative probe and controls. |
| Failure cached for all participating calls suppressed independent sibling error | ER `independentNestedDeclarationBoundViolationsReportedOnceOnDistinctLines` (540); `nestedDeclarationBoundViolationReportedOnce` (378) | `containsExactly` checks two ordinary incompatibilities at distinct nested-call lines, with good/good control; separate method checks direct/subclass violations exactly once. Site-only failure caching replaces graph-wide suppression. Strong diagnostic-count coverage. |
| Failed enclosing inference suppressed independent generic references | GM `referenceSiblingViolationsSurviveEnclosingFailure` (2952) | Expected diagnostics on invocation/reference, reversed reference/invocation, and reference/reference sibling lines; corresponding valid cases accept. Independent reference solving has guarded re-entry and final-target lookup. Permanent negative/positive integration coverage. Unlike ER exact-list tests, line-marker tests do not assert exact duplicate counts on an expected line. |
| Provisional lambda inference duplicated scalar warnings | ER `scalarInferenceFailureInsideImplicitLambdaReportedOnce` (248); `scalarInferenceFailureInsideImplicitLambdaSuppressed` (285) | First filters inference warnings to exactly one and requires an ordinary nullable-parameter diagnostic; second requires an empty diagnostic list. Failure-site provenance is retained separately from root-problem caching. The first does **not** assert the exact complete diagnostic list. |
| Non-publishing inference evicted completed method-reference results | LM `nestedGenericLambdaCallPublishesInnerPolyType` (84), existing LM `issue1919ImplicitLambdaParameterPreservesNestedNullness` (13), and GM structured/sibling reference tests above | Code no longer invalidates persistent reference entries during constraint generation; invalidation occurs inside cacheable failure handling. Later-reference/nested-lambda integration cases are permanent. **Indirect coverage only:** no dedicated test seeds a completed result, performs non-publishing inference, and asserts that the same result survives. Do not label this a one-fix/one-targeted-test proof. |
| Lazy diagnostic path lost local suppression ancestors | ER `nestedDeclarationBoundViolationInsideLazyInferenceLocallySuppressed` (574) | Exact empty diagnostics for `@SuppressWarnings("NullAway") var result = boundedId(bad)` inside a lazy lambda initializer. `stateForInferenceDiagnostic` recovers the real `TreePath` from the compilation unit. Permanent focused local-suppression regression. |
| Explicit `@Nullable T`/`@NonNull T` occurrences lost nested contracts/ownership | GM `annotatedOccurrencePreservesNestedDeclarationBound` (2915); existing ER `annotatedTypeVariableUseIsNotAnInferenceFailure` (332) | Rejects direct and nested `id(bad)` uses for both root annotations; accepts `good`, nested `id(good)`, and nullable null. Scalar override eligibility is separated from structural ownership/adjacency. Existing exact four ordinary diagnostics guard against extra scalar inference failures; that ER method was not newly added. |
| Receiver-instantiated bound overrode explicit nullable handler/library model | **No dedicated new integration method.** Related GM `receiverInstantiatedMethodVariableBounds` (2670), LM `receiverInstantiatedMethodReferenceBound` (145); existing `GenericsTests.testForNullableUpperBoundsInLibModel` (2287) | Registration consults `hasNullableUpperBoundOverride` after receiver substitution and allows modeled nullable roots. Receiver tests have **no custom model**, and the existing model test covers `Function` lambda/reference behavior, not the receiver/model collision. Broad suite pass reported by the repair pass is not permanent targeted coverage. This is the clearest missing one-fix/one-regression gate. |
| Upper-only factory static concern; review probe already passed | GM `upperOnlyFactoryDeclarationBoundConflictsWithNullableNestedTarget` (2895) | Factory `<T extends Box<String>> T make()` rejects invariant nullable-nested field target and accepts `Box<String>` target. Sixth historical temporary probe is permanent, but this was not a reproduced defect requiring repair. |
| Mixed-invariant consumer test was misrepresented as correct inference | ER `knownLimitationMixedInvariantLowerBoundsReportFalsePositiveIncompatibilities` (413) | Renamed/documented as known limitation; exactly two incompatibilities in opposite argument orders, no inference-failure extras. **Characterization/documentation repair only.** `<T> void consume(T,T)` can accept the two box types via a common supertype such as `Object`; the false positives remain. |
| Array acceptance test narrowed to sinks rather than dereferences | GM `fullTypeInferenceMergesCovariantArrayBoundsInEitherOrder` (2642) | Both argument orders reject non-null-element sink and accept nullable-element sink. Does **not** test `pick(...)[0].length()` or `var` extraction then element dereference. Historical review says those explored shapes missed the expected report; no permanent test/fix for them is delivered. This is a known coverage/behavior gap, not evidence of complete array propagation. |

The six original `review...` methods survive under descriptive permanent names, not their temporary names: array, recursive, structured-reference, wildcard, independent siblings, and upper-only factory map to the six corresponding rows above. `REVIEW-REPRODUCERS.md` remains historical material; the permanent source is authoritative for what is now tested.

Additional permanent structured-bound gates include GM `fullTypeInferenceRespectsNestedDeclarationBound` (2607), `inheritedStructuredEvidenceReachesFixedPoint` (2698), and `unmarkedDeclarationBoundIsNotNullnessEvidence` (2744). They cover fixed caller bounds, inherited/dependent evidence, and marked/unmarked authority. They do not fill the custom-model, direct-array-dereference, or repair-independence gaps.

## Comparison with the supplied implementation plan

The plan intentionally removes repairs last and requires a stronger invariant than a type-valued return signature: **a complete substitution must satisfy every relevant recorded constraint**. Current implementation is materially incremental relative to that plan.

| Plan stage / gate | Status in audited 01+02 |
|---|---|
| Stage 0: parent groundwork and baseline | Superseded branch arrangement: #1930 is already merged at the inspected master tip. Historical repair validation is reported, but this audit did not reproduce the old branch/performance baseline. |
| Stage 1: site identity and ownership | Delivered via fresh per-site symbols rather than a literal `ScopedType` wrapper. The `Tree` key is broader than `MethodInvocationTree`, covering diamonds/references. #1291 and repeated diamond permanent gates exist; receiver/caller variables and explicit witnesses remain fixed rather than acquiring site identities. |
| Stage 2: retain/propagate structured bounds | Implemented for supported shapes with lower/upper/declaration evidence, fixed-point constraint preparation, inherited alignment, covariant merging, cycle guards, and independent declaration validation. **Partial relative to plan:** unknown/cyclic/incompatible structures can return scalar-only symbolic results; contextual mismatches can return diagnostic evidence, not a satisfying complete solution. No explicit javac-instantiation parameter or plan-shaped per-problem session/`ScopedType` API was added. Different representation alone is not a defect, but it does not satisfy every promised semantic gate. |
| Stage 3: substitutions, lambda and cache lifecycle | Substantial integration exists, including nested poly-type publication, reference full substitution, provisional/dataflow publication guards, and independent failure recovery. Ordinary invocation consumers still use scalar-root and nested annotation overlays plus repair, rather than uniformly replacing every signature position with a certified complete substitution. No new targeted cache-survival or multi-compilation-unit lifecycle test was identified in the patch additions. |
| Stage 4: remove obsolete repairs | **Not delivered.** Visitor, wrapper, dedicated repair re-entry tracking, annotation overlays, and repair-specific test comments remain. The plan explicitly says removal follows repair-independent gates, not merely a passing suite with the visitor enabled. |
| Compiler-backed direct solver assertions | Added tests compile source through the checker. No new direct solver test asserts returned map values, complete bound satisfaction, contradictory outcome classification, or ownership independently of post-inference compatibility checks. Search found no `*ConstraintSolver*` test file in the main module. |
| Acceptance matrix beyond motivating examples | Some named gates have concrete coverage above. No new targeted proof of direct inferred-array element dereference, multidimensional covariance, full enclosing/capture structure combinations, custom receiver/model override, larger-expression growth/performance, or supported JDK/Error Prone matrix was supplied by 01+02. Existing generic suites cannot be treated as evidence for every cross-product of these scenarios. Constructor references remain outside the plan's scope. |

### Scalar/symbolic fallback is still a real contract

In [ConstraintSolverImpl.java](../nullaway/src/main/java/com/uber/nullaway/generics/ConstraintSolverImpl.java):

1. `inferredType` (542–590) skips structured candidate construction when `hasStructuredDependencyCycle` holds. If no structured bound exists, it returns the **declared type variable annotated only at the root**, defaulting unknown scalar nullness to non-null.
2. `structuredBound` (742–791) returns `null` when lower-bound merging fails or a comparison is unknown. Its Javadoc explicitly delegates nested nullability to javac and the existing repair visitor.
3. When a contextual upper bound is violated, `structuredBound` can return the lower-evidence candidate to let ordinary compatibility checks diagnose it. `validateDeclarationBounds` independently selects diagnostic fallback evidence or throws a site-specific violation. Thus the solution map is not universally a proof that all constraints hold.

In [TypeSubstitutionUtils.java](../nullaway/src/main/java/com/uber/nullaway/generics/TypeSubstitutionUtils.java), ordinary invocation updates first infer root annotations, restore occurrence overrides, then apply nested overlays onto javac's shape. `applyNestedAnnotationsOfInferredTypes` deliberately skips nested updates for an annotated declaration-variable fallback (381–397). The method-reference substitution path separately documents that these fallback variables stay symbolic, not replaced by their upper bounds (277–300).

Finally, [GenericsChecks.java](../nullaway/src/main/java/com/uber/nullaway/generics/GenericsChecks.java) still calls the repair wrapper before inference overlay in the successful invocation path (3510–3535), and the wrapper delegates to `NestedTypeVarSubstitutionRepairVisitor.repairMethodType` (3566–3579). This is stronger evidence against claiming removal readiness than any artifact title or aggregate pass count is evidence for it.

## What would justify a later removal stage?

These are follow-up gates, not work performed or promises made in this audit:

- Separate complete successful substitutions from scalar-only fallback and diagnostic evidence; explicitly handle or safely route structures that currently rely on repair.
- Add the dedicated receiver-instantiated bound/custom nullable-model integration regression, including ordinary invocation and relevant reference adaptation controls.
- Add direct inferred-array element/`var` dereference negative tests alongside positive controls. Sink compatibility alone is insufficient.
- Resolve or explicitly scope the mixed-invariant/common-supertype false positive; do not silently relabel its characterization as a solved correctness gate.
- Add direct attributed-type solver tests for map values, ownership, bound satisfaction, dependent/cyclic outcomes, and contextual contradictions, plus a focused non-publishing reference-cache preservation test.
- Demonstrate #1291, the exact deeper #1585 example, #1919 unsafe dereferences, #1455 reversed-order behavior, Caffeine annotation removal, and root-override controls **without repair output** before deleting the visitor/wrapper/re-entry state. Retain general annotation-preserving utilities.
- Run targeted regressions and then main-module tests/self-check sequentially in that later authorized implementation pass. Broader matrix/performance claims require their own evidence.

An optional 004 could address a bounded missing gate, but its existence or success would not automatically complete all of the above. Any later removal verdict must name the exact additional diff and validation.

## Validation provenance and limits

This audit performed source/test-body inspection, GitHub API reading, read-only git inspection, additive-test diff counting, in-memory staged reconstruction, and combined-payload comparison. **No Gradle, test execution, production/test modifications, or visitor-disable experiment occurred.** Test descriptions above report what the permanent source asserts, not fresh runtime results.

[REPAIR-REPORT.md](REPAIR-REPORT.md) records earlier sequential full runs of `:nullaway:test --rerun-tasks --no-build-cache` and `:nullaway:buildWithNullAway --rerun-tasks --no-build-cache`: successful, with 62 suites, 1,159 tests, zero failures/errors, and 21 skipped. These are **historical reported validation**, not independently rerun here. Existing ignored tests remain outside passing-test evidence.

Final bounded status for **01+02**: 01 independently delivers the #1291 fix; 02 adds meaningful full-type support and permanent regressions for reproduced review blockers; 03 is only their combined packaging. The dedicated model-override test, known mixed-invariant false positive, stronger complete-solution contract, and repair-independent removal gates remain open or unproven. The direct-array behavior gap is now addressed by the additional 004 patch described below.

## Follow-up 004: executed acceptance and review evidence

This section is additional parent-run evidence, not part of the earlier read-only audit.

| Added permanent test | Purpose |
|---|---|
| GM `inferredArrayElementDereferenceInEitherOrder` | Direct call-expression and inferred-local element dereferences, both argument orders, explicit nullable and non-null controls |
| GM `inferredArrayElementFlowRefinement` | Null checks, stable writes, null overwrites, variable indices, and extracted nullable elements |
| GM `inferredArrayAccessAsGenericArgument` | Preserve enhanced component type when an indexed value becomes a generic argument |
| GM `inferredMultidimensionalArrayElementNullness` | Preserve distinct array-dimension and leaf nullness |
| GM `inferredArrayElementsInEnhancedFor` | Original array type supplies desugared loop-element nullness |
| GM `inferredArrayElementsRemainNonNullInLegacyMode` | Do not change legacy mode's existing array-read policy |
| GM `nullableArrayElementFieldIndicesHaveDistinctReceivers` | Do not suppress a warning by conflating two instance-field index receivers |
| GM `nullableArrayElementRequireNonNullRefinesAccess` | Keep modeled require-non-null postconditions |
| GM `nullableArrayElementNonNullGuardRefinesAccess` | Keep modeled conditional postconditions |
| GM `nullableArrayElementGuardSurvivesUnrelatedStatements` | Unrelated primitive assignments and object construction cannot erase a guard |
| GM `nullableArrayElementGuardSurvivesDifferentElementWrite` | A distinct known index does not invalidate an unrelated guarded element |
| GM `nullableArrayElementAliasWriteInvalidatesRefinement` | A possibly aliased write invalidates overlapping element facts |
| GM `nullableArrayElementIndexRebindingInvalidatesRefinement` | Mutable index rebinding invalidates its dependent path |
| GM `nullableArrayRebindingInvalidatesElementRefinement` | Array rebinding invalidates old element facts |

Full uncached main-module tests and self-check passed with 1,173 tests recorded, zero failures/errors, and 21 skipped. Independent review found three issues in the first 004 candidate; these were repaired and the eight precision regressions added. Focused re-review found no remaining P0/P1 blocker in the changed paths.

A separate worktree bypassed repair output and ran eight key regression gates. Seven passed, but `issue1455` failed on the valid `acceptSup(sup)` case with `Supplier<@Nullable OuterT>` incorrectly rejected as `Supplier<OuterT>`. The experimental bypass is not delivered. **We still cannot delete the repair visitor or claim the full #1932 plan complete.**
