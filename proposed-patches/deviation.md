# Final deviations from the supplied issue-1932 plan

## Reference and current implementation

This document compares the current delivered stack through **007** with the supplied `issue-1932-plan.md`, titled “Track call-specific full-type bounds on top of issue-1919,” including its implementation stages, acceptance matrix, and risks. It also uses the supplied screenshots for context; it does not claim access to an unprovided full Codex transcript.

Current apply order:

```text
01 → 02 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006 → 007
```

Or replace 01+02 with combined 03. **03 combines only 01+02**, not the later stages.

005, 006, and 007 are implemented code patches. The final stack passes the main-module suite and self-check uncached with the old visitor physically deleted. Earlier documents describing an unimplemented 005 or unsafe visitor deletion record the pre-005 state, not the current result.

## Overall assessment

The central motivating fixes and repair-removal stage are now delivered: call-specific identities, supported full annotated substitutions, explicit certification/fallback/contradiction status, direct attributed-type solver tests, consumer integration, and deletion of `NestedTypeVarSubstitutionRepairVisitor` and its dedicated lifecycle.

The implementation does **not literally mirror every proposed class/API**. Fresh symbols encode ownership instead of `ScopedType`; several scoped structures replace a single inference-session class; `solve()` returns a status-bearing `Solution` rather than an undifferentiated map.

Unsupported structures can remain explicitly uncertified, broader Java LUB inference is not implemented, and the full compatibility/performance acceptance matrix has not been exercised. Passing local tests and review is not a proof that every possible false positive or false negative is absent.

## Stage 0 — Baseline and delivery workflow

| Plan | Final approach | Reason / limit |
|---|---|---|
| Create a child branch from `issue-1919` at `2490d1e3` | Reconstruct disposable worktrees from master `b8e88803`, then export Markdown diffs and restore master | #1930 had already merged at this baseline; the old branch-divergence facts were obsolete |
| Rebase the child after the parent lands | No implementation branches or commits created | User requested artifact-only temporary application/testing; the merged groundwork is already present |
| Compare baseline timings and regressions | Run targeted and full tests/self-check sequentially, including uncached final runs | No systematic parent-vs-new performance comparison was performed |

## Stage 1 — Call-specific inference identity

| Plan | Final approach | Assessment |
|---|---|---|
| `InferenceVariable(Element, ExpressionTree)` with AST identity | `InferenceVariable(Element, Tree)`; attributed javac tree equality is identity-based | Representation difference; broader key type supports invocations, diamonds, references, and reporting |
| Separate registration, state, graph, queues, and solutions by site | Fresh javac type-variable symbols are created for each site and substituted before constraint traversal | Delivered in standalone 01, initially retaining scalar results |
| Give each operand scoped ownership through nested structures | Fresh symbols carry ownership through composed javac types instead of an explicit `ScopedType` wrapper | Alternative representation; direct and nested annotated-use tests verify ownership survives relevant transformations |
| Receiver/caller variables and explicit witnesses remain fixed | Only registered inference symbols are inferred; fixed caller symbols remain symbolic | Delivered for tested cases, including direct solver fixed-symbol assertions |
| Select cached/substituted answers by site | Project result bindings per invocation/reference, never merely per declaration | Delivered; #1291 is not accidentally folded into 02 |

## Stage 2 — Full shapes, bounds, and result certification

