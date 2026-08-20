-- V050 — o prefixo do código de barras interno vira DADO (2026-08-20).
-- Ver docs/telas/importacao-dados.md ("5. estoque") e CLAUDE.md.
--
-- POR QUE
-- `gerar_ean13_interno()` (V017) montava o código com o literal '9' cravado no corpo da função.
-- Isso bastava enquanto nada mais no sistema precisasse saber qual é o prefixo. Deixou de bastar
-- quando a **importação de estoque inicial** passou a recusar código de barras que comece com ele
-- — o lojista que migra de outro sistema traz os códigos já impressos nas etiquetas, e um deles
-- na nossa faixa colidiria com um SKU que o gerador ainda vai emitir (o sequencial CRESCE: um
-- código importado hoje sem conflito colide no dia em que o contador alcançar aquele número).
--
-- Escrever o '9' de novo no importador criaria uma SEGUNDA CÓPIA da mesma regra. Este projeto já
-- foi mordido por isso mais de uma vez (as três listas de UF, as siglas duplicadas no CSRT): no dia
-- em que o prefixo mudar, alguém troca uma cópia e esquece a outra — e a importação passa a barrar
-- o prefixo errado e a deixar entrar o certo, em silêncio. Então o prefixo vira dado, numa linha só,
-- lida pelos dois lados.
--
-- DUAS COLUNAS, E A DIFERENÇA IMPORTA
--   · `prefixo`             — o que o gerador USA agora. Um só, por definição.
--   · `prefixos_reservados` — tudo que já foi usado algum dia, que é o que a importação BARRA.
-- Trocar o prefixo não apaga o passado: os SKUs já gerados continuam com o prefixo antigo, então
-- ele tem de continuar barrado para sempre. O CHECK abaixo garante que o prefixo corrente esteja
-- sempre dentro da lista — é impossível trocar e esquecer de reservar.
--
-- Como trocar o prefixo no futuro (sem deploy, sem tocar em código):
--   UPDATE cfg_ean_gerador
--      SET prefixo = '8', prefixos_reservados = prefixos_reservados || '8'::text;
-- ⚠️ O `::text` não é decorativo: sem ele o Postgres lê o '8' como literal de ARRAY e recusa o
-- comando com "malformed array literal" (medido).

ALTER TABLE cfg_ean_gerador
  ADD COLUMN prefixo             char(1) NOT NULL DEFAULT '9',
  ADD COLUMN prefixos_reservados text[]  NOT NULL DEFAULT '{9}';

ALTER TABLE cfg_ean_gerador
  ADD CONSTRAINT cfg_ean_gerador_prefixo_ck CHECK (prefixo ~ '^[0-9]$'),
  -- O prefixo corrente TEM de estar reservado. Sem isto, trocar o prefixo abriria silenciosamente
  -- a faixa nova para importação de código legado.
  ADD CONSTRAINT cfg_ean_gerador_prefixo_reservado_ck CHECK (prefixo = ANY (prefixos_reservados));

COMMENT ON COLUMN cfg_ean_gerador.prefixo IS
  'Primeiro digito do codigo interno gerado (SKU). Lido por gerar_ean13_interno() — nunca literal no codigo.';
COMMENT ON COLUMN cfg_ean_gerador.prefixos_reservados IS
  'Todo prefixo ja usado pelo gerador. A importacao de estoque RECUSA codigo de barras que comece por qualquer um deles. Trocar `prefixo` exige acrescentar o antigo aqui (garantido por CHECK).';

-- ---------------------------------------------------------------------------------------------
-- A função passa a ler o prefixo em vez de carregá-lo no corpo. Todo o resto é idêntico à V017:
-- estrutura FIIISSSSSSSSD, dígito verificador EAN-13/GTIN (peso 1/3 alternado), SECURITY DEFINER,
-- e o UPDATE...RETURNING atômico que dispensa lock explícito.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION gerar_ean13_interno() RETURNS text
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  v_banco   smallint;
  v_seq     bigint;
  v_prefixo char(1);
  v_corpo   text;
  v_soma    int := 0;
  v_dv      int;
  i         int;
BEGIN
  UPDATE cfg_ean_gerador
     SET proximo_sequencial = proximo_sequencial + 1
   RETURNING id_banco, proximo_sequencial - 1, prefixo
    INTO v_banco, v_seq, v_prefixo;

  IF v_seq > 99999999 THEN
    RAISE EXCEPTION 'Sequencial de EAN-13 esgotado (máximo 99.999.999) — banco %.', v_banco;
  END IF;

  v_corpo := v_prefixo || lpad(v_banco::text, 3, '0') || lpad(v_seq::text, 8, '0');

  FOR i IN 1..12 LOOP
    v_soma := v_soma + substr(v_corpo, i, 1)::int * (CASE WHEN i % 2 = 0 THEN 3 ELSE 1 END);
  END LOOP;
  v_dv := (10 - (v_soma % 10)) % 10;

  RETURN v_corpo || v_dv::text;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- Leitura dos prefixos reservados pela aplicação. `niner_app` continua SEM grant na tabela (V017
-- é explícita: só via função, para ninguém escrever no contador por fora) — então a importação
-- também consulta por função, não por SELECT direto.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prefixos_ean_reservados() RETURNS text[]
LANGUAGE sql SECURITY DEFINER STABLE AS $$
  SELECT prefixos_reservados FROM cfg_ean_gerador LIMIT 1;
$$;
COMMENT ON FUNCTION prefixos_ean_reservados() IS
  'Prefixos que a importacao de estoque recusa em EAN_CODIGO_BARRAS (colidiriam com o SKU interno).';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_app') THEN
    GRANT EXECUTE ON FUNCTION prefixos_ean_reservados() TO niner_app;
  END IF;
END $$;
