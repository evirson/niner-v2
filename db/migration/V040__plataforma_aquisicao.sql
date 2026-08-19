-- V040 — Aquisição e marketing (ADR-017): funil próprio, first-party, no control-plane.
--
-- Estes dados existem ANTES de qualquer tenant (o visitante ainda não é cliente): são globais
-- (P9), sem id_tenant no RLS. O que os liga ao dinheiro é `lead.id_tenant`, preenchido quando o
-- visitante vira conta — é isso que permite responder "a campanha X gerou quanto de MRR?" com
-- uma consulta, coisa que nenhuma ferramenta externa faz (ela não conhece plataforma.assinatura).

CREATE TYPE plataforma.status_lead AS ENUM
  ('NOVO', 'CONTATADO', 'QUALIFICADO', 'CONVERTIDO', 'PERDIDO');

-- ---------------------------------------------------------------------------------------------
-- visita_site — pageview anônimo. SEM PII: nada de IP bruto, nada digitado em formulário.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE plataforma.visita_site (
  id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  visitante_id  uuid        NOT NULL,
  sessao_id     uuid,
  caminho       text        NOT NULL,
  referrer      text,
  utm_source    text,
  utm_medium    text,
  utm_campaign  text,
  utm_content   text,
  utm_term      text,
  dispositivo   text,                    -- 'MOBILE' | 'DESKTOP' (derivado, sem user-agent bruto)
  criado_em     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX visita_site_visitante_ix ON plataforma.visita_site (visitante_id);
CREATE INDEX visita_site_criado_ix    ON plataforma.visita_site (criado_em);
CREATE INDEX visita_site_campanha_ix  ON plataforma.visita_site (utm_campaign) WHERE utm_campaign IS NOT NULL;

COMMENT ON TABLE plataforma.visita_site IS
  'Pageview anonimo (ADR-017). First-party, sem cookie de terceiro e sem PII. Expurgo/agregacao ainda em aberto.';

-- ---------------------------------------------------------------------------------------------
-- evento_marketing — sinal de intenção: clique em WhatsApp/Instagram, inicio de signup, FAQ,
-- profundidade de leitura. É o que diferencia "entrou e saiu" de "quase comprou".
-- ---------------------------------------------------------------------------------------------
CREATE TABLE plataforma.evento_marketing (
  id           bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  visitante_id uuid         NOT NULL,
  sessao_id    uuid,
  tipo         text         NOT NULL,    -- CLIQUE_WHATSAPP, INICIO_SIGNUP, FAQ, SCROLL, NAV...
  rotulo       text,                     -- de onde/qual (data-rotulo do HTML da landing)
  caminho      text,
  valor        numeric(12,2),
  criado_em    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX evento_marketing_visitante_ix ON plataforma.evento_marketing (visitante_id);
CREATE INDEX evento_marketing_tipo_ix      ON plataforma.evento_marketing (tipo, criado_em);

COMMENT ON TABLE plataforma.evento_marketing IS
  'Evento de interesse do visitante (ADR-017). payload sem PII — e-mail/telefone so existem em lead, com consentimento.';

-- ---------------------------------------------------------------------------------------------
-- lead — visitante identificado. A origem é a da PRIMEIRA visita (primeiro toque): o cadastro
-- costuma acontecer dias depois, em acesso direto, e atribuir pelo último toque creditaria
-- "direto" toda campanha paga.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE plataforma.lead (
  id_lead           integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  visitante_id      uuid,
  nome              text,
  email             text        NOT NULL,
  telefone_whatsapp text,
  nome_loja         text,
  utm_source        text,
  utm_medium        text,
  utm_campaign      text,
  utm_content       text,
  utm_term          text,
  referrer          text,
  pagina_entrada    text,
  status            plataforma.status_lead NOT NULL DEFAULT 'NOVO',
  id_tenant         smallint    REFERENCES plataforma.tenant (id_tenant),   -- virou conta
  anotacao          text,
  consentimento_em  timestamptz,
  criado_em         timestamptz NOT NULL DEFAULT now(),
  atualizado_em     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT lead_email_uk UNIQUE (email)
);
CREATE INDEX lead_visitante_ix ON plataforma.lead (visitante_id);
CREATE INDEX lead_status_ix    ON plataforma.lead (status, criado_em);
CREATE INDEX lead_tenant_ix    ON plataforma.lead (id_tenant) WHERE id_tenant IS NOT NULL;

COMMENT ON TABLE plataforma.lead IS
  'Visitante identificado (ADR-017). id_tenant preenchido no signup fecha o funil visita -> conta -> faixa paga.';
COMMENT ON COLUMN plataforma.lead.utm_source IS 'Origem da PRIMEIRA visita (primeiro toque), nao da visita que converteu.';

GRANT SELECT, INSERT, UPDATE ON plataforma.visita_site      TO niner_app;
GRANT SELECT, INSERT, UPDATE ON plataforma.evento_marketing TO niner_app;
GRANT SELECT, INSERT, UPDATE ON plataforma.lead             TO niner_app;
