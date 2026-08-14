-- V033 — Horário de acesso por dia da semana (docs/telas/usuario.md, 2026-08-11). Restringe
-- QUANDO um OPERADOR pode logar/trabalhar no sistema (segurança de acesso a dados sensíveis).
-- ADMIN nunca é afetado (só existe um por tenant, imutável — V015) — a regra é checada só pelo
-- valor de `usuario.administrador`, não precisa de coluna própria de exceção.

-- Liga/desliga o controle por usuário (2026-08-11, pedido do dono do produto: "colocar um check
-- também se controla horário de acessos" — desligado, nenhum dado de horário é pedido/aplicado).
ALTER TABLE usuario ADD COLUMN controla_horario_acesso boolean NOT NULL DEFAULT false;

-- dia_semana em ISO (1=segunda-feira .. 7=domingo) — bate exatamente com EXTRACT(ISODOW FROM
-- ...) do Postgres e com DayOfWeek.getValue() do Java, sem precisar de tabela de tradução.
-- hora_inicio/hora_fim NULOS = sem acesso naquele dia (ex. do dono do produto: domingo é folga,
-- fica sem horário nenhum). Quando controla_horario_acesso=true, o usuário sempre tem as 7
-- linhas (dias sem acesso entram com os dois campos nulos) — UsuarioService apaga tudo e
-- reinsere a cada criação/atualização, mesmo idioma de usuario_empresa.
CREATE TABLE usuario_horario_acesso (
  id_tenant   smallint NOT NULL,
  id_usuario  integer  NOT NULL,
  dia_semana  smallint NOT NULL CHECK (dia_semana BETWEEN 1 AND 7),
  hora_inicio time,
  hora_fim    time,
  CONSTRAINT usuario_horario_acesso_pk PRIMARY KEY (id_tenant, id_usuario, dia_semana),
  -- FK composta (P8) — mesmo motivo de usuario_empresa_usuario_fk (V015).
  CONSTRAINT usuario_horario_acesso_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario) ON DELETE CASCADE,
  -- início e fim sempre juntos (os dois nulos = dia sem acesso; nunca só um dos dois).
  CONSTRAINT usuario_horario_acesso_par_ck CHECK ((hora_inicio IS NULL) = (hora_fim IS NULL)),
  CONSTRAINT usuario_horario_acesso_ordem_ck CHECK (hora_fim IS NULL OR hora_fim > hora_inicio)
);

CREATE INDEX usuario_horario_acesso_id_tenant_ix ON usuario_horario_acesso (id_tenant);

-- RLS (P8), mesmo padrão de V031/V032 (tabela nasceu depois do bloco único de V024).
ALTER TABLE usuario_horario_acesso ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario_horario_acesso FORCE  ROW LEVEL SECURITY;
CREATE POLICY usuario_horario_acesso_rls ON usuario_horario_acesso
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON usuario_horario_acesso TO niner_app;

COMMENT ON COLUMN usuario.controla_horario_acesso IS
  'Liga o controle de horário de acesso deste usuário (usuario_horario_acesso). Nunca se aplica a administrador=true.';
COMMENT ON TABLE usuario_horario_acesso IS
  'Janela de login/uso permitida por dia da semana (1=segunda..7=domingo, ISO). Nulo = sem acesso naquele dia. RLS.';
