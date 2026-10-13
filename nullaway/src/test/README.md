# Balanced inference regression coverage

Routine CI separates frontend correctness from large solver-graph coverage:

- `jspecify/BalancedInferenceCallsTests.java` compiles 31/63-call balanced
  expressions through NullAway. The nullable-list cases check both an explicit
  return target and an inferred local, including the nullable-element
  dereference diagnostic. Contextual failure, independently valid nested calls,
  dataflow re-entry, receiver changes, and lambda cases remain compiler-backed.
- `generics/BalancedConstraintSolverTests.java` checks 255/511-node balanced
  graphs through the solver API. Flat calls provide real attributed javac types
  and distinct invocation sites for the same declared type variable. Each graph
  has two argument constraints per node, including distinct leaf and
  parent-owned edge origins. Both contextual and unconstrained-root variants
  check every node's concrete shape, nested nullness, completeness, ownership,
  and absence of fresh-variable leaks. Repeated solves must preserve results
  and leave compiler-owned inputs unchanged.

The flat source deliberately does not model AST nesting. These direct tests do
not replace frontend constraint-generation, publication, diagnostic, or
flow coverage; the smaller end-to-end tests cover those paths. There is no
size-specific solver cutoff being tested at 255 or 511 nodes.

## Large frontend profiling workload

The original 255/511-call end-to-end sources and assertions remain available:

```sh
./gradlew :nullaway:test --tests '*BalancedInferenceCallsTests' -PbalancedInferenceStress=true
```

This opt-in changes only the two nullable-list workloads. Each source contains
both the targeted return and the inferred local, so the largest source still
contains 1,022 nested generic invocations. The property is forwarded to every
main-module compatibility test JVM and is part of the Gradle test inputs.

The split avoids routinely benchmarking javac's poly-expression inference in
correctness CI. A local JDK 25 profile of the original largest test attributed
2,757 of 2,917 execution samples to javac leaf methods, particularly inference
context checks and type containment/substitution. A no-op-checker probe of the
same two large expressions also remained expensive. These observations identify
a costly source workload, not a proven explanation for the additional macOS
amplification. No timing assertions are used: runner CPU, architecture, JDK/JIT,
parallelism, and warmup can change timings substantially.
