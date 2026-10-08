# Fresh review — request changes

## Verdict

**The full-type proposal is not ready to merge.** The existing suite passes, but independently supplied regression probes reproduce a new checker crash, an independently suppressed diagnostic, and unresolved nested-nullness gaps. The earlier `APPROVED` / `Good to go` labels are superseded by this review.

This was a review, not a repair pass. Implementation patch payloads have not been changed. Temporary test probes are preserved in `REVIEW-REPRODUCERS.md`.

## Validation performed

Base: `b8e88803a2bb2287c08d9f85361c9317222387d4`.

The combined Markdown patch applied cleanly to a fresh detached worktree. With temporary review probes removed, both commands were rerun without task or build-cache reuse:

```text
./gradlew :nullaway:test --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 1m 19s

./gradlew :nullaway:buildWithNullAway --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 24s
```

Parsed JUnit results: **62 suites, 1,148 tests, 0 failures, 0 errors, 21 skipped**.

`git diff --check` passed.

Independent agents performed separate read-only reviews of solver/substitution correctness and lifecycle/test integrity. Their concrete reproducers were executed where described below, including comparison against unpatched master.

## Confirmed findings

### P1 — New checker crash when declaration-bound array components have different nominal shapes

Relevant symbols:

- `ConstraintSolverImpl.cannotOverlayDeclarationBound`
- `ConstraintSolverImpl.validatedUpperBoundFallback`
- `TypeSubstitutionUtils.applyNestedAnnotations`
- `RestoreNullnessAnnotationsVisitor.visitTypeLists`

Reproducer structure:

```java
class Box<E extends @Nullable Object> {}
class Pair<A extends @Nullable Object, B extends @Nullable Object> extends Box<B> {}
class Parent<X extends @Nullable Object> {
  <T extends X> T id(T value) { return value; }
}

void test(Parent<Box<String>[]> parent, Pair<Integer, @Nullable String>[] value) {
  parent.id(value);
}
```

**Proposal result:** `NullPointerException`, with `other == null` in `updateDirectNullabilityAnnotationsForType`, reached via `applyNestedAnnotations` and positional class-argument copying.

**Master result:** no crash; it misses the unsafe nested declaration-bound conversion.

This is a confirmed new regression. The overlay representability check handles class shapes only at the root, not recursively inside arrays. It subsequently copies annotations between component classes with different argument counts.

Required fix: validate representability recursively, align nominal class views before annotation copying, and reject/report impossible overlays instead of positional copying.

### P1 — A second independent nested declaration-bound diagnostic is suppressed

Relevant symbol: `GenericsChecks.runInferenceForCall`, `NestedUpperBoundViolationException` catch.

```java
pair(boundedId(firstBad), boundedId(secondBad));
```

Both `Bad` values extend `Box<@Nullable String>`, and both `boundedId` calls declare `<T extends Box<String>>`.

**Proposal result:** only the first nested call is reported. The second call emits no diagnostic.

The catch caches a shared failure for every cacheable participating call, even though solving aborted at only one site. Later checking of the second site does not retry inference.

Required fix: distinguish graph-level failure from completed site-level reporting; do not cache unexamined sites as completed failures.

### P1 — Structured generic method-reference incompatibility remains undetected

Relevant symbols: `getInferredMethodTypeForGenericMethodReference`, `updateMethodTypeWithInferredNullability`, `applyNestedAnnotations`.

```java
static <T extends @Nullable Object> T id(T value) { return value; }
static <I extends @Nullable Object> void use(I value, Fn<I, Box<String>> fn) {}

use(nullableBox, Test::id); // nullableBox: Box<@Nullable String>
```

**Proposal result:** no diagnostic.

**Master result:** no diagnostic.

The reference cannot return `Box<String>` while preserving an input `Box<@Nullable String>` through identity. The full structured solution is overlaid onto a declared `TypeVar` target, which keeps only root nullness and loses the structured evidence needed by the method-reference return checker.

This is a pre-existing false negative left unresolved by the proposal, not a demonstrated new regression.

### P1 — Recursive declaration bounds suppress independently provable nested violations

