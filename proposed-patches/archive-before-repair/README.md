# Generic inference patch set — approval withdrawn

> **Fresh review: request changes.** The original suite passes uncached, but new regression probes reproduce a checker crash, suppressed independent diagnostics, and unresolved false negatives. See [FRESH-REVIEW.md](FRESH-REVIEW.md) and [REVIEW-REPRODUCERS.md](REVIEW-REPRODUCERS.md). The implementation patch payloads below are unchanged; their historical approval labels are superseded.

## Previous handoff (superseded)

The complete solution is available in two equivalent forms:

### Staged series

Apply these in order:

1. [`0001-call-specific-inference-variables.md`](0001-call-specific-inference-variables.md)
2. [`0002-reconcile-full-inferred-types.md`](0002-reconcile-full-inferred-types.md)

### Single combined patch

Alternatively, apply only:

- [`0003-complete-approved-solution.md`](0003-complete-approved-solution.md)

Patch 3 is the complete combined diff from `master` at `b8e88803`. Do not apply it after Patches 1 and 2.

Each Markdown file contains the entire patch in one fenced `diff` block. Extract that block before passing it to `git apply`.

## Supplied-patch approval status

None of the originally supplied patches is approved unchanged:

- Supplied patch 1 had an Error Prone `ReferenceEquality` build failure. The approved Patch 1 is the corrected complete version.
- Supplied patch 2 had `TypeEquals` build failures and used first-bound selection, causing order dependence and declaration-bound false negatives.
- Supplied patch 3 removed `NestedTypeVarSubstitutionRepairVisitor` before its replacement was complete.
- Supplied patch 4 cached nested calls whose poly-expression types were not safely published under provisional lambda inference.

The approved files contain the complete replacements, not partial amendment snippets.

## What the solution fixes

- #1291 call-specific inference variables for repeated generic calls, diamonds, and generic references.
- #1585 complete nested nullness evidence for inferred type arguments.
- Full structured lower/upper-bound reconciliation rather than first-bound selection.
- Semantic bound deduplication and terminating mutation-driven fixed points.
- Declaration bounds, dependent bounds, inherited generic views, fixed caller variables, and receiver-instantiated method bounds.
- Receiver-instantiated bounds for generic method references.
- Per-variable marked/unmarked declaration-bound semantics, including mixed class/constructor diamond inference.
- Conservative wildcard, capture, recursive, and cyclic fallback behavior.
- Covariant array merging independent of argument order.
- Invariant conflict handling without new inference-failure false positives.
- Nested lambda/method-reference type publication.
- Monotonic cache invalidation under provisional lambda inference.
- Completed method-reference result invalidation and republishing.
- Deduplicated nested declaration-bound diagnostics.

The existing repair visitor remains as a conservative fallback. It is not removed until all javac attribution shapes can be represented directly without regressions.

## Import and style audit

New code uses imports instead of avoidable qualified names. Remaining `com.sun.tools.javac.util.List` qualifications predate this work and are required where the same file imports `java.util.List`; Java provides no import aliasing.

All new non-trivial methods have Javadoc. No top-level `@Nullable` local annotations were added.

## Test integrity

Tests were not weakened to force success:

- No existing expected diagnostic was removed or relaxed.
- Existing #1455 coverage was strengthened with the reverse argument order.
- All other test changes are additive.
- Exact-diagnostic tests verify no extra inference-failure or duplicate declaration-bound reports.
- Positive and negative controls cover nullable/non-null arrays, good/bad inherited bounds, marked/unmarked declarations, fixed caller variables, receiver bounds, method references, nested lambdas, and cache re-entry.

## False-positive and false-negative review

Independent reviews specifically challenged and led to fixes for:

- nontermination from identity-only bound deduplication;
- distinct inference-variable and wildcard/capture comparisons;
- cycle-derived partial substitutions;
- inherited and fixed-variable declaration-bound false negatives;
- stale method-reference results;
- duplicate declaration-bound reports;
- receiver-instantiated call and method-reference bounds;
- `@NullUnmarked` generic method and generic class bounds;
- mixed class-owned and constructor-owned diamond variables;
- Caffeine wildcard/model behavior.

The final independent blocker-only review returned `APPROVED`.

## Validation

Run sequentially on the exact exported tree:

```text
./gradlew :nullaway:test
BUILD SUCCESSFUL

./gradlew :nullaway:buildWithNullAway
BUILD SUCCESSFUL
```

The patch payloads were also extracted and applied in order to a fresh worktree before handoff.
