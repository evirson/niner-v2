# Progresso do Projeto — niner-v2

Registro cronológico das decisões e entregas. Atualizar a cada marco relevante.
**Última atualização:** 2026-07-10

---

## Estado atual

Projeto **spec-driven** em fase de fundação. O **esqueleto da API (Spring Boot 4 / Java 25)** já existe e sobe: 3 superfícies + infra de contexto de tenant. Domínio do lojista (migrations V013+, controllers) ainda não — depende de Q2/Q5/Q7.

| Artefato | Situação |
|---|---|
| `spec-driven-erp-varejo.md` | **v2.0 — pivô SaaS multi-tenant** (Constituição P1–P9 + PRD R1–R21 + plano técnico + control-plane + migrations) |
| `docs/PLANO-DE-NEGOCIO.md` | **Novo** — plano de negócio (planos/preços, trial, funil, métricas SaaS, roadmap, decisões D1–D10) |
| `docs/padroes/` | Mockup de referência de UI (golden file, §3.7) — `TELA.rar` descompactado e removido |
| `db/*.txt` | Schema **legado (Firebird)** versionado como referência (31 tabelas + generators, procedures, triggers) |
| `CLAUDE.md` | Guia do repositório — atualizado para o SaaS multi-tenant (P8/P9, plataforma, `id_tenant`+RLS) |
| `docker-compose.yml` | Infra local de dev: `db` (postgres:18, `niner_db`) + `flyway` (profile `migrate`) + **`api`** (Spring Boot, porta 8080, conecta como `niner_app`); V001–V012 **aplicadas e validadas em banco real** |
| `api/` | **Novo — esqueleto Spring Boot 4.0.7 / Java 25** (Maven). 3 superfícies (`/api/publico`, `/api/v1`, `/api/admin`) com `SecurityFilterChain` separados; `TenantContext` (`ScopedValue`) + `TenantAwareTransactionManager` (`SET LOCAL app.id_tenant`); pacotes-módulo do monólito; pings de fumaça. **7 testes verdes** (Testcontainers Postgres 18 + Flyway) e app **rodando/curada ao vivo**. Persistência: **Spring Data JDBC** |
| `web/`, `admin/`, `site/` | Ainda não criados (scaffolding pendente). Stack do `site/` (SEO — Astro/Next) a decidir |

**Stack alvo:** Java 25 + Spring Boot 4.x · PostgreSQL 18 (Docker, banco **`niner_db`**) · React 19 + Vite (3 apps) · Flyway · JWT. **SaaS multi-tenant** (banco único + `id_tenant` + Postgres RLS).

---

## Linha do tempo

### 2026-07-10 — Esqueleto da API no ar (Fase 0): 3 superfícies + contexto de tenant

Scaffolding do backend (spec §Roadmap Fase 0), **independente** das decisões
bloqueantes Q2/Q5/Q7 — entrega um esqueleto que compila, sobe e prova a arquitetura,
sem as migrations de domínio (V013+).

1. **`api/` criado** via Spring Initializr — **Spring Boot 4.0.7 / Java 25**, Maven,
   deps: webmvc, security, oauth2-resource-server, validation, actuator, postgresql,
   flyway, **data-jdbc**, testcontainers. `groupId com.vetor.niner`.

2. **Decisão de persistência: Spring Data JDBC** (não JPA/Hibernate). Mais explícito e
   previsível para o padrão RLS + `SET LOCAL` por transação. A spec foi atualizada
   (§3.1.1, §3.2, §3.3.1): removidas as menções a Hibernate `@Filter` e JPA auditing;
   timestamps via `DEFAULT now()` + preenchimento no serviço de domínio.

3. **3 superfícies (ADR-007)** — `SegurancaConfig` com `SecurityFilterChain` separados
   por `securityMatcher`: `/api/publico/**` (+ actuator, permitAll), `/api/v1/**`
   (tenant), `/api/admin/**` (staff) e um chain default que **nega** o resto. JWT ainda
   não exigido (sem emissor nesta fase) — marcado `TODO(jwt)`; validação de `aud` entra
   na fase de auth.

