-- V089 — `venda.id_funcionario`: quem VENDEU, separado de quem EXECUTOU cada linha.
--
-- ⭐ POR QUE ESTA COLUNA PASSOU A SER NECESSÁRIA
--
-- A venda nunca teve vendedor próprio: cinco serviços o derivavam do ledger, com
-- `MAX(pmd.id_funcionario)` ou um `LIMIT 1` — e funcionava porque `PdvVendaService` gravava o
-- MESMO funcionário em todas as linhas. A suposição estava certa e não estava escrita em lugar
-- nenhum.
--
-- A V088 quebrou isso de propósito: cada linha passou a carregar QUEM EXECUTOU aquele item, para
-- que a comissão do mecânico vá para o mecânico (DS5). O efeito colateral apareceu na tela, não na
-- suíte: a Pesquisa de Vendas passou a mostrar "Vendedor: MECANICO JOAO" numa venda em que o
-- vendedor foi outro — o `MAX()` pegava o executor do serviço.
--
-- ⛔ Reverter o executor por linha não era opção: é a feature. O que faltava era a venda ter o
-- próprio vendedor, em vez de adivinhá-lo a partir de uma coluna que passou a significar outra
-- coisa. Agora são dois fatos distintos, com dois lugares distintos:
--   • `venda.id_funcionario`                  → quem atendeu / fechou a venda
--   • `produto_movimento_detalhe.id_funcionario` → quem executou AQUELA linha
--
-- ⚠️ NULLABLE, e sem NOT NULL depois. Devolução e cancelamento também criam movimento e nem toda
-- venda histórica tem funcionário identificado no ledger (a coluna de lá sempre foi opcional).

ALTER TABLE venda ADD COLUMN id_funcionario integer;

ALTER TABLE venda
  ADD CONSTRAINT venda_funcionario_fk
  FOREIGN KEY (id_tenant, id_funcionario) REFERENCES funcionario (id_tenant, id_funcionario);

CREATE INDEX venda_id_funcionario_ix ON venda (id_tenant, id_funcionario);

COMMENT ON COLUMN venda.id_funcionario IS
  'Quem VENDEU (o atendimento/caixa). ⚠️ Não confundir com '
  'produto_movimento_detalhe.id_funcionario, que desde a V088 é quem EXECUTOU aquela linha — numa '
  'venda vinda de ordem de serviço os dois são pessoas diferentes, e é justamente essa diferença '
  'que paga a comissão certa ao mecânico. Quem pergunta "quem vendeu" lê daqui.';

-- ---------------------------------------------------------------------------------------------
-- Backfill do histórico.
--
-- ⚠️ Migration que LÊ dado de tenant sai VAZIA em silêncio: `FORCE ROW LEVEL SECURITY` (V024) vale
-- até para `niner_owner`, que é quem roda o Flyway. Sem o `NO FORCE`, o UPDATE casaria zero linhas
-- e o Flyway anunciaria sucesso — o mesmo defeito que a V057 quase deixou passar.
-- `NO FORCE` (e não `DISABLE`) libera só o dono; a política continua valendo para `niner_app`.
--
-- ⭐ O backfill é CORRETO aqui, ao contrário do da V088: para toda venda anterior a esta migration
-- as linhas do ledger têm o mesmo funcionário (era o vendedor repetido), então derivá-lo dali
-- recupera o fato real, não um chute. `MIN` em vez de `MAX` é indiferente pelo mesmo motivo.
ALTER TABLE venda NO FORCE ROW LEVEL SECURITY;

UPDATE venda v
   SET id_funcionario = sub.id_funcionario
  FROM (SELECT pmm.id_venda, pmm.id_tenant, MIN(pmd.id_funcionario) AS id_funcionario
          FROM produto_movimento_mestre pmm
          JOIN produto_movimento_detalhe pmd
                 ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
         WHERE pmm.tipo_movimento = 'VENDA' AND pmd.id_funcionario IS NOT NULL
         GROUP BY pmm.id_venda, pmm.id_tenant) sub
 WHERE v.id_venda = sub.id_venda AND v.id_tenant = sub.id_tenant;

ALTER TABLE venda FORCE ROW LEVEL SECURITY;
