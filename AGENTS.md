# Project Instructions

This repository implements the personal bookkeeping application v0.1.

Authoritative project documents are under `docs/`.

General rules:

- Follow the confirmed v0.1 baselines; do not silently redesign frozen Product, Domain, Architecture, Data Model, or API decisions.
- Do not expand v0.1 product scope without explicit human approval.
- Work only on the requested Implementation Plan phase/task and stop when that task is complete.
- Money must use exact decimal semantics; never use `float`/`double` for business money calculations.
- Business data access must respect Trusted User Context and User Scope.
- Do not introduce Redis, MQ/Kafka, JWT, microservices, distributed locks/transactions, Elasticsearch, Kubernetes, CQRS, Event Sourcing, Saga, or other excluded architecture unless explicitly approved.
- Run the tests required by the current task before reporting completion; never claim tests passed if they were not executed successfully.
- Do not commit passwords, full opaque tokens, or other secrets.
- Prefer the minimum context needed for the current task rather than rereading all historical documents every time.

Current implementation sequence:

`docs/implementation/v0.1-implementation-plan.md`

Current first task:

`docs/implementation/v0.1-first-codex-task.md`
