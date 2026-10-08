# Preserve nested inference evidence and completed site results

## Scope and reading guide

These diagrams compare the state **after 01 but before 02** with [the actual repaired patch 02](0002-reconcile-full-inferred-types.md).
[Patch 03](0003-complete-solution.md) packages 01 and 02 as an alternative delivery, not a third architectural stage.
The additional array/dataflow stage is documented separately in [the 004 diagrams](DIAGRAM-0004-ARRAY-DATAFLOW.md); the quality patch changes readability, not architecture.

NullAway checks possible null misuse without running code.
Generic inference chooses arguments for parameters such as `T`; NullAway needs both root nullness and facts inside generic arguments, array components, enclosing types, and supported wildcard bounds.
For example, a non-null `Box<@Nullable String>` may contain null even though the box itself cannot be null.

## Inference before 02: Return scalar nullability

```mermaid
graph TD
    A["Register call-specific fresh variables from 01"]
    A --> B["Generate subtype constraints from arguments and target context"]
    B --> C["Recognize unannotated variable uses for scalar constraints"]
    C --> D["Decompose supported nested type relationships"]
    D --> E["Propagate root nullable or non-null states"]
    E --> F["Return one scalar nullability answer per inference variable"]
    F --> G["Project the shared solution to the requested site"]
    G --> H["Apply root annotations to call or reference types"]
    H --> I["Use javac shapes and the existing nested-substitution repair"]
    I --> J["No full-type annotation source in the solver result"]
```

Before 02, decomposition can constrain variables in nested positions, but the result map cannot carry a structured replacement such as `R = Box<@Nullable String>`.
An explicitly annotated variable occurrence is excluded from scalar inference; 02 adds structural ownership independent of that root eligibility.

## Inference after 02: Separate root and structural graphs

```mermaid
graph TD
    A["Register fresh site variables and receiver-instantiated bounds"]
    A --> B["Honor authoritative declaration contracts and nullable model overrides"]
    B --> C["Generate constraints using fresh site signatures"]
    C --> D["Root graph: scalar edges only for uses without explicit root overrides"]
    D --> E["Structural graph: retain ownership and edges even with root overrides"]
    E --> F["Collect lower, contextual upper, and declaration-bound evidence"]
    F --> G["Prepare structured constraints to a fixed point"]
    G --> H["Propagate root-nullness states"]
    H --> I["Validate known lowers against declaration bounds before candidates"]
    I --> J{"Proven declaration violation?"}
    J -->|Yes| K{"Safe annotation source for ordinary diagnostics?"}
    K -->|Yes| L["Stage diagnostic fallback until every variable is validated"]
    K -->|No| M["Throw site-owned nested-bound violation for caller reporting"]
    J -->|No| N["Construct cycle-safe structured candidates"]
    L --> N
    N --> O["Merge covariant arrays; require consistent invariant generic arguments"]
    O --> P{"Complete structured evidence available?"}
    P -->|No| Q["Unknown structure or cycles: return annotated declared variable"]
    P -->|Yes| W["Retain nested annotations and resolve fresh peers"]
    Q --> R["Return type-valued evidence indexed by declaration and site"]
    W --> R
    R --> S["Calls: align nominal shapes and overlay annotations onto javac types"]
    S --> T["References: substitute full types into still-symbolic signatures"]
    T --> U["Preserve explicit root overrides and ordinary compatibility checks"]
    U --> V["Retain the existing repair visitor where fallback is needed"]
```

The root and structural nodes depict two views of the **same inference variables**, not unrelated solvers; candidate/fallback nodes describe alternatives within result construction, not mandatory transformations of every result.

### Declaration validation is not candidate construction

`ConstraintSolverImpl.solve` prepares structural constraints, propagates scalar states, validates declaration bounds, and only then constructs final inferred types.
`validateDeclarationBounds` checks known lower evidence even when a cycle or incompatible candidate merge prevents full inference; a concrete violation is not erased by an unknown sibling position.
Diagnostic fallback types are collected temporarily and installed only after every variable has been validated, so a fallback for one variable cannot hide evidence needed to validate another.
Representable violations preserve ordinary argument/reference diagnostics; otherwise `NestedUpperBoundViolationException` carries the declaration, site, lower bound, and upper bound for `GenericsChecks` to report.
Wildcard containment respects the wildcard-generics feature gate and uses recursion guards; unmarked declarations are not promoted into authoritative nullness evidence.

### Root annotations do not erase structural contracts

`inferenceVariableForUse` excludes explicit `@Nullable`/`@NonNull` root occurrences from scalar inference.
`inferenceVariableForStructure` retains their registered symbol ownership, and `structuralSubtypes`/`structuralSupertypes` preserve nested evidence separately from scalar `subtypes`/`supertypes`.
Thus an explicit annotation may fix the nullness of a `T` occurrence without discarding constraints on the contents of the inferred `T`.

### Calls and references consume full results differently

