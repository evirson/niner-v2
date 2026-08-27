-- V076 — Quais AÇÕES cada tela realmente tem (2026-08-27)
--
-- Pedido do dono do produto: "tem que verificar quais telas têm mesmo incluir/alterar/excluir,
-- por exemplo, PDV-Vendas, tendo acesso já basta, pq esta tela não tem as outras opções". Marcar
-- as quatro caixas em toda tela faz o administrador conceder permissões que não existem — e
-- pior, sugere um controle que o sistema não tem.
--
-- ⭐ COMO ISTO FOI DECIDIDO: não por opinião. Um script varreu, para cada tela, as FUNÇÕES que
-- ela importa e o método HTTP dentro do corpo de cada uma, agregando as rotas filhas
-- (/clientes/novo, /clientes/:id) à tela do menu. POST → incluir, PUT/PATCH → alterar,
-- DELETE → excluir.
--
-- ⚠️ Três falsos resultados foram medidos e corrigidos no caminho, e valem como aviso para quem
-- refizer isto: (a) olhar a lib inteira em vez das funções importadas marcava "incluir" em quem
-- só listava — 55 das 57 telas apareciam com tudo; (b) sem agregar as rotas filhas, todo cadastro
-- virava somente leitura, porque a lista só lista; (c) `apiUpload<Tipo>(` não casa com uma regex
-- que exige "(" logo após o nome — o genérico entra no meio, e as telas de upload apareciam
-- sem "incluir".
--
-- Resultado: 19 telas somente-acesso (todos os relatórios, as consultas e o PDV).

ALTER TABLE cfg_tela ADD COLUMN IF NOT EXISTS tem_incluir boolean NOT NULL DEFAULT true;
ALTER TABLE cfg_tela ADD COLUMN IF NOT EXISTS tem_alterar boolean NOT NULL DEFAULT true;
ALTER TABLE cfg_tela ADD COLUMN IF NOT EXISTS tem_excluir boolean NOT NULL DEFAULT true;

UPDATE cfg_tela t SET tem_incluir = v.inc, tem_alterar = v.alt, tem_excluir = v.exc
  FROM (VALUES
  ('pdv', false, false, false),
  ('expedicao', true, false, false),
  ('orcamentos', true, false, false),
  ('pesquisa-vendas', false, false, false),
  ('recebimento-crediario', true, false, false),
  ('reimpressao-recebimento-crediario', false, false, false),
  ('devolucao-produto', true, false, false),
  ('fechamento-caixa', true, false, false),
  ('estorno-recebimento-crediario', true, false, false),
  ('cancelamento-devolucao-produtos', false, false, false),
  ('entrada-produtos-compra', true, false, false),
  ('estoque.devolucao-compra', true, false, false),
  ('estoque', true, true, true),
  ('estoque.contagem', true, true, true),
  ('estoque.diferencas', false, false, false),
  ('estoque.efetivar-balanco', true, false, false),
  ('estoque.zerar-contagem', true, false, true),
  ('contas-corrente', true, true, true),
  ('contas-corrente-movimento', true, true, true),
  ('planos-contas', true, true, true),
  ('tipos-carteira', true, true, true),
  ('contas-pagar', true, true, true),
  ('clientes', true, true, true),
  ('fornecedores', true, true, true),
  ('funcionarios', true, true, true),
  ('produtos', true, true, true),
  ('canais', true, true, true),
  ('minha-conta', true, false, false),
  ('usuarios', true, true, true),
  ('empresas', false, true, false),
  ('configuracoes-gerais', false, true, false),
  ('etiqueta-configuracao', true, true, true),
  ('importacao-dados.clientes', true, false, false),
  ('importacao-dados.contas-receber', true, false, false),
  ('importacao-dados.fornecedores', true, false, false),
  ('importacao-dados.produtos', true, false, false),
  ('importacao-dados.estoque', true, false, false),
  ('exportacao-dados', false, false, false),
  ('fiscal.configuracao', false, true, false),
  ('fiscal.perfis', true, true, true),
  ('fiscal.certificados', true, false, false),
  ('fiscal.conformidade', false, false, false),
  ('fiscal.contingencia', true, false, false),
  ('fiscal.documentos', true, false, false),
  ('fiscal.exportacao-xml', false, false, false),
  ('fiscal.inutilizacao', true, false, false),
  ('relatorio-vendas', false, false, false),
  ('relatorio-comissoes', false, false, false),
  ('relatorio-estoque', false, false, false),
  ('relatorio-movimentacao-produtos', false, false, false),
  ('etiqueta-emissao', false, false, false),
  ('relatorio-contas-receber', false, false, false),
  ('relatorio-contas-pagar', false, false, false),
  ('fluxo-caixa', false, false, false),
  ('relatorio-dre', false, false, false),
  ('lucratividade', false, false, false),
  ('crm', false, false, false)
  ) AS v(chave, inc, alt, exc)
 WHERE t.chave = v.chave;

-- Permissão já concedida para uma ação que a tela não tem vira ruído na grade: o administrador
-- veria a caixa marcada e não entenderia por que ela sumiu depois.
UPDATE usuario_permissao p SET
  incluir = p.incluir AND t.tem_incluir,
  alterar = p.alterar AND t.tem_alterar,
  excluir = p.excluir AND t.tem_excluir
  FROM cfg_tela t WHERE t.chave = p.chave_tela;
