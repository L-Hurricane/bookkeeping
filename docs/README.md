# v0.1 Engineering Documentation Index

This directory contains the formal v0.1 baselines and implementation handoff documents for Codex.

## Authority / conflict order

For product requirements:

`CR-002 > CR-001 > v0.1-product-requirements.md`

Across engineering stages, later confirmed baselines concretize earlier stages but must not silently change their upstream semantics:

1. Product / Change Requests / Product UI Freeze
2. Confirmed Domain Baseline
3. Architecture Context + Reviewed Architecture Options + Confirmed Architecture Baseline + Accepted ADRs
4. Confirmed Data Model Baseline
5. Confirmed API Baseline
6. Implementation Plan
7. Current Codex Task

## Directory layout

- `product/` — formal product requirements, CR-001, CR-002, Product/UI Freeze
- `domain/` — Confirmed Domain Baseline
- `architecture/` — architecture framing, reviewed options, confirmed baseline, ADRs
- `design/` — Confirmed Data Model and API Baselines
- `implementation/` — Implementation Plan and current Codex task
- `frontend/` — WeChat Mini Program frontend design baseline

## Agent usage

AI coding agents start at the repository `AGENTS.md`, then `docs/implementation/ai-engineering-rules.md`.

Do not read every document on every task. `docs/implementation/v0.1-first-codex-task.md` is the historical Phase 0 handoff, not the default entry for a later task.

Historical drafts, superseded intermediate files, clickable prototypes, and UI visual artifacts are intentionally not included in this engineering-context bundle.
