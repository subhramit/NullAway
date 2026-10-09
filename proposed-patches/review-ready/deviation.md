# Final approach versus the supplied issue-1932 plan

Reference: the supplied `issue-1932-plan.md`, “Track call-specific full-type bounds on top of issue-1919.” This compares the delivered implementation and acceptance evidence, not an unprovided full Codex transcript.

## Packaging changed; final code did not

Current review order:

```text
01-certified-call-specific-inference
    → 02-inferred-array-flow
    → 03-remove-obsolete-repair
```

| Historical artifact | Current review unit |
|---|---|
| Old 01 call identities | New 01, presented directly with the final result API |
| Old 02 structured evidence and diagnostic fixes | New 01, with final certified construction/consumption rather than an intermediate candidate API |
| Old CODE-QUALITY-FOLLOWUP | New 01's final branch layout; no separate rewrite of newly added code |
| Old 005 attributed shapes and certification | New 01 |
| Old 007 fixed-bound wildcard obligations | New 01, including the independently reproduced/fixed projection and diagnostic problems |
| Old 004 array consumers/dataflow | New 02 |
| Old 006 repair removal | New 03, intentionally last |
| Old combined 03 | Historical alternative for old 01+02; not applied alongside this new stack |

Before formatting, the final three-patch tree matched all fifteen historical baseline-relative changed paths byte-for-byte, and every boundary passed the uncached main-module suite and self-check. The branch commits and exports now include Spotless output; the final rebased branch matches all 433 tracked files of a separately Spotless-formatted original-final checkout, and its suite and self-check were rerun successfully. No new implementation behavior, compatibility shim, removed expectation, or altered skipped test was introduced to manufacture these boundaries.

## Stage 0 — Baseline and workflow

| Plan | Delivered approach | Deviation / reason |
|---|---|---|
| Start a child branch from issue-1919 at `2490d1e3` | Base all exports on master `b8e88803a2bb2287c08d9f85361c9317222387d4` | #1930 had merged the needed parent work; the old branch-divergence premise no longer applies |
| Rebase the child after the parent lands | Disposable worktrees, Markdown patch exports, no implementation branches or commits | User requested artifact-only delivery |
| Compare baseline timings and regressions | Targeted regressions, full module suites, self-checks, and fresh export application | No systematic parent-versus-new performance comparison |

## Stage 1 — Per-call ownership

| Plan | Final approach in new 01 | Assessment |
|---|---|---|
| `InferenceVariable(Element, ExpressionTree)` with site identity | `InferenceVariable(Element, Tree)` | Broader site type; javac AST equality is identity-based |
| Keep different calls' variables independent | Fresh symbols per declaration/site, replaced before constraint traversal | Same intent, with shared graph connections but distinct ownership |
| Scoped ownership throughout nested operands | Fresh symbols embedded in javac types instead of a `ScopedType` wrapper | Representation deviation; annotated occurrence tests guard ownership |
| Fixed caller/receiver variables and explicit witnesses remain fixed | Only registered fresh symbols are inferred; caller symbols remain symbolic | Direct supplier/fixed-symbol assertions protect this |
| Initial scalar-only identity stage | No scalar-only intermediate public API in the review stack | Historical development is folded into the final architecture to avoid repeated review |

## Stage 2 — Full shapes, bounds, and certification

| Plan | Final approach in new 01 | Assessment / limit |
|---|---|---|
| One per-problem inference session | Solver-owned registration/graph state plus scoped consumer maps and publication guards | No single `InferenceSession` class; coordinated structures remain |
| Register each variable with javac's instantiated shape | Bulk registration accepts instantiated bounds, per-variable declaration authority, and shape maps; compatibility overload retained | Different API, same attributed-shape input responsibility |
| Match declaration and attributed signatures | `InferenceTypeShapes` traverses results, parameters, throws, arrays, arguments, enclosing types, and supported call/reference adaptations | Conflicting/unresolved evidence is not complete |
| Distinguish lower, upper, and equality requirements | Lower/upper bounds and directed edges; equality encoded in both directions | No separate equality list |
| Retain full annotated types rather than scalar root answers | `Solution(inferredTypes, incompleteVariables, inconsistentVariables)` | Explicit validity status extends the proposed bare map |
| Revisit dependent constraints to a fixed point | Mutation-version passes, semantic fingerprints, active comparison and recursion guards | Not a narrowly queued dependency worklist; performance matrix remains unperformed |
| Validate every final substituted bound | Independent declaration checking plus substituted lower/upper/declaration/edge validation | Supported represented constraints certified; unknown remains incomplete |
| Preserve fixed variables and root-use annotations | Symbolic fixed leaves, separate scalar versus structural ownership, occurrence overrides retained during substitution | Upper bounds are checking evidence, not automatic replacement types |
| Model/receiver/declaration authority | Receiver-instantiated bounds, declared owner/index models, per-variable markedness | Method-owner modeled fixed-source obligation is directly tested; no dedicated class-owner obligation fixture |
| Immutable/detached reconstruction | Existing metadata/capture helpers retained; operation-level source nonmutation tested | Result collections are shallow immutable snapshots, not proof of deep detachment of every javac graph |
| Infer unused variables without changing observable types | Representative nonrecursive bounds when appropriate; recursive/unresolved unused shapes remain incomplete | Independently certified functional targets may survive an unrelated unused variable |

