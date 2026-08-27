-- V073 — Permissão por tela e por ação (RBAC do ERP, 2026-08-27)
--
-- O QUE MUDA. Até aqui o ERP tinha dois papéis fixos no código: ADMIN (um por conta, imutável) e
-- OPERADOR — e OPERADOR entrava em tudo que não estivesse marcado como ADMIN-only (8 telas de 63).
-- Agora cada usuário ganha uma grade própria: por tela, o que ele pode ACESSAR, INCLUIR, ALTERAR
-- e EXCLUIR.
--
-- ⛔ SEM PERFIS, E A RAZÃO É DO DONO DO PRODUTO: "montar perfis de usuários eu não acho uma boa
-- ideia, pq às vezes para um tenant o estoquista pode fazer uma coisa, e no outro tenant vai fazer
-- coisas diferentes". Perfil compartilhado entre tenants seria camisa de força; perfil por tenant
-- seria uma indireção a mais para o mesmo resultado. A permissão mora no USUÁRIO.
--
-- ⭐ NÃO É UMA TABELA NOVA POR ACASO: `usuario_rotina` (V015) já era exatamente isto — permissão
-- presa ao usuário, sem perfil no meio ("mantém permissões finas legadas"). Ela nasceu vazia, sem
-- UI e sem uso, esperando o produto definir a granularidade. A granularidade agora existe, então
-- ela é substituída por `usuario_permissao`, com as quatro ações.

CREATE TABLE cfg_tela (
  chave        text     PRIMARY KEY,          -- derivada da rota: '/relatorio-vendas' → 'relatorio-vendas'
  nome         text     NOT NULL,             -- o rótulo que o lojista vê no menu
  grupo        text     NOT NULL,             -- para agrupar a grade de permissões na tela
  ordem        smallint NOT NULL,
  -- Telas que hoje o menu marca como `adminOnly` (Configurações inteira, DRE, Lucratividade).
  -- Continuam fora do alcance de quem não é admin, mesmo que alguém marque a permissão: são as
  -- telas que mexem na conta e no resultado do negócio.
  admin_apenas boolean  NOT NULL DEFAULT false
);

COMMENT ON TABLE cfg_tela IS
  'Catálogo de telas do ERP (63 em 2026-08-27), gerado a partir de web/src/lib/menu.ts. Global, sem RLS.';

-- ⚠️ GLOBAL, sem id_tenant/RLS — mesma exceção de cfg_produto_ncm e cfg_ramo_atividade: a lista de
-- telas é do PRODUTO, igual para todos. Quem é por tenant é a permissão, não o catálogo.
GRANT SELECT ON cfg_tela TO niner_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_tela TO niner_owner;

-- A grade. Ausência de linha = sem acesso nenhum: usuário novo nasce sem ver nada, e o admin
-- libera tela a tela (decisão do dono do produto). Isso também torna a migração segura — nenhuma
-- linha existe hoje, então ninguém ganha acesso por acidente.
CREATE TABLE usuario_permissao (
  id_tenant  smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_usuario integer  NOT NULL,
  chave_tela text     NOT NULL REFERENCES cfg_tela (chave) ON DELETE CASCADE,
  acessar    boolean  NOT NULL DEFAULT false,
  incluir    boolean  NOT NULL DEFAULT false,
  alterar    boolean  NOT NULL DEFAULT false,
  excluir    boolean  NOT NULL DEFAULT false,
  CONSTRAINT usuario_permissao_pk PRIMARY KEY (id_tenant, id_usuario, chave_tela),
  -- FK composta (P8): garante que o usuário é do MESMO tenant da permissão.
  CONSTRAINT usuario_permissao_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario) ON DELETE CASCADE,
  -- ⚠️ Incluir/alterar/excluir sem acessar é combinação sem sentido — e perigosa, porque a tela
  -- mostraria "pode excluir" para quem nem abre a tela. O banco recusa em vez de deixar a
  -- coerência por conta de quem escrever a próxima rotina.
  CONSTRAINT usuario_permissao_acessar_ck CHECK (
    acessar OR NOT (incluir OR alterar OR excluir))
);

CREATE INDEX usuario_permissao_id_tenant_ix ON usuario_permissao (id_tenant);

ALTER TABLE usuario_permissao ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario_permissao FORCE ROW LEVEL SECURITY;
CREATE POLICY usuario_permissao_rls ON usuario_permissao
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

GRANT SELECT, INSERT, UPDATE, DELETE ON usuario_permissao TO niner_app;

