-- V020 — canais (§3.3.5/§3.1): canal de venda + anúncio (de-para SKU ↔ canal, R6).
-- Credenciais cifradas em repouso (AES-GCM, chave fora do banco); payloads/config em JSONB.
-- Adapter por marketplace implementa CanalDeVenda; o domínio nunca vê payload de ML/Shopee.

CREATE TABLE canal (
  id_canal      bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  tipo          tipo_canal  NOT NULL,
  nome          text        NOT NULL,
  credenciais   jsonb,                              -- cifrado AES-GCM (ADR-005)
  status        status_canal NOT NULL DEFAULT 'DESCONECTADO',
  config        jsonb,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX canal_id_tenant_ix ON canal (id_tenant);

CREATE TABLE anuncio (
  id_anuncio    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_canal      bigint      NOT NULL REFERENCES canal (id_canal) ON DELETE CASCADE,
  id_variacao   bigint      NOT NULL REFERENCES produto_barra (id_variacao),   -- de-para com o SKU (R6)
  id_externo    text        NOT NULL,               -- id do anúncio no marketplace
  preco         numeric(12,2),
  status_sync   status_sync NOT NULL DEFAULT 'PENDENTE',
  ultimo_erro   text,
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT anuncio_canal_externo_uk UNIQUE (id_canal, id_externo)
);
CREATE INDEX anuncio_id_tenant_ix ON anuncio (id_tenant);
CREATE INDEX anuncio_variacao_ix  ON anuncio (id_tenant, id_variacao);

COMMENT ON TABLE  canal   IS 'Canal de venda (ML/Shopee/Amazon/e-commerce). Credenciais cifradas (ADR-005). RLS.';
COMMENT ON TABLE  anuncio IS 'De-para anúncio ↔ variação/SKU (R6). Sincronização de estoque/preço via outbox (P2).';
