-- V059 — `venda.origem`: distingue a venda REAL da venda SINTÉTICA criada pela importação
-- de Contas a Receber (auditoria 2026-08-21, item 22; decisão do dono do produto em 2026-08-22).
--
-- POR QUE EXISTE
-- --------------
-- `ContasReceberImportador` precisa de uma venda para pendurar as parcelas migradas do sistema
-- antigo (`contas_receber.id_venda` é obrigatório), e cria uma venda VAZIA: sem item, sem
-- movimento de estoque, sem custo. Ela não representa uma venda feita no Nainer — é uma casca.
--
-- O DRE em regime CAIXA parte de `contas_receber` e busca o custo por fora, com LEFT JOIN no
-- ledger de movimento. Para a venda sintética esse JOIN não acha nada, o COALESCE transforma o
-- NULL em zero, e a receita entra inteira: uma parcela de R$ 45.000 já recebida antes da migração
-- aparece no DRE do mês em que foi paga com CMV R$ 0,00 — margem de 100%, lucro que nunca existiu
-- no Nainer (a mercadoria foi comprada e vendida no sistema anterior).
--
-- Pior, é incoerente com o próprio importador, que avisa: "nenhum lançamento de caixa é criado
-- para parcelas já pagas". O dinheiro NÃO entra no Fluxo de Caixa mas ENTRA no DRE caixa — dois
-- relatórios financeiros, o mesmo fato, respostas opostas.
--
-- O QUE MUDA (e o que deliberadamente NÃO muda)
-- ---------------------------------------------
-- A coluna só MARCA a origem. Quem decide o que fazer com a marca é o DreService, e ele exclui
-- apenas as parcelas que JÁ VIERAM RECEBIDAS na migração (`data_recebimento` anterior à
-- importação). Parcela legada que estava em aberto e o cliente vier pagar depois, no Nainer,
-- CONTINUA entrando como receita: esse dinheiro entrou no caixa da loja de verdade.
--
-- As parcelas seguem inteiras em `contas_receber` e continuam aparecendo no Relatório de Contas a
-- Receber — é lá que elas precisam estar, porque o cliente deve esse dinheiro e a loja vai cobrar.
--
-- SEM BACKFILL — e isso é deliberado
-- ----------------------------------
-- `DEFAULT 'PDV'` carimba TODAS as linhas existentes, inclusive as vendas sintéticas de
-- importações já feitas: elas ficam marcadas como 'PDV' e o DRE caixa continua somando as
-- parcelas delas. Isso é aceito porque, em 2026-08-22, o dono do produto confirmou que **todo o
-- dado importado até aqui é carga de teste** — o sistema está em homologação e nenhum cliente real
-- usa o Nainer (ele avisará quando entrar em produção).
--
-- ⚠️ Se este banco algum dia tiver importação REAL anterior a esta migration, o conserto é uma
-- migration NOVA (nunca editar esta — checksum do Flyway trava o deploy inteiro) marcando as
-- vendas sem item nem movimento:
--
--   UPDATE venda v SET origem = 'IMPORTACAO', importado_em = <data da migração>
--    WHERE NOT EXISTS (SELECT 1 FROM produto_movimento_mestre m
--                       WHERE m.id_venda = v.id_venda AND m.id_tenant = v.id_tenant);
--
-- ⚠️ E ela precisaria de `ALTER TABLE venda NO FORCE ROW LEVEL SECURITY` antes e `FORCE` depois:
-- migration roda como niner_owner, e com FORCE RLS (V024) nem o dono escapa da política — o UPDATE
-- casaria ZERO linhas e o Flyway anunciaria sucesso. Ver feedback_migration_backfill_rls_vazio.

CREATE TYPE origem_venda AS ENUM ('PDV', 'IMPORTACAO');

ALTER TABLE venda
  ADD COLUMN origem      origem_venda NOT NULL DEFAULT 'PDV',
  ADD COLUMN importado_em timestamptz;

COMMENT ON COLUMN venda.origem IS
  'PDV = venda feita no sistema. IMPORTACAO = casca criada pela importacao de Contas a Receber para pendurar parcela migrada (sem item, sem movimento de estoque, sem custo) — o DRE caixa ignora as parcelas dela ja recebidas antes da migracao.';

-- `importado_em` é o MARCO que separa as duas populações de parcela dentro da mesma venda
-- sintética, e é por isso que não bastava a coluna `origem`:
--
--   * parcela com `data_recebimento` ANTERIOR a este instante = já estava paga no sistema antigo.
--     Nunca passou pelo caixa do Nainer (o importador avisa: "nenhum lançamento de caixa é criado
--     para parcelas já pagas"). NÃO é receita do Nainer — é o caso do lucro de margem 100%.
--
--   * parcela com `data_recebimento` POSTERIOR = estava em aberto na migração e o cliente veio
--     pagar depois, pela tela de Recebimento de Crediário. Esse dinheiro entrou no caixa da loja
--     de verdade: É receita, e continua no DRE (com CMV zero, que aqui é honesto — o custo é de
--     antes e não existe neste sistema).
--
-- NULL para venda de PDV. Não usei um `criado_em` na tabela inteira de propósito: com DEFAULT
-- now() ele carimbaria a data de HOJE em toda venda antiga — dado errado gravado para sempre.
COMMENT ON COLUMN venda.importado_em IS
  'Instante da importacao (so para origem = IMPORTACAO; NULL no PDV). Separa parcela ja paga antes da migracao — que nao e receita do Nainer — da que foi paga depois, pelo caixa.';

ALTER TABLE venda
  ADD CONSTRAINT venda_importado_em_ck
  CHECK ((origem = 'IMPORTACAO') = (importado_em IS NOT NULL));

-- Índice parcial: a venda sintética é a exceção, e é ela que o DRE precisa reconhecer. Um índice
-- sobre a coluna inteira seria quase todo 'PDV' e não ajudaria em nada.
CREATE INDEX venda_origem_importacao_ix ON venda (id_tenant, id_venda) WHERE origem = 'IMPORTACAO';

-- Sem GRANT aqui, de propósito: `venda` recebeu GRANT SELECT/INSERT/UPDATE/DELETE de TABELA no
-- loop da V024, e no Postgres o grant de tabela cobre colunas criadas depois. Isso só seria
-- necessário se o privilégio de `venda` fosse por coluna — como é o de produto_movimento_mestre,
-- que tem REVOKE de tabela + GRANT das quatro colunas de cancelamento (ver V024:53-64).
