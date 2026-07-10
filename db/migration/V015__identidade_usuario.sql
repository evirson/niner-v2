-- V015 — identidade.usuario + usuario_rotina (§3.3.2). Usuário do TENANT (lojista).
-- Não confundir com plataforma.staff (P9). email único POR tenant (não global).
-- senha do legado (texto) vira senha_hash (BCrypt/Argon2). Papel ADMIN/OPERADOR
-- deriva de `administrador` (R8); `usuario_rotina` mantém permissões finas legadas.

CREATE TABLE usuario (
  id_usuario    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     bigint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa    bigint      REFERENCES empresa (id_empresa),   -- empresa de lotação (opcional)
  nome_usuario  text        NOT NULL,
  email         text        NOT NULL,
  senha_hash    text        NOT NULL,
  ativo         boolean     NOT NULL DEFAULT true,
  administrador boolean     NOT NULL DEFAULT false,             -- true => ADMIN, false => OPERADOR (R8)
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT usuario_email_uk UNIQUE (id_tenant, email)
);

CREATE INDEX usuario_id_tenant_ix ON usuario (id_tenant);

CREATE TABLE usuario_rotina (
  id_usuario bigint NOT NULL REFERENCES usuario (id_usuario) ON DELETE CASCADE,
  id_tenant  bigint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_rotina text  NOT NULL,
  CONSTRAINT usuario_rotina_pk PRIMARY KEY (id_usuario, nome_rotina)
);

CREATE INDEX usuario_rotina_id_tenant_ix ON usuario_rotina (id_tenant);

COMMENT ON TABLE usuario         IS 'Usuário do lojista (RLS). email único por tenant. ADMIN/OPERADOR deriva de administrador (R8).';
COMMENT ON TABLE usuario_rotina  IS 'Permissões finas legadas por rotina (opcional além do papel ADMIN/OPERADOR).';