4. **Infra de contexto de tenant (P8, §3.1.1)** — `TenantContext` com **`ScopedValue`**
   (Java 25); `TenantFilter` lê o claim `tid` do JWT e liga o contexto na cadeia de
   `/api/v1`; `TenantAwareTransactionManager` roda
   `select set_config('app.id_tenant', :tid, true)` no início da transação, casando com
   as políticas RLS (`plataforma.tenant_atual()`, V002). A app conecta como **`niner_app`**
   (sem BYPASSRLS); **Flyway roda separado** como `niner_owner` (serviço do compose) —
   `spring.flyway.enabled=false` na app.

5. **Pacotes-módulo do monólito** criados com `package-info.java`: `plataforma`,
   `identidade`, `catalogo`, `estoque`, `pedidos`, `precos`, `canais`, `integracao`,
   `comum/{config,tenant,web}`. Domínio entra com as migrations V013+.

6. **Serviço `api` no `docker-compose.yml`** + `Dockerfile` multi-stage (Maven+JDK 25 →
   JRE 25, usuário não-root). Ordem documentada: `db` → `flyway` → `api`.

7. **Verificação de ponta a ponta (tudo verde):**
   - `./mvnw test` → **7 testes** (context loads + 3 superfícies + propagação de tenant
     `tid=42 → id_tenant=42` + rota fora → 403), com **Testcontainers Postgres 18.4** e
     **Flyway aplicando V001–V012** (bootstrap de roles via `bootstrap-test.sql`).
   - App rodando ao vivo contra o `db` do compose (como `niner_app`):
     `/actuator/health` UP; `/api/publico|v1|admin/ping` → 200; rota fora → 403.
   - ⚠️ **Colima + Testcontainers:** exige `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
     (senão o Ryuk falha ao montar o docker.sock). Anotado no README da `api/`.

8. **Gate P8 parcial:** o teste de isolamento cross-tenant só fecha quando existirem
   tabelas de domínio com RLS (V013+). Registrado como pendência — **não** cumprido ainda.

### 2026-07-09 — Infra local no ar: Postgres 18 + migrations validadas

1. **`docker-compose.yml` criado** (raiz, spec §3.5) com dois serviços por ora:
   - `db` — `postgres:18` (banco **`niner_db`**), volume `pgdata`, healthcheck, e `db/bootstrap/` montado em `/docker-entrypoint-initdb.d` (roles criadas no primeiro init).
   - `flyway` — `flyway/flyway:11` sob profile `migrate`; roda como **`niner_owner`** e aplica `db/migration/`. Uso: `docker compose up -d db` e `docker compose run --rm flyway`.
   - Nota técnica: a imagem `postgres:18` mudou o volume de dados para `/var/lib/postgresql` (o yaml de exemplo da spec §3.5 ainda mostra o caminho antigo `/var/lib/postgresql/data`).
   - `api`/`web`/`admin`/`site` entram no compose quando forem scaffolded.

2. **Migrations V001–V012 aplicadas com sucesso em banco real** (PostgreSQL 18.4) — antes só havia validação estática. Verificado:
   - Roles `niner_owner`/`niner_app` criadas, ambas **sem `BYPASSRLS`** (P8).
   - 9 tabelas no schema `plataforma` (dono `niner_owner`); seed dos 3 planos (Essencial R$ 99 / Profissional R$ 249 / Escala R$ 599 — 🔴 provisórios, D1).
   - `plataforma.tenant_atual()` lê `app.id_tenant` corretamente.
   - `niner_app` lê `plataforma.plano` (grant V011) e **não consegue** criar objetos no schema (`permission denied`) — como esperado.

### 2026-07-08 — Pivô para SaaS multi-tenant (spec v2.0)

1. **Padrão de telas incorporado** — `docs/padroes/TELA.rar` descompactado; mockup `cadastro_fornecedor_campos_cinza.html` mantido como *golden file* de UI; RAR e pasta avulsa removidos. Design system documentado na nova **§3.7** da spec (tokens de cor, tema claro/escuro, grid de 12 colunas, componentes de campo/botão, acessibilidade).

2. **Decisão de produto: virar SaaS multi-tenant.** A spec passou a **v2.0**. O que mudou:
   - **Constituição:** novos **P8** (isolamento de tenant inviolável via Postgres RLS) e **P9** (separação control-plane × tenant).
   - **PRD:** multi-tenant/site público/trial/cobrança deixaram de ser non-goal e viraram **CORE**; novas personas C–F; requisitos **R10–R21**; métricas de funil SaaS.
   - **Plano técnico:** topologia de **uma API com 3 superfícies** (`/api/publico`, `/api/v1`, `/api/admin`) + 3 apps React (`web`/`admin`/`site`); **§3.1.1** isolamento de tenant; **§3.3.11** módulo `plataforma` (control-plane); **§3.5.1** sequência de migrations V001–V091 para `niner_db`; ADR-006 a 009.
   - **Novo `docs/PLANO-DE-NEGOCIO.md`** com o plano comercial.

3. **Decisões tomadas nesta sessão:**
   - **Topologia:** Opção A (uma API, monólito modular) — mas **API stateless** e **base-URL configurável em runtime** nos fronts, para rodar 2 servidores/2 APIs e trocar o endereço em manutenção/failover.
   - **Isolamento:** banco único + `id_tenant` + **Postgres RLS** (`FORCE`).
   - **Gateway de cobrança:** **adiado** — adapter abstrato, cobrança manual no início (D3).
   - **Trial:** **14 dias, sem cartão** (D2).
   - **Q6 fechada:** manter `id_empresa` **e** adicionar `id_tenant` (`tenant 1:N empresa`, 1:1 no v1).

4. **Migrations do control-plane criadas** (`db/migration/V001–V012` + `db/bootstrap/00_roles.sql`) — o schema `plataforma` que controla o tenant: roles `niner_owner`/`niner_app` (esta **sem BYPASSRLS**), função de contexto `plataforma.tenant_atual()`, tipos ENUM, e tabelas `tenant`, `plano`, `assinatura`, `fatura`, `pagamento`, `webhook_gateway`, `uso_tenant`, `staff`, `impersonacao_log`, grants e seed de planos (🔴 preços provisórios). Numeração **renumerada em ordem contígua**; domínio do lojista + políticas RLS ficam em V013+ (§3.5.1). Validação apenas estática (Docker daemon desligado no momento).

5. **Novo requisito R22 (ajuda por tela):** toda tela (ERP/backoffice/site) deve ter **ajuda/manual de operação contextual + acesso a vídeo explicativo** (§3.7.1: componente `AjudaDaTela`, catálogo `ajuda_tela` servido pela API, `url_video` NULL ⇒ "em breve"). Adicionado ao template de Spec de Feature (§5) e ao gate de aprovação.

### 2026-07-07 — Fundação da documentação

1. **`CLAUDE.md` criado** — documenta que o repo é spec-driven, a Constituição (P1–P7), a arquitetura pretendida (monolito modular, adapters de canal, outbox no Postgres) e o alerta de que o `db/` é legado Firebird (não carregar padrões como `FLOAT` para dinheiro).

2. **Spec atualizado para v1.1** — modelo de dados (§3.3) reescrito a partir das tabelas reais do `db/`, adaptado de Firebird para PostgreSQL:
   - Nova **§3.3.1** — regras de conversão Firebird → PostgreSQL (`FLOAT`→`NUMERIC`, `S/N`→`BOOLEAN`, `GENERATOR`→`IDENTITY`, procedures/triggers→domínio Java, `TIMESTAMP`→`TIMESTAMPTZ`, etc.).
   - Modelo reorganizado por módulo (§3.3.2–§3.3.9): `identidade`, `catalogo`, `estoque`, `pedidos`, `integracao`, `financeiro`, config e cadastros.
   - Domínio de **marketplaces** (ausente no legado) integrado: `canal`, `anuncio`, `pedido`, `pedido_item`, `outbox_evento`, `webhook_recebido`.
   - Pendências marcadas em **vermelho** (🔴) — convenção definida no topo do documento.

3. **Commit `6c65765`** — CLAUDE.md + `db/` + spec v1.1 enviados para `origin/main`.

4. **GitHub CLI instalado e autenticado** — `gh` 2.96.0 em `C:\Program Files\GitHub CLI\gh.exe` (via winget), autenticado como `evirson` (device flow; escopos `gist`, `read:org`, `repo`).

5. **Coautor convidado** — `claudiocalixto` (`claudio@vetorsistemas.com.br`) convidado com acesso **Write** via `gh api PUT .../collaborators/claudiocalixto -f permission=push`. Convite **aguardando aceite**.

6. **Commit `76922ac`** — `docs/PROGRESSO.md` enviado para `origin/main` (após rebase sobre o commit remoto `9e4fa65` "inclusao de base").

---

## Decisões bloqueantes em aberto (ver §2.7 e §3.3 da spec)

Precisam ser resolvidas **antes de codar** os módulos afetados:

- **Q5 — módulo `financeiro`** (caixa, crediário, contas a pagar/receber, conta corrente): entra no v1 ou fica para depois? Tangencia o Non-goal de PDV. *(Não confundir com o faturamento da plataforma — Anexo A do plano de negócio.)*
- **Q7 — SKU vs EAN**: `codigo_barra` é o EAN ou código interno + coluna `ean` separada?
- **Q2 — estratégia de reserva**: reservar estoque no `recebido` ou no `pago`?
- **Reserva de estoque**: adicionar colunas `reservado`/`minimo` a `produto_estoque` (sem isso não há anti-overselling — P1/R5).
- **Decisões de negócio do SaaS (D1–D10)** — ver `docs/PLANO-DE-NEGOCIO.md`. Abertas: D1 preços, D3 gateway (adiado), D5 nome "Niner", D6 NFS-e da assinatura, D8 dunning, D9 metas, D10 comportamento do estado `INADIMPLENTE`.

> ✅ **Q6 — multi-empresa/tenant:** fechada em 2026-07-08 — manter `id_empresa` **e** adicionar `id_tenant` (banco único + RLS; `tenant 1:N empresa`, 1:1 no v1).

---

## Colaboração / acesso ao repositório

- **Claudio** — username GitHub **`claudiocalixto`** (`claudio@vetorsistemas.com.br`) — é **coautor** com acesso **Write** ao repo `evirson/niner-v2`.
- **Status:** ✅ **convite enviado (2026-07-07), aguardando aceite.** Ele aceita pelo e-mail do GitHub ou em https://github.com/evirson/niner-v2/invitations.
- Após aceitar, comita com a própria identidade Git (`user.name`/`user.email`) e aparece como autor nos commits.

---

## Próximos passos sugeridos

1. Fechar as decisões bloqueantes restantes (Q2, Q5, Q7) e as de negócio (D1, D5, D6, D8, D9, D10); registrar Q6 e as de arquitetura como ADRs (ADR-006 a 009).
2. Scaffolding da Fase 0: ✅ **esqueleto Spring Boot (`api/`) no ar em 2026-07-10** (módulos incl. `plataforma`, 3 superfícies, contexto de tenant, serviço `api` no compose). **Falta** scaffoldar os 3 apps React (`web/`, `admin/`, `site/`) — e decidir o stack de SEO do `site/` (Astro/Next), registrando como ADR-010.
3. Continuar as migrations V013+ (§3.5.1): domínio do lojista (`identidade`, `catalogo`, `estoque`, `pedidos`, `integracao`…) — depende de Q2/Q5/Q7 — e o arquivo final de RLS de domínio.
4. Teste de isolamento (P8) como gate da Fase 0: um tenant nunca lê dado de outro.
