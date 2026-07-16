-- V015 — identidade.usuario + usuario_rotina (§3.3.2). Usuário do TENANT (lojista).
-- Não confundir com plataforma.staff (P9). email único POR tenant (não global).
-- senha do legado (texto) vira senha_hash (BCrypt/Argon2). Papel ADMIN/OPERADOR
-- deriva de `administrador` (R8); `usuario_rotina` mantém permissões finas legadas.

CREATE TABLE usuario (
  id_usuario    integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa    integer,                                        -- empresa de lotação (opcional)
  nome_usuario  text        NOT NULL,
  email         text        NOT NULL,
  senha_hash    text        NOT NULL,
  ativo         boolean     NOT NULL DEFAULT true,
  administrador boolean     NOT NULL DEFAULT false,             -- true => ADMIN, false => OPERADOR (R8)
  criado_em     timestamptz NOT NULL DEFAULT now(),
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  -- FK composta (2026-07-16, P8): garante que id_empresa é do MESMO tenant do usuário — FK
  -- simples não valida isso (RLS não se aplica a checagem de integridade referencial).
  CONSTRAINT usuario_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT usuario_id_usuario_uk UNIQUE (id_tenant, id_usuario)
);

-- e-mail único (case-insensitive) por tenant — mesmo padrão de plataforma.staff (V010)
CREATE UNIQUE INDEX usuario_email_uk ON usuario (id_tenant, lower(email));

CREATE INDEX usuario_id_tenant_ix ON usuario (id_tenant);

CREATE TABLE usuario_rotina (
  id_usuario integer  NOT NULL,
  id_tenant  smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_rotina text    NOT NULL,
  CONSTRAINT usuario_rotina_pk PRIMARY KEY (id_usuario, nome_rotina),
  -- FK composta (2026-07-16, P8) — ver comentário em usuario_empresa_fk acima.
  CONSTRAINT usuario_rotina_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario)
);

CREATE INDEX usuario_rotina_id_tenant_ix ON usuario_rotina (id_tenant);

COMMENT ON TABLE usuario         IS 'Usuário do lojista (RLS). email único por tenant. ADMIN/OPERADOR deriva de administrador (R8).';
COMMENT ON TABLE usuario_rotina  IS 'Permissões finas legadas por rotina (opcional além do papel ADMIN/OPERADOR).';
