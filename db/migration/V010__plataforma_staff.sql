-- V010 — staff (usuários da plataforma) e impersonacao_log (auditoria — R21/P3/P9).
-- População separada dos usuários do tenant (identidade.usuario, migration futura).

CREATE TABLE plataforma.staff (
  id_staff   integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nome       text        NOT NULL,
  email      text        NOT NULL,
  senha_hash text        NOT NULL,              -- BCrypt/Argon2 (nunca texto puro)
  ativo      boolean     NOT NULL DEFAULT true,
  papel      plataforma.papel_staff NOT NULL,
  criado_em  timestamptz NOT NULL DEFAULT now()
);

-- e-mail único (case-insensitive) no universo do staff
CREATE UNIQUE INDEX staff_email_uk ON plataforma.staff (lower(email));

COMMENT ON TABLE plataforma.staff IS 'Staff da plataforma (SUPER_ADMIN/SUPORTE/FINANCEIRO). JWT aud=plataforma; sem claim tid (R18).';

CREATE TABLE plataforma.impersonacao_log (
  id           integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_staff     integer     NOT NULL REFERENCES plataforma.staff (id_staff),
  id_tenant    integer     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  iniciado_em  timestamptz NOT NULL DEFAULT now(),
  encerrado_em timestamptz,
  motivo       text
);

CREATE INDEX impersonacao_log_tenant_ix ON plataforma.impersonacao_log (id_tenant, iniciado_em);
CREATE INDEX impersonacao_log_staff_ix  ON plataforma.impersonacao_log (id_staff, iniciado_em);

COMMENT ON TABLE plataforma.impersonacao_log IS 'Trilha imutavel de acesso de suporte a um tenant (R21/P3). Nunca deletar; so encerrar (encerrado_em).';
