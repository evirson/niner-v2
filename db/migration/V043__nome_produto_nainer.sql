-- V043 — O produto passa a se chamar **Nainer** (decisão do dono do produto, 2026-08-19).
--
-- Só o que é VISÍVEL ao usuário muda. Os identificadores internos ficam como estão — banco
-- `niner_db`, roles `niner_app`/`niner_owner`/`niner_backup`, pacote `com.vetor.niner`, prefixo
-- de variável `NINER_` e o repositório `niner-v2`. Renomeá-los seria um refactor de centenas de
-- arquivos e uma migração de banco inteira para trocar um nome que ninguém fora do time lê.
--
-- (O domínio `niner.com.br` também foi registrado, mas como defensivo: redireciona 301 para
-- `nainer.com.br` no nginx — ver infra/nginx/nainer.conf.)

-- Remetente padrão dos e-mails da plataforma (V041 nasceu com 'Niner').
ALTER TABLE plataforma.configuracao_plataforma
  ALTER COLUMN smtp_remetente_nome SET DEFAULT 'Nainer';

-- A linha singleton já existente também acompanha — desde que ninguém tenha personalizado.
UPDATE plataforma.configuracao_plataforma
   SET smtp_remetente_nome = 'Nainer'
 WHERE id = 1 AND smtp_remetente_nome = 'Niner';

COMMENT ON SCHEMA plataforma IS
  'Control-plane do SaaS Nainer (tenants, planos, assinaturas, faturas, cobranca, staff). Tabelas globais, fora do RLS de tenant (P9).';
