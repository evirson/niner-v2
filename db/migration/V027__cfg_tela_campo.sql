-- V027 — configuração de tela por tenant: quais campos aparecem e quais são obrigatórios
-- em cada tela do produto. Tabela nova (não mexe nas migrations já aplicadas) — reutilizável
-- para qualquer tela futura via `chave_tela` (mesma convenção do catálogo de ajuda, R22/§3.7.1;
-- ver AjudaDaTela no front). Primeiro uso: `cadastros.cliente.form` (2026-07-21).
--
-- Nasce depois do guarda-corpo de V024, então tem RLS próprio no arquivo (mesmo padrão de
-- V025/V026).

CREATE TABLE cfg_tela_campo (
  id_tenant     smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  chave_tela    text        NOT NULL,   -- ex.: 'cadastros.cliente.form'
  campo         text        NOT NULL,   -- ex.: 'email', 'cep', 'limiteCredito' (nome do campo no formulário)
  visivel       boolean     NOT NULL DEFAULT true,
  obrigatorio   boolean     NOT NULL DEFAULT false,
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT cfg_tela_campo_pk PRIMARY KEY (id_tenant, chave_tela, campo),
  -- um campo não pode ser obrigatório se estiver oculto.
  CONSTRAINT cfg_tela_campo_visivel_ck CHECK (NOT obrigatorio OR visivel)
);
CREATE INDEX cfg_tela_campo_tenant_tela_ix ON cfg_tela_campo (id_tenant, chave_tela);

COMMENT ON TABLE cfg_tela_campo IS
  'Configuração por tenant de quais campos aparecem/são obrigatórios em cada tela (chave_tela). Campo sem linha aqui usa o default da tela (visível, não obrigatório). Só ADMIN configura (ver ConfiguracaoTelaController).';

ALTER TABLE cfg_tela_campo ENABLE ROW LEVEL SECURITY;
ALTER TABLE cfg_tela_campo FORCE ROW LEVEL SECURITY;
CREATE POLICY cfg_tela_campo_rls ON cfg_tela_campo
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_tela_campo TO niner_app;
