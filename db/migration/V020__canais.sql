-- V020 — canais (§3.3.5/§3.1): canal de venda + anúncio (de-para SKU ↔ canal, R6).
-- Credenciais cifradas em repouso (AES-GCM, chave fora do banco); payloads/config em JSONB.
-- Adapter por marketplace implementa CanalDeVenda; o domínio nunca vê payload de ML/Shopee.

CREATE TABLE canal (
  id_canal      integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  tipo          tipo_canal   NOT NULL,
  nome          text         NOT NULL,
  credenciais   jsonb,                              -- cifrado AES-GCM (ADR-005)
  status        status_canal NOT NULL DEFAULT 'DESCONECTADO',
  config        jsonb,
  criado_em     timestamptz  NOT NULL DEFAULT now(),
  atualizado_em timestamptz  NOT NULL DEFAULT now(),
  -- base para FK composta (2026-07-16, P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT canal_id_canal_uk UNIQUE (id_tenant, id_canal)
);
CREATE INDEX canal_id_tenant_ix ON canal (id_tenant);

CREATE TABLE anuncio (
  id_anuncio    integer        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint       NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_canal      integer        NOT NULL,
  id_variacao   integer        NOT NULL,               -- de-para com o SKU (R6)
  id_externo    text           NOT NULL,               -- id do anúncio no marketplace
  preco         numeric(12,2),
  status_sync   status_sync    NOT NULL DEFAULT 'PENDENTE',
  ultimo_erro   text,
  criado_em     timestamptz    NOT NULL DEFAULT now(),
  atualizado_em timestamptz    NOT NULL DEFAULT now(),
  CONSTRAINT anuncio_canal_externo_uk UNIQUE (id_canal, id_externo),
  -- base para FK composta (2026-07-16, P8) de pedido_item.id_anuncio.
  CONSTRAINT anuncio_id_anuncio_uk UNIQUE (id_tenant, id_anuncio),
  -- FKs compostas — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT anuncio_canal_fk FOREIGN KEY (id_tenant, id_canal)
    REFERENCES canal (id_tenant, id_canal),
  CONSTRAINT anuncio_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao)
);
CREATE INDEX anuncio_id_tenant_ix ON anuncio (id_tenant);
CREATE INDEX anuncio_variacao_ix  ON anuncio (id_tenant, id_variacao);

COMMENT ON TABLE  canal   IS 'Canal de venda (ML/Shopee/Amazon/e-commerce). Credenciais cifradas (ADR-005). RLS.';
COMMENT ON TABLE  anuncio IS 'De-para anúncio ↔ variação/SKU (R6). Sincronização de estoque/preço via outbox (P2).';
