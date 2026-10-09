# Patch 007: deferred wildcard obligations

007 applies after 006. These diagrams show the missing direct/nested contract and its general replacement, not the separate scalar-marker implementation in open PR #1834.

## Before 007

```mermaid
flowchart TD
    A[Fixed T has a nullable-admitting bound] --> B[Box of T or List of T]
    B --> C[Inner identity or diamond call]
    C --> D[Fresh inner inference variable]
    D --> E[Outer wildcard requires a non-null-bounded variable]
    E --> F[Scalar constraints see no explicit nullable occurrence]
    F --> G[Missing fixed-bound containment obligation]
    G --> H[Unsafe call accepted without a warning]
```

The same missing obligation affects direct calls on this baseline; full-type result preservation alone does not establish it.

## After 007

```mermaid
flowchart TD
    A[Record an extends wildcard requirement] --> B[Retain the actual bound and required variable's site]
    B --> C[Finish structured constraint preparation]
    C --> D[Traverse complete root-inference subtype edges]
    D --> E[Read dedicated root-compatible fixed lower evidence]
    E --> F{Explicit fixed bound admits null?}
    F -->|Yes| G{Required variable's bound excludes null?}
    G -->|Yes| H[Throw an owning-site wildcard-bound failure]
    H --> I[Report using the inner call's actual ancestor path]
    I --> J[Cache only the owning failure site]
    G -->|No| K[Continue normal inference and certification]
    F -->|No| K
    K --> L[Keep fixed variables symbolic and preserve full substitutions]
```

Requirements are recorded only for eligible non-null-bounded inference variables, so the second decision summarizes the registration-time gate rather than an extra solve-time branch. The obligation is checked before successful result publication; it does not mutate a fixed `T` into `@Nullable T`.

## Why structural and root evidence must stay separate

```mermaid
flowchart TD
    A[Actual fixed T flows to an annotated formal occurrence] --> B{Does the destination infer the variable's root?}
    B -->|Bare E| C[Record root-fixed evidence]
    C --> D[Evidence may reach an outer wildcard requirement]
    B -->|Nullable E or NonNull E| E[Do not record root-fixed evidence]
    E --> F[Projection controls that parameter occurrence only]
    A --> G[Retain structural evidence for nested positions]
    G --> H[Existing nested checking and certification]
```

The review-discovered `empty(@Nullable E)` false positive showed why reading ordinary structural lower bounds was insufficient: permission for a nullable input does not make an independent empty result nullable. Dedicated root evidence fixes that distinction without discarding nested constraints.

## Acceptance evidence

Eleven source-level regressions and three direct solver methods cover the reported four diagnostics, projected source/formal controls, nullable-accepting and non-null-bound APIs, bound chains, intersections, markedness, method-owner models, graph ordering/repeated solves, and owning-site/sibling/suppression reporting. Both patch-stack export alternatives reproduce all fifteen tested changed paths byte-for-byte; the full uncached suite and self-check pass. See [ISSUE-1947-VALIDATION.md](ISSUE-1947-VALIDATION.md) for results and limitations.