| Plan | Final approach | Assessment / deviation |
|---|---|---|
| One per-problem session owns registrations, shapes, bounds, dependencies, and provisional environments | Solver owns its graph/registration state; `GenericsChecks` owns scoped lambda maps, staging, completed contexts, and publication guards | No single `InferenceSession` class was introduced; coordinated scoped structures remain |
| `registerInferenceVariable(InferenceVariable, Type javacInstantiation)` | Bulk registration returns fresh variables and accepts instantiated upper bounds, per-variable authority, and javac shape maps; a compatibility overload remains | API shape differs, but explicit attributed shape input is delivered in 005 |
| Match declaration signatures against attributed call signatures | `InferenceTypeShapes` traverses methods, results, throws, arrays, class arguments, enclosing types, and supported reference/constructor adaptations | Delivered; inconsistent or unresolved shape recovery is not treated as a complete answer |
| Derive unused variables from substituted bounds without changing observable types | Non-recursive unused bounds can supply representative instantiations; unresolved/recursive unused variables are explicitly incomplete | Delivered with direct and integration tests; a certified functional target can remain usable independently of an unrelated opaque variable |
| `addSubtypeConstraint(ScopedType, ScopedType, boolean)` | Existing javac `Type` operands contain fresh ownership symbols; scalar and structural adjacency are separate | Representation difference; explicit root annotations suppress scalar propagation without erasing nested structural relationships |
| Keep lower, upper, and equality requirements distinct | Lower/upper collections and directed edges; equality expressed in both subtype directions; declaration authority is tracked separately | Relation encoding differs from a separate equality list |
| Change public scalar results to `Map<InferenceVariable, Type>` | `Solution(inferredTypes, incompleteVariables, inconsistentVariables)` wraps that type map | Deliberate API extension: unknown/contradictory evidence must not masquerade as successful inference |
| Revisit dependencies to a fixed point | Mutation-version repeated passes with semantic fingerprints and recursion guards | Correctness gates delivered for tested paths; not a narrowly queued dependency work list, so systematic performance work remains |
| Check all relevant bounds after substitution | Substitute final bindings into structured lower, upper, declaration, and variable-edge relations; unknown marks incomplete and violations mark inconsistent | Delivered certification for supported represented constraints; explicitly unsupported cases remain uncertified |
| Preserve scalar warning controls and ordinary diagnostics | Existing scalar contradictions still throw; structurally inconsistent evidence is distinguished and routed through ordinary checks | Deliberate separation of result validity from diagnostic evidence; expected diagnostics are unchanged |
| Preserve fixed-variable facts without substituting their bounds as concrete values | Fixed variables remain symbolic leaves; bounds are checking evidence | The previously blocking `Supplier<@Nullable OuterT>` substitution now has a direct COMPLETE-status test |
| Preserve/remove nested annotations consistently, including Caffeine | Project authoritative evidence onto aligned Java shapes and consume full substitutions on complete paths | Motivating and repair regression gates pass without the old visitor |
| Deduplicate bounds and guard cycles | Semantic structural keys include symbols, annotations, and intersection components; active comparisons and structural dependencies are guarded | Delivered after review found identity-only nontermination and incomplete comparison paths |
| Reconstruct detached types and preserve unrelated metadata | Retain general metadata-copy/detached-variable/capture utilities and assert operations leave source/declaration graphs unchanged | Tests establish operation-level isolation, not deep immutability of every returned javac object graph; immutable result collections are only shallow snapshots |

## Stage 3 — Consumers, provisional lambdas, and caches

| Plan | Final approach | Assessment / deviation |
|---|---|---|
| Build on #1919 implicit parameter environments, return/`var` scanning, and `finally` restoration | Preserve merged #1930 groundwork and extend owning-lambda lookup and scoped return-target recovery | Delivered without independently rewriting the parent fix |
| Use session-local scoped parameter values referencing unresolved variables | Fresh symbols retain ownership; temporary maps and guarded state remain in `GenericsChecks` | No literal `ScopedType` parameter environment was introduced |
| Replace scalar overlays with full substitution in all complete consumers | Complete invocation signatures/results, generic references, and diamond arguments consume full bindings | Delivered; general annotation/fallback utilities remain for explicitly uncertified or diagnostic paths |
| Consistently update returns, parameters, throws, constructed types, poly targets, and locals | Complete method substitution covers signature positions; enhanced results propagate into constructed arguments, locals, and supported targets | Related integration coverage exists; no new dedicated thrown-type solver regression was added |
| Publish final nested lambda/reference targets | Stage participating targets and publish after solving; independently recover references after failed enclosing problems | Delivered with positive and negative sibling/reference tests |
| Do not cache unfinished dataflow/provisional results | Reusable call caching is disabled monotonically after provisional-lambda participation; non-publishing runs do not destructively evict completed reference results | Delivered lifecycle guards; dedicated state-inspection and multi-compilation-unit coverage is not exhaustive |
| Publish only completed solutions | `InferenceSuccess` and `InferencePartial` differ; contradictory root diagnostic evidence may be retained as Partial, never successful inference | Root diagnostic retention is an explicit deviation from simply discarding every invalid candidate; it preserves established ordinary reporting |
| Do not let unrelated unresolved variables erase completed observable facts | A specific functional target can publish only if its relevant bindings are certified and pending private symbols are absent | Additional refinement introduced in 005; it does not certify or cache the incomplete root call |
| Preserve source attribution and deduplication | Failure-site provenance is separate from root caching, and actual AST ancestors are recovered for reporting | Exact independent-error, warning-count, and local-suppression regressions delivered |
| Observable nullable array elements remain nullable after inference | 004 adds enhanced component projection to fast checks and dataflow, including dimensions, `var`, indexed arguments, and enhanced-for | This gate was initially incomplete in 02 and is now addressed by fourteen additional regression methods |
| Prevent array recovery from re-entering dataflow | A scoped recovery depth avoids starting analysis from an array transfer; ordinary lambda refinement retains its prior behavior | Additional repair justified by an executed stack-overflow regression |

