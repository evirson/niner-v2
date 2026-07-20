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
| **V013** | tipos ENUM de domínio (canal, pedido, movimento, outbox, `genero_cliente`, `tipo_movimento_conta`…) | ✅ |
| **V014** | `identidade.empresa` (`codigo_empresa` único por tenant, só para exibição em relatório; `cfg_nome_etiqueta` obrigatório) | ✅ |
| **V015** | `identidade.usuario` (e-mail único por tenant, case-insensitive) + `usuario_rotina` | ✅ |
| **V016** | cadastros: `cfg_categoria_cliente`, `cliente` (`id_categoria_cliente` NOT NULL; `data_nascimento`/`genero` obrigatórios só p/ pessoa física, `CHECK`; `whatsapp`/`instagram`/`facebook`/`tiktok`; `complemento` entre `numero` e `bairro`, 2026-07-20), `cfg_plano_contas` (PK composta `(id_tenant, id_plano_contas)`, prep. p/ DRE — Q5/ADR-010), `fornecedor` (`id_plano_contas` NOT NULL), `funcionario` (`telefone`) | ✅ |
| **V017** | `catalogo`: configs, `produto` (sem `imagem`), `produto_categoria`, `produto_barra` (**Q7:** `sku` + `ean`), `produto_imagem` (galeria, `indice smallint` único por produto) | ✅ |
| **V018** | vendas: `venda` (sem `id_funcionario`, `valor_total`, `observacao`, `criado_em` — comissão/vendedor e total ficam em `produto_movimento_detalhe`), `venda_devolucao` (sem `criado_em`) (R9; sem financeiro — Q5) | ✅ |
| **V019** | `estoque`: `produto_estoque` (**Q2:** `reservado`, `disponivel`), `produto_movimento_mestre` (imutável) + `produto_movimento_detalhe` (sem `saldo_apos` por linha; corrigível, ver trigger), `produto_balanco` (`id_balanco BIGINT`, sem `qtd_sistema`/`observacao`), **trigger `trg_produto_movimento_detalhe_estoque`** (`fn_atualiza_estoque_movimento`) mantém `produto_estoque.qtd_estoque` em INSERT/UPDATE/DELETE do ledger | ✅ |
| **V020** | `canais`: `canal` (credenciais cifradas), `anuncio` (de-para SKU, R6) | ✅ |
| **V021** | pedidos de canal: `pedido` (idempotente `(canal,id_externo)`), `pedido_item` | ✅ |
| **V022** | `integracao`: `outbox_evento`, `webhook_recebido` (P2) | ✅ |
| **V023** | `cfg_geral` (singleton por tenant; `cfg_usa_variante_linha`/`cfg_usa_variante_coluna` boolean default true; sem `moeda_devolucao`) | ✅ |
| **V024** | **RLS de domínio** (final para V014–V023): `ENABLE`+`FORCE` + `USING/WITH CHECK (id_tenant = plataforma.tenant_atual())` em todas as tabelas de tenant + grants de `niner_app` + `REVOKE UPDATE, DELETE` só em `produto_movimento_mestre` (imutabilidade, P3 — `produto_movimento_detalhe` ficou de fora desde 2026-07-16, ver trigger em V019) + guarda-corpo que falha se alguma tabela com `id_tenant` ficar sem RLS | ✅ |
| **V025** | **`financeiro` (parcial) — crediário + caixa** (revisão de Q5/ADR-010, 2026-07-16): `tipo_carteira` (prazo/parcelas min-max/taxa adm), `moeda` (formas de recebimento, seed **por tenant** no signup — não global), `moeda_detalhe` (moeda × carteira), `contas_receber`/`contas_receber_detalhe` (1:1, taxas de cartão), `caixa_mestre`/`caixa_detalhe` (sessão de caixa + lançamentos, ENUM `tipo_operacao_caixa` mapeado do legado `RV/RP/DC/CC/TR`). Tem **RLS próprio** no mesmo arquivo (V024 já tinha rodado). | ✅ |
| **V026** | **`financeiro` — `contas_pagar`** (mais uma revisão de Q5/ADR-010/ADR-012, 2026-07-16): PK renomeada de `localizador` (legado) para `id_conta_pagar`; `nota_fiscal integer` nullable (sem `DEFAULT 0`, sem valor mágico). RLS próprio no arquivo. Com esta migration, só `conta_corrente`/`conta_corrente_movimento` continuam fora do v1 (§3.3.7) | ✅ |
| **V027** | **`cfg_tela_campo`** (novo, 2026-07-21, §3.7.2): configuração por tenant de campos visíveis/obrigatórios por tela (`chave_tela`), reutilizável entre telas — primeiro uso `cadastros.cliente.form`. `CHECK` impede campo obrigatório e oculto ao mesmo tempo. RLS próprio no arquivo. **Migration aditiva**: aplicada com `docker compose run --rm flyway` sem recriar o banco (só ela rodou, V001–V026 já estavam aplicadas) | ✅ |

