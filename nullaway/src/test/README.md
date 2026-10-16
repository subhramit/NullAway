# Inference performance regression coverage

The performance regressions documented below run NullAway on source programs and
assert diagnostics, rather than using a custom checker to inspect javac types or
private solver/cache state.

## Routine checker-level coverage

Java paths below are relative to `java/com/uber/nullaway/`.

- `jspecify/BalancedInferenceCallsTests.java` compiles 31/63-call balanced
  expressions through NullAway. The nullable-list cases check both an explicit
  return target and an inferred local, including the nullable-element
  dereference diagnostic. Other cases check contradictory targets, independently
  valid nested calls after a failed enclosing problem, dataflow re-entry,
  assignments within an inference root, loop-store changes, receiver inputs,
  and lambda parameter types.
- `jspecify/ReceiverInstantiatedBoundsTests.java` checks annotated peer bounds
  and chains in both declaration orders, alternating nullable/non-null receivers,
  scalar and wildcard receiver bounds, and intersection bounds. These existing
  diagnostic tests cover the observable receiver-bound behavior of call
  preprocessing without counting executable-type traversals.
- `jspecify/GenericMethodTests.java` includes
  `unmarkedBoundOnlyVariablesPreserveReceiverInstantiations`: an unmarked
  receiver has a method variable occurring only in a peer's bound, plus unused
  recursive/dependent variables. Alternating receiver instantiations must retain
  each inferred local's element nullness. Nullable elements produce both an
  incompatible-argument diagnostic and dereference diagnostics; non-null
  elements remain accepted.
- `jspecify/JSpecifyLibraryModelsTests.java` includes
  `optionalModelsApplyToEachReceiverInstantiation`: repeated calls to the same
  modeled declaration on `Optional<String>`, `Optional<Integer>`, and
  `Optional<List<@Nullable String>>` accept null arguments, preserve the concrete
  result type, and report nullable-result/element dereferences. Calls to `get()`
  remain non-null. Existing `optionalMethods` covers other parameter, return,
  and generic-bound models.
- `JSpecifyJDKModelsTest.java` already checks inherited models
  through `mapGetOrDefaultModelInheritedByOverrides` and modeled override
  signatures through `mapComputeOverrideUsesJSpecifyModel` and
  `collectionToArrayOverrideUsesJSpecifyModel`.
- `handlers/LibraryModelsHandlerTest.java` retains the noncompiler resource-stream
  regression; descriptor-cache assertions and their reflective javac harness are
  not part of this test.

These tests intentionally do not assert allocation counts, map/set identities,
exact traversal/conversion counts, synthetic declaration-type replacement, or
private cache lifetimes. Nor do they inspect every intermediate solver node or
its evidence snapshots. There is no size-specific solver cutoff being tested at
255 or 511 calls.

## Large frontend profiling workload

The original 255/511-call end-to-end sources and diagnostic assertions remain
available:

```sh
./gradlew :nullaway:test --tests '*BalancedInferenceCallsTests' -PbalancedInferenceStress=true
```

This opt-in changes only the two nullable-list workloads. Each source contains
both the targeted return and the inferred local, so the largest source contains
1,022 nested generic invocations. The property is forwarded to every
main-module compatibility test JVM and is part of the Gradle test inputs.

The smaller default sources avoid routinely benchmarking javac's poly-expression
inference in correctness CI. Large solver workloads still run through the actual
checker when requested; no flat compiler-type solver harness replaces them. No
timing assertions are used: runner CPU, architecture, JDK/JIT, parallelism, and
warmup can change timings substantially.
