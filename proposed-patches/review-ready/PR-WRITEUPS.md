# PR titles and descriptions for the review-ready stack

Each description is at most six sentences. Submit these sequentially; do not mix the old seven-stage stack with this packaging.

## PR 01

### Title

Certify call-specific full-type inference

### Description

Use fresh call-specific inference symbols and retain complete annotated type evidence instead of scalar-only answers.
Recover javac's Java shapes, reconcile dependent evidence, and distinguish complete, incomplete, and inconsistent solutions after bound validation.
Apply full substitutions to complete calls, references, and diamonds while preserving fixed symbols, explicit occurrences, model authority, and enclosing types.
Preserve lambda/cache lifecycle and diagnostic ownership, including deferred fixed-bound wildcard obligations that cannot be hidden by nested calls.
Add the motivating and review-discovered regressions, the unchanged #1942 regression, and direct solver assertions without weakening existing expectations.
The uncached main-module suite and self-check pass, with the pre-existing repair fallback retained for removal in the last PR.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

A non-null box can hold a nullable value, and different calls to one generic method can infer different kinds of box. This patch keeps those facts separate and checks a full proposed type before reusing it. A caller's type variable stays symbolic instead of being replaced by its broad upper bound or declared nullable everywhere. That also lets a non-null-only API reject an unsafe generic collection even when another generic call wraps it.

## PR 02

### Title

Propagate inferred array nullness through flow

### Description

Use enhanced inferred array types when checking direct, var-local, multidimensional, indexed-argument, and enhanced-for element reads.
Carry component nullness into ordinary dataflow rather than adding solver-specific dereference warnings.
Prevent transfer-driven type recovery from restarting analysis without disabling ordinary lambda refinement.
Refine stable, faithfully represented element assignments and invalidate overlapping or rebound dependencies while retaining unaffected guards and modeled postconditions.
Add fourteen array-flow regressions, and keep the solver API from the preceding PR unchanged.
The full uncached main-module suite and self-check pass.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

The solver may know that an array holds nullable elements, but a read must actually use that information. This patch delivers it to the normal checker and keeps valid facts established by null checks or stable assignments. A write through an alias or a changed index can invalidate those facts; an unrelated statement should not. It does not attempt complete alias or method-effect analysis.

## PR 03

### Title

Remove obsolete nested substitution repair

### Description

Delete the old nested-substitution repair visitor, wrapper, and dedicated re-entry state after replacement acceptance passes.
Keep complete inference substitutions unchanged and retain general annotation-preservation and explicit partial-diagnostic utilities.
Update repair-specific test wording and rename one method without changing assertions or expected diagnostics.
The full uncached main-module suite and self-check pass with the visitor physically removed, recording 1,201 tests with zero failures/errors and 21 skipped.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

The old visitor repaired annotations after javac substituted generic types, using selected argument shapes. The new solver owns full type evidence and checks it before successful reuse. This last patch removes that specific repair mechanism, not every utility that copies or preserves annotations. Keeping deletion separate makes it possible to verify that the replacement works before removing the fallback.
