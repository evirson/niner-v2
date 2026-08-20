-- V039 — Cobrança da assinatura via Mercado Pago (ADR-016).
--
-- PIX avulso mensal (Payments API) e recorrência (Preapproval) atrás da interface
-- `GatewayCobranca` (ADR-008). Tabelas de plataforma são GLOBAIS (P9) — sem id_tenant no RLS.

-- ---------------------------------------------------------------------------------------------
-- fatura: qual faixa/ciclo está sendo cobrado e os dados do PIX gerado.
-- O PIX é dado VOLÁTIL (expira em horas) — fica na fatura porque é dela, e é regravado a cada
-- nova tentativa de pagamento da mesma competência.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE plataforma.fatura
  ADD COLUMN id_plano       integer REFERENCES plataforma.plano (id_plano),
  ADD COLUMN ciclo          plataforma.ciclo_cobranca NOT NULL DEFAULT 'MENSAL',
  ADD COLUMN link_pagamento text,
  ADD COLUMN pix_copia_cola text,
  ADD COLUMN qr_code_base64 text,
  ADD COLUMN expira_em      timestamptz,
  ADD COLUMN pago_em        timestamptz;

COMMENT ON COLUMN plataforma.fatura.id_plano       IS 'Faixa que esta fatura paga (ADR-015). A troca de plano da assinatura so acontece quando ela e PAGA.';
COMMENT ON COLUMN plataforma.fatura.pix_copia_cola IS 'Payload PIX (copia e cola) da tentativa corrente. Volatil: regravado a cada nova cobranca da mesma competencia.';
COMMENT ON COLUMN plataforma.fatura.expira_em      IS 'Validade do PIX gerado. Expirado, a tela pede um novo — a fatura continua ABERTA.';

-- ---------------------------------------------------------------------------------------------
-- webhook_gateway: retentativa com espera crescente + dead-letter visível (P2).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE plataforma.webhook_gateway
  ADD COLUMN tentativas        smallint    NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
  ADD COLUMN proxima_tentativa timestamptz NOT NULL DEFAULT now();

COMMENT ON COLUMN plataforma.webhook_gateway.tentativas        IS 'Quantas vezes o worker tentou aplicar o efeito. Backoff exponencial; acima do teto vira dead-letter (erro preenchido).';
COMMENT ON COLUMN plataforma.webhook_gateway.proxima_tentativa IS 'Quando o worker pode pegar este evento de novo.';

-- fila do worker: nao processados cuja hora chegou (FOR UPDATE SKIP LOCKED)
DROP INDEX IF EXISTS plataforma.webhook_gateway_pendentes_ix;
CREATE INDEX webhook_gateway_pendentes_ix
  ON plataforma.webhook_gateway (proxima_tentativa)
  WHERE processado_em IS NULL;

-- Os GRANTs de V011 ja cobrem estas tabelas (existiam antes); colunas novas nao mudam privilegio.