Relevant symbol: `ConstraintSolverImpl.inferredType`.

```java
class Bad extends Node<Bad, Box<@Nullable String>> {}
static <T extends Node<T, Box<String>>> void require(T value) {}
require(bad);
```

**Proposal result:** no diagnostic.

**Master result:** no diagnostic.

The self-dependency causes `inferredType` to skip `structuredBound` entirely. It therefore skips declaration-bound validation even for the concrete, nonrecursive second argument.

Required fix: separate cycle-safe type construction from validation of concrete declaration-bound positions.

### P1 — Wildcard declaration bounds leave concrete nested violations unchecked

Relevant symbols: `sameInvariantStructure`, `structuredBound`.

```java
class Bad extends Box<Box<@Nullable String>> {}
static <T extends Box<? extends Box<String>>> void require(T value) {}
require(bad);
```

**Proposal result:** no diagnostic.

**Master result:** no diagnostic.

Conservative wildcard fallback does not establish equality, but it also does not ensure a separate declaration-bound check happens. The concrete nested mismatch is lost.

Required fix: validate wildcard containment separately from candidate construction, including when annotation-source selection falls back.

## Test audit

Tracked test diffs are additive: **508 added lines, zero deleted lines** across three test files. No existing expectation was removed, helper was weakened, or test disabled. Existing #1455 coverage gains a reversed-argument assertion.

Nevertheless, additive does not automatically mean semantically correct:

### Mixed invariant consumer test locks in an existing false positive

`mixedInvariantLowerBoundsDoNotReportInferenceFailure` asserts ordinary incompatibilities for both orders of:

```java
static <T extends @Nullable Object> void consume(T first, T second) {}
consume(nullableBox, nonNullBox);
consume(nonNullBox, nullableBox);
```

A common supertype such as `Object` can accept both arguments; invariance does not require choosing either exact `Box` instantiation. The test deliberately matches current first-source repair behavior rather than a fully correct generic-inference result.

Running the same positive program on **unpatched master** reproduced the same two incompatibility diagnostics. This is therefore not a newly introduced false positive, but the added exact-diagnostic test should be labeled as characterization of a known limitation, not evidence of correct full-type inference.

### Some review-stage tests were narrowed before this review

The proposal's final array test observes inferred nullness through parameter compatibility, not through direct element dereference. Earlier exploration had tested `pick(...)[0].length()` and `var` extraction, but those shapes did not produce the expected nullable-dereference report. They are absent from the delivered patch.

This does not weaken an existing master test, but it means claims of complete observable array-nullness propagation are not justified by the final tests.

### Useful additions

The #1291 isolation, #1585 nested substitution, receiver-bound, marked/unmarked, positive/negative array sink, and isolated declaration-diagnostic tests meaningfully exercise the intended improvement.

The upper-only factory reviewer probe (`<T extends Box<String>> T make()` assigned to `Box<@Nullable String>`) **passed**: an incompatibility was reported. The broader static concern about upper-only constraints was not confirmed by this example.

## Static concerns not yet reproduced

- Non-publishing dataflow/provisional inference can remove previously completed method-reference results without publishing replacements.
- Failure reporting may duplicate warnings across outer and inner roots when reusable call caching is disabled.
- Receiver-instantiated upper bounds may override an explicit nullable-upper-bound library model; a model-backed regression is needed.

These are not presented as experimentally confirmed bugs.

## Architectural observations

- `solve()` returns a mixture of complete structured types, root-only declaration variables, and annotation evidence knowingly incompatible with contextual constraints. That makes success ambiguous.
- Constructing a type candidate, validating a declaration contract, and retaining evidence for diagnostics are different operations and should have distinct outcomes.
- The full-type patch adds approximately a thousand lines to a solver that still relies on the repair visitor. Passing current tests does not establish that the intended principled replacement is complete.
- Cache validity and diagnostic completion should not share one graph-wide success/failure state.

## Handoff

Do not merge the full-type patch or combined patch as currently labeled. Preserve the independently useful call-specific first stage, but repair and re-review the full-type path before approving the complete set. No implementation changes were made during this review.
