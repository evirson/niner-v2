-- V094 — Sangria de Caixa: tirar dinheiro da gaveta e mandar para uma conta bancária.
--
-- ⛔ O QUE FALTAVA
--
-- Não existia NENHUMA forma de o operador tirar dinheiro do caixa. O valor `DEBITO_CAIXA` está no
-- enum `tipo_operacao_caixa` desde a V025, mas o único que o emite é a **baixa de Contas a Pagar**
-- (pagar fornecedor pelo caixa) — que é outra coisa: paga uma dívida, não move o dinheiro para o
-- banco. E `CREDITO_CAIXA` ("Suprimento de caixa") só aparece num rótulo do Fluxo de Caixa;
-- ninguém o emite.
--
-- Consequência prática: a loja vende R$ 5.000 em dinheiro, deposita no banco de tarde, e o sistema
-- continua afirmando que os R$ 5.000 estão na gaveta.
--
-- DECISÃO DO DONO DO PRODUTO (2026-08-29):
--   "hoje não é possível fazer a sangria pela rotina débito de caixa?? se não existir tem que criar
--    a rotina de sangria, mas lembrando esta sangria tem que ter um DESTINO: sempre será depositada
--    numa conta bancária, ou vai pro caixa central que tb está definido como uma conta bancária."
--
-- ⭐ É por isso que a sangria é uma TRANSFERÊNCIA, não uma saída. Ela sempre escreve nos dois
-- lados na mesma transação: débito no caixa e crédito na conta corrente. Dinheiro que "sai" sem
-- destino desaparece do fluxo — e o caixa central do lojista é, para o sistema, uma conta corrente
-- como qualquer outra.

-- ---------------------------------------------------------------------------------------------
-- 1. O mestre da sangria — quem, quando, quanto, de onde e para onde (P3)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE caixa_sangria (
  id_sangria        integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant         smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_caixa          integer       NOT NULL,
  id_conta_corrente text          NOT NULL,
  id_usuario        integer       NOT NULL,
  valor             numeric(12,2) NOT NULL,
  observacao        text,
  data_sangria      timestamptz   NOT NULL DEFAULT now(),
  criado_em         timestamptz   NOT NULL DEFAULT now(),

  CONSTRAINT caixa_sangria_id_uk    UNIQUE (id_tenant, id_sangria),
  CONSTRAINT caixa_sangria_valor_ck CHECK (valor > 0),

  -- FKs COMPOSTAS com id_tenant (P8): FK simples não valida que a linha referenciada é do mesmo
  -- tenant.
  CONSTRAINT caixa_sangria_caixa_fk FOREIGN KEY (id_tenant, id_caixa)
    REFERENCES caixa_mestre (id_tenant, id_caixa),
  CONSTRAINT caixa_sangria_conta_fk FOREIGN KEY (id_tenant, id_conta_corrente)
    REFERENCES conta_corrente (id_tenant, id_conta_corrente),
  CONSTRAINT caixa_sangria_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario)
);

CREATE INDEX caixa_sangria_id_tenant_ix ON caixa_sangria (id_tenant);
CREATE INDEX caixa_sangria_caixa_ix     ON caixa_sangria (id_tenant, id_caixa);
CREATE INDEX caixa_sangria_conta_ix     ON caixa_sangria (id_tenant, id_conta_corrente);

COMMENT ON TABLE caixa_sangria IS
  'Sangria de caixa (V094): dinheiro que sai da gaveta e entra numa conta corrente. Toda sangria '
  'escreve TRÊS linhas na mesma transação — esta, o débito em caixa_detalhe e o crédito em '
  'conta_corrente_movimento — e as duas outras apontam de volta por id_sangria.';

-- ---------------------------------------------------------------------------------------------
-- 2. Os dois lados apontam de volta
-- ---------------------------------------------------------------------------------------------
-- ⚠️ COM FK de verdade, ao contrário de `id_conta_pagar` nas mesmas duas tabelas — aquele vínculo
-- nasceu sem FK "de propósito" e foi assim que um `excluir()` passou meses deixando movimento
-- órfão sem o banco reclamar. Aqui não há motivo para abrir mão dela: a sangria nunca é apagada
-- por uma rotina que não conheça os três lados.
ALTER TABLE caixa_detalhe ADD COLUMN id_sangria integer;
ALTER TABLE caixa_detalhe ADD CONSTRAINT caixa_detalhe_sangria_fk
  FOREIGN KEY (id_tenant, id_sangria) REFERENCES caixa_sangria (id_tenant, id_sangria);
CREATE INDEX caixa_detalhe_sangria_ix ON caixa_detalhe (id_tenant, id_sangria);

ALTER TABLE conta_corrente_movimento ADD COLUMN id_sangria integer;
ALTER TABLE conta_corrente_movimento ADD CONSTRAINT conta_corrente_movimento_sangria_fk
  FOREIGN KEY (id_tenant, id_sangria) REFERENCES caixa_sangria (id_tenant, id_sangria);
CREATE INDEX conta_corrente_movimento_sangria_ix ON conta_corrente_movimento (id_tenant, id_sangria);

COMMENT ON COLUMN conta_corrente_movimento.id_sangria IS
  'Crédito gerado por uma sangria de caixa (V094). ⚠️ Marca de ORIGEM, e é ela que faz o CRUD '
  'manual de extrato RECUSAR alterar/excluir esta linha: editá-la pela tela do extrato descasaria '
  'o banco do caixa em silêncio — mesma regra já aplicada a id_conta_pagar em 2026-08-15.';

-- ---------------------------------------------------------------------------------------------
-- 3. RLS e grants (P8)
-- ---------------------------------------------------------------------------------------------
ALTER TABLE caixa_sangria ENABLE ROW LEVEL SECURITY;
ALTER TABLE caixa_sangria FORCE  ROW LEVEL SECURITY;

CREATE POLICY caixa_sangria_rls ON caixa_sangria
  USING      (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

-- ⛔ Sem DELETE: sangria é movimento de dinheiro já conferido pelo fechamento do caixa. Desfazer
-- exigiria o mesmo guard de "caixa fechado" das outras rotinas, e a decisão de permitir desfazer
-- ainda não foi tomada. Sem GRANT, um DELETE acidental falha no banco em vez de sumir com o rastro.
GRANT SELECT, INSERT ON caixa_sangria TO niner_app;
GRANT USAGE, SELECT ON SEQUENCE caixa_sangria_id_sangria_seq TO niner_app;

-- ---------------------------------------------------------------------------------------------
-- 4. O catálogo de telas (RBAC)
-- ---------------------------------------------------------------------------------------------
-- ⚠️ Travado por `AcoesPorTelaConferemTest`: tela sem linha aqui = permissão impossível de
-- conceder; ação divergente = caixa que não governa nada.
--
-- Só INCLUIR: a tela registra sangria e lista as do caixa aberto. Não altera nem exclui — ver o
-- comentário do GRANT acima.
INSERT INTO cfg_tela (chave, nome, grupo, subgrupo, ordem, admin_apenas,
                      tem_incluir, tem_alterar, tem_excluir)
VALUES ('sangria-caixa', 'Sangria de Caixa', 'Financeiro', NULL, 4, false,
        true, false, false)
ON CONFLICT (chave) DO NOTHING;
