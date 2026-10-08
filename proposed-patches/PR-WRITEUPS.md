# PR titles, descriptions, and beginner explanations

01 and 02 are stacked architectural PRs. 03 is their combined delivery alternative, not an additional commit. 004 is an incremental behavioral follow-up; the quality patch is included before the newly implemented 005 and 006 in the tested/exported stack. Earlier PR descriptions describe those stages in isolation; current certification and removal stages are described at the end.

Each description below contains at most six sentences. Validation details and remaining requirements are in `REPAIR-REPORT.md`, `ISSUE-COMPLETENESS-AND-TEST-MAP.md`, and `REMAINING-WORK.md`.

## PR 01

### Title

Use call-specific generic inference variables

### Description

Key inferred nullness by the declared type parameter and invocation site so repeated calls to one generic method do not share inference state.
Substitute fresh javac variables per call, diamond, or generic reference, preserving explicit occurrence annotations and modeled bounds.
Project shared solutions back to the correct site and add positive and negative #1291 regressions for repeated calls, deeper nesting, and diamonds.
Preserve the #1930 provisional-lambda cache behavior.
The main module tests and self-check pass, while this first stage intentionally retains scalar results and the existing repair visitor.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

NullAway checks Java source without running it and warns about possible null dereferences. A generic method such as `<T> T id(T value)` can be called with different types and different nullness. Two calls to `id` share the same declared letter `T`, but their inferred answers must be separate. This patch gives each call its own identity so a nullable argument at one call cannot contaminate another call's result.

Diagram: [Before and after call scopes](DIAGRAM-0001-CALL-SCOPES.md).

## PR 02

### Title

Preserve inferred nested nullness and site results

### Description

Return annotated type evidence instead of scalar-only nullness and propagate supported structured constraints to a semantic fixed point.
Separate root-nullness propagation from structural ownership, and validate declaration bounds independently of candidate construction and cycle fallback.
Preserve receiver, library-model, markedness, wildcard, and occurrence-annotation rules while applying nominally aligned annotations and complete method-reference substitutions.
Recover independent failed siblings, preserve completed caches during temporary analysis, and report diagnostics using site provenance and suppression-aware paths.
Add permanent regressions for the review-discovered crashes, missed bounds, references, duplicate reports, and suppression failures, with positive controls and unchanged pre-existing diagnostic expectations.
Full uncached module tests and self-check pass, but the visitor and the characterized common-supertype limitation remain.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

A non-null `Box<@Nullable String>` is a real box whose contents may be null; the box and its contents have different nullness. A single nullable/non-null answer cannot describe both facts, so this stage keeps nested type information. A declaration bound is an API rule that must be checked even if the solver cannot build a complete answer. The patch also makes sure that one failed call does not stop another call or method reference from being checked.

Diagram: [Before and after inference and lifecycle](DIAGRAM-0002-INFERENCE-AND-LIFECYCLE.md).

## Combined PR alternative — Patch 03

### Title

Scope generic inference and preserve nested nullness

### Description

Combine call-specific inference identities, structured nullness evidence, declaration-bound validation, and completed site-result publication in one diff from `b8e88803`.
Include the same implementation and permanent regression coverage as 01 followed by 02.
Preserve explicit annotations and existing diagnostics while fixing the motivating #1291 and #1585 examples and the independently reproduced repair blockers.
The main module suite and self-check pass uncached.
Retain the repair visitor until repair-independent acceptance passes; this combined delivery is not evidence that every requirement of #1932 is complete.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

This combines the previous two explanations: every call gets its own inference identity, and supported nested nullness is preserved and checked. It does not add a third algorithm. Apply 03 instead of 01+02, never after them, and use the same two diagrams.

## PR 004

### Title

Propagate inferred array nullness through element reads

### Description

