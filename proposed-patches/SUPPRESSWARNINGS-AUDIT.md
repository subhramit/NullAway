# Audit of warning suppressions added by the proposal

> **005→006 update:** 005 adds one method-local `ReferenceEquality`/`TypeEquals` suppression in `GenericsChecks.isSyntheticNullnessAnnotation`, solely for identity checks against the synthetic annotation singletons. Shape/status helpers use normal tree equality and explicitly typed identity maps rather than adding suppressions for those checks. 006 contains no experiment-only `UnusedMethod` suppression and adds no production `NullAway` suppression. The detailed inventory below describes the earlier stages.

## Conclusion

**No added suppression is inherently harmful in its inspected use, and no production `@SuppressWarnings("NullAway")` was added to hide checker defects.** This is a source audit, not a proof that suppressed code is bug-free.

The delivered incremental patch payloads 01, 02, 004, and the quality cleanup contain five added suppression occurrences, all in 02: three on production helper methods and two inside compiled test-source snippets. 03 duplicates the base changes as combined packaging and is not an additional source of suppressions.

## Added production occurrences

| Location | Suppression | Purpose | Assessment |
|---|---|---|---|
| `ConstraintSolverImpl.containsIdentical` | `ReferenceEquality`, `TypeEquals` | Determine whether the identical javac `Type` object is present, not whether two Java types are semantically equal | Intentional and method-local. `Types.isSameType` is not equivalent because javac semantic equality can ignore annotation/metadata differences. Identity checks must not substitute for actual bound-consistency checking; the implementation also uses structural deduplication and separate compatibility logic |
| `ConstraintSolverImpl.markNestedTypesNonNull` | `ReferenceEquality`, `TypeEquals` | Detect whether recursive rebuilding returned the same node, so unchanged types can be reused and changed annotation metadata can be reconstructed | Intentional and method-local. This is change detection, not subtype or equality validation. Semantic type equality would be inappropriate for detecting newly added/removed nullness annotations |
| `TypeSubstitutionUtils.applyNestedAnnotationsOfInferredTypes` | `ReferenceEquality` | Detect unchanged reconstructed nodes and compare compiler symbol identity while applying nested annotations | Intentional, but redundant under the utility class's already existing suppression; removing the local duplication would be a cleanup, not a behavior fix |

The **class-level** `@SuppressWarnings({"ReferenceEquality", "TypeEquals"})` on `TypeSubstitutionUtils` already exists on unpatched master. The proposal did not introduce that broad class-level scope. It should not be mistakenly counted as a new suppression or cited as new proof that these algorithms are correct.

## Added test-source occurrences

| Compiled test snippet | Suppression | Purpose | Assessment |
|---|---|---|---|
| `scalarInferenceFailureInsideImplicitLambdaSuppressed` | `NullAway` on the snippet's method | Verify that an explicitly suppressed caller does not receive ordinary/inference diagnostics | Appropriate suppression-behavior test, not a way of making an unsuppressed correctness test pass. The corresponding unsuppressed case requires a warning and ordinary nullable-parameter checking |
| `nestedDeclarationBoundViolationInsideLazyInferenceLocallySuppressed` | `NullAway` on the snippet's local declaration | Verify that lazy inference retains the local declaration's actual suppression ancestors | Appropriate targeted regression for diagnostic TreePath recovery. Separate unsuppressed declaration-bound tests verify that the underlying invalid call is detected |

These annotations are embedded in strings compiled by the test helper. They do not suppress the JUnit assertions or disable the test classes themselves.

## Suppression not shipped

The repair-disabled experiment temporarily added `@SuppressWarnings("UnusedMethod")` to the now-unused repair wrapper so its bypass could be tested under Error Prone's `-Werror`. That annotation exists **only in the discarded experiment**, not in the delivered patch payloads.

No new blanket suppression of all Error Prone warnings, no new production `NullAway` suppression, and no ignored regression added to conceal a failing correctness test was found in this audit.

## Risks and recommendations

- Keep identity-warning suppressions restricted to identity/change-detection helpers.
- Do not use identity equality to conclude two annotated types satisfy the same constraints.
- Do not expand these suppressions to surrounding solver or reporting code just to silence new diagnostics.
- The duplicate helper-level suppression in the already-suppressed substitution utility can be removed separately for clarity.
- Full module tests, self-check, and explicit compatibility regressions remain necessary; a justified suppression does not make an algorithm sound by itself.

This audit did not change implementation payloads or rerun Gradle. It inspected the current delivered diffs and the pre-existing utility-class suppression on master.
