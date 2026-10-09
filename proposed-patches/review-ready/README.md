# Review-ready generic inference stack

This is the recommended submission packaging. It replaces the historical seven-stage packaging for review purposes without changing implementation behavior or test expectations. The payloads now include the `spotlessApply` output from the three stacked branches.

## Apply order

Start from `b8e88803a2bb2287c08d9f85361c9317222387d4`:

```text
01-certified-call-specific-inference
    → 02-inferred-array-flow
    → 03-remove-obsolete-repair
```

These patches are stacked, not independent alternatives. Do not apply them on top of the historical 01–07 stack. Each Markdown file contains a complete fenced `diff` payload; extract it before `git apply`.

| Patch | Review scope | Size |
|---|---|---|
| [01-certified-call-specific-inference.md](01-certified-call-specific-inference.md) | Final ownership, full-type evidence, attributed shapes, certification, lambda/cache/diagnostic integration, fixed-bound wildcard obligations, and their permanent tests | 11 files; 5,808 additions / 281 deletions |
| [02-inferred-array-flow.md](02-inferred-array-flow.md) | Enhanced array-component consumption, transfer re-entry prevention, refinement/invalidation precision, and fourteen regression methods | 5 files; 657 additions / 30 deletions |
| [03-remove-obsolete-repair.md](03-remove-obsolete-repair.md) | Delete the visitor, wrapper and dedicated guard; update repair-specific test wording without changing assertions | 3 files; 7 additions / 421 deletions |

01 is necessarily the largest unit because the public solver contract and its consumers change together. Splitting its historical development steps would ask reviewers to review scalar/intermediate APIs and reconciliation code later replaced; no extra compatibility shim was introduced merely to create smaller PRs. The small structural branch cleanup is folded into its final form rather than submitted as a separate rewrite of new code.

02 does not revise 01's solver API. 01 already infers and certifies array structure; 02 makes ordinary expression/dataflow consumers use it. 03 remains last because removing fallback repair is a separate behavioral change, even after the complete inference path is implemented.

## Evidence at each boundary

Both commands were run sequentially and uncached at **each** logical boundary before formatting. After `spotlessApply` on all three branches and rebasing the stack, both commands were rerun on the final formatted branch:

```sh
./gradlew :nullaway:test --rerun-tasks --no-build-cache
./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
```

| Applied stack | Tests recorded | Failures / errors | Skipped | Self-check |
|---|---:|---:|---:|---|
| 01 | 1,187 | 0 / 0 | 21 | Pass |
| 01 + 02 | 1,201 | 0 / 0 | 21 | Pass |
| 01 + 02 + 03 | 1,201 | 0 / 0 | 21 | Pass |

The initial regrouped payloads matched all **15 baseline-relative changed paths** of the historical 01–07 tree byte-for-byte. After formatting, the final rebased branch was compared with an independently Spotless-formatted checkout of its original final commit: **all 433 tracked files match byte-for-byte**. The refreshed payloads are exported from the current branch commits. No existing assertion or diagnostic marker was changed, no skipped test was toggled, and no production suppression was added by formatting. Source diffs pass `git diff --check`.

Current branch tips: `call-specific-full-type-inference` at `489bea6c`, `inferred-array-flow` at `0e41959a`, and `remove-obsolete-inference-repair` at `f10c71b2`. Each is the direct child of its preceding stage; commit subjects and required trailers were preserved.

## Issues and documented reasoning

01 includes #1291, #1585, the complete/certified #1932 rewrite, the unchanged PR #1943 regression for #1942 with companion controls, and the #1947 case added to the #1932 discussion. 02 provides observable array-flow behavior. 03 delivers obsolete-visitor removal after replacement validation. #1930 is already part of the baseline, not a newly solved issue.

- [PR-WRITEUPS.md](PR-WRITEUPS.md): titles, descriptions of at most six sentences, and beginner explanations.
- [deviation.md](deviation.md): comparison with the supplied plan, historical-to-new patch mapping, and limits.
- [REVIEW-AND-ASSERTION-RATIONALE.md](REVIEW-AND-ASSERTION-RATIONALE.md): independent architecture review, projection-assertion rationale, and comparison with the old #1473/#1574 repair approach.
- [DIAGRAM-01-INFERENCE.md](DIAGRAM-01-INFERENCE.md): inference ownership, certification, lifecycle, and deferred wildcard obligations.
- [DIAGRAM-02-ARRAY-FLOW.md](DIAGRAM-02-ARRAY-FLOW.md): enhanced component types and flow precision.
- [DIAGRAM-03-REPAIR-REMOVAL.md](DIAGRAM-03-REPAIR-REMOVAL.md): guarded repair fallback before deletion and diagnostic overlay afterward.

Detailed historical regressions/results remain in the parent folder's `ISSUE-1942-VALIDATION.md` and `ISSUE-1947-VALIDATION.md`; their numbered stages refer to the old packaging. This folder's apply order and diagrams are the current review presentation.

## Bounded claims

Explicitly incomplete raw/captured/unresolved/feature-disabled paths, the pre-existing mixed-invariant/common-supertype false positive, and the unperformed supported-version/performance matrix remain documented limits. The fixed-bound obligation is not a wholesale implementation of every rule in open PR #1834. A type-map status of COMPLETE certifies represented inference constraints, not every ordinary argument-compatibility check or every possible compiler type graph. Passing tests and independent source review are not universal soundness proofs.
