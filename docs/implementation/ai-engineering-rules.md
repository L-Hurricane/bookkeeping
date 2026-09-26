# AI Engineering Rules

Stable rules for coding agents. This file says how to execute the formal baselines. It does not replace them.

Business and technical facts stay in the documents indexed by `AGENTS.md`. If this file and a baseline disagree, the baseline wins. Stop and report `Conflict / Required Decision`.

Feature history, Human Gate step lists, copy decks, test counts, and one-off DevTools incidents stay in `docs/implementation/v0.1-execution-log.md` and the feature task. Do not copy them here.

## 1. Source of truth

Product authority is `docs/product/v0.1-change-002.md`, then `docs/product/v0.1-change-001.md`, then `docs/product/v0.1-product-requirements.md`. The Product/UI freeze is `docs/product/v0.1-product-ui-freeze.md`.

Domain: `docs/domain/v0.1-domain-model.md`.

Architecture: `docs/architecture/v0.1-architecture-baseline.md` and the accepted ADRs in `docs/architecture/adrs/`.

Data and API: `docs/design/v0.1-data-model.md`, `docs/design/v0.1-api-design.md`.

Frontend: `docs/frontend/v0.1-frontend-design.md`.

Implementation sequence: `docs/implementation/v0.1-implementation-plan.md`. Execution history: `docs/implementation/v0.1-execution-log.md`.

A file in `docs/changes/` overrides a baseline only when it is marked `Status: Accepted`, or a later equivalent Current/Accepted status defined by the project. Draft, Proposed, and Deprecated documents do not override. An accepted change overrides only the rule it explicitly changes. `docs/changes/v0.2-optional-transaction-category.md` is `Status: Accepted` and changes Transaction Category from exactly one to zero or one. It does not reopen the rest of v0.1.

A task prompt may select the feature, narrow the scope, and name the technical action. It may not silently replace a frozen product, domain, architecture, API, or data-semantics rule.

## 2. Context loading

Do not scan the repository because something might be useful.

Default order:

1. `AGENTS.md`
2. This file
3. The formal documents that directly govern the current feature
4. The target implementation files
5. Their direct dependencies
6. The tests for those files

When the prompt already names the module, files, or feature, start from those files, their direct dependencies, and their tests.

Search for a rule. Do not open every document under `docs/`, every source tree, every historical task, every old diff, or the full execution log.

## 3. Review artifacts

`all_changes.diff`, review diffs, temporary patches, test logs, and scratch reports are not business sources of truth.

Read the source file. Do not also read a generated copy of the same change. A review diff is for an independent Cross Review. Prefer to generate it outside the repository or in a temporary directory. This rule does not change `.gitignore`.

## 4. Backend shape

The architecture is a Modular Monolith. See `docs/architecture/v0.1-architecture-baseline.md` and ADR-001.

The backend stack is Java 25, Spring Boot 4.1.1, Maven, and MySQL 8.4. Persistence is defined in `docs/implementation/v0.1-implementation-plan.md`: JPA for the write model; JdbcClient or explicit SQL for complex read models. They share one MySQL datasource and one local transaction boundary.

The v0.1 exclusions in Architecture Baseline section 17 stay in force until a new accepted architecture decision changes them. That includes microservices, Redis, MQ/Kafka, Elasticsearch, distributed transactions, distributed locks, Kubernetes, CQRS, Event Sourcing, and Saga. Authentication stays an opaque database session, not JWT. See ADR-008.

The backend is the source of truth for business facts. The frontend does not copy backend domain state.

## 5. Money

API money is a decimal string. The database uses `DECIMAL(19,2)`. Definitions stay in the data model and API design.

Do not use JavaScript `Number` for authoritative money. A display calculation or preview that uses money uses exact decimal-string arithmetic. A client preview is not a business fact. The backend response is.

Do not trust a client-computed balance, Adjustment Amount, or statistics figure because the UI already showed it.

## 6. Business time

The API contract is Beijing time `+08:00`, with seconds. See the domain time model and ADR-010.

