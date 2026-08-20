-- V048 — competência da cota no fuso da PLATAFORMA, não no do banco (2026-08-20).
-- Ver docs/PROGRESSO.md (2026-08-20) e com.vetor.niner.comum.tempo.FusoDaPlataforma.
--
-- A sessão do Postgres roda em `Etc/UTC`, então `date_trunc('month', now())::date` vira o mês
-- seguinte às **21:00 de Brasília do último dia do mês**. Nas colunas abaixo isso é o valor
-- gravado quando a linha nasce sem competência explícita — e a competência errada de partida faz o
-- contador de vendas do tenant apurar contra o mês que não é o dele.
--
-- O caminho quente (LimiteVendasService) sempre informa a competência, e foi corrigido em Java
-- junto com esta migration; estes DEFAULTs são a rede de baixo, para quem inserir sem informar.
--
-- ⚠️ Aqui o fuso é fixo de propósito, ao contrário do fuso do lojista (que vem da UF da empresa
-- desde 2026-08-20): competência de cota é fato do negócio da VETOR (P9), e um tenant com empresas
-- em UFs diferentes não teria um "fuso do tenant" para desempatar.
--
-- Só troca o DEFAULT. Nenhuma linha existente é reescrita: uma competência já gravada é histórico,
-- e reescrever histórico com a regra nova produziria número que nunca foi apurado.

ALTER TABLE plataforma.uso_tenant
  ALTER COLUMN competencia_pedidos
  SET DEFAULT date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date;

ALTER TABLE plataforma.uso_tenant
  ALTER COLUMN competencia_vendas
  SET DEFAULT date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date;

COMMENT ON COLUMN plataforma.uso_tenant.competencia_vendas IS
  'Mes de apuracao da cota, no fuso da PLATAFORMA (America/Sao_Paulo), nunca no do banco (UTC): em UTC a competencia virava as 21:00 do ultimo dia do mes e o contador zerava 3h antes da hora.';
