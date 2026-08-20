-- V053 — Devolução de Produtos Comprados: o vínculo com a entrada de origem (2026-08-20).
--
-- O QUE ESTA ROTINA PRECISOU DE SCHEMA — E O QUE NÃO PRECISOU
-- Bem menos do que parecia no estudo, porque três peças já existiam:
--   · `tipo_operacao_fiscal.DEVOLUCAO_FORNECEDOR` — semeado na V035, que gravou o domínio completo
--     de propósito ("acrescentar valor a ENUM depois é ALTER TYPE");
--   · `documento_fiscal.id_movimento` — o documento fiscal já sabia nascer de um movimento de
--     estoque, não só de uma venda;
--   · `documento_fiscal_referencia.id_documento_referenciado` — já documentado como "preenchido
--     quando a nota é do próprio Niner", ou seja, já previa referenciar nota de TERCEIRO só pela
--     chave, que é exatamente o caso da nota do fornecedor.
--
-- Também NÃO existe tabela-cabeçalho aqui, ao contrário da devolução do consumidor
-- (`venda_devolucao`). Lá o cabeçalho existe porque a devolução gera um **vale-mercadoria**, que é
-- um objeto de negócio com vida própria. Aqui não há nada equivalente: por decisão do dono do
-- produto (2026-08-20), a devolução ao fornecedor **não mexe no financeiro** — o lojista negocia o
-- crédito por fora. Então a operação É o movimento de estoque, e inventar um cabeçalho vazio só
-- criaria uma tabela para manter sincronizada com o ledger.

-- ---------------------------------------------------------------------------------------------
-- A única coluna nova: de qual entrada esta devolução saiu.
-- ---------------------------------------------------------------------------------------------
-- Sem ela não há como calcular o saldo devolvível ("quanto desta nota já voltou ao fornecedor?")
-- nem responder "esta devolução se refere a qual compra?". As colunas de vínculo que já existiam
-- (`id_venda`, `id_transferencia`, `id_devolucao`) são de outras operações.
--
-- FK composta com `id_tenant` (P8) e apontando para a própria tabela: o movimento de origem é
-- sempre uma COMPRA do mesmo tenant.
ALTER TABLE produto_movimento_mestre
  ADD COLUMN id_movimento_origem integer;

ALTER TABLE produto_movimento_mestre
  ADD CONSTRAINT produto_movimento_mestre_origem_fk
  FOREIGN KEY (id_tenant, id_movimento_origem)
  REFERENCES produto_movimento_mestre (id_tenant, id_movimento);

CREATE INDEX produto_movimento_mestre_origem_ix
  ON produto_movimento_mestre (id_tenant, id_movimento_origem)
  WHERE id_movimento_origem IS NOT NULL;

COMMENT ON COLUMN produto_movimento_mestre.id_movimento_origem IS
  'Movimento que originou este: a COMPRA que a DEVOLUCAO_COMPRA devolve, ou a devolucao que um CANCELAMENTO desfaz. E por ele que se calcula o saldo devolvivel de uma nota de entrada.';

-- ---------------------------------------------------------------------------------------------
-- Saldo devolvível por item de uma entrada.
-- ---------------------------------------------------------------------------------------------
-- Uma view em vez de repetir a mesma agregação no serviço, na tela e no montador do XML: o cálculo
-- é o mesmo nos três, e é o tipo de regra que, duplicada, diverge no dia em que alguém acrescenta
-- uma condição num lugar só.
--
-- ⚠️ `cancelado = false` nos DOIS lados: entrada cancelada não devolve nada, e devolução cancelada
-- devolve o saldo de volta (é o que torna o Cancelamento de Devolução de Compra uma operação
-- reversível de verdade, sem UPDATE em linha nenhuma).
CREATE VIEW vw_entrada_saldo_devolucao AS
SELECT compra.id_tenant,
       compra.id_movimento,
       compra.id_empresa,
       det.id_variacao,
       SUM(det.qtd_produto)                                        AS qtd_comprada,
       COALESCE(dev.qtd_devolvida, 0)                              AS qtd_devolvida,
       SUM(det.qtd_produto) - COALESCE(dev.qtd_devolvida, 0)       AS qtd_saldo
  FROM produto_movimento_mestre compra
  JOIN produto_movimento_detalhe det
    ON det.id_tenant = compra.id_tenant AND det.id_movimento = compra.id_movimento
  LEFT JOIN LATERAL (
        SELECT SUM(dd.qtd_produto) AS qtd_devolvida
          FROM produto_movimento_mestre dm
          JOIN produto_movimento_detalhe dd
            ON dd.id_tenant = dm.id_tenant AND dd.id_movimento = dm.id_movimento
         WHERE dm.id_tenant = compra.id_tenant
           AND dm.id_movimento_origem = compra.id_movimento
           AND dm.tipo_movimento = 'DEVOLUCAO_COMPRA'
           AND dm.cancelado = false
           AND dd.id_variacao = det.id_variacao
       ) dev ON true
 WHERE compra.tipo_movimento = 'COMPRA'
   AND compra.cancelado = false
 GROUP BY compra.id_tenant, compra.id_movimento, compra.id_empresa,
          det.id_variacao, dev.qtd_devolvida;

COMMENT ON VIEW vw_entrada_saldo_devolucao IS
  'Quanto de cada item de uma entrada ainda pode ser devolvido ao fornecedor. Entrada cancelada nao aparece; devolucao cancelada devolve o saldo. NAO considera o estoque atual — esse limite e aplicado no servico (regra do dono do produto: so devolve o que ainda esta em estoque).';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_app') THEN
    GRANT SELECT ON vw_entrada_saldo_devolucao TO niner_app;
  END IF;
END $$;