Use enhanced inferred array types for JSpecify element-read checks instead of relying only on javac's symbol type.
Project component annotations through multidimensional accesses, indexed arguments, and enhanced-for reads while preserving null-check refinements and definite-null store values.
Scope array recovery inside dataflow so it cannot restart analysis recursively, without changing ordinary lambda inference.
Refine only faithfully represented stable array assignments and invalidate facts affected by overlapping writes or rebinding, preserving unrelated guards and library-model postconditions.
Add six array-inference tests and eight precision regressions covering both argument orders, direct and `var` reads, dimensions, guards, aliases, field indices, and legacy mode.
Full uncached module tests and self-check pass with 1,173 tests recorded, while common-supertype inference and repair removal remain follow-up work.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

The solver could already know that an array contains nullable strings, but reading `pick(...)[0]` previously consulted a type that had lost that information. This patch carries the enhanced type into the read and then into the normal flow analysis. A null check or a stable assignment can make one element safe, while writing through an alias or changing the index may invalidate that fact. It does not add a special-case warning in the solver; the ordinary dereference checker receives the right nullness.

Diagram: [Before and after array dataflow](DIAGRAM-0004-ARRAY-DATAFLOW.md).

## Optional quality PR

### Title

Clarify structural constraint case selection

### Description

Express the mutually exclusive variable-to-variable, concrete-lower-bound, and concrete-upper-bound cases with one branch tree.
Keep root-nullness eligibility and structural ownership separate without changing the analysis rules.
Document the intent of direct pair constraint generation.
The cleanup is validated alongside 004 and is independent of that behavioral patch.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

There are three different relationships to record, but only one can apply to this structural pair. The old code used three separate checks; the new branches show that exclusivity directly. This is a readability improvement, not a new inference algorithm, so it needs no architectural before/after diagram.

Patch: [Code-quality follow-up](CODE-QUALITY-FOLLOWUP.md).

## PR 005

### Title

Certify call-specific full-type inference results

### Description

Recover attributed Java shapes for call-specific variables and distinguish complete, incomplete, and structurally inconsistent results explicitly.
Resolve annotated evidence on those shapes and validate substituted bounds and dependency edges before classifying reusable inference as successful.
Use full substitutions for complete invocation/reference signatures and diamond arguments, preserving fixed symbols, occurrence overrides, model precedence, and established scalar failure diagnostics.
Retain completed targets and owning-lambda context during re-entry, and publish a functional target independently only when its relevant bindings are certified and no pending private symbols remain.
Add ten compiler-backed solver tests plus enclosing-type and unused-variable integration regressions without weakening existing diagnostic expectations.
The full main-module suite and self-check pass uncached, with explicitly unsupported structures remaining uncertified rather than advertised as complete.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

A proposed type is not automatically a valid answer. This patch asks javac for the underlying Java shape, keeps NullAway's nullable facts separately, and then checks whether the assembled answer meets the constraints. Results that are unknown or contradictory get explicit labels instead of being reused as successful inference. That also lets a fully known lambda target stay correct even when an unrelated unused type variable is still opaque.

Diagram: [Certified inference before and after](DIAGRAM-0005-CERTIFIED-INFERENCE.md).

## PR 006

### Title

Remove obsolete nested substitution repair

### Description

Delete `NestedTypeVarSubstitutionRepairVisitor`, its wrapper, and dedicated re-entry tracking after the replacement passes without repair output.
Update repair-specific comments and rename the top-level annotation preservation test without changing its assertions or expected diagnostics.
Retain general annotation-preserving utilities and explicitly uncertified diagnostic/fallback handling because they serve other checking paths.
The entire NullAway module and self-check pass uncached with the visitor physically removed, recording 1,185 tests with zero failures/errors and 21 skipped.

Assisted-by: Zed (GPT-6.1 Sol)

### Beginner explanation

The old visitor patched annotations after inference when javac lost or misplaced them. The new solver path now handles the tested replacement cases, including the supplier example that previously blocked deletion. This commit removes that special-purpose repair machinery rather than adding another fallback switch. General type-copying and annotation-preservation tools remain because other parts of NullAway still need them.

Diagram: [Repair lifecycle before and after](DIAGRAM-0006-REPAIR-REMOVAL.md).
