# Patch 02: enhanced array types reach ordinary flow checks

01 can infer and certify nested array nullness. This patch connects that result to element-read consumers without changing the solver API.

## Before

```mermaid
flowchart TD
    A[Solver knows enhanced array component types] --> B[Direct call or var local array expression]
    B --> C[Element read consults only an array symbol]
    C --> D[Missing symbol or enhanced annotation]
    D --> E[Nullable element can appear non-null]
    E --> F[Ordinary dereference checker misses the risk]
```

## After

```mermaid
flowchart TD
    A[Enhanced array expression type] --> B[Project one component dimension at a time]
    B --> C[Fast nullability check and dataflow transfer]
    C --> D{Usable element flow fact?}
    D -->|Yes| E[Retain exact nullness lattice value]
    D -->|No| F[Use enhanced component nullness]
    E --> G[Ordinary null-use checks]
    F --> G
    H[Array recovery from a running transfer] --> I[Scoped no-restart guard]
    I --> C
    J[Original enhanced-for array expression] --> B
```

## Keep sound invalidation and useful refinement together

```mermaid
flowchart TD
    A[Assignment or array write] --> B[Invalidate overlapping or symbol-dependent facts]
    B --> C{Stable assignment with faithfully represented indices?}
    C -->|Yes| D[Record known post-assignment value]
    C -->|No| E[Do not invent an element refinement]
    D --> F[Later reads use remaining valid flow facts]
    E --> F
    G[Unrelated statement or known distinct index] --> H[Keep unaffected refinements]
    H --> F
```

Different array roots may alias. Instance-field index receivers are not faithfully represented by the existing index abstraction, so they cannot justify assignment refinement. This is bounded assignment invalidation, not complete alias/call-effect analysis; method calls retain the existing refinement policy and library-model postconditions remain effective.