## Stage 4 — Remove obsolete repairs

| Planned cleanup | Final result |
|---|---|
| Delete `NestedTypeVarSubstitutionRepairVisitor` | **Deleted in 006**, including the whole 351-line file |
| Delete its wrapper | `restoreNestedNullabilityForTypeVarArguments` removed |
| Delete dedicated repair re-entry handling | `nestedNullabilityRepairInProgress` and its cleanup removed |
| Remove obsolete scalar representation/adapters | Public scalar enum was removed earlier; general annotation-preserving and uncertified checking utilities remain because they still serve other paths |
| Keep general member/supertype, wildcard/capture, and target logic | Retained |
| Update repair-specific test wording | Comments updated and one repair-named method renamed; expected diagnostics unchanged |
| Require repair-independent regression acceptance before deletion | Relevant generic suites passed with repair output bypassed; the entire module and self-check then passed with the visitor physically deleted |

006 adds no bypass flag or `UnusedMethod` suppression. It is a cleanup-only diff on the tested 005 implementation, not the change that first repairs #1455.

## Important final approaches not spelled out identically in the plan

1. **Ownership through fresh symbols:** equivalent intent to scoped operands, but without a dedicated wrapper type.
2. **Two graph views:** root nullness and nested structural ownership use separate edges so occurrence annotations affect only the root.
3. **Status-bearing results:** COMPLETE/unknown/contradictory classification is explicit through `Solution` and call-result types rather than implied by the presence of a `Type` value.
4. **Validation before and after construction:** declaration checks remain independent of cycles/candidate merging, followed by certification of substituted structured relations.
5. **Diagnostic evidence is not success:** shaped contradictory evidence can support ordinary root diagnostics without being reused as a successful nested solution.
6. **Observable target certification:** an unrelated incomplete unused parameter need not prevent publication of a fully certified functional target.
7. **Stable final context:** completed targets and owning-lambda return contexts are retained separately from reusable results to avoid incorrect strengthening during re-entry.
8. **Array flow precision:** 004 extends the original solver work into ordinary dataflow rather than emitting special-case solver dereference warnings.

## Permanent acceptance evidence

005 adds ten direct compiler-backed solver methods in `ConstraintSolverImplTests`, with a callback-execution assertion so missing execution cannot silently pass. They inspect site identity, complete nested supplier structure and fixed symbols, enclosing arguments, covariant order independence, contradiction status, unused defaults, source isolation, late dependencies, nullable model precedence, and missing-shape incompleteness.

Two additional `GenericMethodTests` methods cover:

- `inferredEnclosingTypeNullnessSurvivesInnerClassIdentity`
- `unusedTypeVariablesDoNotEraseCertifiedLambdaTargets`

02 and combined 03 now also include PR #1943's unchanged #1942 JDK-model regression and a separate nullable/non-null payload control test. Both pass on 01+02; the full stack passes them with the repair visitor deleted and no new production changes. See `ISSUE-1942-VALIDATION.md`.

Existing #1291, #1585, #1919/#1930, #1455, Caffeine, annotation-override, wildcard, diamond, and diagnostic tests remain. 006 renames `nestedGenericMethodRepairPreservesTopLevelNullability` to `nestedGenericMethodSubstitutionPreservesTopLevelNullability` without changing assertions.

