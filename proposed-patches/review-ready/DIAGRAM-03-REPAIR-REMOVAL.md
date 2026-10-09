# Patch 03: remove the old repair only after replacement validation

This is the final review unit. It removes the old visitor, its wrapper, dedicated guard and cleanup entry, without changing the complete inference API from 01.

## Before deletion: after 01 and 02

```mermaid
flowchart TD
    A[Generic call inference result] --> B{Carries inferred type evidence?}
    B -->|No: inference failure| C[Use javac call-site type without substitutions]
    B -->|Yes| D{Complete and consistent?}
    D -->|Yes| E[Full declared-type substitution]
    D -->|No: partial| F[Attributed shape and partial diagnostic evidence]
    F --> G[Dedicated repair re-entry guard]
    G --> H[NestedTypeVarSubstitutionRepairVisitor]
    H --> I[Diagnostic annotation overlay]
    C --> J[Ordinary checking]
    E --> J
    I --> J
    K[Compilation-unit cleanup] --> L[Clear repair-specific guard state]
```

## After deletion

```mermaid
flowchart TD
    A[Generic call inference result] --> B{Carries inferred type evidence?}
    B -->|No: inference failure| C[Same javac call-site type without substitutions]
    B -->|Yes| D{Complete and consistent?}
    D -->|Yes| E[Same full declared-type substitution]
    D -->|No: partial| F[Attributed shape and explicit partial evidence]
    F --> G[General diagnostic annotation overlay]
    C --> H[Ordinary checking]
    E --> H
    G --> H
    I[Compilation-unit cleanup] --> J[Remaining general cache and lifecycle cleanup]
```

Partial results do not become successful simply because the repair is gone. Inference failure is distinct from partial evidence and does not enter the annotation-overlay path. General metadata-copying, substitution, wildcard/capture, and diagnostic utilities remain needed by other paths.

The valid issue1455 supplier case, motivating generic cases, and modeled-return regression pass with the visitor physically deleted. Only repair-specific wording and one test method name change in this patch; expected diagnostics do not.
