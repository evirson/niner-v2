-- V052 — valor novo no enum `tipo_movimento`: DEVOLUCAO_COMPRA (2026-08-20).
-- Rotina de Devolução de Produtos Comprados (devolução ao fornecedor).
--
-- ⚠️ ESTA MIGRATION FAZ UMA COISA SÓ, E O MOTIVO NÃO É ESTILO.
-- No Postgres, `ALTER TYPE ... ADD VALUE` e o **uso** do valor novo não podem estar na mesma
-- transação ("unsafe use of new value of enum type"). O Flyway roda cada migration numa transação,
-- então acrescentar o valor e já usá-lo no mesmo arquivo falha. O valor entra aqui; quem usa é a
-- V053 em diante.
--
-- POR QUE UM VALOR NOVO, E NÃO REUSAR `DEVOLUCAO`
-- `DEVOLUCAO` é a devolução do CONSUMIDOR: mercadoria voltando para a loja (`credito_debito = 'C'`).
-- A devolução ao fornecedor é o oposto — mercadoria saindo (`'D'`). Distinguir só pelo
-- credito_debito faria o Kardex e o Relatório de Movimentação mostrarem as duas como "devolução",
-- sem o operador conseguir saber qual é qual pela natureza do lançamento.

ALTER TYPE tipo_movimento ADD VALUE IF NOT EXISTS 'DEVOLUCAO_COMPRA';
