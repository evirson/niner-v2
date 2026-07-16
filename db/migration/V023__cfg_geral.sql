-- V023 — cfg_geral: configuração geral, singleton POR tenant (§3.5.1).
-- Parâmetros da loja. Campos de crediário ficam prontos, mas o crediário é Fase 2 (Q5).

CREATE TABLE cfg_geral (
  id_tenant                 smallint     PRIMARY KEY REFERENCES plataforma.tenant (id_tenant),
  percentual_desconto_venda numeric(5,2) NOT NULL DEFAULT 0,
  juros_crediario_dias      integer      NOT NULL DEFAULT 0,   -- Fase 2 (Q5)
  juros_crediario           numeric(5,2) NOT NULL DEFAULT 0,   -- Fase 2 (Q5)
  multa_crediario_dias      integer      NOT NULL DEFAULT 0,   -- Fase 2 (Q5)
  multa_crediario           numeric(5,2) NOT NULL DEFAULT 0,   -- Fase 2 (Q5)
  cfg_usa_variante_linha    boolean      NOT NULL DEFAULT true,
  cfg_usa_variante_coluna   boolean      NOT NULL DEFAULT true,
  atualizado_em             timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE cfg_geral IS 'Config geral da loja: 1 linha por tenant (id_tenant é PK). RLS. Campos de crediário são Fase 2 (Q5).';
