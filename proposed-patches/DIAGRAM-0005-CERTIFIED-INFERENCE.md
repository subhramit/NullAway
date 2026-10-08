# Patch 005: Recover shapes and certify results

## Before 005

Type-valued results could represent complete evidence, scalar fallback, or a diagnostic source without a separate status. Ordinary invocation consumers used overlays and the repair visitor.

```mermaid
flowchart TD
    A[Call-specific constraints from 01 and 02] --> B[Propagate root and structured evidence]
    B --> C[Construct type-valued annotation sources]
    C --> D[Complete structure or fallback or diagnostic evidence]
    D --> E[Common result representation]
    E --> F[Overlay onto javac invocation types]
    F --> G[Repair visitor compensates for remaining gaps]
    G --> H[Ordinary compatibility checks]
```

## After 005

```mermaid
flowchart TD
    A[Receiver-substituted declaration and attributed call] --> B[Recover Java instantiation shapes]
    B --> C[Register site variables with shapes and authoritative bounds]
    C --> D[Propagate scalar and structural constraints]
    D --> E[Check declaration bounds independently]
    E --> F[Construct annotated candidates on fixed Java shapes]
    F --> G[Substitute final bindings into relevant bounds and edges]
    G --> H{Certification outcome?}
    H -->|Complete and consistent| I[Full substitution for supported consumers]
    H -->|Unknown| J[Explicit incomplete status and fallback evidence]
    H -->|Contradictory| K[Explicit inconsistent status and ordinary diagnostics]
    I --> L[Publish only completed reusable results]
    J --> M[Do not cache as successful inference]
    K --> M
    L --> N[Normal checking with preserved occurrence overrides]
    M --> N
```

Scalar contradictions still use established exception/warning handling. Collection immutability is not a claim that contained javac types are deeply immutable.

## Target publication with unused variables

```mermaid
flowchart TD
    A[Completed constraint evaluation] --> B{Whole solution certified?}
    B -->|Yes| C[Publish eligible completed call and reference results]
    B -->|No| D{Any inconsistency?}
    D -->|Yes| E[Keep diagnostic handling separate]
    D -->|No| F[Inspect bindings used by each functional target]
    F --> G{Target depends on incomplete variables?}
    G -->|Yes| H[Do not publish target]
    G -->|No| I[Rebuild target and reject pending private symbols]
    I --> J[Publish only the certified target]
    J --> K[Root call remains explicitly partial]
```

This preserves a valid lambda target when an unrelated unused recursive parameter remains opaque; it does not promote the incomplete root solution to success.

## Stable context during re-entry

```mermaid
flowchart TD
    A[Root inference receives assignment or argument target] --> B[Retain completed context separately from result reuse]
    B --> C[Parameter or receiver checking re-enters inference]
    C --> D[Recover original target and owning lambda]
    D --> E[Use real ancestor paths and scoped lambda return targets]
    E --> F[Recompute without stale javac-only target strengthening]
    F --> G[Restore temporary environments in finally]
```

Direct compiler-backed tests inspect the result map and certification sets, including enclosing-type constraints, fixed symbols, late evidence, model precedence, source isolation, and contradictions. Unsupported cases remain explicitly incomplete; the diagram does not claim broader Java LUB inference.