INSERT INTO cfg_tela (chave, nome, grupo, ordem, admin_apenas) VALUES
  ('pdv', 'PDV - Vendas', 'Frente de Loja', 1, false),
  ('expedicao', 'Fila de Expedição', 'Frente de Loja', 2, false),
  ('orcamentos', 'Orçamentos', 'Frente de Loja', 3, false),
  ('pesquisa-vendas', 'Pesquisa de Vendas', 'Frente de Loja', 4, false),
  ('recebimento-crediario', 'Recebimento de Crediário', 'Frente de Loja', 5, false),
  ('reimpressao-recebimento-crediario', 'Reimpressão de Recebimento de Crediário', 'Frente de Loja', 6, false),
  ('devolucao-produto', 'Devolução de Produtos', 'Frente de Loja', 7, false),
  ('fechamento-caixa', 'Fechamento de Caixa', 'Frente de Loja', 8, false),
  ('estorno-recebimento-crediario', 'Estorno de Crediário', 'Cancelamentos', 9, false),
  ('cancelamento-devolucao-produtos', 'Cancelamento de Devolução de Produtos', 'Cancelamentos', 10, false),
  ('entrada-produtos-compra', 'Entrada de Produtos por Compra', 'Estoque', 11, false),
  ('estoque.devolucao-compra', 'Devolução de Produtos Comprados', 'Estoque', 12, false),
  ('estoque', 'Transferência de Produtos', 'Estoque', 13, false),
  ('estoque.contagem', 'Contagem de Estoque', 'Contagem de Estoque', 14, false),
  ('estoque.diferencas', 'Diferenças de Estoque', 'Contagem de Estoque', 15, false),
  ('estoque.efetivar-balanco', 'Efetivar Balanço', 'Contagem de Estoque', 16, false),
  ('estoque.zerar-contagem', 'Zerar Contagem de Estoque', 'Contagem de Estoque', 17, false),
  ('contas-corrente', 'Conta Corrente', 'Financeiro', 18, false),
  ('contas-corrente-movimento', 'Movimentação de Conta Corrente', 'Financeiro', 19, false),
  ('planos-contas', 'Plano de Contas', 'Financeiro', 20, false),
  ('tipos-carteira', 'Tipo de Carteira', 'Financeiro', 21, false),
  ('contas-pagar', 'Contas a Pagar / Pagas', 'Financeiro', 22, false),
  ('clientes', 'Clientes', 'Cadastros', 23, false),
  ('fornecedores', 'Fornecedores', 'Cadastros', 24, false),
  ('funcionarios', 'Funcionários', 'Cadastros', 25, false),
  ('produtos', 'Produtos', 'Cadastros', 26, false),
  ('canais', 'Canais de Venda', 'Configurações', 27, true),
  ('minha-conta', 'Minha Conta', 'Configurações', 28, true),
  ('usuarios', 'Usuários', 'Configurações', 29, true),
  ('empresas', 'Empresas', 'Configurações', 30, true),
  ('configuracoes-gerais', 'Parâmetros do Sistema', 'Configurações', 31, true),
  ('etiqueta-configuracao', 'Configuração de Etiqueta de Produtos', 'Configurações', 32, true),
  ('importacao-dados.clientes', 'Clientes', 'Importação de Dados', 33, false),
  ('importacao-dados.contas-receber', 'Contas a Receber', 'Importação de Dados', 34, false),
  ('importacao-dados.fornecedores', 'Fornecedores', 'Importação de Dados', 35, false),
  ('importacao-dados.produtos', 'Produtos', 'Importação de Dados', 36, false),
  ('importacao-dados.estoque', 'Estoque Inicial', 'Importação de Dados', 37, false),
  ('exportacao-dados', 'Exportação de Dados', 'Importação de Dados', 38, false),
  ('fiscal.configuracao', 'Configuração Fiscal', 'Fiscal', 39, false),
  ('fiscal.perfis', 'Perfis Fiscais', 'Fiscal', 40, false),
  ('fiscal.certificados', 'Certificado Digital', 'Fiscal', 41, false),
  ('fiscal.conformidade', 'Conformidade Fiscal', 'Fiscal', 42, false),
  ('fiscal.contingencia', 'Contingência Fiscal', 'Fiscal', 43, false),
  ('fiscal.documentos', 'Documentos Fiscais', 'Fiscal', 44, false),
  ('fiscal.exportacao-xml', 'Exportação de XML em Lote', 'Fiscal', 45, false),
  ('fiscal.inutilizacao', 'Inutilização de Numeração', 'Fiscal', 46, false),
  ('relatorio-vendas', 'Vendas', 'Faturamento', 47, false),
  ('relatorio-comissoes', 'Comissões', 'Faturamento', 48, false),
  ('relatorio-estoque', 'Posição de Estoque', 'Estoque', 49, false),
  ('relatorio-movimentacao-produtos', 'Movimentação de Produtos', 'Estoque', 50, false),
  ('etiqueta-emissao', 'Etiquetas de Produtos', 'Estoque', 51, false),
  ('relatorio-contas-receber', 'Contas a Receber / Recebidas', 'Financeiro', 52, false),
  ('relatorio-contas-pagar', 'Contas a Pagar / Pagas', 'Financeiro', 53, false),
  ('fluxo-caixa', 'Fluxo de Caixa', 'Financeiro', 54, false),
  ('relatorio-dre', 'DRE — Demonstração do Resultado', 'Resultados', 55, true),
  ('lucratividade', 'Lucratividade', 'Resultados', 56, true),
  ('crm', 'CRM', 'Resultados', 57, false),
  ('painel', 'Painel', 'Implementações Futuras', 58, false),
  ('pedidos', 'Pedidos', 'Implementações Futuras', 59, false),
  ('bi-dashboard', 'BI Dashboard', 'Implementações Futuras', 60, false),
  ('relatorio-movimentacao-bancaria', 'Relatório de Movimentação Bancária', 'Implementações Futuras', 61, false),
  ('integracao-marketplace', 'Integração com Marketplace', 'Implementações Futuras', 62, false),
  ('cobranca-crediario-atraso', 'Cobrança de Crediário em Atraso', 'Implementações Futuras', 63, false)
ON CONFLICT (chave) DO UPDATE
  SET nome = EXCLUDED.nome, grupo = EXCLUDED.grupo, ordem = EXCLUDED.ordem,
      admin_apenas = EXCLUDED.admin_apenas;

-- `usuario_rotina` (V015) fica obsoleta: mesmo propósito, sem as ações. Nunca teve UI nem gravação
-- — está vazia em todos os bancos. Não é apagada aqui de propósito: excluir tabela é irreversível
-- e a decisão é do dono do produto, não da migration que a substitui.
COMMENT ON TABLE usuario_rotina IS
  'OBSOLETA desde a V073 (2026-08-27) — substituída por usuario_permissao, que tem as 4 ações. Nunca foi usada; mantida até o dono do produto autorizar a remoção.';
