# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A **greenfield, spec-driven ERP** for small retailers that sell both in a physical store and across 2–5 online marketplaces (Mercado Livre, Shopee, Amazon, own e-commerce). The product's job is to make the ERP the single source of truth for stock and price, and to keep every channel in sync automatically (zero overselling).

**There is no application source code yet.** The repo currently contains only:
- `spec-driven-erp-varejo.md` — the governing document (Constitution + PRD + technical plan + templates). This is the primary source of truth.
- `db/*.txt` — a **legacy** Firebird/InterBase schema (DDL, procedures, triggers, generators) from an *existing* retail ERP, kept for reference. It is **not** the schema of the system being built.
- `docs/` — currently empty.
- `db/*.RAR` — archived dumps of the legacy database.

Everything below flows from `spec-driven-erp-varejo.md`; read it before making any design decision.

## Working model: spec-driven

Nothing is implemented without the preceding artifact being approved, in this chain:
**Constitution → Product spec (PRD) → Technical plan → Tasks.** Golden rule: *if a question arises during implementation, the answer must be in the spec; if it isn't, update the spec before writing code.*

For any new feature use the templates in the spec: **§5 Feature Spec**, **§6 ADR**, **§7 Task**. Acceptance criteria are written as `Dado/Quando/Então` (Given/When/Then) and each becomes an automated test **before** merge.

## Constitution — non-negotiable principles (spec §1)

These override any convenience; do not violate them without a superseding ADR:

- **P1 — Stock is the source of truth.** The ERP is master of stock and price; marketplaces are replicas. No integration writes stock back without going through the central domain.
- **P2 — Every integration is async and idempotent.** Marketplace calls go through the outbox/queue with retry. Reprocessing an event must never duplicate an order, stock movement, or invoice.
- **P3 — Auditability.** Every mutation of stock/price/order writes an immutable record (who, when, source, old/new value).
- **P4 — API-first.** The React frontend consumes only the REST API. **No business logic in the frontend.**
- **P5 — Tests track the spec.** Every acceptance criterion becomes an automated test before merge.
- **P6 — Simplicity first.** Modular monolith before microservices; one Postgres instance; no external broker (Kafka/RabbitMQ) in v1 — use Postgres as the queue via outbox + `SELECT … FOR UPDATE SKIP LOCKED`.
- **P7 — Money in `NUMERIC`.** Never float for monetary values — `NUMERIC(12,2)` in the DB, `BigDecimal` in Java. **Note:** the legacy `db/` schema uses `FLOAT` for money; do not carry that forward.

## Intended architecture (spec §3)

Modular monolith. Planned stack: **Java 25 + Spring Boot 4.x**, **PostgreSQL 18** (Docker), **React 19 + Vite + TypeScript**, Flyway migrations, JWT auth (roles `ADMIN` / `OPERADOR`), springdoc-openapi. Tests: JUnit 5, Testcontainers (real Postgres), WireMock (marketplace APIs), Playwright (critical e2e). Virtual threads enabled for integration I/O.

Domain modules (packages): `catalogo/ estoque/ pedidos/ precos/ canais/ identidade/ integracao/{mercadolivre,shopee}`. Modules communicate via Java interfaces + internal domain events.

Key patterns:
- **Adapter / anti-corruption layer per marketplace**, all implementing a common `CanalDeVenda` interface (`publicarAnuncio`, `atualizarEstoque`, `atualizarPreco`, `importarPedidos`, `confirmarEnvio`). The domain never sees ML/Shopee payloads. Adding a second channel (Shopee, §Fase 3) is the test of this design.
- **Outbox pattern:** stock/price mutations write an event to `outbox_eventos` in the same transaction; a `@Scheduled` worker publishes to channels with exponential backoff + dead-letter.
- **Idempotency:** imported orders keyed by natural key `(canal, id_externo)` with a unique constraint; processed webhooks record their `webhook_id`.

The new core data model, API contract sample, and docker-compose layout live in spec §3.3–§3.5. The repo layout the plan assumes is `api/` (Spring Boot) and `web/` (React), not yet created.

## Build / run

No build tooling exists yet — there is no `pom.xml`, `package.json`, or `docker-compose.yml` in the repo. When scaffolding, follow spec §3.5: dev is `docker compose up` bringing up `db` (postgres:18), `api` (port 8080), `web` (port 5173). Do not invent build/test commands in docs before the corresponding project is scaffolded.

## Conventions to honor when building

- API: version in path (`/api/v1/...`); errors as Problem Details (RFC 9457); cursor pagination for lists; OpenAPI written **before** implementation (the contract is part of the feature spec).
- Flyway migrations under `db/migration`, versioned and reversible.
- Channel credentials encrypted at rest (AES-GCM, key outside the DB); `JSONB` for raw integration payloads.
- Everything (identifiers, table/column names, domain language) is in **Portuguese** — match the existing vocabulary (`produto`, `variacao`, `estoque`, `movimentacao_estoque`, `canal`, `anuncio`, `pedido`).
