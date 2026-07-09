-- V001 — Schema do Plano de Controle (control-plane) do SaaS Niner.
-- Executado pela role dona (niner_owner). Ver §3.1.1 / §3.3.11 / §3.5.1 da spec.
--
-- As tabelas deste schema são GLOBAIS: pertencem à plataforma (Vetor), não a um
-- lojista. Não são sujeitas ao RLS de tenant (P9). O corte staff × tenant é feito
-- na camada de aplicação (SecurityFilterChain por superfície).

CREATE SCHEMA IF NOT EXISTS plataforma AUTHORIZATION niner_owner;

COMMENT ON SCHEMA plataforma IS
  'Control-plane do SaaS Niner (tenants, planos, assinaturas, faturas, cobranca, staff). Tabelas globais, fora do RLS de tenant (P9).';
