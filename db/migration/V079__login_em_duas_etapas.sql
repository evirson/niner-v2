-- V079 — Login em duas etapas por e-mail (2026-08-27)
--
-- Pedido do dono do produto: uma opção no cadastro do usuário que, ligada, faz o login pedir um
-- código de 4 dígitos enviado por e-mail, válido por 10 minutos, com opção de reenviar.
--
-- É por usuário, não por conta: numa loja, o dono pode querer a segunda etapa para si e não para
-- o caixa, que entra e sai do sistema o dia inteiro no balcão.

ALTER TABLE usuario ADD COLUMN IF NOT EXISTS exige_codigo_login boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN usuario.exige_codigo_login IS
  'Login em duas etapas: depois da senha, exige um código de 4 dígitos enviado por e-mail (V079).';

-- ⚠️ SEM RLS, como plataforma.recuperacao_senha (V042) e plataforma.oauth_estado_canal (V065):
-- quem consulta é o endpoint PÚBLICO de login, que ainda não tem TenantContext — a segunda etapa
-- acontece antes de existir token. Sob RLS a consulta devolveria zero linhas em silêncio e
-- nenhum código seria aceito, sem nada em log.
CREATE TABLE plataforma.codigo_login (
  id_desafio     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  id_tenant      smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant) ON DELETE CASCADE,
  id_usuario     integer     NOT NULL,
  -- A empresa já resolvida na 1ª etapa. Guardada porque o token emitido na 2ª etapa precisa dela,
  -- e refazer a escolha depois do código seria pedir duas vezes a mesma coisa.
  id_empresa     integer     NOT NULL,
  -- HASH do código, nunca o código — mesmo princípio do token de recuperação de senha: um dump
  -- vazado não vira acesso.
  codigo_hash    text        NOT NULL,
  expira_em      timestamptz NOT NULL,
  usado_em       timestamptz,
  -- ⚠️ 4 dígitos são 10.000 combinações: sem teto de tentativas, um script acerta em minutos.
  -- É o limite de tentativas — não o tamanho do código — que sustenta a segurança aqui.
  tentativas     smallint    NOT NULL DEFAULT 0,
  reenvios       smallint    NOT NULL DEFAULT 0,
  ip_solicitante text,
  criado_em      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX codigo_login_usuario_ix ON plataforma.codigo_login (id_tenant, id_usuario);
-- Limpeza: o expirado não serve para nada e não é histórico de auditoria.
CREATE INDEX codigo_login_expira_ix ON plataforma.codigo_login (expira_em) WHERE usado_em IS NULL;

GRANT SELECT, INSERT, UPDATE ON plataforma.codigo_login TO niner_app;
-- Sem DELETE para a aplicação: apagar desafio usado apagaria o rastro de quem entrou e quando.
REVOKE DELETE ON plataforma.codigo_login FROM niner_app;
