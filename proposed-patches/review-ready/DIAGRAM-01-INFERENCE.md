# Patch 01: final call-specific full-type inference

This combines the final inference design rather than presenting its discarded intermediate APIs. Array expression/dataflow consumers arrive in 02; the existing fallback repair remains until 03.

## Before

```mermaid
flowchart TD
    A[Generic declaration type parameter] --> B[Declaration-keyed inference state]
    C[Different invocation sites] --> B
    B --> D[Scalar nullable or non-null answer]
    D --> E[Overlay root annotations onto javac call types]
    E --> F[Nested substitution information can be lost]
    F --> G[Repair selected parameter types from actual arguments]
    G --> H[Ordinary checking with partially restored types]
```

## After

```mermaid
flowchart TD
    A[Declaration plus invocation site] --> B[Fresh ownership symbols]
    B --> C[Collect root and structural constraints separately]
    D[Attributed Java signatures] --> E[Recover Java shapes]
    C --> F[Reconcile full annotated evidence to a fixed point]
    E --> F
    F --> G[Check declared and substituted bounds and edges]
    G --> H{Result contract}
    H -->|Complete and consistent| I[Substitute full inferred types into consumers]
    I --> J[Preserve explicit occurrences and fixed symbols]
    J --> K[Publish certified targets under lifecycle guards]
    H -->|Incomplete or inconsistent| L[Explicit partial diagnostic evidence]
    L --> M[No reuse as successful inference]
    M --> N[Guarded legacy repair remains until03]
    K --> O[Ordinary compatibility and null-use checks]
    N --> O
```

COMPLETE describes the represented inference problem; it does not disable ordinary argument checking. Result maps have immutable collection snapshots, not a guarantee that every contained compiler type graph is deeply immutable.

## Fixed-bound wildcard obligations

```mermaid
flowchart TD
    A[Extends wildcard has a non-null-bounded inference variable] --> B[Record actual and owning call as a deferred obligation]
    B --> C[Finish graph preparation across nested calls]
    C --> D[Follow root subtype edges and fixed root evidence]
    D --> E{Explicit bound admits null?}
    E -->|Yes| F[Owning-site inference failure]
    E -->|No proof| G[Continue normal solving and certification]
    H[Nullable or non-null formal projection] --> I[Structural evidence retained]
    H --> J[No root provenance from a projected destination]
    J --> D
    K[Explicit non-null source] --> L[Stop nullable-bound proof]
    L --> G
```

A fixed variable T that permits nullable instantiations stays symbolic. The restrictive wildcard requirement is checked without converting T globally to nullable-T, so nullable-accepting APIs and same-T operations remain valid. Raw/capture/unsupported paths retain explicit limitations rather than gaining blanket success from the absence of this specialized exception.