> As tabelas do schema `plataforma` são **globais** (P9) e **não** entram no RLS de tenant.
> O RLS (`FORCE`) se aplica a toda tabela de **domínio** do lojista — V014–V023 (ativado em V024)
> e V025/V026 (cada uma com RLS próprio no arquivo, por terem sido criadas depois do
> guarda-corpo de V024).
> `financeiro` do lojista está no v1 desde V025/V026 (crediário, caixa, contas a pagar);
> só `conta_corrente(_movimento)` continua fora (Q5/ADR-010 revisado — Fase 2).
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
- **`cfg_plano_contas` (V016, 2026-07-16) foge do padrão de PK surrogate + `integer`:** a PK é
  `(id_tenant, id_plano_contas)` — `id_plano_contas` é `text` (código contábil, ex.:
  `"3.1.001"`), a própria chave de negócio, sem `id_<entidade> integer GENERATED ALWAYS AS
  IDENTITY`. Preparação para relatórios/DRE; o módulo `financeiro` completo (caixa,
  contas a pagar/receber) continua fora do v1 (Q5/ADR-010, Fase 2) — só o plano de contas
  em si (e o vínculo em `fornecedor.id_plano_contas`, `NOT NULL`, sem linha padrão
  pré-cadastrada) entrou agora.
- Datas de auditoria (`criado_em` `DEFAULT now()` / `atualizado_em`) mantidas pela aplicação
  (Spring Data JDBC), **sem** JPA auditing (§3.3.1 / §3.2). Única exceção a "sem trigger de
  banco": `produto_estoque.qtd_estoque`, mantido por `trg_produto_movimento_detalhe_estoque`
  (V019, decisão de 2026-07-16) — todo o resto do domínio continua sem lógica em PL/pgSQL.
- **`venda`/`venda_devolucao` (V018, 2026-07-16) não têm `criado_em`** — únicas tabelas do
  domínio sem esse campo (exceção deliberada à convenção acima). Motivo: `data_venda`/
  `data_devolucao` já cumprem o papel de timestamp de criação nessas duas tabelas — `criado_em`
  seria redundante (seria sempre igual a `data_venda`/`data_devolucao`, já que nenhuma das
  duas tem fluxo de "criar rascunho hoje, confirmar depois"). `venda` também perdeu
  `valor_total` (derivado do ledger `produto_movimento_detalhe`) e `observacao`.
- **`financeiro` parcial (V025, 2026-07-16) revisa Q5/ADR-010:** crediário (`tipo_carteira`,
  `moeda`, `moeda_detalhe`, `contas_receber`/`_detalhe`) e caixa (`caixa_mestre`/`_detalhe`)
  antecipados da Fase 2 para o v1. `contas_pagar` e `conta_corrente(_movimento)` continuam
  fora — não fazem parte desta migration. Pontos específicos:
  - `contas_receber_detalhe` é **1:1** com `contas_receber`: PK é `(id_tenant, id_conta_receber)`,
    não um surrogate próprio.
  - `caixa_detalhe.tipo_operacao` é ENUM `tipo_operacao_caixa` — nomes por extenso confirmados
    pelo dono do produto para os códigos do legado: `RV`→`RECEBIMENTO_VENDA`,
    `RP`→`RECEBIMENTO_PARCELA_CREDIARIO`, `DC`→`DEBITO_CAIXA`, `CC`→`CREDITO_CAIXA`, `TR`→`TROCO`.
  - `caixa_detalhe.credito_debito` **reaproveita** o ENUM `credito_debito` (`C`/`D`) já criado em
    V013 para o ledger de estoque — não cria tipo novo.
  - `caixa_detalhe` ganhou `criado_em` (ausente no legado) porque uma sessão de caixa pode durar
    o dia todo com vários lançamentos em horários diferentes — sem isso não dá pra saber
    "quando" cada lançamento ocorreu (P3).
  - `moeda` não tem seed global de Flyway (o legado insere 7 linhas fixas: DINHEIRO, PIX,
    CARTAO DEBITO/CREDITO, CREDIARIO, VALE PRESENTE, VALE MERCADORIA) porque `id_tenant` é
    obrigatório e não existe no momento da migration — o seed é **por tenant**, feito pelo
    `SignupService` no signup (mesmo padrão de `cfg_geral`). ✅ Implementado (2026-07-16):
    `SignupService.assinar()` insere as 7 linhas logo após `cfg_geral`.
  - RLS destas 7 tabelas está **no próprio V025**, não em V024 — o guarda-corpo de V024 rodou
    antes delas existirem e não as alcançaria; V025 repete o padrão (ENABLE+FORCE+policy+grants)
    e tem seu próprio guarda-corpo.
- **`contas_pagar` (V026, 2026-07-16) revisa Q5/ADR-010/ADR-012 mais uma vez:** PK renomeada de
  `localizador` (nome do legado, mantido em `caixa_detalhe`/V025) para **`id_conta_pagar`** —
  pedido explícito do dono do produto, quebra a consistência de nome com `caixa_detalhe` de
  propósito. `nota_fiscal` é `integer` **nullable**, sem `DEFAULT 0` (o legado tinha `DEFAULT 0`,
  que é valor mágico para "sem nota fiscal"). Essa mesma padronização (`nota_fiscal integer`)
  também corrigiu `produto_movimento_mestre.nota_fiscal` (V019), que era `text` — nenhum outro
  lugar do schema tinha esse campo. `documento_pago` ganhou `DEFAULT false`, mesmo tratamento
  de `contas_receber.documento_recebido` (V025). RLS próprio no arquivo (mesmo motivo de V025).
- **`cliente.complemento` (V016, 2026-07-20):** coluna `text` nullable adicionada entre
  `numero` e `bairro` — pedido do dono do produto ao testar a primeira tela de cliente
  (`web/`, `docs/telas/cliente.md`). Banco ainda em construção (convenção da sessão — §7):
  a coluna entrou direto na migration V016 já existente, em vez de uma V027 nova; o dev
  precisou recriar o banco do zero (`docker volume rm niner_pgdata` + `flyway migrate`)
  para aplicar a mudança à migration já rodada.
