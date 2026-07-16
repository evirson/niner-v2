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

> **Cuidado com `public`:** `ALTER DATABASE niner_db OWNER TO niner_owner` muda o dono do
> **banco**, não o dono do schema `public` (que já existia, criado pelo superusuário de
> bootstrap da imagem). Como as migrations de domínio (V013+) criam tipos/tabelas **sem**
> prefixo de schema — portanto em `public` — o bootstrap precisa de um
> `GRANT CREATE ON SCHEMA public TO niner_owner` explícito, senão o Flyway falha em V013
> com `permission denied for schema public`. Isso não aparece nos testes porque
> `api/src/test/resources/bootstrap-test.sql` roda o Flyway como o superusuário do
> container Testcontainers, não como `niner_owner` — o caminho real
> (`docker compose run --rm flyway`) só é exercitado pelo bootstrap de produção/dev.

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
| **V013** | tipos ENUM de domínio (canal, pedido, movimento, outbox, `genero_cliente`…) | ✅ |
| **V014** | `identidade.empresa` (`codigo_empresa` único por tenant, só para exibição em relatório; `cfg_nome_etiqueta` obrigatório) | ✅ |
| **V015** | `identidade.usuario` (e-mail único por tenant, case-insensitive) + `usuario_rotina` | ✅ |
| **V016** | cadastros: `cfg_categoria_cliente`, `cliente` (`id_categoria_cliente` NOT NULL; `data_nascimento`/`genero` obrigatórios só p/ pessoa física, `CHECK`; `whatsapp`/`instagram`/`facebook`/`tiktok`), `fornecedor`, `funcionario` (`telefone`) | ✅ |
| **V017** | `catalogo`: configs, `produto` (sem `imagem`), `produto_categoria`, `produto_barra` (**Q7:** `sku` + `ean`), `produto_imagem` (galeria, `indice smallint` único por produto) | ✅ |
| **V018** | vendas: `venda` (sem `id_funcionario` — comissão/vendedor ficam em `produto_movimento_detalhe`), `venda_devolucao` (R9; sem financeiro — Q5) | ✅ |
| **V019** | `estoque`: `produto_estoque` (**Q2:** `reservado`, `disponivel`), `produto_movimento_mestre` (imutável) + `produto_movimento_detalhe` (sem `saldo_apos` por linha; corrigível, ver trigger), `produto_balanco` (`id_balanco BIGINT`, sem `qtd_sistema`/`observacao`), **trigger `trg_produto_movimento_detalhe_estoque`** (`fn_atualiza_estoque_movimento`) mantém `produto_estoque.qtd_estoque` em INSERT/UPDATE/DELETE do ledger | ✅ |
| **V020** | `canais`: `canal` (credenciais cifradas), `anuncio` (de-para SKU, R6) | ✅ |
| **V021** | pedidos de canal: `pedido` (idempotente `(canal,id_externo)`), `pedido_item` | ✅ |
| **V022** | `integracao`: `outbox_evento`, `webhook_recebido` (P2) | ✅ |
| **V023** | `cfg_geral` (singleton por tenant; `cfg_usa_variante_linha`/`cfg_usa_variante_coluna` boolean default true; sem `moeda_devolucao`) | ✅ |
| **V024** | **RLS de domínio** (final): `ENABLE`+`FORCE` + `USING/WITH CHECK (id_tenant = plataforma.tenant_atual())` em todas as tabelas de tenant + grants de `niner_app` + `REVOKE UPDATE, DELETE` só em `produto_movimento_mestre` (imutabilidade, P3 — `produto_movimento_detalhe` ficou de fora desde 2026-07-16, ver trigger em V019) + guarda-corpo que falha se alguma tabela com `id_tenant` ficar sem RLS | ✅ |

> As tabelas do schema `plataforma` são **globais** (P9) e **não** entram no RLS de tenant.
> O RLS (`FORCE`) só se aplica às tabelas de **domínio** do lojista (V014–V023, ativado em V024).
> `financeiro` do lojista **não** entra no v1 (Q5/ADR-010 — Fase 2).
>
> **FKs compostas (2026-07-16):** V014–V022 tiveram **todas** as FKs entre tabelas de
> domínio convertidas de simples para compostas `(id_tenant, id_x)` — ~34 constraints em
> ~17 tabelas — fechando um gap real de isolamento (P8) que FK simples não cobre. Ver
> "Convenções" abaixo para o porquê.

## Reversibilidade

O projeto é greenfield: **V001 é o baseline** (não há migração in-place; os `db/*.txt`
Firebird são só referência de modelagem, convertidos conforme §3.3.1). A spec (§7) exige
migration reversível — manter, quando necessário, o script de reversão manual correspondente.
Em desenvolvimento, recriar do zero (`flyway clean` + `migrate`) é aceitável.

