# Issue #1942: modeled nested return annotations survive generic inference

## Verdict and patch placement

**Confirmed for the reported reproducer and PR #1943's complete regression:** the existing #1585-oriented implementation already handles #1942 without new production changes. The regression passes on **01+02**, and on the full **01 → 02 → 004 → CODE-QUALITY-FOLLOWUP → 005 → 006** stack with the old repair visitor physically deleted.

The permanent tests are embedded in **02** and its alternative combined **03**, modifying `nullaway/src/test/java/com/uber/nullaway/JSpecifyJDKModelsTest.java`. Do not apply both packaging forms. No patch 007, issue-specific inference branch, added production suppression, or PR #1943 production repair is needed for these cases.

Sources:
- Issue: https://github.com/uber/NullAway/issues/1942
- Regression and alternative production fix: https://github.com/uber/NullAway/pull/1943.diff

## What was added

1. **`modeledNestedReturnAnnotationInGenericInference`** is copied unchanged from PR #1943, including all expected diagnostics. It checks direct modeled returns, `Optional.of(...).get()`, identity calls, generic container results, double nesting, `var`, nullable payload dereferences, incompatible non-null assignments, and valid/invalid method returns.
2. **`modeledNestedReturnInferencePreservesPayloadNullness`** is a separate companion test added following independent review. Non-null `completedFuture("ok")` payloads remain assignable and safely dereferenceable through `Optional`, identity, and double nesting. Nullable payloads retain negative assignment checks after `var` and double nesting, and a nested `join()` dereference remains diagnosed.

The upstream regression was not weakened, existing test expectations and helper/model configuration were not changed, and the companion adds both positive and negative controls. Test snippets use imports rather than qualified type names.

## Why this is general propagation rather than another repair

The modeled `allOf()` return supplies `CompletableFuture<@Nullable Void>` as argument evidence. The solver retains the entire structured substitution for `T`, including the nested nullable payload, rather than only inferring whether `T` itself is nullable. Substitution into `Optional<T>` or the identity return preserves that evidence; receiver substitution into `Optional.get()` then exposes the same enhanced payload type. The final certified solver consumes full bindings while respecting javac's Java shape.

PR #1943 extends the old argument-repair visitor into returns. This stack instead handles the regression through the existing full-type constraints and substitutions, and still passes after 006 deletes that visitor. Passing this independently reported case without modifying production strengthens the evidence that the approach generalizes; it is not a proof of universal soundness or absence of all false positives/negatives.

## Executed validation

Baseline: `b8e88803a2bb2287c08d9f85361c9317222387d4`, in disposable detached worktrees. The user's current `rearchitecture` branch and implementation files were not modified.

| Configuration | Command / result |
|---|---|
| Baseline + unchanged upstream regression only | Targeted method fails with unexpected `CompletableFuture<Void>` versus `CompletableFuture<@Nullable Void>` diagnostics, reproducing #1942 |
| 01+02 + unchanged upstream regression | Targeted method passes |
| 01+02 + both regression methods | Entire `JSpecifyJDKModelsTest` class passes |
| Full stack including 006 + unchanged upstream regression | Targeted method passes with visitor absent |
| Full stack including 006 + both regression methods | Full main-module suite and self-check pass uncached |

Commands, run sequentially:

```sh
./gradlew :nullaway:test --tests com.uber.nullaway.JSpecifyJDKModelsTest.modeledNestedReturnAnnotationInGenericInference --rerun-tasks --no-build-cache
./gradlew :nullaway:test --tests com.uber.nullaway.JSpecifyJDKModelsTest --rerun-tasks --no-build-cache
./gradlew :nullaway:test --rerun-tasks --no-build-cache
./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
```

Final full-suite reports: **1,187 tests recorded, zero failures, zero errors, 21 skipped**. Full-suite run: 1m 18s; self-check: 25s. These are the current local environment's results, not a supported-version matrix or performance benchmark. Existing skipped tests remain skipped.

The revised exported Markdown payloads were reapplied in a fresh worktree, using both 01+02 and the combined 03 alternative. Both stacks matched all **14 changed paths**, including the visitor deletion, byte-for-byte against the tested tree. `git diff --check` on the tested source tree passed.

A first test attempt stopped during Gradle configuration because the temporary directory name began with a dot; renaming the disposable worktree resolved it without editing project configuration. That infrastructure failure is distinct from the subsequent reproduced baseline test failure.

## Independent review

A separate agent compared the upstream regression with PR #1943 and traced modeled argument recovery, structured bound inference, full return substitution, and receiver propagation. It found no weakened assertions or issue-specific production fix. It identified missing non-null controls and negative checks after `var`/nesting; the companion test addresses those findings and was reviewed again without concrete remaining findings.

Diagnostic matching remains the upstream broad substring style. The complementary acceptance/rejection checks establish the intended nullness behavior for these exercised paths, not exact diagnostic text or universal solver correctness.
