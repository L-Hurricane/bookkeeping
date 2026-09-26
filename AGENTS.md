# Project Instructions

This repository is the personal bookkeeping application. The backend owns business facts. The WeChat mini program is a thin client with rich interaction.

A new coding task starts here, then reads `docs/implementation/ai-engineering-rules.md`. After that, read only the baselines and source files the current task needs.

## Source of truth

Product, highest first:

1. `docs/product/v0.1-change-002.md`
2. `docs/product/v0.1-change-001.md`
3. `docs/product/v0.1-product-requirements.md`

The current Product/UI freeze boundary is `docs/product/v0.1-product-ui-freeze.md`.

A file in `docs/changes/` overrides a baseline only when it is marked `Status: Accepted`, or a later equivalent Current/Accepted status defined by the project. Draft, Proposed, and Deprecated documents do not override. An accepted change overrides only the sentence it names. The v0.1 files stay as history.

| Area | Authority |
|---|---|
| Domain | `docs/domain/v0.1-domain-model.md` |
| Architecture | `docs/architecture/v0.1-architecture-baseline.md` and the accepted ADRs in `docs/architecture/adrs/` |
| Data model | `docs/design/v0.1-data-model.md` |
| API | `docs/design/v0.1-api-design.md` |
| Frontend | `docs/frontend/v0.1-frontend-design.md` |
| Implementation sequence | `docs/implementation/v0.1-implementation-plan.md` |
| Execution history | `docs/implementation/v0.1-execution-log.md` |
| Agent workflow | `docs/implementation/ai-engineering-rules.md` |

A task prompt may name the feature, narrow the scope, and set this round’s goal. It does not silently override a frozen product, domain, architecture, API, or data-semantics decision. If it conflicts with a baseline, stop that part, report `Conflict / Required Decision`, and wait. Do not choose.

## How much to read

Do not scan the repository by default.

1. This file.
2. `docs/implementation/ai-engineering-rules.md`.
3. The formal documents that directly govern the current feature.
4. The target source files, their direct dependencies, and their tests.

Search for a rule instead of opening every document, historical task, old diff, or the full execution log. `docs/implementation/v0.1-first-codex-task.md` is the historical Phase 0 handoff, not the default task.

## Rules that must hold while coding

Details and references are in `docs/implementation/ai-engineering-rules.md`. These are the invariants:

- The backend is the source of truth. The client may hold drafts, previews, and navigation intent. It does not keep a domain replica or optimistic balance, ledger, or statistics.
- Money is exact decimal. API money is a decimal string. Do not use JavaScript `Number`, `float`, or `double` for authoritative money.
- Business time is Beijing time `+08:00` to the second. It selects the natural month and ledger order. It does not reconstruct a historical balance. Saved records, including future business time, affect Current Balance immediately.
- An idempotent create freezes an immutable snapshot, then an Idempotency-Key. An uncertain retry uses that same snapshot and key.
- Transaction and BalanceAdjustment are separate records. Transaction type is immutable after save. BalanceAdjustment has no edit. Its persisted fact is Adjustment Amount.
- Business data access respects Trusted User Context and User Scope.
- Do not add Redis, MQ/Kafka, JWT, microservices, distributed locks or transactions, Elasticsearch, Kubernetes, CQRS, Event Sourcing, or Saga unless a new accepted architecture decision says so.
- Stay inside the current task. Record out-of-scope improvements. Do not do them.
- Do not commit secrets. Do not commit, skip a gate, or start the next phase unless the task says so.
- Do not claim tests passed unless they were executed and succeeded.

## When the task is done

Return Implementation Summary, Changed Files, Verification, and Issues / Deviations. Report a real decision or deviation. Do not restate the task.

Run the checks the task names. A phase closeout runs the full suite for the area that changed. A small fix in the same task runs the affected tests.

## Not yours to decide

Frozen baselines, product scope, new libraries, excluded architecture, and changing a test’s expected result so that a failing implementation can pass.
