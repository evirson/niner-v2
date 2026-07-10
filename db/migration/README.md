# Migrations Flyway — `niner_db`

Migrations versionadas do banco. Convenção Flyway: `V<versão>__<descrição>.sql`, aplicadas
em ordem crescente, uma única vez. Referência: spec `spec-driven-erp-varejo.md` §3.5.1,
§3.1.1 (isolamento de tenant) e §3.3.11 (módulo `plataforma`).

## Antes das migrations: bootstrap

`../bootstrap/00_roles.sql` **não é** migration — roda uma vez na inicialização do
cluster, como superusuário (ex.: `docker-entrypoint-initdb.d`). Cria as roles do modelo
de isolamento (P8):

- **`niner_owner`** — dona dos objetos; é quem o Flyway usa para aplicar as migrations.
- **`niner_app`** — role da aplicação; **sem `BYPASSRLS`** e sem ser dona de tabela, para
  que o Row-Level Security seja realmente aplicado a ela.

## Ordem (renumerada)

Primeiro o **Plano de Controle** (control-plane) + a infra de contexto de tenant — porque
`id_tenant` é FK a partir das tabelas de domínio e a função de contexto é base das políticas
RLS. Só depois os módulos de domínio do lojista e, por fim, as políticas RLS.

| Faixa | Conteúdo | Situação |
|-------|----------|----------|
| **V001** | schema `plataforma` | ✅ criado |
| **V002** | infra de contexto de tenant (`plataforma.tenant_atual()`) | ✅ |
| **V003** | tipos ENUM do control-plane | ✅ |
| **V004** | `tenant` (conta assinante) | ✅ |
| **V005** | `plano` (tiers e limites) | ✅ |
| **V006** | `assinatura` | ✅ |
| **V007** | `fatura` + `pagamento` | ✅ |
| **V008** | `webhook_gateway` (idempotência do gateway) | ✅ |
| **V009** | `uso_tenant` (enforcement de limites) | ✅ |
| **V010** | `staff` + `impersonacao_log` | ✅ |
| **V011** | grants de `niner_app` no schema `plataforma` | ✅ |
| **V012** | seed dos planos (🔴 preços provisórios, D1) | ✅ |
| **V013** | tipos ENUM de domínio (canal, pedido, movimento, outbox…) | ✅ |
| **V014** | `identidade.empresa` | ✅ |
| **V015** | `identidade.usuario` + `usuario_rotina` | ✅ |
| **V016** | cadastros: `cliente`, `fornecedor`, `funcionario` | ✅ |
| **V017** | `catalogo`: configs, `produto`, `produto_categoria`, `produto_barra` (**Q7:** `sku` + `ean`) | ✅ |
| **V018** | vendas: `venda`, `venda_devolucao` (R9; sem financeiro — Q5) | ✅ |
| **V019** | `estoque`: `produto_estoque` (**Q2:** `reservado`, `disponivel`), ledger `produto_movimento_*`, `produto_balanco` | ✅ |
| **V020** | `canais`: `canal` (credenciais cifradas), `anuncio` (de-para SKU, R6) | ✅ |
| **V021** | pedidos de canal: `pedido` (idempotente `(canal,id_externo)`), `pedido_item` | ✅ |
| **V022** | `integracao`: `outbox_evento`, `webhook_recebido` (P2) | ✅ |
| **V023** | `cfg_geral` (singleton por tenant) | ✅ |
| **V024** | **RLS de domínio** (final): `ENABLE`+`FORCE` + `USING/WITH CHECK (id_tenant = plataforma.tenant_atual())` em todas as tabelas de tenant + grants de `niner_app` + guarda-corpo que falha se alguma tabela com `id_tenant` ficar sem RLS | ✅ |

> As tabelas do schema `plataforma` são **globais** (P9) e **não** entram no RLS de tenant.
> O RLS (`FORCE`) só se aplica às tabelas de **domínio** do lojista (V014–V023, ativado em V024).
> `financeiro` do lojista **não** entra no v1 (Q5/ADR-010 — Fase 2).

## Reversibilidade

O projeto é greenfield: **V001 é o baseline** (não há migração in-place; os `db/*.txt`
Firebird são só referência de modelagem, convertidos conforme §3.3.1). A spec (§7) exige
migration reversível — manter, quando necessário, o script de reversão manual correspondente.
Em desenvolvimento, recriar do zero (`flyway clean` + `migrate`) é aceitável.

## Convenções

- Nomes de objeto em **português**, `snake_case` (vocabulário de domínio da spec).
- PKs `bigint GENERATED ALWAYS AS IDENTITY`; dinheiro `NUMERIC(12,2)` (P7); tempo `TIMESTAMPTZ`.
- Tabela de domínio: `id_<x>` surrogate PK + `id_tenant bigint NOT NULL REFERENCES plataforma.tenant`;
  chaves naturais únicas **por tenant** (`UNIQUE (id_tenant, …)`); FKs internas por surrogate.
- Datas de auditoria (`criado_em` `DEFAULT now()` / `atualizado_em`) mantidas pela aplicação
  (Spring Data JDBC) — **sem triggers** de banco e **sem** JPA auditing (§3.3.1 / §3.2).
