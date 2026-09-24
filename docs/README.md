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

## Codex usage

Do not read every document on every task.

For Phase 0, start with:

`docs/implementation/v0.1-first-codex-task.md`

That task identifies the minimum baseline documents required for the phase.

Historical drafts, superseded intermediate files, clickable prototypes, and UI visual artifacts are intentionally not included in this Codex engineering-context bundle.
