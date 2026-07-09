-- V011 — Privilégios da role de aplicação (niner_app) no control-plane.
--
-- As tabelas de plataforma são GLOBAIS (sem RLS de tenant, P9). O corte
-- staff × tenant é feito na camada de aplicação (SecurityFilterChain por
-- superfície). niner_app NUNCA tem BYPASSRLS (P8) — isso vale para o RLS das
-- tabelas de domínio (migrations futuras), não para estas tabelas globais.

GRANT USAGE ON SCHEMA plataforma TO niner_app;

GRANT SELECT, INSERT, UPDATE, DELETE
  ON ALL TABLES IN SCHEMA plataforma TO niner_app;

GRANT EXECUTE ON FUNCTION plataforma.tenant_atual() TO niner_app;

-- Objetos futuros criados por niner_owner neste schema já nascem acessíveis à app.
ALTER DEFAULT PRIVILEGES FOR ROLE niner_owner IN SCHEMA plataforma
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO niner_app;

-- Auditoria de impersonação é imutável (P3): permitir só encerrar (UPDATE), nunca apagar.
REVOKE DELETE ON plataforma.impersonacao_log FROM niner_app;
