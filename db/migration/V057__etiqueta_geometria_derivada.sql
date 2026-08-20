-- V057 — a geometria do rolo passa a ser DERIVADA de 7 números (2026-08-20).
--
-- A PROPOSTA DO DONO DO PRODUTO, E POR QUE ELA ESTÁ CERTA
-- "Você já sabe a largura do rolo, o número de colunas, a largura e a altura da etiqueta. O que
-- preciso a mais é margem até a primeira coluna, espaçamento entre colunas e espaçamento entre
-- fileiras. Com isso você sabe a área de impressão e a posição x,y de cada etiqueta."
--
-- Está exatamente certo, e o modelo antigo tinha um defeito de fundo: ele **guardava dado
-- redundante**. `cfg_etiqueta_coluna.posicao_inicial_mm` é calculável — e, por ser digitado à mão,
-- era onde o erro entrava. O caso real que motivou tudo: 3 colunas de 34 mm gravadas em 3/41/79
-- (passo 38) num rolo de passo 40. Cada coluna nascia 2 mm mais à esquerda que a anterior, o texto
-- saía progressivamente cortado, e nada na tela denunciava — porque a tela mostrava fielmente o
-- número errado que estava gravado.
--
-- Com a posição derivada, esse erro **deixa de ser representável**: informa-se a medida física
-- (margem e espaço, que se tiram com a régua) e a posição é consequência.
--
--   x da coluna i (0-based) = margem_esquerda_mm + i × (largura_etiqueta_mm + espacamento_horizontal_mm)
--   y da fileira f          =                      f × (altura_etiqueta_mm  + espacamento_vertical_mm)
--
-- ⚠️ O QUE SE PERDE, E POR QUE ESTÁ SENDO ACEITO
-- 1. **Rolo com espaçamento irregular** deixa de ser representável. Não é perda real: matriz de
--    corte é padrão repetido — rolo irregular não existe de fábrica. Era complexidade defensiva.
-- 2. **As 4 bordas** (superior/inferior/esquerda/direita) somem por decisão explícita do dono do
--    produto ("vamos esquecer as bordas"). Elas nunca afetaram a impressão: eram só a área
--    tracejada de aviso no editor. O aviso útil ("campo saindo da etiqueta") continua, agora
--    contra a borda REAL do adesivo, que é o que de fato corta.

-- ---------------------------------------------------------------------------------------------
-- 1. As duas medidas novas
-- ---------------------------------------------------------------------------------------------
ALTER TABLE cfg_etiqueta_config
  ADD COLUMN IF NOT EXISTS margem_esquerda_mm        numeric(6,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS espacamento_horizontal_mm numeric(6,2) NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------------------------
-- 2. Backfill ANTES de apagar — nenhuma configuração existente perde a geometria
-- ---------------------------------------------------------------------------------------------
-- ⚠️ `NO FORCE ROW LEVEL SECURITY` ANTES DE LER — sem isto o backfill sai ZERADO EM SILÊNCIO.
-- Migration roda como `niner_owner`, e com `FORCE ROW LEVEL SECURITY` (V024) **nem o dono da
-- tabela escapa da política**: sem `app.id_tenant` no contexto, o SELECT em `cfg_etiqueta_coluna`
-- devolve 0 linhas e o UPDATE em `cfg_etiqueta_config` casa 0 linhas — as colunas novas ficam no
-- DEFAULT 0 e ninguém percebe, porque a migration termina com "sucesso". Foi exatamente o que
-- aconteceu na primeira execução em dev (2026-08-20), pego só porque o resultado foi conferido no
-- banco depois. É o mesmo defeito que já tinha mordido o backup (`pg_dump` sem BYPASSRLS levando
-- estrutura completa e zero linha de cliente).
--
-- `NO FORCE` (e não `DISABLE`) é o mínimo necessário: libera só o DONO, mantendo a política
-- valendo para `niner_app`. `cfg_etiqueta_coluna` não precisa ser restaurada — é apagada logo
-- abaixo; `cfg_etiqueta_config` volta a FORCE no fim do bloco.
ALTER TABLE cfg_etiqueta_config NO FORCE ROW LEVEL SECURITY;
ALTER TABLE cfg_etiqueta_coluna NO FORCE ROW LEVEL SECURITY;

-- A margem é a posição da coluna 1; o espaçamento é a distância entre a coluna 2 e o fim da 1.
-- Modelo de 1 coluna só tem margem (espaçamento fica 0, que é o correto: não há vizinha).
-- `GREATEST(..., 0)` protege contra uma configuração antiga com colunas sobrepostas, que daria
-- espaçamento negativo — caso em que 0 é o palpite honesto e a tela avisa na abertura.
UPDATE cfg_etiqueta_config c SET
  margem_esquerda_mm = COALESCE((
      SELECT k.posicao_inicial_mm FROM cfg_etiqueta_coluna k
       WHERE k.id_tenant = c.id_tenant AND k.id_config_etiqueta = c.id_config_etiqueta
         AND k.numero_coluna = 1), 0),
  espacamento_horizontal_mm = GREATEST(COALESCE((
      SELECT k2.posicao_inicial_mm - k1.posicao_inicial_mm - c.largura_etiqueta_mm
        FROM cfg_etiqueta_coluna k1
        JOIN cfg_etiqueta_coluna k2
          ON k2.id_tenant = k1.id_tenant AND k2.id_config_etiqueta = k1.id_config_etiqueta
         AND k2.numero_coluna = 2
       WHERE k1.id_tenant = c.id_tenant AND k1.id_config_etiqueta = c.id_config_etiqueta
         AND k1.numero_coluna = 1), 0), 0);

ALTER TABLE cfg_etiqueta_config FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------------------------
-- 3. Fora o que virou redundante
-- ---------------------------------------------------------------------------------------------
DROP TABLE cfg_etiqueta_coluna;

ALTER TABLE cfg_etiqueta_config
  DROP COLUMN borda_superior_mm,
  DROP COLUMN borda_inferior_mm,
  DROP COLUMN borda_esquerda_mm,
  DROP COLUMN borda_direita_mm;

ALTER TABLE cfg_etiqueta_config
  ADD CONSTRAINT cfg_etiqueta_config_espacamento_horizontal_ck
  CHECK (espacamento_horizontal_mm >= 0 AND espacamento_horizontal_mm <= 100),
  ADD CONSTRAINT cfg_etiqueta_config_margem_esquerda_ck
  CHECK (margem_esquerda_mm >= 0 AND margem_esquerda_mm <= 500);

COMMENT ON COLUMN cfg_etiqueta_config.margem_esquerda_mm IS
  'Distancia da borda do rolo ate o comeco da 1a coluna, em mm.';
COMMENT ON COLUMN cfg_etiqueta_config.espacamento_horizontal_mm IS
  'Espaco em branco ENTRE duas colunas, em mm. x da coluna i = margem + i * (largura_etiqueta + este valor).';
