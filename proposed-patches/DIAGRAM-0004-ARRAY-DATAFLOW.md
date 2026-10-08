# Patch 004: Inferred array types reach element reads

This diagram covers the real incremental Patch 004, not the proposed repair-removal work. Apply it after 01 + 02 or after combined 03.

## Before

The solver could preserve nullable array components for parameter compatibility, while an element read still inspected javac's symbol type. Direct call expressions have no array-variable symbol; `var` symbols can omit the inferred component annotation.

```mermaid
flowchart TD
    A[Generic call returning an array] --> B[NullAway infers component nullness]
    B --> C[Enhanced array type available for compatibility]
    A --> D[Array element read]
    D --> E[Inspect declared javac symbol type]
    E --> F{Component annotation retained?}
    F -->|Yes| G[Nullable element enters dataflow]
    F -->|No| H[Element assumed non-null]
    H --> I[Unsafe dereference can be missed]
```

## After

Both the fast dereference precheck and the dataflow transfer ask for the enhanced array type. Each array access projects one component dimension. Existing access-path facts refine the result; definite-null facts remain definite-null instead of becoming non-null.

```mermaid
flowchart TD
    A[Generic call or inferred local array] --> B[Recover enhanced array type]
    B --> C[Project the immediate component]
    C --> D[Fast dereference nullness precheck]
    C --> E[Array-read dataflow transfer]
    E --> F{Tracked element fact?}
    F -->|Yes| G[Preserve nullness lattice value]
    F -->|No| H[Use inferred component nullness]
    G --> I[Ordinary nullable-use checker]
    H --> I
    I --> J[Report unsafe dereference or accept refined use]
```

## Analysis re-entry

```mermaid
flowchart TD
    A[Recover array type from a transfer] --> B[Enter scoped array-recovery depth]
    B --> C[Generate generic constraints]
    C --> D{Relevant dataflow already running?}
    D -->|Yes| E[Read currently available facts]
    D -->|No| F[Keep authoritative expression type]
    E --> G[Do not persist unfinished inference]
    F --> G
    G --> H[Restore recovery depth in finally]
```

The no-restart rule is scoped to array recovery; ordinary lambda inference retains its previous refinement behavior.

## Assignment precision

```mermaid
flowchart TD
    A[Assignment] --> B{Writes an array element?}
    B -->|Yes| C[Invalidate potentially overlapping element facts]
    B -->|No| D[Invalidate only facts dependent on the rebound symbol]
    C --> E{Location and RHS stable and index faithful?}
    E -->|Yes| F[Record the new element nullness]
    E -->|No| G[Do not introduce a refinement]
    D --> H[Preserve unrelated array guards]
    F --> I[Later read consults updated store]
    G --> I
    H --> I
```

Instance-field indices are not used to create assignment refinements because the existing representation omits their receiver identity. Method/constructor calls retain the existing call-effect policy and library-model postconditions; this patch does not claim a complete alias or call-effect analysis.