### Additional #1947 acceptance requirement

The case in https://github.com/uber/NullAway/issues/1932#issuecomment-6080536601 initially emitted none of its four expected direct/nested diagnostics. Retaining full return types alone did not impose its missing wildcard contract.

New 01 therefore includes the old 007's deferred `? extends U` obligation when U's effective bound excludes null. Root-compatible fixed lower evidence and root subtype edges retain symbolic nullable-admitting provenance without converting T globally to nullable-T. Annotated destination occurrences block that root evidence while keeping nested structural constraints. A distinct owning-site exception preserves the failing call's diagnostic path/cache without rewriting established contextual scalar policy.

This is a general constraint kind, not a `List.copyOf`/identity/diamond syntax special case and not the scalar UNCONSTRAINED-marker approach of open PR #1834. Eleven source-level methods and three direct solver methods cover the case, projections, bound chains, markedness, intersections, method models, ordering/repeated solves, siblings and suppression-aware attribution.

## Stage 3 — Consumers, lambda environments, and caches

| Plan | Final approach | Assessment / deviation |
|---|---|---|
| Build on issue-1919 provisional lambda handling | Preserve merged #1930 groundwork, owning-lambda lookup, scoped return targets and final call contexts | Parent fix is preserved rather than independently rewritten |
| Complete substitutions for all complete consumers | Complete invocations/signatures, references and diamond arguments consume full bindings, preserving enclosing types | No dedicated new thrown-type solver fixture |
| Final nested functional targets | Staged targets published after solving; references independently checked after an enclosing failure | Relevant binding certification can publish one target without certifying an incomplete root call |
| Do not reuse provisional solutions | Monotonic cache restrictions after provisional-lambda participation; non-publishing analysis preserves completed reference results | Not an exhaustive generation/re-entry state matrix |
| Publish only successful solutions | Separate `InferenceSuccess` and `InferencePartial`; partial types are not reusable success | Guarded root diagnostic evidence retention is an intentional diagnostic-placement deviation |
| Preserve diagnostics and suppressions | Site provenance, real AST ancestors, independent sibling reports | Exact location/count and suppression regressions retained |
| Inferred nullable array elements observable | New 02 connects enhanced component types to direct/var/dimensional/indexed/enhanced-for reads and normal dataflow | Separate consumer review unit; all fourteen tests travel with it |
| Maintain flow precision | Scoped no-restart recovery, faithful indices, overlap/dependency invalidation, existing model postconditions | Not complete alias/call-effect analysis; existing call refinement policy retained |

## Stage 4 — Remove obsolete repair last

New 03 deletes the whole 351-line `NestedTypeVarSubstitutionRepairVisitor`, its wrapper, dedicated guard, and cleanup entry. It also updates repair-specific wording and renames one test without changing expected diagnostics. General substitution, metadata, wildcard/capture and explicitly incomplete diagnostic utilities remain because they have other uses.

Complete inference already returns before the guarded repair fallback in new 01. Nevertheless removal is a separate behavioral gate, not merely an unused-file deletion: new 03 is tested with the implementation physically absent. The valid supplier regression that previously blocked deletion remains tested, and #1942's upstream modeled-return regression passes without adding its repair-based production fix.

## Remaining limits and unperformed gates

- Raw/captured/unresolved/feature-disabled paths can remain explicitly incomplete. Neither full inference nor the specialized wildcard proof establishes exhaustive capture correctness.
- The pre-existing mixed-invariant/common-supertype false positive remains characterized, not silently reclassified as correct or fixed by broader Java LUB inference.
- The projection direct fixture tests Object shapes and root barriers. It does not itself establish every nested-structure projection interaction; related integration/declaration-bound tests cover some, not every cross-product.
- No new external model-provider service-loading integration or dedicated class-owner fixed-source obligation fixture.
- Operation-level source isolation is tested; deep immutability of every returned javac graph is not proved.
- No supported JDK/Error Prone matrix, exhaustive cache-state inspection, or systematic performance benchmark.
- Existing skipped tests remain skipped.

## Validation and independent judgement

New 01: **1,187 tests**. New 01+02 and full 01+02+03: **1,201 tests** at each stage. All stages report **zero failures/errors, 21 skipped**, and pass the uncached self-check. The two removed/re-added areas required only context adjustment during reconstruction, not new behavior; exported patches are verified by fresh application.

Fresh independent review supported the three boundaries and found no concrete blocker in the final assertion or reviewed architecture. It distinguished generic provenance/obligation solving from the old #1473/#1574 argument-correspondence repair visitor. A typed unified evidence representation could be a future maintainability refactor, but no demonstrated smaller or more correct replacement justified changing the net code during this packaging pass.

These are local executed regression gates and bounded source-review conclusions, not universal correctness, compatibility, or performance proofs.