Business time selects the natural month and ledger ordering. It does not decide whether a saved record affects Current Balance. It does not replace First Saved Time, First Save Order, or Updated Time.

Every saved record affects Current Balance immediately, including a future business time. A historical business time is not a reconstruction of the balance at that moment. Business time must not be earlier than Account Creation Time.

BalanceAdjustment uses the same choosable business time. The time still does not mean the historical balance was set to the target.

## 7. Client drafts

The frontend does not apply an optimistic Current Balance, ledger, or statistics, and it does not keep a local domain replica or a local business database.

After a successful write, reload from the backend, then render those facts.

UI draft, form state, a temporary preview, navigation intent, and locate intent are allowed. They are not business facts.

## 8. Idempotent create

Create Transaction and Create BalanceAdjustment follow one pattern:

form, validate, immutable snapshot, preparing lock, Idempotency-Key, submit.

After the snapshot exists, that attempt does not read the mutable form again.

Network failure, 5xx, and a contract result that cannot be confirmed are uncertain. Retry the same snapshot and the same key. Do not change the key, reread the form, or reinterpret the old attempt.

A definite failure ends the attempt. The next manual save builds a new snapshot and a new key.

The key is client-generated, high-entropy, and scoped to the operation intent. See API design and ADR-009.

Same key and same request replays. Same key and a different request conflicts. `NO_CHANGE` is a definite idempotent business result. After the created resource is deleted, the old create key must not create a second resource. The frontend does not simulate backend idempotency.

## 9. Runtime contracts

Validate every backend response at runtime. A TypeScript type does not replace that check.

Validate business discriminators, ids, money, time, required relations, and domain invariants. Do not require the JSON to omit null fields when the real backend DTO serializes the other union branch as null. Be strict about business meaning and compatible with the serialization shape.

A malformed 2xx or a contract mismatch is uncertain when the business result cannot be confirmed.

## 10. HTTP and authentication

The shared HTTP client owns authenticated `401`: best-effort session clear, then the centralized login navigation. A page does not implement its own session-expiry owner.

## 11. Transaction

Ordinary transactions are income or expense. Type is immutable after save. Update is a PUT of the editable fields and does not send `type`.

A present Category, and every Tag, must match that fixed type. Category is at most two levels. Zero or one Category follows `docs/changes/v0.2-optional-transaction-category.md`.

A Tag has its own lifecycle. A successful Create Tag remains even if the transaction later fails or is cancelled. The transaction-to-tag association exists only after the transaction save succeeds.

After a successful transaction write, reload the backend ledger. Do not push the row locally.

## 12. BalanceAdjustment

BalanceAdjustment is its own record, not a Transaction subtype. See the domain model and ADR-006.

Create submits Target Balance. Under the account lock, the backend recomputes Current Balance and sets Adjustment Amount to target minus that balance. The lasting persisted fact is Adjustment Amount, not a locked Target Balance.

It changes Current Balance. It is not income or expense, has no Category or Tags, stays out of income and expense statistics, and has no edit. Delete reverses the fixed Adjustment Amount.

`NO_CHANGE` is a normal business result, not an error. It does not create a ledger record.

## 13. Current Balance and Ledger

Current Balance is a derived value, not an entity. Ledger is a derived read model of Transaction plus BalanceAdjustment, not a persisted entity. The definitions stay in the domain model, ADR-005, and the API design.

The frontend does not recompute either into an authority, and it does not cache Current Balance as a business fact. A write that changes the balance keeps account-scoped consistency on the backend.

The ledger’s sort, month, summary, and items are authoritative as returned. Do not resort them on the client unless the formal design says to.

## 14. Post-save locate

The home locate intent is `kind` + `recordId` + `targetMonth`. `transaction` and `adjustment` are both supported. Matching uses kind and record id together.

After a successful create or update that must be shown: reload, render, locate, scroll, highlight. Keep render-before-locate, the generation guard, clearing the pending locate on a manual month switch, preserving it when the backend load fails, and the highlight timeout.