The older failed repair-disabled #1455 experiment is still useful history: it demonstrates why deletion was unsafe before 005. It does not describe the current stack, where the supplier case and full module pass with the visitor deleted.

## Additional acceptance case from the #1932 discussion — Patch 007

The new case in https://github.com/uber/NullAway/issues/1932#issuecomment-6080536601 is now resolved. #1947 initially emitted none of its four expected direct/nested wildcard diagnostics on our pre-007 stack. Full-type preservation did not by itself impose the missing fixed-bound containment obligation.

007 defers non-null-bounded `? extends U` obligations until root inference evidence is complete. Dedicated fixed-root lower evidence and root subtype edges preserve projected-formal barriers; fixed variables remain symbolic rather than becoming globally nullable. A distinct deferred-proof exception owns reporting and caching at the actual failing call, preserving ordinary scalar diagnostic behavior. This is an additional constraint kind, not the scalar UNCONSTRAINED marker/API approach in open PR #1834 and not a restoration of the obsolete visitor.

Eleven integration methods and three direct solver methods cover the issue, projection/markedness/intersection controls, both independently reproduced review findings, evidence order, repeated solves, and modeled fixed-source bounds. See `ISSUE-1947-VALIDATION.md` for the full map and validation. The previous gap assessment is retained as historical failure evidence there, not as a remaining limitation.

## Remaining limitations and unperformed plan gates
- Raw, captured, unresolved, or feature-disabled structures may remain explicitly incomplete; they are not advertised as complete reusable substitutions.
- Broader common-supertype/Java LUB inference remains outside this implementation. The pre-existing mixed-invariant false positive is characterized as a known limitation, not claimed correct or solved.
- The plan also preserves javac's nominal shapes and #1455 negative expectations. Broadening candidate shapes to fix valid mixed invariant consumers needs an explicit semantics/test-policy justification, not silent removal of warnings.
- Nullable handler precedence now has a direct solver regression. No new external model-provider service-loading integration was added.
- Operation-level source isolation is tested; deep immutability/detachment of every possible compiler-owned graph is not established by these tests.
- Dedicated state-inspection tests for all cache generation/re-entry combinations, a supported JDK/Error Prone matrix, and systematic parent-vs-new performance benchmarks were not performed.
- Existing ignored tests remain ignored; the local pass count does not certify those cases.

Therefore the central rewrite and obsolete-visitor removal are delivered, but the entire cross-product of the plan's acceptance/performance matrix is not claimed exhaustively proven.

## Issue and patch clarification

- **#1291:** standalone 01 identity stage.
- **#1585:** full nested type evidence developed in 02 and completed/certified for supported shapes by 005; 004 supplies observable array behavior.
- **#1932:** broader solver rewrite, API adaptation, motivating acceptance, and obsolete-visitor removal represented across the staged stack.
- **#1947:** new #1932 acceptance case resolved by 007, with deferred wildcard obligations and fourteen permanent regression methods.
- **#1930:** already merged in baseline `b8e88803`; preserved and extended, not separately solved by a new patch.
- **#1921:** unrelated merged Commons Lang dependency update.

03 is a delivery alternative for 01+02, not a third architectural stage. 005 and 006 are intentionally separate correctness and cleanup stages, consistent with the plan's “remove repairs last” direction.

## Validation and review limits

005 independently and 006 on top both passed:

```text
./gradlew :nullaway:test --rerun-tasks --no-build-cache
./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
```

Latest full-stack reports through 007, including #1942 and fourteen #1947/review/solver regression methods: **1,201 tests recorded, zero failures/errors, 21 skipped**. The exported stack was applied to a fresh worktree and matched the tested files byte-for-byte, including the visitor deletion. Independent reviews checked certification, contexts/caches, unused-target publication, and cleanup; concrete findings were repaired and tested, with no remaining P0/P1 blocker found in the reviewed paths.

This document update changes documentation only. It does not claim that finite tests and static reviews prove universal soundness, compatibility, or performance. See `COMPLETION-005-006.md` and `ISSUE-1947-VALIDATION.md` for the detailed implementation/test reports.
