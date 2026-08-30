-- V098 — o backfill da V055 saiu VAZIO, e ninguém percebeu por nove dias.
--
-- ⚠️ QUINTA vez que este mesmo defeito morde neste projeto (V057, V089, V096 e agora a V055):
-- migration roda como `niner_owner`, e `FORCE ROW LEVEL SECURITY` (V024) **não poupa nem o dono da
-- tabela**. Sem `app.id_tenant` no contexto, o `UPDATE ... WHERE` casa zero linhas — e o Flyway
-- anuncia SUCESSO, porque não tem como saber que zero era o número errado.
--
-- MEDIDO NESTE BANCO, e é a diferença entre as duas leituras que prova o mecanismo:
--     niner_owner (rolsuper=f, rolbypassrls=f)
--     SET app.id_tenant=1; SELECT count(*) FROM cfg_geral;  -->  1
--     (sem SET)            SELECT count(*) FROM cfg_geral;  -->  0
--
-- A CRONOLOGIA que prova o dano, também medida:
--     tenant 1 criado ....... 2026-08-18 17:22
--     V054 aplicada ......... 2026-08-20 19:35   (ADD COLUMN ... NOT NULL DEFAULT false)
--     V055 aplicada ......... 2026-08-20 19:53   (UPDATE ... = true  -->  ZERO linhas)
-- Ou seja: a linha do tenant 1 já existia quando a coluna nasceu, recebeu `false`, e o UPDATE que
-- deveria consertá-la não a enxergou. O parâmetro ficou LIGADO (controle de estoque exigido) de
-- 20/08 a 29/08 — o contrário da decisão do dono do produto (*"na maioria das vezes o usuário não
-- vai querer controlar o estoque"*) — até alguém salvar Parâmetros do Sistema pela tela.
--
-- ⛔ E a justificativa escrita na isenção do `MigrationQueMexeEmDadoDeTenantTest` dizia que "o
-- `SET DEFAULT true` da linha acima salvou o caso (medido: o parâmetro está ligado)". O DEFAULT
-- cobre linha NOVA; ele nunca tocou a linha que já existia. O que salvou foi uma edição manual, dias
-- depois, por acaso — a medição estava certa e a conclusão sobre a CAUSA, errada.
--
-- ⛔ POR QUE ESTA MIGRATION NÃO REPETE O `UPDATE` CEGO DA V055:
-- em 20/08 era verdade que "ninguém chegou a escolher `false`". Hoje não é: o parâmetro está na
-- tela há dez dias e uma loja pode ter DESLIGADO o estoque negativo de propósito. Um
-- `UPDATE cfg_geral SET ... = true` sobrescreveria essa decisão sem avisar.
-- O critério abaixo separa acidente de escolha: só volta para `true` a linha que (a) está `false`,
-- (b) pertence a um tenant que já existia quando a V055 rodou e (c) NUNCA foi editada desde então
-- (`atualizado_em` anterior ao instante da V055). Quem mexeu na tela depois disso escolheu, e a
-- escolha é respeitada.

ALTER TABLE cfg_geral NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
  v_v055 timestamptz;
  v_corrigidas integer;
  v_restantes integer;
BEGIN
  SELECT installed_on INTO v_v055
    FROM flyway_schema_history
   WHERE version = '055'
   LIMIT 1;

  IF v_v055 IS NULL THEN
    -- Banco criado do zero DEPOIS da V055 (ou histórico limpo): a coluna já nasce com
    -- `DEFAULT true` e nenhum tenant existiu antes dela. Não há nada a consertar.
    RAISE NOTICE 'V098: V055 não está no histórico — banco novo, nada a corrigir.';
    RETURN;
  END IF;

  UPDATE cfg_geral g
     SET cfg_permite_estoque_negativo = true
    FROM plataforma.tenant t
   WHERE t.id_tenant = g.id_tenant
     AND g.cfg_permite_estoque_negativo = false
     AND t.criado_em < v_v055
     AND g.atualizado_em <= v_v055;
  GET DIAGNOSTICS v_corrigidas = ROW_COUNT;

  -- ⚠️ Conferir o resultado é o ponto da migration inteira: backfill que ninguém conferiu não foi
  -- verificado, foi torcido. As que sobram em `false` são escolha de alguém, e ficam como estão.
  SELECT count(*) INTO v_restantes
    FROM cfg_geral
   WHERE cfg_permite_estoque_negativo = false;

  RAISE NOTICE 'V098: % linha(s) corrigida(s); % linha(s) seguem em false por escolha do lojista.',
               v_corrigidas, v_restantes;
END $$;

ALTER TABLE cfg_geral FORCE ROW LEVEL SECURITY;
