-- V075 — Catálogo de telas: abas nos 6 grupos de topo do menu (2026-08-27)
--
-- O dono do produto definiu as abas da tela de permissões: Frente de Loja, Estoque, Financeiro,
-- Cadastros, Configurações e Relatórios — "e dentro destas abas você coloca tudo que precisa de
-- permissão pra aquele menu". São exatamente os grupos de TOPO do menu do ERP.
--
-- ⚠️ As V073/V074 gravavam como grupo o rótulo do SUBGRUPO ("Cancelamentos", "Contagem de
-- Estoque", "Fiscal"), porque o extrator era linear e não enxergava aninhamento. Agora a
-- hierarquia sai da indentação do menu: `grupo` é o de topo (a aba) e `subgrupo` vira separador
-- dentro dela — sem ele, as 20 telas de Configurações virariam uma lista sem divisão.
--
-- ⛔ "Implementações Futuras" SAI do catálogo: são telas que ainda não existem, e ele foi direto
-- ao ponto — "quando o ERP for concluído este item não vai existir". Permissão para tela
-- inexistente é ruído permanente na grade de todo mundo.

ALTER TABLE cfg_tela ADD COLUMN IF NOT EXISTS subgrupo text;

INSERT INTO cfg_tela (chave, nome, grupo, subgrupo, ordem, admin_apenas) VALUES
  ('pdv', 'PDV - Vendas', 'Frente de Loja', NULL, 1, false),
  ('expedicao', 'Fila de Expedição', 'Frente de Loja', NULL, 2, false),
  ('orcamentos', 'Orçamentos', 'Frente de Loja', NULL, 3, false),
  ('pesquisa-vendas', 'Pesquisa de Vendas', 'Frente de Loja', NULL, 4, false),
  ('recebimento-crediario', 'Recebimento de Crediário', 'Frente de Loja', NULL, 5, false),
  ('reimpressao-recebimento-crediario', 'Reimpressão de Recebimento de Crediário', 'Frente de Loja', NULL, 6, false),
  ('devolucao-produto', 'Devolução de Produtos', 'Frente de Loja', NULL, 7, false),
  ('fechamento-caixa', 'Fechamento de Caixa', 'Frente de Loja', NULL, 8, false),
  ('estorno-recebimento-crediario', 'Estorno de Crediário', 'Frente de Loja', 'Cancelamentos', 9, false),
  ('cancelamento-devolucao-produtos', 'Cancelamento de Devolução de Produtos', 'Frente de Loja', 'Cancelamentos', 10, false),
  ('entrada-produtos-compra', 'Entrada de Produtos por Compra', 'Estoque', NULL, 11, false),
  ('estoque.devolucao-compra', 'Devolução de Produtos Comprados', 'Estoque', NULL, 12, false),
  ('estoque', 'Transferência de Produtos', 'Estoque', NULL, 13, false),
  ('estoque.contagem', 'Contagem de Estoque', 'Estoque', 'Contagem de Estoque', 14, false),
  ('estoque.diferencas', 'Diferenças de Estoque', 'Estoque', 'Contagem de Estoque', 15, false),
  ('estoque.efetivar-balanco', 'Efetivar Balanço', 'Estoque', 'Contagem de Estoque', 16, false),
  ('estoque.zerar-contagem', 'Zerar Contagem de Estoque', 'Estoque', 'Contagem de Estoque', 17, false),
  ('contas-corrente', 'Conta Corrente', 'Financeiro', NULL, 18, false),
  ('contas-corrente-movimento', 'Movimentação de Conta Corrente', 'Financeiro', NULL, 19, false),
  ('planos-contas', 'Plano de Contas', 'Financeiro', NULL, 20, false),
  ('tipos-carteira', 'Tipo de Carteira', 'Financeiro', NULL, 21, false),
  ('contas-pagar', 'Contas a Pagar / Pagas', 'Financeiro', NULL, 22, false),
  ('clientes', 'Clientes', 'Cadastros', NULL, 23, false),
  ('fornecedores', 'Fornecedores', 'Cadastros', NULL, 24, false),
  ('funcionarios', 'Funcionários', 'Cadastros', NULL, 25, false),
  ('produtos', 'Produtos', 'Cadastros', NULL, 26, false),
  ('canais', 'Canais de Venda', 'Configurações', NULL, 27, true),
  ('minha-conta', 'Minha Conta', 'Configurações', NULL, 28, true),
  ('usuarios', 'Usuários', 'Configurações', NULL, 29, true),
  ('empresas', 'Empresas', 'Configurações', NULL, 30, true),
  ('configuracoes-gerais', 'Parâmetros do Sistema', 'Configurações', NULL, 31, true),
  ('etiqueta-configuracao', 'Configuração de Etiqueta de Produtos', 'Configurações', NULL, 32, true),
  ('importacao-dados.clientes', 'Clientes', 'Configurações', 'Importação de Dados', 33, true),
  ('importacao-dados.contas-receber', 'Contas a Receber', 'Configurações', 'Importação de Dados', 34, true),
  ('importacao-dados.fornecedores', 'Fornecedores', 'Configurações', 'Importação de Dados', 35, true),
  ('importacao-dados.produtos', 'Produtos', 'Configurações', 'Importação de Dados', 36, true),
  ('importacao-dados.estoque', 'Estoque Inicial', 'Configurações', 'Importação de Dados', 37, true),
  ('exportacao-dados', 'Exportação de Dados', 'Configurações', 'Importação de Dados', 38, true),
  ('fiscal.configuracao', 'Configuração Fiscal', 'Configurações', 'Fiscal', 39, true),
  ('fiscal.perfis', 'Perfis Fiscais', 'Configurações', 'Fiscal', 40, true),
  ('fiscal.certificados', 'Certificado Digital', 'Configurações', 'Fiscal', 41, true),
  ('fiscal.conformidade', 'Conformidade Fiscal', 'Configurações', 'Fiscal', 42, true),
  ('fiscal.contingencia', 'Contingência Fiscal', 'Configurações', 'Fiscal', 43, true),
  ('fiscal.documentos', 'Documentos Fiscais', 'Configurações', 'Fiscal', 44, true),
  ('fiscal.exportacao-xml', 'Exportação de XML em Lote', 'Configurações', 'Fiscal', 45, true),
  ('fiscal.inutilizacao', 'Inutilização de Numeração', 'Configurações', 'Fiscal', 46, true),
  ('relatorio-vendas', 'Vendas', 'Relatórios', 'Faturamento', 47, false),
  ('relatorio-comissoes', 'Comissões', 'Relatórios', 'Faturamento', 48, false),
  ('relatorio-estoque', 'Posição de Estoque', 'Relatórios', 'Estoque', 49, false),
  ('relatorio-movimentacao-produtos', 'Movimentação de Produtos', 'Relatórios', 'Estoque', 50, false),
  ('etiqueta-emissao', 'Etiquetas de Produtos', 'Relatórios', 'Estoque', 51, false),
  ('relatorio-contas-receber', 'Contas a Receber / Recebidas', 'Relatórios', 'Financeiro', 52, false),
  ('relatorio-contas-pagar', 'Contas a Pagar / Pagas', 'Relatórios', 'Financeiro', 53, false),
  ('fluxo-caixa', 'Fluxo de Caixa', 'Relatórios', 'Financeiro', 54, false),
  ('relatorio-dre', 'DRE — Demonstração do Resultado', 'Relatórios', 'Resultados', 55, true),
  ('lucratividade', 'Lucratividade', 'Relatórios', 'Resultados', 56, true),
  ('crm', 'CRM', 'Relatórios', 'Resultados', 57, false)
ON CONFLICT (chave) DO UPDATE
  SET nome = EXCLUDED.nome, grupo = EXCLUDED.grupo, subgrupo = EXCLUDED.subgrupo,
      ordem = EXCLUDED.ordem, admin_apenas = EXCLUDED.admin_apenas;

-- Tela que saiu do catálogo leva junto as permissões dela (ON DELETE CASCADE da V073). São as
-- de "Implementações Futuras", que ninguém tinha como conceder de forma útil.
DELETE FROM cfg_tela WHERE chave NOT IN ('pdv', 'expedicao', 'orcamentos', 'pesquisa-vendas', 'recebimento-crediario', 'reimpressao-recebimento-crediario', 'devolucao-produto', 'fechamento-caixa', 'estorno-recebimento-crediario', 'cancelamento-devolucao-produtos', 'entrada-produtos-compra', 'estoque.devolucao-compra', 'estoque', 'estoque.contagem', 'estoque.diferencas', 'estoque.efetivar-balanco', 'estoque.zerar-contagem', 'contas-corrente', 'contas-corrente-movimento', 'planos-contas', 'tipos-carteira', 'contas-pagar', 'clientes', 'fornecedores', 'funcionarios', 'produtos', 'canais', 'minha-conta', 'usuarios', 'empresas', 'configuracoes-gerais', 'etiqueta-configuracao', 'importacao-dados.clientes', 'importacao-dados.contas-receber', 'importacao-dados.fornecedores', 'importacao-dados.produtos', 'importacao-dados.estoque', 'exportacao-dados', 'fiscal.configuracao', 'fiscal.perfis', 'fiscal.certificados', 'fiscal.conformidade', 'fiscal.contingencia', 'fiscal.documentos', 'fiscal.exportacao-xml', 'fiscal.inutilizacao', 'relatorio-vendas', 'relatorio-comissoes', 'relatorio-estoque', 'relatorio-movimentacao-produtos', 'etiqueta-emissao', 'relatorio-contas-receber', 'relatorio-contas-pagar', 'fluxo-caixa', 'relatorio-dre', 'lucratividade', 'crm');
