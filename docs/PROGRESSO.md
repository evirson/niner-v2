# Progresso do Projeto — niner-v2

Registro cronológico das decisões e entregas. Atualizar a cada marco relevante.
**Última atualização:** 2026-07-07

---

## Estado atual

Projeto **spec-driven** em fase de fundação (pré-código). Ainda **não há código de aplicação** — só a spec, o schema legado de referência e a documentação de apoio.

| Artefato | Situação |
|---|---|
| `spec-driven-erp-varejo.md` | v1.1 — Constituição + PRD + plano técnico + modelo de dados adaptado |
| `db/*.txt` | Schema **legado (Firebird)** versionado como referência (31 tabelas + generators, procedures, triggers) |
| `CLAUDE.md` | Guia do repositório para instâncias do Claude Code |
| `api/`, `web/` | Ainda não criados (scaffolding pendente) |

**Stack alvo:** Java 25 + Spring Boot 4.x · PostgreSQL 18 (Docker) · React 19 + Vite · Flyway · JWT.

---

## Linha do tempo

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

- **Q5 — módulo `financeiro`** (caixa, crediário, contas a pagar/receber, conta corrente): entra no v1 ou fica para depois? Tangencia o Non-goal de PDV.
- **Q6 — multi-empresa**: manter `id_empresa` desde já (preparando P2) ou remover no v1?
- **Q7 — SKU vs EAN**: `codigo_barra` é o EAN ou código interno + coluna `ean` separada?
- **Q2 — estratégia de reserva**: reservar estoque no `recebido` ou no `pago`?
- **Reserva de estoque**: adicionar colunas `reservado`/`minimo` a `produto_estoque` (sem isso não há anti-overselling — P1/R5).

---

## Colaboração / acesso ao repositório

- **Claudio** — username GitHub **`claudiocalixto`** (`claudio@vetorsistemas.com.br`) — é **coautor** com acesso **Write** ao repo `evirson/niner-v2`.
- **Status:** ✅ **convite enviado (2026-07-07), aguardando aceite.** Ele aceita pelo e-mail do GitHub ou em https://github.com/evirson/niner-v2/invitations.
- Após aceitar, comita com a própria identidade Git (`user.name`/`user.email`) e aparece como autor nos commits.

---

## Próximos passos sugeridos

1. Fechar as decisões bloqueantes (Q2, Q5, Q6, Q7) e registrar como ADRs.
2. Scaffolding da Fase 0: `docker-compose.yml`, esqueleto Spring Boot (`api/`) com módulos, Flyway, `web/` React.
3. Gerar as primeiras migrations Flyway (`V001__*.sql`) convertendo o legado conforme §3.3.1.
