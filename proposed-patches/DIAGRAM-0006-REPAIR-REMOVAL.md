# Patch 006: Remove the obsolete repair lifecycle

## Before 006, with 005 present

Complete solutions already take the full-substitution path. The old visitor still exists in the partial invocation path, with its own wrapper and re-entry tracking.

```mermaid
flowchart TD
    A[Invocation result] --> B{Complete certified solution?}
    B -->|Yes| C[Substitute full inferred types]
    B -->|No| D[Partial evidence or fallback]
    D --> E[Check repair re-entry guard]
    E --> F[Repair visitor inspects actual argument types]
    F --> G[Copy or remove selected nested annotations]
    G --> H[Apply general annotation-preserving utilities]
    C --> I[Ordinary checking]
    H --> I
```

## After 006

```mermaid
flowchart TD
    A[Invocation result] --> B{Complete certified solution?}
    B -->|Yes| C[Substitute full inferred types]
    B -->|No| D[Explicit fallback or diagnostic evidence]
    D --> E[General annotation-preserving checking path]
    C --> F[Ordinary checking]
    E --> F
    F --> G[No dedicated repair visitor or repair re-entry state]
```

## Cleanup gate

```mermaid
flowchart TD
    A[005 implemented and tested] --> B[Bypass visitor output in separate experiment]
    B --> C[Run motivating and repair-regression gates]
    C --> D[Delete visitor and wrapper in 006]
    D --> E[Run entire NullAway module uncached]
    E --> F[Run self-check uncached]
    F --> G[Independent cleanup and lifecycle review]
    G --> H[Export separate removal diff]
```

The valid #1455 case that failed before 005 now passes without the visitor. General metadata-copy, member/supertype, explicit-annotation, and fallback utilities remain; they are not the obsolete visitor being removed. 006 changes test comments and one method name, but not expected diagnostics.
