-- V088 — Comissão congelada na linha do movimento (DS5 do docs/MODULOSERVICOS.md).
--
-- ⭐ O QUE ESTA MIGRATION CONSERTA, E QUE NINGUÉM TINHA NOTADO
--
-- O RelatorioComissoesService sempre calculou `valorLiquido × funcionario.perc_comissao / 100`
-- **na consulta, sem nada persistido**. É elegante e tem um efeito colateral grave: **mudar o
-- percentual do funcionário reescreve a comissão de todos os meses passados**. O vendedor que
-- fechou março a 3% e foi promovido a 5% em agosto vê março virar 5% — e a folha de março, que já
-- foi paga, deixa de bater com o relatório que a originou.
--
-- Congelar o percentual aplicado no dia conserta isso, e alinha com o F9 do módulo fiscal
-- ("todo cálculo é auditável e reproduzível") e com o P3 (auditabilidade).
--
-- ⚠️ A COLUNA É NULLABLE DE PROPÓSITO, e o relatório lê com COALESCE. As linhas que já existem
-- não têm como saber qual percentual valia no dia — inventar um valor de backfill (o percentual
-- de hoje) seria pior que assumir a ausência: gravaria como "congelado" um número que na verdade
-- é o de agora, e ninguém conseguiria distinguir depois o que foi medido do que foi chutado.
-- NULL diz a verdade: "esta linha é anterior ao congelamento".
--
-- ⚠️ E é por isso que NÃO há UPDATE de backfill aqui. Migration que lê ou transforma dado de
-- tenant precisaria de `NO FORCE ROW LEVEL SECURITY` (V024 vale até para o dono) e sairia vazia em
-- silêncio sem ele — mas o ponto aqui é outro: não há dado correto para escrever.

ALTER TABLE produto_movimento_detalhe
  ADD COLUMN perc_comissao numeric(5,2);

ALTER TABLE produto_movimento_detalhe
  ADD CONSTRAINT produto_movimento_detalhe_comissao_ck
  CHECK (perc_comissao IS NULL OR (perc_comissao >= 0 AND perc_comissao <= 100));

COMMENT ON COLUMN produto_movimento_detalhe.perc_comissao IS
  'Percentual de comissão APLICADO nesta linha, congelado na gravação (DS5). '
  'Resolvido como COALESCE(produto_servico.perc_comissao, funcionario.perc_comissao): a comissão '
  'do SERVIÇO vence a da pessoa quando está preenchida — o tosador ganha 20% do banho e 10% da '
  'tosa, que é a prática de mercado. NULL = linha anterior ao congelamento (V088); o relatório '
  'cai no percentual atual do funcionário para essas, que é o comportamento que ela sempre teve.';

-- `niner_app` já tem os grants da tabela; coluna nova herda o privilégio de tabela em Postgres,
-- ao contrário do que aconteceu no Cancelamento de Entrada — lá o GRANT era por COLUNA.