`TypeSubstitutionUtils.updateTypeWithInferredNullability` overlays inferred nested annotations onto javac's already-instantiated call type, preserving its nominal shape and explicit root annotations.
`overlayInferredTypeAnnotations` recursively aligns class shapes via `asSuper`, checks argument counts, and avoids positional overlay onto unmatched classes, including inside arrays.
`substituteInferredTypesForGenericMethodReference` instead substitutes complete inferred types into a still-symbolic referenced signature: replacing only the annotation on `T` would lose its nested structure.
Annotated declared-variable fallback results remain symbolic; they do not become their upper bounds as replacement types.
Contextual incompatibilities can retain lower-bound annotation evidence so established ordinary checks report at their usual locations.

## Lifecycle before 02: Broad result reuse

```mermaid
graph TD
    A["Start an enclosing inference problem and track all participating calls"]
    A --> B["Analyze lambda bodies using temporary parameter types"]
    B --> C["Solve one shared scalar result"]
    C --> D{"Inference succeeds?"}
    D -->|Yes| E["When caching is allowed, share success across all tracked calls"]
    E --> F["Publish poly-argument types for the root call"]
    F --> G["Reference checks look for the enclosing call's cached answer"]
    D -->|No| H["When caching is allowed, share failure across all tracked calls"]
    H --> I["An unresolved sibling can inherit another site's failure"]
    G --> J["Reuse root-scoped results during later checks"]
    I --> J
    J --> K["Clear compilation-unit caches"]
```

This comparison is with stage 01, not with the archived rejected version of 02.
The pre-02 path already has cache eligibility checks and compilation-unit cleanup; 02 refines participating-call eligibility, completed reference publication, failure ownership, and nested-context restoration.

## Lifecycle after 02: Stage work, then publish completed results

```mermaid
graph TD
    A["Begin inference with local InferenceCacheState and poly-argument staging"]
    A --> B["Save outer callTypesForPolyArguments and lambda parameter state"]
    B --> C["Generate from declarations, not previously completed reference substitutions"]
    C --> D["Do not evict completed reference entries during generation"]
    D --> E["If provisional lambda types participate, disable reusable call caching for the problem"]
    E --> F["Restore nested temporary state in finally blocks"]
    F --> G{"Solver completes successfully?"}
    G -->|Yes| H{"Current context allows publication?"}
    H -->|Yes| I["Publish completed reference-site solutions"]
    I --> J["Cache only eligible calls and publish staged nested poly-argument targets"]
    H -->|No| K["Return transient result without persistent publication"]
    G -->|No| L["Use failure-site provenance for diagnostic deduplication"]
    L --> M["Recover real diagnostic ancestors for nested-bound suppression"]
    M --> N["Only a publishable failure invalidates participating reference results"]
    N --> O["Cache nested declaration failure at its site; scalar failure at the root problem"]
    O --> P["Leave other calls available for independent checking"]
    P --> Q["Independently solve unresolved references against final targets with re-entry guard"]
    Q --> R["Publish only completed independent reference results in eligible contexts"]
    J --> S["Clear call, reference, diagnostic, poly-type, and guard caches per compilation unit"]
    K --> S
    R --> S
```

### Transactional publication and provenance

Here, **transactional** means separating temporary constraint-generation work from persistent publication; it does not imply a database transaction or atomic concurrency guarantee.
`InferenceCacheState.recordCall` permanently disables reusable call caching for the shared problem once provisional lambda parameters participate, since all its variables may be connected.
Completed reference results and staged poly-argument targets can still be published after the enclosing problem finishes, when `okToCacheInferenceResult` permits publication; that gate rejects dataflow and currently active provisional-lambda contexts.
`callTypesForPolyArguments` and `lambdaParameterTypesForInference` are restored in `finally` blocks when inference nests, fails, or re-enters.
A nested declaration violation caches failure only at the completed failing site, while a scalar contradiction caches the root problem; neither marks every sibling as failed.
The scalar exception's owning inference site deduplicates warnings separately from root-problem caching, and nested-bound reporting uses `stateForInferenceDiagnostic` to recover actual suppression ancestors from the compilation unit.
`inferGenericMethodReferenceIndependently` guards re-entry and checks unresolved siblings without publishing or invalidating call results.
`clearCache` clears persistent results, diagnostic provenance sets, and re-entry/repair guards after each compilation unit.

## Evidence and limits

- Permanent tests in `GenericMethodTests`, `GenericMethodLambdaOrMethodRefArgTests`, and `GenericInferenceErrorReportingTests` cover nested nullness, receiver bounds, arrays, recursive/wildcard declarations, explicit root overrides, structured references, nested poly-argument publication, failed siblings, exact diagnostic counts, and local suppression.
- [REPAIR-REPORT.md](REPAIR-REPORT.md) records passing full `:nullaway:test` and `:nullaway:buildWithNullAway` runs: 62 suites, 1,159 tests, zero failures/errors, and 21 skipped. This documentation task ran no Gradle commands.
- The existing `NestedTypeVarSubstitutionRepairVisitor` remains a fallback; these changes do not replace all Java generic-shape inference.
- `knownLimitationMixedInvariantLowerBoundsReportFalsePositiveIncompatibilities` characterizes a pre-existing mixed-invariant/common-supertype false positive, rather than proving that inference case correct.
- Direct array-element dereference propagation is added separately by 004. Nullable library-model overrides are handled, but no dedicated external custom-model integration test was added.
- Passing finite tests and reviews does not establish the absence of every false positive or false negative.
