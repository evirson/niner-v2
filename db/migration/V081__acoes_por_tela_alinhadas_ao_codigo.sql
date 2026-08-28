-- V081 — Ações por tela alinhadas ao que o código realmente exige (2026-08-27)
--
-- ⭐ O DEFEITO QUE MOTIVOU ISTO: `cfg_tela.pdv` estava com `tem_incluir = false`, e
-- `POST /api/v1/pdv/vendas` traduz para INCLUIR. Resultado: **nenhum operador conseguia vender**.
-- E não havia como consertar pela tela — `PermissaoController.salvar` grava
-- `p.incluir() AND disponiveis.incluir()`, então a concessão era descartada mesmo vinda pela API.
-- O administrador liberava tudo o que a grade oferecia, o operador abria o PDV, montava a venda,
-- apertava F5 e recebia "Você não tem permissão para incluir em PDV - Vendas".
--
-- A V076 mediu as ações pelos métodos HTTP que o **front** chama; esta mede pelo que os
-- **controllers exigem**, que é quem decide de verdade. Seis telas divergiam:
--
--   pdv                       incluir  -> NINGUÉM VENDIA
--   etiqueta-emissao          incluir  -> ninguém emitia etiqueta
--   entrada-produtos-compra   alterar  -> ninguém editava item de entrada (PUT /itens/{id})
--   empresas                  incluir  -> POST existe (admin-only, tela exclusiva)
--   estoque (transferência)   alterar  -> caixa a mais: não há PUT nessa tela
--   minha-conta               incluir  -> caixa a mais: não há POST nessa tela
--
-- ⚠️ As duas últimas são o erro na direção oposta, e não são inofensivas: oferecem uma caixa que
-- não governa nada, e quem configura permissão passa a desconfiar do que a grade diz.
--
-- ⛔ O CONSERTO DE VERDADE NÃO É ESTA MIGRATION — é `AcoesPorTelaConferemTest`, que varre os
-- controllers, deriva as ações e compara com `cfg_tela`. Sem ele, a próxima tela nova volta a
-- divergir e ninguém descobre até um operador não conseguir trabalhar.

UPDATE cfg_tela SET tem_incluir = true  WHERE chave IN ('pdv', 'etiqueta-emissao', 'empresas');
UPDATE cfg_tela SET tem_alterar = true  WHERE chave = 'entrada-produtos-compra';
UPDATE cfg_tela SET tem_alterar = false WHERE chave = 'estoque';
UPDATE cfg_tela SET tem_incluir = false WHERE chave = 'minha-conta';

-- Sem backfill de permissão, pelo mesmo motivo da V077: o Nainer está em homologação, sem cliente
-- real, e usuário novo não enxerga nada até ser liberado — que é o comportamento definitivo.
