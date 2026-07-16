-- V002 — Infra de contexto de tenant, usada pelas políticas RLS do domínio (P8).
--
-- Convenção: a aplicação executa, no início de cada transação de domínio,
--   SET LOCAL app.id_tenant = <id_tenant>;
-- (via interceptor JPA/JDBC, a partir do claim `tid` do JWT ou, no worker/outbox,
--  a partir de `evento.id_tenant`). SET LOCAL vive só na transação — sem vazamento
-- entre conexões do pool.
--
-- As tabelas de DOMÍNIO (migrations futuras, módulos do lojista) terão política:
--   USING (id_tenant = plataforma.tenant_atual())
-- As tabelas de PLATAFORMA (este schema) NÃO usam esta função (são globais, P9).

CREATE OR REPLACE FUNCTION plataforma.tenant_atual()
RETURNS smallint
LANGUAGE sql
STABLE
AS $$
  SELECT NULLIF(current_setting('app.id_tenant', true), '')::smallint;
$$;

COMMENT ON FUNCTION plataforma.tenant_atual() IS
  'id_tenant do contexto da transacao (app.id_tenant) ou NULL se ausente. Base das policies RLS de dominio (P8).';
