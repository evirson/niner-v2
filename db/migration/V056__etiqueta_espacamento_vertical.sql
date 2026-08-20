-- V056 — passo vertical entre fileiras de etiquetas (2026-08-20).
--
-- O DEFEITO QUE ESTA COLUNA CONSERTA
-- Diagnosticado com uma etiqueta impressa na mão: a partir da 2ª fileira o conteúdo subia em
-- relação ao adesivo, e na 4ª já estava inteiramente fora. Motivo: a impressão empilhava as
-- fileiras usando `altura_etiqueta_mm` como passo, e o rolo físico tem **espaço entre as
-- fileiras**. Com adesivo de 29 mm num rolo de passo 32 mm, cada fileira nascia 3 mm acima da
-- anterior — erro que não aparece na primeira etiqueta e vira desastre na quarta.
--
-- POR QUE NÃO DAVA PARA DEDUZIR
-- Não há como calcular o gap a partir do que já existia: `altura_etiqueta_mm` é o adesivo e a
-- largura do rolo não diz nada sobre a vertical. É medida física do material, igual à largura da
-- etiqueta — tem de ser perguntada.
--
-- ⚠️ DEFAULT 0 É O COMPORTAMENTO ATUAL, DE PROPÓSITO
-- Quem já tem modelo cadastrado continua imprimindo exatamente como imprimia (passo = altura da
-- etiqueta). Só quem informar o espaçamento passa a paginar diferente — nenhuma configuração
-- existente muda de comportamento sozinha.
ALTER TABLE cfg_etiqueta_config
  ADD COLUMN IF NOT EXISTS espacamento_vertical_mm numeric(6,2) NOT NULL DEFAULT 0;

ALTER TABLE cfg_etiqueta_config
  ADD CONSTRAINT cfg_etiqueta_config_espacamento_vertical_ck
  CHECK (espacamento_vertical_mm >= 0 AND espacamento_vertical_mm <= 100);

COMMENT ON COLUMN cfg_etiqueta_config.espacamento_vertical_mm IS
  'Espaco em branco ENTRE duas fileiras do rolo, em mm. O passo vertical da impressao e altura_etiqueta_mm + este valor. 0 = fileiras coladas (rolo continuo sem gap).';
