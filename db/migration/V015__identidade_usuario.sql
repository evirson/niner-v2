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

-- Um único ADMIN por tenant (2026-07-28, decisão de produto) — o admin nasce no signup
-- (SignupService.assinar) e nunca muda: a tela cadastros.usuario não cria nem edita o campo
-- administrador (UsuarioService ignora/nunca grava esse campo pra criar/atualizar). Índice
-- único parcial é a garantia de banco, defesa em profundidade além da regra de aplicação.
CREATE UNIQUE INDEX usuario_um_admin_uk ON usuario (id_tenant) WHERE administrador = true;

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

-- Empresas que o usuário pode acessar (2026-07-28, tela cadastros.usuario) — N:N, independente
-- de usuario.id_empresa (que é só a empresa de lotação, não usada no formulário desta tela).
-- Pré-requisito pra telas/relatórios restritos por empresa quando o tenant tiver mais de uma
-- (Q6 já permite N empresas por tenant, mesmo que hoje só nasça uma no signup). Não confundir
-- com usuario_rotina: aqui é "em quais lojas ele opera", lá é "quais rotinas/telas ele pode
-- usar" — a segunda ainda não tem UI (ver docs/telas/usuario.md, "Non-goals").
CREATE TABLE usuario_empresa (
  id_usuario integer  NOT NULL,
  id_tenant  smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa integer  NOT NULL,
  CONSTRAINT usuario_empresa_pk PRIMARY KEY (id_usuario, id_empresa),
  -- FK composta (P8) — mesmo motivo de usuario_empresa_fk/usuario_rotina_usuario_fk acima.
  -- ON DELETE CASCADE: um acesso a empresa não tem sentido sem o usuário dono (diferente de
  -- usuario_rotina, que fica sem CASCADE porque ainda não é gravada por nenhum código).
  CONSTRAINT usuario_empresa_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario) ON DELETE CASCADE,
  CONSTRAINT usuario_empresa_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa)
);

CREATE INDEX usuario_empresa_id_tenant_ix ON usuario_empresa (id_tenant);

COMMENT ON TABLE usuario         IS 'Usuário do lojista (RLS). email único por tenant. ADMIN/OPERADOR deriva de administrador (R8).';
COMMENT ON TABLE usuario_rotina  IS 'Permissões finas legadas por rotina (opcional além do papel ADMIN/OPERADOR). Sem UI ainda.';
COMMENT ON TABLE usuario_empresa IS 'Empresas que o usuário pode acessar (N:N, tela cadastros.usuario). Sujeita a RLS (V024).';
