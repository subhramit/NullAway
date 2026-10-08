# Remaining work and patch stacking

> **005→006 update:** The certification and cleanup stages described below are now implemented. The final repair-free stack passes the entire uncached suite and self-check, and 006 deletes the visitor/wrapper/guard. The earlier failed bypass experiment remains historical evidence for the pre-005 stack, not the current removal verdict. See `COMPLETION-005-006.md` for new tests, exact apply order, and remaining bounded limitations.

## Historical pre-005 assessment

## What is delivered now

- **01** is the standalone #1291 fix: call-specific inference identities, with exact/reversed/deeper-call and diamond regression tests.
- **02** is stacked on 01: supported full-type evidence, declaration validation, method-reference consumption, cache/diagnostic recovery, and permanent review regressions.
- **03** is only the combined 01+02 delivery form, not a third commit or proof of complete issue satisfaction.
- **004** is a real incremental follow-up after 01+02 or 03: enhanced inferred-array nullness reaches direct reads, `var` reads, multidimensional accesses, indexed arguments, and enhanced-for loops.
- **CODE-QUALITY-FOLLOWUP.md** is an optional behavior-preserving commit on the repaired base. It is independent of 004 because its only changed production file is the solver.

Use `01 → 02 → 004`, or `03 → 004`. The quality patch may be applied before or after 004. Do not apply 03 after 01+02.

## Can the visitor now be removed?

**No. This was tested, not merely inferred from the source.**

In a separate disposable worktree containing 03 + the array follow-up, the successful-invocation call to `restoreNestedNullabilityForTypeVarArguments` was bypassed. The unused wrapper was suppressed only in that experiment so Error Prone could compile it. No experimental removal/suppression is included in the deliverable.

The targeted command covered #1291, both #1585 methods, `issue1455`, Caffeine nested argument repair, top-level root-annotation preservation, and mixed-invariant characterization.

**Result: 8 tests ran, 1 failed — `GenericMethodTests.issue1455`.** The valid `acceptSup(sup)` case again reported:

```text
incompatible types: Supplier<@Nullable OuterT> cannot be converted to Supplier<OuterT>
```

The earlier first-bound removal patch is therefore not a safe cleanup. Keeping the repair visitor currently preserves a demonstrated valid case.

## Why the stronger #1932 contract remains incomplete

The solver map uses site-specific keys and `Type` values, and the motivating examples pass. But the implementation still distinguishes only implicitly between:

1. complete structured substitution;
2. scalar-only annotated declaration-variable fallback;
3. annotated evidence retained to trigger an ordinary compatibility diagnostic.

An API returning `Type` alone does not certify that every relevant constraint is satisfied. Ordinary invocation consumers still apply annotation overlays to javac shapes and can rely on the repair visitor. Deletion needs a replacement for those fallback-supported cases, not just a renamed or type-valued result.

## Recommended next implementation stages

### Proposed 005: Recover fixed-variable structure and make solver outcomes explicit

This is **a design proposal, not an implemented patch**.

- Separate complete, fallback, and contradictory outcomes internally; do not represent them as indistinguishable successes.
- Recover javac instantiation shapes for every registered site, including unconstrained variables, while keeping fixed caller/receiver variables symbolic.
- Preserve annotations on fixed type-variable occurrences through wildcard upper-bound reconciliation, including the exact `Supplier<@Nullable OuterT>` case.
- Keep contextual diagnostic evidence distinct from certified reusable solutions.
- Apply complete results consistently to invocation parameters, results, reference targets, inferred locals, and applicable thrown types.
- Add compiler-backed solver tests that inspect returned substitutions, satisfy all recorded bounds, preserve site ownership, and leave compiler-owned types unchanged.

**Acceptance:** the exact #1455 valid call must pass with repair output disabled, and contradictory contextual/declaration constraints must still report legitimate diagnostics without caching a partial solution.

### Common-supertype/nullness LUB reconciliation

The current characterization test records a known false positive:

```java
<T extends @Nullable Object> void consume(T first, T second)
consume(Box<@Nullable String>, Box<String>);
```

Invariance prevents directly converting one box instantiation to the other; it does not prohibit a valid common supertype such as `Object` or an appropriately contained wildcard view.

A complete fix must choose a nullness-correct common candidate within the API's declaration and target constraints, not simply copy the first argument. It must handle both argument orders and distinguish valid broad consumers from genuinely incompatible invariant target contexts.

The supplied plan also explicitly preserves existing #1455 negative diagnostics while using javac's fixed inferred shape. That policy and a broader common-supertype improvement must be reconciled explicitly with maintainers; tests should change only alongside a demonstrated corrected semantics, not merely to make the build pass.

**Acceptance:** accepted common-supertype cases in both orders, rejected precise invariant targets, observable nullable results, and exact diagnostic counts. The existing limitation test must then be replaced by positive correctness coverage with a clear explanation.

### Dedicated lifecycle/model gates

Add focused coverage for:

- an explicit nullable upper-bound library model after receiver substitution, for both calls and references;
- completed reference publication followed by non-publishing dataflow/provisional re-entry;
- independently failed reference siblings with exact diagnostic counts;
- multiple compilation units and failure→success re-entry;
- supported JDK/Error Prone combinations and performance on large constraint graphs.

Related broad integration tests exist, but these precise one-fix/one-test assertions are not all present.

### Final cleanup: Delete repairs only after independent acceptance

Once the complete path passes the repair-disabled gates, remove:

- `NestedTypeVarSubstitutionRepairVisitor`;
- its wrapper and dedicated re-entry tracking;
- obsolete scalar adapters or overlays proven unnecessary;
- repair-specific comments while preserving general annotation-aware substitution/member/supertype utilities.

Run the #1291/#1585/#1919/#1455/Caffeine/root-override gates and full main-module tests/self-check without repair output. Cleanup must not be the first change that makes a preceding stage correct.

## Evidence for 004

Full uncached validation of `01+02 + quality + 004` passed:

```text
./gradlew :nullaway:test --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 1m 8s

./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 26s
```

Reports: **1,173 tests, 0 failures, 0 errors, 21 skipped**. The six new array methods and eight precision regressions are permanent in 004. Independent focused review identified three issues in the first candidate; they were fixed and their reproducers added, with no remaining P0/P1 blocker found in the changed paths.

This closes the delivered direct-array-dereference gap. It does **not** justify claiming that #1932's full plan or repair removal is complete.
