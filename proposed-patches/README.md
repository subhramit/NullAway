# Generic inference patch stack through 006

005 and 006 are now implemented and validated. 006 physically removes the old nested-substitution repair visitor after the new solver path passes without its output.

## Apply order

Start from master at `b8e88803a2bb2287c08d9f85361c9317222387d4`:

```text
01 → 02 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006
```

Or use 03 for the original combined base:

```text
03 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006
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

Each Markdown patch contains a complete fenced `diff` payload; extract that block before using `git apply`.

## Current evidence

See [COMPLETION-005-006.md](COMPLETION-005-006.md) for the result contract, new tests, cleanup scope, validation, and limits.

Both 005 independently and 006 on top passed the full main-module tests and self-check with `--rerun-tasks --no-build-cache`. Final reports: **1,185 tests, zero failures/errors, 21 skipped**.

Independent reviews found a missing enclosing-type constraint, which was repaired and directly tested. Final reviews found no concrete P0/P1 blockers in the changed certification/lifecycle/cleanup paths. The exported stack is checked against the tested source tree.

## What changed from the previous removal verdict?

The earlier repair-disabled experiment failed on the valid `issue1455` supplier call. 005 now recovers a Java shape and retains the fixed outer variable's nested nullable annotation, with a direct solver assertion of the result. The motivating and repair regression gates pass without the visitor; 006 then deletes it rather than leaving an experiment-only bypass or suppression.

The scalar public result enum was already removed in the earlier stage. General annotation-preserving and explicitly uncertified diagnostic/fallback utilities remain; they are not the obsolete visitor.

## Documentation

- [PR-WRITEUPS.md](PR-WRITEUPS.md): PR titles, short descriptions, and beginner explanations.
- [ISSUE-COMPLETENESS-AND-TEST-MAP.md](ISSUE-COMPLETENESS-AND-TEST-MAP.md): baseline requirements and regression map, with updates for later stages.
- [COMPLETION-005-006.md](COMPLETION-005-006.md): current completion/removal evidence.
- [deviation.md](deviation.md): comparison with the supplied Codex plan, including current-stage updates.
- [SUPPRESSWARNINGS-AUDIT.md](SUPPRESSWARNINGS-AUDIT.md): suppression purpose and risks.
- [REMAINING-WORK.md](REMAINING-WORK.md): historical pre-005 gates and remaining broader limits.
- [DIAGRAM-0001-CALL-SCOPES.md](DIAGRAM-0001-CALL-SCOPES.md)
- [DIAGRAM-0002-INFERENCE-AND-LIFECYCLE.md](DIAGRAM-0002-INFERENCE-AND-LIFECYCLE.md)
- [DIAGRAM-0004-ARRAY-DATAFLOW.md](DIAGRAM-0004-ARRAY-DATAFLOW.md)
- [DIAGRAM-0005-CERTIFIED-INFERENCE.md](DIAGRAM-0005-CERTIFIED-INFERENCE.md)
- [DIAGRAM-0006-REPAIR-REMOVAL.md](DIAGRAM-0006-REPAIR-REMOVAL.md)

## Bounded claims

Raw, captured, unresolved, or feature-disabled structures can still be explicitly incomplete. They are not published as complete successful substitutions just because a `Type` value exists. Some root diagnostic evidence can be retained separately as `InferencePartial`; it does not certify nested calls or references.

The pre-existing mixed-invariant/common-supertype limitation remains characterized rather than implementing broader Java LUB inference. A supported JDK/Error Prone matrix and systematic performance study were not performed. Passing finite tests and reviews does not prove that every possible false positive or false negative has been eliminated.

## Historical material

`FRESH-REVIEW.md`, `REPAIR-REPORT.md`, `REVIEW-REPRODUCERS.md`, and `archive-before-repair/` record earlier implementations and findings. Earlier statements that the visitor must remain describe the pre-005 stack; the current removal result is documented in `COMPLETION-005-006.md`. Do not apply archived patch versions.
