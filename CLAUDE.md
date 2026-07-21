# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A **greenfield, spec-driven ERP** (product name **Niner**, DB `niner_db`) for small retailers that sell both in a physical store and across 2–5 online marketplaces (Mercado Livre, Shopee, Amazon, own e-commerce). The product's job is to make the ERP the single source of truth for stock and price, and to keep every channel in sync automatically (zero overselling).

**As of spec v2.0 it is a multi-tenant SaaS**, sold by subscription. Two planes that must never be conflated:
- **Control Plane ("Plataforma Niner")** — Vetor's business: tenants, pricing plans, subscriptions, invoices, billing, support. Global tables under module `plataforma/` (outside tenant RLS).
- **Tenant Plane (the retailer's ERP)** — catalog, stock, orders, channels and the retailer's own internal finance (legacy `financeiro`, scope Q5). Every row isolated by `id_tenant`.
- Vocabulary rule: *assinatura/plano/trial/mensalidade/gateway* = **platform** (Vetor charges the retailer); *caixa/crediário/contas a pagar-receber/conta corrente da loja* = **retailer's finance**. Never mix.

Acquisition is self-service via a **public site** with a **60-day, no-card trial**; a **backoffice** manages tenants; billing charges subscribers (gateway **to be defined** — modelled behind an abstract adapter, manual billing at first).

**Implementation is underway** (see `docs/PROGRESSO.md` for the up-to-date, chronological state). As of 2026-07-21: `api/` (Spring Boot 4 / Java 25) has the control-plane + tenant-domain schema (`db/migration/V001`–`V027`), auth/signup/trial, four full cadastro screens (`cadastros.cliente` — CRUD + categoria —, `cadastros.funcionario`, `cadastros.planocontas` and `cadastros.fornecedor`) plus the first system-config screen (`configuracao.geral`, singleton per tenant, ADMIN-only — deliberately outside the cadastro pattern), and the cross-cutting `comum.telaconfig` (per-tenant field visibility/required); `web/` (React) has the ERP shell, login/handoff, painel, and the Clientes/Fornecedores/Funcionários/Plano de Contas/Parâmetros do Sistema screens; `site/` (Astro) has the public landing + signup; `admin/` (backoffice) does not exist yet. The repo also contains:
- `spec-driven-erp-varejo.md` — the governing document (Constitution + PRD + technical plan + templates), **v2.0 (SaaS pivot)**. Primary source of truth.
- `docs/PLANO-DE-NEGOCIO.md` — the business plan (pricing, funnel, SaaS metrics, roadmap, open decisions D1–D10).
- `docs/PROGRESSO.md` — progress log. `docs/padroes/` — UI reference mockup (golden file, spec §3.7). `docs/telas/` — per-screen feature specs (`cliente.md`, `funcionario.md`, `plano-contas.md`, `fornecedor.md`, `configuracao-geral.md`, `configuracao-tela.md`); `cliente.md` is the reference for the cadastro screen pattern, `plano-contas.md` shows how to adapt it when the schema differs (business-key PK, no `ativo`), `fornecedor.md` shows the pattern for a mandatory FK to another cadastro (select + embedded quick-create modal, `PlanoContasModal.tsx`), `configuracao-geral.md` shows the pattern for a per-tenant singleton settings screen (no list/create/delete, ADMIN-only).
- `db/*.txt` — a **legacy** Firebird/InterBase schema (DDL, procedures, triggers, generators) from an *existing* retail ERP, kept for reference. It is **not** the schema of the system being built (converted per §3.3.1 into `db/migration/`).
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
- **P8 — Tenant isolation is inviolable.** No query crosses a tenant boundary; enforced by the DB via Postgres RLS (`FORCE ROW LEVEL SECURITY`), not just app code. App role `niner_app` never has `BYPASSRLS`; migrations run as `niner_owner`. Every tenant-data table carries `id_tenant` + an RLS policy; `plataforma/` tables are the documented exception. Every non-request path (worker/outbox/webhook) sets the `TenantContext` before touching domain data.
- **P9 — Plane separation (control-plane × tenant).** Platform data (subscription, invoices, billing) and retailer data never share an access policy. Platform staff reach tenant data only via **audited impersonation** (P3).

## Intended architecture (spec §3)

Modular monolith, **one API with three surfaces** (ADR-007): `/api/publico/**` (public site: signup, checkout, trial), `/api/v1/**` (tenant ERP, JWT `tid` claim + RLS), `/api/admin/**` (platform backoffice, staff JWT). Planned stack: **Java 25 + Spring Boot 4.x**, **PostgreSQL 18** (Docker, DB `niner_db`), **React 19 + Vite + TypeScript**, Flyway migrations, JWT auth (tenant roles `ADMIN`/`OPERADOR`; staff roles `SUPER_ADMIN`/`SUPORTE`/`FINANCEIRO`), springdoc-openapi. Tests: JUnit 5, Testcontainers (real Postgres), WireMock (marketplace APIs), Playwright (critical e2e). Virtual threads enabled for integration I/O. **API is stateless**; the three React apps read the API base-URL at **runtime** (not baked into the bundle) so two API servers can run behind a switchable address for maintenance/failover.

**Tenant isolation (ADR-006):** shared DB + `id_tenant` discriminator on every domain table + Postgres RLS. `tenant 1:N empresa` (1:1 in v1). Request resolves tenant from JWT `tid` → `TenantContext` → `SET LOCAL app.id_tenant` per transaction; policies use `current_setting('app.id_tenant')`. Closes legacy Q6: keep `id_empresa` **and** add `id_tenant`.

Domain modules (packages): `catalogo/ estoque/ pedidos/ precos/ canais/ identidade/ integracao/{mercadolivre,shopee}` (+ `financeiro/` if Q5), plus the control-plane module **`plataforma/`** (tenant, plano, assinatura, fatura, pagamento, uso_tenant, staff — global, no tenant RLS). Modules communicate via Java interfaces + internal domain events.

Key patterns:
- **Adapter / anti-corruption layer per marketplace**, all implementing a common `CanalDeVenda` interface (`publicarAnuncio`, `atualizarEstoque`, `atualizarPreco`, `importarPedidos`, `confirmarEnvio`). The domain never sees ML/Shopee payloads. Adding a second channel (Shopee, §Fase 3) is the test of this design.
- **Outbox pattern:** stock/price mutations write an event to `outbox_eventos` in the same transaction; a `@Scheduled` worker publishes to channels with exponential backoff + dead-letter.
- **Idempotency:** imported orders keyed by natural key `(canal, id_externo)` with a unique constraint; processed webhooks record their `webhook_id`.

The new core data model, API contract sample, and docker-compose layout live in spec §3.3–§3.5 (migration sequence V001–V091 in §3.5.1; control-plane tables in §3.3.11). The repo layout is `api/` (Spring Boot) + three React apps — `web/` (retailer ERP), `admin/` (platform backoffice, not yet created), `site/` (public acquisition).

## Build / run

Per spec §3.5: `docker compose up -d db && docker compose run --rm flyway` brings up Postgres and applies `db/migration/`; `docker compose up -d --build api` (or `cd api && ./mvnw spring-boot:run`) for the API (port 8080, connects as `niner_app`); `cd web && npm run dev` (5173) and `cd site && npm run dev` (5175) for the fronts. API tests: `cd api && ./mvnw test` (Testcontainers — see `api/README.md` for the container-runtime workaround when no JDK is installed on the host). `admin/` has no build yet (not scaffolded).

## Conventions to honor when building

- API: version in path (`/api/v1/...`); errors as Problem Details (RFC 9457); list endpoints paginate by page number (`pagina`/`limite`, `LIMIT/OFFSET` + total count) — not cursor, so screens can jump to any page (revised 2026-07-21, see `docs/PROGRESSO.md`); OpenAPI written **before** implementation (the contract is part of the feature spec).
- Flyway migrations under `db/migration`, versioned and reversible.
- Channel credentials encrypted at rest (AES-GCM, key outside the DB); `JSONB` for raw integration payloads.
- Everything (identifiers, table/column names, domain language) is in **Portuguese** — match the existing vocabulary (`produto`, `variacao`, `estoque`, `movimentacao_estoque`, `canal`, `anuncio`, `pedido`).
- **CPF/CNPJ validation:** CPF is always 11 numeric digits (unchanged). CNPJ is **alphanumeric** since Receita Federal's IN RFB 2.229/2024 (rollout from July 2026): positions 1–12 (raiz+ordem) accept `0-9A-Z`, positions 13–14 (check digits) are always numeric. Check-digit algorithm: each character's value = ASCII code minus 48 (digits keep 0–9, letters become 17–42); same weights/mod-11 as before (`[5,4,3,2,9,8,7,6,5,4,3,2]` then `[6,5,4,3,2,9,8,7,6,5,4,3,2]`). **Any table with a CNPJ field must reuse this logic** — see `web/src/lib/masks.ts` (`somenteAlfanumerico`, `cnpjValido`) and `api/.../cadastros/cliente/Documentos.java` for the reference implementation; never strip letters with a digits-only cleaner before validating/persisting a CNPJ.
- **Cadastro screen pattern (consolidated 2026-07-21):** new CRUD screens must copy the structure already proven by `cadastros.cliente` + `cadastros.funcionario` — do not reinvent it. Read `docs/telas/cliente.md` and `docs/telas/funcionario.md`, then mirror: page-number pagination fixed at 50 items with sliding-window navigation; sortable column headers (allowlist on the backend, never string-concatenate the client's column into SQL); three row-action icons (green view → `/x/:id/visualizar` in read-only mode via `<fieldset disabled>`, blue edit, red delete); delete falls back to deactivating when the row has dependents; screen-level field config per tenant (`cfg_tela_campo` + `ConfiguracaoTelaService.CAMPOS_POR_TELA`), whose "required" flag is enforced **again on the server**; free-text fields always uppercased (front + back); `AjudaDaTela` (R22) entry; screen icon left of the title; fixed header/footer with only the table (or form body) scrolling; and the read-only audit section (`InfoRegistro.tsx`) at the end of every form.
