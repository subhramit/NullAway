# Independent review and projection-assertion rationale

## Why the new assertion intentionally requires COMPLETE

The questioned change is in the newly added `checkWildcardProjectionBarriers` fixture, not a pre-existing regression expectation. It initially assumed that every variable must be incomplete when the source was a fixed variable with a nullable-admitting bound. That was an incorrect assumption about this fixture and the solver contract.

The fixture supplies authoritative `Object` Java shapes for E, R, and U. It adds E-to-R and a wildcard containment requirement; the two controls differ in this relation:

| Control | Relation | Why it does not establish nullable root evidence |
|---|---|---|
| Nullable destination | `OuterT <: @Nullable E` | The destination occurrence accepts null without requiring bare E's inferred root to be nullable |
| Non-null source | `@NonNull OuterT <: E` | The source occurrence explicitly excludes null even though its declaration admits nullable instantiations |

Both keep structural evidence, but both permit the non-null Object shapes. There are no unresolved nested arguments or array components in those shapes. The previous assertion confused an unknown bare fixed-variable root with an unknown structural relationship.

`validateSolution` has deliberately used `compareTopLevel=false` for these stored structural relations since before the fixed-bound repair. It still checks nested roots where required, such as generic arguments and array components. The repair did **not** change this validation policy to make the test pass. Root constraints and ordinary source-expression/formal-occurrence compatibility remain separate responsibilities.

The final assertion requires:

- exactly the expected variable keys;
- empty incomplete and inconsistent sets, including site completeness;
- the precise Object symbols and non-null annotations;
- no private fresh inference symbols in returned types.

This is more precise than forcing an unexplained incomplete result. It is **not** permission to label all fixed-variable problems complete. Other direct tests require the named wildcard-bound exception, including when late nullable-bound evidence arrives after a previous successful solve.

The ordinary call path still substitutes into the declared formal type, preserving `@Nullable E` as a nullable occurrence, and performs argument compatibility/null-use checking. COMPLETE certifies represented inference substitutions; it does not mean every caller argument has passed every ordinary checker.

## Review-discovered defects were tested before fixing

The first fixed-bound implementation passed the issue's four negative cases and nine integration methods but had two real defects:

1. Reading structural lower evidence leaked `T <: @Nullable E` into bare E's root. An `empty(@Nullable E)` call then produced a false positive on its independent non-null result.
2. Deferred inner failures used inner ownership for deduplication but outer ownership for description/cache, moving diagnostics onto an innocent outer `id`.

Both were reproduced by executing new tests before the fixes. Dedicated root-compatible fixed evidence and a distinct owning-site deferred exception repair them. The permanent tests now pass without altered expectations. The new direct fixture's initially incorrect incomplete-status assumption was separately corrected after source tracing, and independent review confirmed the final COMPLETE contract.

## Comparison with #1473 and #1574

Fresh independent review fetched those PRs and compared their merged mechanisms with the final source:

- #1473 repaired selected call-site parameter types using corresponding actual argument types, initially for top-level method-variable parameters.
- #1574 extended the repair recursively into class/array substitutions, still driven by argument-to-parameter correspondence rather than a fully solved inference problem.
- The current complete path instead assigns per-call ownership, collects lower/upper/declaration evidence, recovers Java shape, validates reconciled substitutions, and feeds complete executable types to consumers.

The fixed-bound wildcard rule is a specialized semantic obligation, not a syntax-specific annotation repair. A symbolic T may admit nullable instantiations without being an explicitly nullable occurrence. The non-null wildcard requirement needs that provenance after nested constraints arrive; forcing T globally nullable would break legitimate parametric uses. No rule names List.copyOf, ArrayList, identity methods, or an issue-specific AST pattern.

In the first new patch, complete inference returns through full substitution before the guarded legacy fallback. The last patch physically deletes that fallback's visitor, wrapper and guard. General incomplete diagnostic overlays and metadata-copying utilities remain because removing them would change other behaviors.

## Independent judgement on complexity

The fresh correctness review found no concrete blocker in the final projection assertion or reviewed solver/lifecycle paths. It supported retaining the current code rather than inventing a broader rewrite to make the design look cleaner.

It did identify a real maintenance tradeoff: constraint generation, scalar propagation, structured reconciliation, validation, and ordinary compatibility encode related rules in multiple places. A unified typed evidence/obligation representation could reduce parallel bookkeeping, but no demonstrated smaller equally correct replacement justified changing the implementation during a no-net-code-change packaging pass.

The direct projection fixture tests root barriers on Object shapes, not every nested structure/projection cross-product. Related integration and declaration-bound tests provide additional coverage, but that is not exhaustive proof. Captures, class-owner model callbacks, and the supported-version/performance matrix retain the documented limits in `deviation.md`.

## Why three patches rather than seven

A separate independent stack review recommended:

1. present the final inference API and consumers once;
2. isolate array expression/dataflow consumption, which does not revise the solver contract;
3. remove the old visitor last, because fallback removal is a distinct behavioral acceptance gate.

Source inspection found only two context mismatches when separating arrays and cleanup, not architectural dependencies. Full module tests and self-checks subsequently passed at all three actual boundaries. Thus the three-unit split is tested rather than merely stylistic.

The pre-format regrouped tree was byte-identical to the historical 01–07 implementation and tests. Subsequent Spotless formatting and stack rebasing preserve the solution and expectations: all 433 tracked files match a separately formatted checkout of the original final commit, and the final formatted suite/self-check pass. Independent reviews were read-only; executed validation is reported separately in the README. No claim of universal absence of false positives or false negatives follows from finite tests or review.