## Convenções

- Nomes de objeto em **português**, `snake_case` (vocabulário de domínio da spec).
- PKs `GENERATED ALWAYS AS IDENTITY`: `plataforma.tenant.id_tenant` é `smallint` (raiz do
  isolamento); todo `id_tenant` de tabela de domínio/control-plane é `smallint`, igual ao
  da raiz. Demais PKs surrogate (`id_<entidade>`) são `integer`, **exceto
  `produto_balanco.id_balanco` (`bigint`, decisão explícita de 2026-07-16 — volume de
  contagens de inventário esperado bem maior que o das demais tabelas)**. Dinheiro
  `NUMERIC(12,2)` (P7); quantidade `NUMERIC(14,3)`; percentual `NUMERIC(5,2)`; tempo
  `TIMESTAMPTZ`.
- Tabela de domínio: `id_<x>` surrogate PK (`integer`) + `id_tenant smallint NOT NULL
  REFERENCES plataforma.tenant`; chaves naturais únicas **por tenant** (`UNIQUE (id_tenant,
  …)`); e-mail/login sempre **case-insensitive** (`UNIQUE INDEX ... (id_tenant,
  lower(email))`, mesmo padrão em `usuario` e `plataforma.staff`).
- **FKs entre tabelas de domínio são sempre compostas: `FOREIGN KEY (id_tenant, id_x)
  REFERENCES tabela (id_tenant, id_x)`** — nunca `FOREIGN KEY (id_x) REFERENCES tabela
  (id_x)` sozinho (decisão de 2026-07-16, P8). **Motivo (achado real, não teórico):** o
  Postgres não aplica RLS na checagem de integridade referencial — com FK simples, um
  tenant conseguia inserir uma linha com `id_tenant` próprio mas `id_empresa`/`id_variacao`
  (etc.) de **outro** tenant, e a trigger de estoque (abaixo) materializava esse cruzamento
  como saldo fabricado. Toda tabela referenciada por outra ganhou `UNIQUE (id_tenant,
  id_<pk>)` (ex.: `empresa_id_empresa_uk`) como base da FK composta — a PK sozinha não
  serve de destino porque não inclui `id_tenant`. FKs nullable (ex.: `venda.id_cliente`)
  continuam funcionando normal: `MATCH SIMPLE` (padrão) não checa a FK se qualquer coluna
  for `NULL`.
- **Sem `ON DELETE CASCADE`** em nenhuma FK do domínio: apagar um registro com dependentes
  falha por violação de FK — desfazer a dependência é sempre ação explícita da aplicação,
  nunca implícita no schema.
- **`produto_movimento_mestre` é imutável** (mesmo tratamento de `plataforma.impersonacao_log`):
  `niner_app` não recebe `UPDATE`/`DELETE` (V024/V011) — correção é sempre um novo movimento
  compensatório, nunca edição/exclusão do existente (P3).
- **`produto_movimento_detalhe` NÃO é mais imutável (2026-07-16):** `niner_app` pode
  `UPDATE`/`DELETE`. Não grava mais `saldo_apos` por linha (removido) — o saldo materializado
  vive só em `produto_estoque.qtd_estoque`, mantido pela trigger
  `trg_produto_movimento_detalhe_estoque` (função `fn_atualiza_estoque_movimento`, V019):
  `credito_debito='C'` soma, `'D'` subtrai; `INSERT` aplica o efeito, `UPDATE` desfaz o efeito
  antigo e aplica o novo, `DELETE` desfaz o efeito. Faz UPSERT em `produto_estoque` — cria a
  linha na hora se ainda não existir para o `(id_tenant, id_empresa, id_variacao)`. Não existe
  mais `SP_ATUALIZA_QUANTIDADE_ESTOQUE` separada: toda a lógica está na trigger.
- **`funcionario_cpf_uk` (V016, 2026-07-16):** passou a `UNIQUE(id_tenant, id_funcionario)` —
  como `id_funcionario` já é a PK, essa constraint não impõe nada além do que a PK já
  garante; **o CPF deixou de ser único por tenant** (decisão explícita).
- Datas de auditoria (`criado_em` `DEFAULT now()` / `atualizado_em`) mantidas pela aplicação
  (Spring Data JDBC), **sem** JPA auditing (§3.3.1 / §3.2). Única exceção a "sem trigger de
  banco": `produto_estoque.qtd_estoque`, mantido por `trg_produto_movimento_detalhe_estoque`
  (V019, decisão de 2026-07-16) — todo o resto do domínio continua sem lógica em PL/pgSQL.
