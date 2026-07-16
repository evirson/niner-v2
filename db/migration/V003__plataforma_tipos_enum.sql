-- V003 — Tipos ENUM do control-plane (conjuntos estáveis, §3.3.1).

-- Ciclo de vida da conta assinante (tenant).
CREATE TYPE plataforma.status_tenant AS ENUM
  ('TRIAL', 'ATIVA', 'INADIMPLENTE', 'SUSPENSA', 'CANCELADA');

-- Ciclo de vida da assinatura (espelha o do tenant).
CREATE TYPE plataforma.status_assinatura AS ENUM
  ('TRIAL', 'ATIVA', 'INADIMPLENTE', 'SUSPENSA', 'CANCELADA');

-- Periodicidade da cobrança.
CREATE TYPE plataforma.ciclo_cobranca AS ENUM ('MENSAL', 'ANUAL');

-- Situação da fatura da assinatura.
CREATE TYPE plataforma.status_fatura AS ENUM
  ('ABERTA', 'PAGA', 'VENCIDA', 'CANCELADA', 'ESTORNADA');

-- Meio de pagamento (Brasil).
CREATE TYPE plataforma.metodo_pagamento AS ENUM ('PIX', 'BOLETO', 'CARTAO');

-- Situação de um pagamento no gateway.
CREATE TYPE plataforma.status_pagamento AS ENUM
  ('PENDENTE', 'CONFIRMADO', 'FALHOU', 'ESTORNADO');

-- Papéis do staff da plataforma (separados do RBAC ADMIN/OPERADOR do tenant — R18).
CREATE TYPE plataforma.papel_staff AS ENUM ('SUPER_ADMIN', 'SUPORTE', 'FINANCEIRO');

