-- V103 — as duas telas da NFS-e no catálogo de permissões (blocos S5.5 e S6)
--
-- ⚠️ As ações declaradas aqui NÃO são escolha: o `AcoesPorTelaConferemTest` varre
-- `api/src/main`, lê as anotações @Tela/@Acao e deriva a ação pela MESMA regra do interceptor —
-- se divergir, o build cai. Foi ele que impediu o PDV de nascer sem "incluir", o que teria
-- deixado nenhum operador conseguir vender.
--
-- As duas direções do erro doem, e a V076 pagou pelas duas:
--   · ação EXIGIDA e não oferecida → permissão impossível de conceder (403 numa tela que o admin
--     jura ter liberado);
--   · ação OFERECIDA e não exigida → caixa que não governa nada, e quem configura passa a
--     desconfiar da grade inteira.

INSERT INTO cfg_tela (chave, nome, grupo, subgrupo, ordem,
                      admin_apenas, tem_incluir, tem_alterar, tem_excluir) VALUES

  -- Configuração: GET + PUT (salvar, testar conexão, verificar). Só ALTERAR — a linha nasce do
  -- primeiro PUT (upsert) e nunca é removida, então não há incluir nem excluir.
  -- ⚠️ É por isso que "testar conexão" e "verificar" são PUT e não POST: eles gravam resultado,
  -- que é alterar; declará-los POST exigiria "incluir" numa tela que não inclui nada.
  ('fiscal.nfse-configuracao', 'Configuração da NFS-e', 'Configurações', 'Fiscal', 47,
   false, false, true, false),

  -- Emissão: POST (emitir) = incluir, DELETE (cancelar) = excluir.
  -- ⚠️ O DELETE não apaga nada — a nota muda de situação e o evento fica registrado para sempre
  -- (F6, e a V102 não dá GRANT de DELETE nessas tabelas). O verbo é o que o RBAC lê, e cancelar
  -- documento fiscal é a ação destrutiva da tela.
  ('fiscal.nfse', 'NFS-e', 'Configurações', 'Fiscal', 48,
   false, true, false, true);

COMMENT ON TABLE cfg_tela IS
  'Catálogo de telas do ERP para o RBAC por tela e ação. ⚠️ As colunas tem_* têm de casar com o '
  'que os controllers exigem — o AcoesPorTelaConferemTest deriva pela mesma regra do interceptor '
  'e reprova o build se divergirem.';
