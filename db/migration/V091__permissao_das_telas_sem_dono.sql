-- V091 — cinco chaves da grade passaram a governar de verdade (auditoria de 2026-08-29).
--
-- ⛔ O QUE ESTAVA ERRADO
--
-- `cfg_tela` oferecia cinco chaves que NENHUM `@Tela` do código usava. A grade e o menu obedeciam
-- a elas; o servidor não. As duas direções doíam:
--
--   • FALSA PROTEÇÃO — o admin concedia "Recebimento de Crediário" (acessar+incluir+excluir, o
--     mínimo para o operador receber e desfazer o próprio erro do dia) e deixava "Estorno de
--     Crediário" DESMARCADO. O item sumia do menu… e `POST /recebimento-crediario/estornos/{id}`
--     respondia **200**, porque o interceptor só pedia `recebimento-crediario` EXCLUIR. Estorno é
--     dinheiro saindo do caixa, e o admin acreditava ter fechado a porta.
--   • PERMISSÃO IMPOSSÍVEL — o admin marcava só "Efetivar Balanço" (um conferente que só fecha o
--     inventário) e o botão respondia 403 citando "Contagem de Estoque", uma tela que ele não
--     liberou e cujo nome não aparece na decisão que ele tomou.
--
-- O código foi corrigido com `@Tela` de MÉTODO (o `PermissaoInterceptor` a prefere à da classe).
-- Esta migration alinha a grade ao que o código passou a exigir — e migra as concessões, para que
-- a correção não tire de ninguém um acesso que o administrador havia dado.

-- ---------------------------------------------------------------------------------------------
-- 1. As ações que cada chave passa a governar
--
-- `estoque.zerar-contagem`  → só EXCLUIR (DELETE /contagem e POST /desfazer; nenhum é incluir)
-- `estorno-recebimento-crediario` → só EXCLUIR ("desfazer é excluir", como os outros 8 desfazeres)
-- `recebimento-crediario`   → perde EXCLUIR, que migrou inteiro para a chave do estorno

UPDATE cfg_tela SET tem_incluir = false WHERE chave = 'estoque.zerar-contagem';
UPDATE cfg_tela SET tem_incluir = false, tem_excluir = true WHERE chave = 'estorno-recebimento-crediario';
UPDATE cfg_tela SET tem_excluir = false WHERE chave = 'recebimento-crediario';

-- ---------------------------------------------------------------------------------------------
-- 2. ⭐ O BACKFILL DAS CONCESSÕES — a parte que evita tirar acesso pelas costas
--
-- Quem tinha `recebimento-crediario.excluir` **podia estornar** até ontem, porque era essa a
-- permissão que o servidor pedia. Agora o estorno pede a chave própria; sem este passo, essas
-- pessoas perderiam o acesso em silêncio, num deploy que ninguém associaria à causa.
--
-- ⚠️ `NO FORCE ROW LEVEL SECURITY` nas DUAS tabelas — a que lê e a que escreve. A V089 aprendeu
-- isso do jeito difícil: liberou só a tabela escrita, a subconsulta leu de outra ainda bloqueada,
-- o UPDATE casou zero linhas e o Flyway anunciou sucesso (347 vendas, 0 preenchidas).
ALTER TABLE usuario_permissao NO FORCE ROW LEVEL SECURITY;

INSERT INTO usuario_permissao (id_tenant, id_usuario, chave_tela, acessar, incluir, alterar, excluir)
SELECT up.id_tenant, up.id_usuario, 'estorno-recebimento-crediario', true, false, false, true
  FROM usuario_permissao up
 WHERE up.chave_tela = 'recebimento-crediario' AND up.excluir = true
ON CONFLICT (id_tenant, id_usuario, chave_tela) DO UPDATE
   SET acessar = true, excluir = true;

-- A ação que deixou de existir na tela-mãe é limpa DEPOIS de migrada, nunca antes.
UPDATE usuario_permissao SET excluir = false WHERE chave_tela = 'recebimento-crediario';
-- `estoque.zerar-contagem` nunca teve INCLUIR governando nada: a limpeza é só higiene do dado.
UPDATE usuario_permissao SET incluir = false WHERE chave_tela = 'estoque.zerar-contagem';
-- Quem tinha INCLUIR no estorno queria poder estornar — a caixa era a única que a tela oferecia.
UPDATE usuario_permissao SET excluir = true, incluir = false
 WHERE chave_tela = 'estorno-recebimento-crediario' AND incluir = true;

ALTER TABLE usuario_permissao FORCE ROW LEVEL SECURITY;

COMMENT ON TABLE usuario_permissao IS
  'Grade de permissão por usuário e tela (V073). ⚠️ A chave é `cfg_tela.chave` e precisa ter um '
  '`@Tela` correspondente no código — chave sem dono é caixa que não governa nada, e o teste '
  '`AcoesPorTelaConferemTest.nenhumaChaveDaGradeFicaSemDonoNoCodigo` reprova o build se aparecer '
  'uma. Ao mover uma ação de uma tela para outra, MIGRE as concessões antes de limpar a antiga.';
