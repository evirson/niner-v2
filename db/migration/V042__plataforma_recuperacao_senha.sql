-- V042 — Recuperação de senha do usuário do lojista (2026-08-19).
--
-- Fica em `plataforma` e não no domínio por um motivo prático: o pedido chega pela superfície
-- PÚBLICA, onde não há JWT e portanto não há TenantContext — uma tabela sob RLS de tenant seria
-- ilegível justamente no momento em que precisa ser lida. O `id_tenant` fica como coluna, e o
-- serviço estabelece o contexto antes de tocar em `usuario` (que é de domínio e tem RLS).
--
-- Regras de segurança embutidas no formato:
--   · guarda o HASH do token (SHA-256), nunca o token — vazamento de banco não vira invasão;
--   · uso único (`usado_em`) e validade curta (`expira_em`), decididos no serviço;
--   · nada de e-mail/nome aqui: o vínculo é com o usuário, e o resto se lê dele.

CREATE TABLE plataforma.recuperacao_senha (
  id_recuperacao bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant      smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_usuario     integer     NOT NULL,
  token_hash     text        NOT NULL,
  expira_em      timestamptz NOT NULL,
  usado_em       timestamptz,
  criado_em      timestamptz NOT NULL DEFAULT now(),
  ip_solicitante text,
  CONSTRAINT recuperacao_senha_token_uk UNIQUE (token_hash)
);

CREATE INDEX recuperacao_senha_usuario_ix ON plataforma.recuperacao_senha (id_tenant, id_usuario, criado_em);

COMMENT ON TABLE plataforma.recuperacao_senha IS
  'Pedidos de redefinicao de senha (uso unico, validade curta). Guarda o HASH do token, nunca o token.';

GRANT SELECT, INSERT, UPDATE ON plataforma.recuperacao_senha TO niner_app;
