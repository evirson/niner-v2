-- V078 — A lista de telas exclusivas do administrador, definida por ele tela a tela (2026-08-27)
--
-- Até aqui a marca `admin_apenas` era herdada do menu, em bloco: o grupo Configurações INTEIRO
-- era ADMIN-only, mais DRE e Lucratividade — decisão anterior ao RBAC, quando só existiam dois
-- papéis. Revisando item a item, o dono do produto reduziu a lista a NOVE telas.
--
-- O que ficou exclusivo tem um traço em comum: mexe na CONTA (canais, minha conta, empresas) ou
-- é carga de implantação, feita uma vez (as importações e a exportação de dados). O resto —
-- inclusive o bloco fiscal inteiro, Usuários, Parâmetros do Sistema, DRE e Lucratividade — passa
-- a ser concedível: são rotinas que o operador pode precisar usar no dia a dia.
--
-- ⚠️ E o COMPORTAMENTO da marca mudou junto: tela exclusiva do administrador não aparece mais na
-- grade de permissões. Antes ela era listada e o salvamento a recusava — foi o que ele viu ao
-- clicar em "liberar tudo": um erro citando 22 telas que ele nem sabia que estavam lá. Oferecer o
-- que não pode ser concedido é convidar a um erro que o próprio sistema depois recusa.

UPDATE cfg_tela SET admin_apenas = chave IN (
  'canais',                       -- Canais de Venda
  'minha-conta',                  -- Minha Conta
  'empresas',                     -- Empresas
  'importacao-dados.clientes',
  'importacao-dados.contas-receber',
  'importacao-dados.fornecedores',
  'importacao-dados.produtos',
  'importacao-dados.estoque',
  'exportacao-dados'
);

-- Permissão concedida antes desta revisão para uma tela que agora é exclusiva deixa de valer —
-- senão o operador continuaria entrando numa tela que a grade não mostra mais.
DELETE FROM usuario_permissao p
 USING cfg_tela t
 WHERE t.chave = p.chave_tela AND t.admin_apenas;
