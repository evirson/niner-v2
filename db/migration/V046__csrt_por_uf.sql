-- V046 — CSRT do responsável técnico POR UF (2026-08-20).
-- Ver docs/MODULOFISCAL.md §9.9.
--
-- POR QUE ESTA TABELA EXISTE
-- O CSRT (Código de Segurança do Responsável Técnico, NT 2018.005) é emitido pela SEFAZ **de cada
-- UF** para o CNPJ de quem desenvolve o software emissor (a MITRYUSCASH). Até aqui ele morava em
-- duas variáveis de ambiente ÚNICAS para a aplicação inteira — o que só funciona enquanto todo
-- lojista emitir no mesmo estado. O Nainer é vendido para as 27 unidades da federação, então o
-- código da UF de São Paulo não pode ser o mesmo campo que o do Paraná: vira LINHA, igual ao
-- endpoint do autorizador (F10, "a UF é dado, não código").
--
-- Chaveada também por AMBIENTE de propósito: homologação e produção são cadastros separados no
-- portal, e usar o de homologação em produção é exatamente o tipo de erro que só aparece na
-- primeira venda real.
--
-- GLOBAL, sem id_tenant e sem RLS — é dado da casa de software, igual para todos os lojistas,
-- mesma exceção documentada de cfg_uf_autorizador e cfg_produto_ncm (P8 não se aplica a tabela
-- sem id_tenant). ⚠️ E é o único cfg_* que niner_app ESCREVE: os outros são carga por script do
-- dono, este é mantido pelo backoffice (/api/admin), porque um CSRT novo chega quando entra
-- lojista de um estado novo — evento de horário comercial, não de deploy.

CREATE TABLE cfg_csrt_resptec (
  uf            char(2)      NOT NULL,
  ambiente      smallint     NOT NULL,   -- tpAmb: 1 produção · 2 homologação
  id_csrt       char(2)      NOT NULL,   -- idCSRT do XSD: exatamente 2 dígitos
  -- ⚠️ SEGREDO. Cifrado com AES-256-GCM (comum.seguranca.SegredoCifrador) pela chave mestra
  -- niner.seguranca.chave-segredos, que vive FORA do banco: quem levar só o dump não decifra.
  -- O que vai no XML é o hashCSRT, nunca este valor.
  csrt_cifrado  text         NOT NULL,
  observacao    text,
  atualizado_em timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT cfg_csrt_resptec_pk PRIMARY KEY (uf, ambiente),
  CONSTRAINT cfg_csrt_resptec_uf_ck       CHECK (uf ~ '^[A-Z]{2}$'),
  CONSTRAINT cfg_csrt_resptec_ambiente_ck CHECK (ambiente IN (1, 2)),
  CONSTRAINT cfg_csrt_resptec_id_ck       CHECK (id_csrt ~ '^[0-9]{2}$')
);
COMMENT ON TABLE cfg_csrt_resptec IS 'CSRT (NT 2018.005) do responsavel tecnico por UF x ambiente. GLOBAL, sem RLS. Segredo cifrado em repouso; mantido pelo backoffice.';

-- ---------------------------------------------------------------------------------------------
-- exige_csrt — a NT deixa a exigência do par idCSRT/hashCSRT "a critério da UF", e o PR já prova
-- que ela varia DENTRO do mesmo estado: cobra na NF-e 55 (cStat 975 sem o par) e não cobra na
-- NFC-e 65, que rodou meses sem. Então também é dado, não `if`.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE cfg_uf_autorizador ADD COLUMN exige_csrt boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN cfg_uf_autorizador.exige_csrt IS 'UF exige idCSRT/hashCSRT neste modelo (NT 2018.005, a criterio da UF). PR: 55 sim, 65 nao — medido ao vivo em 2026-08-19.';

UPDATE cfg_uf_autorizador SET exige_csrt = true  WHERE modelo = 55;
UPDATE cfg_uf_autorizador SET exige_csrt = false WHERE modelo = 65;

-- =============================================================================================
-- GRANTS
-- niner_app LÊ (para emitir) e ESCREVE (backoffice). ⚠️ Sem DELETE de linha por engano valer
-- barato: apagar o CSRT de uma UF derruba a emissão de modelo 55 daquele estado inteiro — mas o
-- backoffice precisa poder remover um código revogado, então o DELETE existe e a tela confirma.
-- Bloco condicional à existência da role: ela é objeto de CLUSTER, não de banco (mesmo motivo
-- da V044), e em banco de teste/CI a aplicação conecta como superusuário.
-- =============================================================================================
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_csrt_resptec TO niner_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_owner') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_csrt_resptec TO niner_owner;
  END IF;
END $$;
