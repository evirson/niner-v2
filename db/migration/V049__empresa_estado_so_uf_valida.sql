-- V049 — `empresa.estado` só aceita UF válida (2026-08-20).
-- Ver CLAUDE.md ("Fuso: da LOJA no plano do lojista") e com.vetor.niner.comum.tempo.FusoDaUf.
--
-- POR QUE AGORA
-- A coluna nasceu como texto livre, anulável e sem CHECK (V014). Isso era tolerável enquanto ela
-- só aparecia no endereço impresso. Deixou de ser em 2026-08-20, quando a UF passou a decidir
-- **duas coisas que quebram silenciosamente**:
--   1. o **fuso horário da loja** — sigla errada = hora errada no cupom e "hoje" errado no caixa;
--   2. **para qual SEFAZ** o documento é transmitido (`cfg_uf_autorizador`, V047).
-- Nos dois casos o defeito não aparece no cadastro: aparece na primeira venda do cliente.
--
-- ⚠️ NULL CONTINUA VALENDO, de propósito. O signup cria a empresa **sem** UF (o funil de aquisição
-- não pergunta) e o lojista preenche depois em Dados da Empresa. Um NOT NULL aqui quebraria o
-- cadastro de conta nova — que é o pior lugar possível para quebrar. Quem **exige** a UF é o
-- caminho fiscal, que falha explicitamente sem ela.
--
-- ⛔ POR QUE A CONSTRAINT NASCE `NOT VALID`
-- Ontem (2026-08-19) o deploy de produção parou no Flyway e nada foi publicado. Uma constraint que
-- varre linha existente é exatamente esse risco de novo: basta **uma** empresa com 'Paraná', 'pr '
-- ou 'XX' gravada antes desta regra para o `ALTER TABLE` falhar e derrubar a publicação inteira.
-- `NOT VALID` garante o que importa — **toda linha nova ou alterada é validada** — sem tocar no
-- passado. O bloco abaixo tenta validar o histórico e, se não der, avisa em vez de abortar.

-- 1) Normaliza o que dá para normalizar sem adivinhar. Só caixa e espaço — 'pr ' vira 'PR'.
--    Nada é apagado: valor que não for sigla de 2 letras continua exatamente como está, visível
--    para quem for corrigir.
UPDATE empresa
   SET estado = upper(trim(estado))
 WHERE estado IS NOT NULL
   AND estado <> upper(trim(estado));

-- 2) A regra. As 27 siglas são as mesmas de `ChaveAcesso.CODIGO_UF` e de `FusoDaUf` — três cópias
--    que precisam andar juntas, e é isso que `EmpresaUfValidaTest` confere.
ALTER TABLE empresa
  ADD CONSTRAINT empresa_estado_ck CHECK (
    estado IS NULL OR estado IN (
      'AC','AL','AP','AM','BA','CE','DF','ES','GO','MA','MT','MS','MG','PA',
      'PB','PR','PE','PI','RJ','RN','RS','RO','RR','SC','SP','SE','TO')
  ) NOT VALID;

-- 3) Tenta promover a constraint a validada. Banco limpo (dev, CI, cliente novo) sai daqui com o
--    histórico conferido; banco com sujeira sai com a regra valendo para o futuro e um aviso no
--    log, em vez de um deploy travado.
DO $$
BEGIN
  ALTER TABLE empresa VALIDATE CONSTRAINT empresa_estado_ck;
  RAISE NOTICE 'empresa_estado_ck validada: todas as UFs gravadas sao validas.';
EXCEPTION WHEN check_violation THEN
  RAISE WARNING 'Existe empresa com UF invalida — empresa_estado_ck fica NOT VALID (a regra JA vale '
                'para insercao e alteracao). Liste com: SELECT id_tenant, id_empresa, estado FROM '
                'empresa WHERE estado IS NOT NULL AND estado NOT IN (''AC'',''AL'',''AP'',''AM'','
                '''BA'',''CE'',''DF'',''ES'',''GO'',''MA'',''MT'',''MS'',''MG'',''PA'',''PB'',''PR'','
                '''PE'',''PI'',''RJ'',''RN'',''RS'',''RO'',''RR'',''SC'',''SP'',''SE'',''TO''); '
                'corrija pela tela Dados da Empresa e depois rode: ALTER TABLE empresa VALIDATE '
                'CONSTRAINT empresa_estado_ck;';
END $$;

COMMENT ON COLUMN empresa.estado IS
  'UF do endereco. Decide o FUSO da loja (FusoDaUf) e o autorizador da SEFAZ (cfg_uf_autorizador). NULL = ainda nao informada (o signup nao pergunta); o caminho fiscal exige e falha sem ela.';