The target month comes from the response business time. An external feature event keeps its own contract. Do not change that contract just because the internal intent was generalized.

## 15. Delete

Delete runs after user confirmation. Distinguish at least idle, deleting, and uncertain.

`deleting` blocks a second operation. `uncertain` retries the same resource identity.

For a resource already known to exist, `204` means finally absent. A `404` on that delete retry is finally absent when the feature’s confirmed semantics say so.

Then reload the home or parent from the backend. Do not optimistically remove the row or patch balance or statistics.

## 16. Reference data

Category and Tag candidate APIs supply edit choices. Saved associations on Transaction Detail are the transaction facts.

If a candidate list fails to load, do not present a saved Category or saved Tags as uncategorized or untagged. If the full reference path cannot be restored, keep the saved fact and allow a degraded display. Do not add a local domain cache for offline fallback.

## 17. Frontend architecture

The client is a native WeChat mini program plus TypeScript: a thin client with rich interaction. Complex UI state is allowed. Business facts still come from the backend.

Do not add a Redux-like global domain store, a local domain database, or an offline-first architecture unless a later formal decision says so.

## 18. Task size

The first time a pattern is built, split the work finely enough to check the new boundary. Once the pattern is stable, deliver the whole feature in one task.

Do not mechanically split model, service, page, submit, detail, and delete into separate agent tasks. Split only for a new high-risk boundary, a new architectural pattern, new concurrency, or a new external contract that needs its own check.

## 19. Collaboration

Normal feature flow:

1. Human and design: requirement, design, and task boundary.
2. Coding agent: implementation, automated tests, and a self-check.
3. Independent Cross Review.
4. If needed, a narrow fix, then review again.
5. After Cross Review PASS, Human Gate.
6. After Human Gate PASS: closeout, execution log, full verification, commit.

The self-check covers compile, lint, tests, an obvious contract mismatch, and scope. It is not a second architecture review. Do not write a long argument that the change is correct. The code and tests are the review input.

Do not skip a gate, commit, or start the next phase unless the task says so.

## 20. Tests and expected results

Do not drop tests to save context.

During implementation, run the related tests and the typecheck or lint the task needs. A Cross Review fix runs the affected tests. A phase closeout runs the full suite. For the mini program that is `npm run typecheck`, `npm run lint`, and `npm test` in `frontend/miniprogram`.

Do not rerun the full suite after every one or two lines unless the change is actually wide. Do not declare PASS without the full suite the closeout requires.

When a test fails, do not change the expected result to match the implementation. First classify it as a requirement defect, design defect, implementation defect, test defect, or omitted phase scope. Change the expected result only after the test itself is shown to be wrong.

## 21. Scope and technical debt

Do the current feature only. Do not refactor an unrelated module, pay down debt, build a future feature, widen a product requirement, add a library, or adjust the architecture because it is nearby.

Record a useful out-of-scope improvement as a non-blocking follow-up or technical debt, and leave it undone.

Fix technical debt in the current task only when it blocks correctness, security, or data integrity for this feature, clearly blocks the next step, or is a small and well-bounded cost that has to be paid to finish this feature. Do not enlarge the diff to make code prettier.

## 22. Context economy and reports

This rule reduces repeated context. It does not lower the quality bar.

Inside one coding task, a later fix prompt states the new defect, the intended behavior, the edit boundary, and the targeted tests. It does not paste the product, the architecture, the original feature prompt, or this file again.

Repeat the stable baseline only when starting a new task, when a rule has changed, when the context is clearly gone, or when the agent is misreading a baseline. A new task can say: read `AGENTS.md` first, then add only what is specific to this feature.

A finished task reports:

1. Implementation Summary
2. Changed Files
3. Verification
4. Issues / Deviations

Report a decision or deviation only when one actually happened.

## 23. Human Gate

Automated tests and Cross Review cover technical correctness, contracts, state machines, edge cases, races, and data consistency.

Human Gate covers the real user path, the real DevTools runtime, product fit, interaction, visual hierarchy, and manual operation. Do not ask a person to repeat the whole automated suite by hand.
