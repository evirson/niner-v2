-- V007 — fatura (cobrança de uma competência) e pagamento (tentativas no gateway).
-- Valores em NUMERIC (P7). Idempotência de cobrança e de transação de gateway (P2).

CREATE TABLE plataforma.fatura (
  id_fatura           integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_assinatura       integer       NOT NULL REFERENCES plataforma.assinatura (id_assinatura),
  id_tenant           smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  competencia         date          NOT NULL,
  valor               numeric(12,2) NOT NULL CHECK (valor >= 0),
  vencimento          date          NOT NULL,
  status              plataforma.status_fatura NOT NULL DEFAULT 'ABERTA',
  id_gateway_cobranca text,
  criado_em           timestamptz   NOT NULL DEFAULT now(),
  -- uma fatura por assinatura x competencia (evita cobranca duplicada — P2)
  CONSTRAINT fatura_assinatura_competencia_uk UNIQUE (id_assinatura, competencia)
);

CREATE INDEX fatura_id_tenant_ix        ON plataforma.fatura (id_tenant);
CREATE INDEX fatura_status_vencimento_ix ON plataforma.fatura (status, vencimento);

COMMENT ON TABLE plataforma.fatura IS 'Fatura/cobranca da assinatura (R14/R16). id_tenant desnormalizado para consulta.';

CREATE TABLE plataforma.pagamento (
  id_pagamento         integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_fatura            integer       NOT NULL REFERENCES plataforma.fatura (id_fatura),
  metodo               plataforma.metodo_pagamento NOT NULL,
  gateway              text,
  id_gateway_transacao text,
  valor                numeric(12,2) NOT NULL CHECK (valor >= 0),
  status               plataforma.status_pagamento NOT NULL DEFAULT 'PENDENTE',
  pago_em              timestamptz,
  payload_bruto        jsonb,
  criado_em            timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX pagamento_id_fatura_ix ON plataforma.pagamento (id_fatura);

-- idempotência da transação no gateway (não registra o mesmo pagamento 2x — P2)
CREATE UNIQUE INDEX pagamento_gateway_transacao_uk
  ON plataforma.pagamento (gateway, id_gateway_transacao)
  WHERE gateway IS NOT NULL AND id_gateway_transacao IS NOT NULL;

COMMENT ON TABLE plataforma.pagamento IS 'Tentativas/confirmacoes de pagamento de uma fatura, via adapter de gateway (ADR-008).';
