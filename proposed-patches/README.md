# Generic inference patch delivery

**Recommended review packaging:** use the three-patch stack in [review-ready/README.md](review-ready/README.md). It presents the final inference design once, isolates array-flow consumers, and removes the visitor last. The regrouped stack was byte-identical before formatting; current exports include Spotless output and match the three branch commits exactly. Every logical boundary passed the uncached main-module suite and self-check, and the final formatted branch was rerun successfully. Do not apply both packaging forms.

## Historical seven-stage packaging through 007

005 and 006 certify full inferred types and physically remove the old nested-substitution repair visitor. 007 now also resolves the #1947 acceptance case with deferred wildcard obligations, projection-safe root evidence, and owning-site diagnostics.

## Apply order

Start from master at `b8e88803a2bb2287c08d9f85361c9317222387d4`:

```text
01 → 02 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006 → 007
```

Or use 03 for the original combined base:

```text
03 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006 → 007
```

03 combines **only 01+02**; it is not a combined patch for the new stages. Do not apply it after that pair. The quality cleanup is included before 005 to match the tested/exported baseline.

### Current patch files

- [0001-call-specific-inference-variables.md](0001-call-specific-inference-variables.md) — standalone #1291 identity stage.
- [0002-reconcile-full-inferred-types.md](0002-reconcile-full-inferred-types.md) — original repaired structured-evidence stage.
- [0003-complete-solution.md](0003-complete-solution.md) — alternative combined 01+02 delivery.
- [0004-inferred-array-element-nullness.md](0004-inferred-array-element-nullness.md) — inferred array reads and flow propagation.
- [CODE-QUALITY-FOLLOWUP.md](CODE-QUALITY-FOLLOWUP.md) — behavior-preserving structural-case cleanup.
- [0005-certified-full-type-inference.md](0005-certified-full-type-inference.md) — attributed shapes, explicit result certification, complete consumers, context recovery, and direct solver tests.
- [0006-remove-obsolete-substitution-repair.md](0006-remove-obsolete-substitution-repair.md) — visitor/wrapper/guard deletion and test wording maintenance.
- [0007-preserve-fixed-bounds-through-wildcard-inference.md](0007-preserve-fixed-bounds-through-wildcard-inference.md) — #1947 fixed-bound wildcard obligations, root projection barriers, owning-site diagnostics, and fourteen regression methods.

Each Markdown patch contains a complete fenced `diff` payload; extract that block before using `git apply`.

## Current evidence

See [COMPLETION-005-006.md](COMPLETION-005-006.md) for the result contract, new tests, cleanup scope, validation, and limits.

005 independently, 006 on top, and the final stack through 007 passed the full main-module tests and self-check with `--rerun-tasks --no-build-cache`. Latest full-stack reports, including #1942 and all fourteen new 007 regression methods: **1,201 tests, zero failures/errors, 21 skipped**. Both export alternatives match all fifteen tested changed paths byte-for-byte.

Independent reviews found a missing enclosing-type constraint, which was repaired and directly tested. Final reviews found no concrete P0/P1 blockers in the changed certification/lifecycle/cleanup paths. The exported stack is checked against the tested source tree.

## What changed from the previous removal verdict?

The earlier repair-disabled experiment failed on the valid `issue1455` supplier call. 005 now recovers a Java shape and retains the fixed outer variable's nested nullable annotation, with a direct solver assertion of the result. The motivating and repair regression gates pass without the visitor; 006 then deletes it rather than leaving an experiment-only bypass or suppression.

The scalar public result enum was already removed in the earlier stage. General annotation-preserving and explicitly uncertified diagnostic/fallback utilities remain; they are not the obsolete visitor.

## Documentation

- [PR-WRITEUPS.md](PR-WRITEUPS.md): PR titles, short descriptions, and beginner explanations.

- [ISSUE-COMPLETENESS-AND-TEST-MAP.md](ISSUE-COMPLETENESS-AND-TEST-MAP.md): baseline requirements and regression map, with updates for later stages.
- [COMPLETION-005-006.md](COMPLETION-005-006.md): current completion/removal evidence.
- [deviation.md](deviation.md): comparison with the supplied Codex plan, including current-stage updates.
- [ISSUE-1942-VALIDATION.md](ISSUE-1942-VALIDATION.md): unchanged PR #1943 regression, companion controls, baseline failure, and passing 02/final-stack evidence.
- [ISSUE-1947-VALIDATION.md](ISSUE-1947-VALIDATION.md): #1947 resolved by 007, permanent regression map, independently reproduced/repaired findings, and final validation.
- [SUPPRESSWARNINGS-AUDIT.md](SUPPRESSWARNINGS-AUDIT.md): suppression purpose and risks.
- [REMAINING-WORK.md](REMAINING-WORK.md): historical pre-005 gates and remaining broader limits.
- [DIAGRAM-0001-CALL-SCOPES.md](DIAGRAM-0001-CALL-SCOPES.md)
- [DIAGRAM-0002-INFERENCE-AND-LIFECYCLE.md](DIAGRAM-0002-INFERENCE-AND-LIFECYCLE.md)
- [DIAGRAM-0004-ARRAY-DATAFLOW.md](DIAGRAM-0004-ARRAY-DATAFLOW.md)
- [DIAGRAM-0005-CERTIFIED-INFERENCE.md](DIAGRAM-0005-CERTIFIED-INFERENCE.md)
- [DIAGRAM-0006-REPAIR-REMOVAL.md](DIAGRAM-0006-REPAIR-REMOVAL.md)
- [DIAGRAM-0007-WILDCARD-OBLIGATIONS.md](DIAGRAM-0007-WILDCARD-OBLIGATIONS.md)

## Bounded claims

**#1947 acceptance is now covered:** the case added in [this #1932 comment](https://github.com/uber/NullAway/issues/1932#issuecomment-6080536601) is resolved by 007 and included in the latest suite. This does not import or claim the complete unrelated wildcard overhaul of open PR #1834; see `ISSUE-1947-VALIDATION.md`.

Raw, captured, unresolved, or feature-disabled structures can still be explicitly incomplete. They are not published as complete successful substitutions just because a `Type` value exists. Some root diagnostic evidence can be retained separately as `InferencePartial`; it does not certify nested calls or references.

The pre-existing mixed-invariant/common-supertype limitation remains characterized rather than implementing broader Java LUB inference. A supported JDK/Error Prone matrix and systematic performance study were not performed. Passing finite tests and reviews does not prove that every possible false positive or false negative has been eliminated.

## Historical material

`FRESH-REVIEW.md`, `REPAIR-REPORT.md`, `REVIEW-REPRODUCERS.md`, and `archive-before-repair/` record earlier implementations and findings. Earlier statements that the visitor must remain describe the pre-005 stack; the current removal result is documented in `COMPLETION-005-006.md`. Do not apply archived patch versions.
