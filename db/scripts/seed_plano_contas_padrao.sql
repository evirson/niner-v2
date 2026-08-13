-- Script de carga do plano de contas padrão em cfg_plano_contas (V016) — NÃO é uma migration
-- Flyway. Diferente de cfg_produto_ncm (global, sem tenant), este plano é POR TENANT — é
-- dado de negócio de um tenant específico, não estrutura de schema, por isso fica fora das
-- migrations versionadas (banco em construção, 2026-07-31). Rodar como niner_owner:
--
--   docker exec -i niner-db psql -U niner_owner -d niner_db < db/scripts/seed_plano_contas_padrao.sql
--
-- >>> TROQUE O LITERAL 1 ABAIXO PELO id_tenant DESEJADO ANTES DE RODAR PARA OUTRO TENANT <<<
--
-- Revisão 2026-08-13 (pedido do dono do produto): máscara encurtada de 9.99.999.999 (4 níveis,
-- ~200 contas) para 9.99.999 (3 níveis — grupo.família.conta) e plano padrão enxuto de ~70
-- contas, adequado a pequenos negócios. A estrutura de grupos não mudou: 1 Receitas,
-- 2 Deduções, 3 Custos Variáveis, 4 Custos/Despesas Fixas, 5 Financeiras/Empréstimos,
-- 6 Investimentos/CAPEX, 7 Movimentos Neutros e Sócios, 8 Tributos sobre o Lucro. Grupo 9 fica
-- reservado para contas específicas do tenant/cliente — nunca ocupado por este script.
-- Dentro de cada grupo, famílias .90–.99 e contas .900–.999 também ficam reservadas ao
-- cliente, então uma atualização futura deste plano padrão nunca colide com customização.
--
-- DRE e Fluxo de Caixa são classificações INDEPENDENTES (grupo_dre/grupo_dfc por conta) e há
-- contas que não entram em nenhum dos dois (ex.: grupo 7, movimentos neutros) — é isso que
-- evita contar a mesma movimentação duas vezes (compra de mercadoria: só caixa; CMV: só DRE;
-- depreciação: só DRE; amortização de principal: só caixa).
--
-- Idempotente (ON CONFLICT DO NOTHING) — pode ser rodado de novo sem duplicar nem sobrescrever
-- contas já existentes.

SET app.id_tenant = '1';
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO cfg_plano_contas (
    id_tenant, id_plano_contas, descricao, tipo_movimento, natureza,
    inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
    aceita_lancamento, padrao_sistema
)
SELECT
    1::smallint,
    v.cod,
    v.descr,
    v.tipo::tipo_movimento_conta,
    (CASE WHEN v.analitica THEN 'ANALITICA' ELSE 'SINTETICA' END)::natureza_conta,
    v.dre,
    v.cx,
    v.g_dre::grupo_dre_conta,
    v.g_dfc::grupo_dfc_conta,
    CASE v.tipo WHEN 'CREDITO' THEN 1 WHEN 'DEBITO' THEN -1 ELSE 0 END,
    v.analitica,
    true
FROM (VALUES

-- ============================ 1 · RECEITAS ===================================
-- Entram na DRE (receita) e no fluxo de caixa (operacional).
('1.00.000','RECEITAS','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false),

('1.01.000','Receita de Vendas','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false),
('1.01.001','Venda de Mercadorias — Loja','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true),
('1.01.002','Venda de Mercadorias — Marketplace/Online','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true),

('1.02.000','Outras Receitas','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false),
('1.02.001','Juros e Multas de Crediário Recebidos','CREDITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true),
('1.02.002','Outras Receitas Operacionais','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true),

-- ==================== 2 · DEDUÇÕES DA RECEITA BRUTA ==========================
('2.00.000','DEDUÇÕES DA RECEITA BRUTA','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false),

('2.01.000','Impostos sobre Vendas','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false),
('2.01.001','Simples Nacional / Impostos sobre Vendas','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true),

('2.02.000','Devoluções e Descontos','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false),
('2.02.001','Devoluções de Vendas','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true),
-- Desconto concedido reduz a receita na DRE mas nunca movimenta caixa (o dinheiro simplesmente
-- não entrou) — só DRE.
('2.02.002','Descontos Concedidos','DEBITO',true,false,'DEDUCOES','NAO_APLICA',true),

-- ========================= 3 · CUSTOS VARIÁVEIS ==============================
('3.00.000','CUSTOS VARIÁVEIS','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false),

-- CMV: só DRE (o desembolso de caixa acontece na COMPRA, família 3.03) — anti-duplicação.
('3.01.000','Custo das Mercadorias Vendidas','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',false),
('3.01.001','CMV — Custo das Mercadorias Vendidas','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true),

('3.02.000','Custos Diretos sobre Vendas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false),
('3.02.001','Comissões sobre Vendas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),
('3.02.002','Taxas de Cartão e PIX','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),
('3.02.003','Comissões de Marketplace','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),
('3.02.004','Fretes sobre Vendas / Entregas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),
('3.02.005','Embalagens e Sacolas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),

-- Compra de mercadoria: só fluxo de caixa (na DRE quem aparece é o CMV) — anti-duplicação.
-- 3.03.001 é a conta padrão de cfg_geral.id_plano_contas_compra_mercadoria (Entrada de
-- Produtos por Compra / contas a pagar geradas por ela).
('3.03.000','Compras de Mercadoria','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',false),
('3.03.001','Compra de Mercadoria para Revenda','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true),
('3.03.002','Fretes sobre Compras','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true),

-- ===================== 4 · CUSTOS E DESPESAS FIXAS ===========================
('4.00.000','CUSTOS E DESPESAS FIXAS','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),

('4.01.000','Ocupação e Estrutura','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),
('4.01.001','Aluguel e Condomínio','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.002','Energia Elétrica','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.003','Água','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.004','Internet e Telefone','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.005','Manutenção e Reparos','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.006','Segurança e Monitoramento','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),

('4.02.000','Pessoal','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),
('4.02.001','Salários e Ordenados','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.02.002','Encargos Sociais (FGTS/INSS)','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.02.003','Pró-labore','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.02.004','Vale-transporte e Alimentação','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.02.005','Férias e 13º Salário','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),

('4.03.000','Despesas Administrativas','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),
('4.03.001','Contabilidade','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.03.002','Softwares e Sistemas','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.03.003','Material de Expediente e Limpeza','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.03.004','Veículos e Combustível','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.03.005','Outras Despesas Administrativas','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),

('4.04.000','Comercial e Marketing','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),
('4.04.001','Publicidade e Anúncios','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.04.002','Brindes e Promoções','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),

-- Depreciação: só DRE (o desembolso já aconteceu no investimento, grupo 6) — anti-duplicação.
('4.05.000','Depreciação e Amortização','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',false),
('4.05.001','Depreciação de Móveis e Equipamentos','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true),

-- ================== 5 · FINANCEIRAS E EMPRÉSTIMOS ============================
('5.00.000','FINANCEIRAS E EMPRÉSTIMOS','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',false),

('5.01.000','Despesas Financeiras','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',false),
('5.01.001','Juros e Encargos Pagos','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',true),
('5.01.002','Tarifas Bancárias','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true),

-- Amortização de principal: só caixa (juros, esses sim, entram na DRE em 5.01.001).
-- Captação: dinheiro que ENTRA (crédito), só caixa.
('5.02.000','Empréstimos','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',false),
('5.02.001','Amortização de Principal de Empréstimos','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true),
('5.02.002','Captação de Empréstimos','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true),

-- ==================== 6 · INVESTIMENTOS E IMOBILIZADO ========================
-- Só fluxo de caixa (investimento); na DRE aparece via depreciação (4.05).
('6.00.000','INVESTIMENTOS E IMOBILIZADO','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false),

('6.01.000','Imobilizado','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false),
('6.01.001','Compra de Móveis, Máquinas e Equipamentos','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true),
('6.01.002','Reformas e Instalações','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true),
('6.01.003','Venda de Imobilizado','CREDITO',false,true,'NAO_APLICA','INVESTIMENTO',true),

-- ==================== 7 · MOVIMENTOS NEUTROS E SÓCIOS ========================
-- Contas NEUTRAS não entram nem na DRE nem no fluxo de caixa — são movimentações internas
-- (transferência entre contas próprias, sangria/suprimento, liquidação de recebíveis) que,
-- se contadas, duplicariam valores. Sócios (7.03) entram só no caixa (financiamento).
('7.00.000','MOVIMENTOS NEUTROS E SÓCIOS','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false),

('7.01.000','Transferências entre Contas Próprias','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false),
('7.01.001','Transferência entre Contas','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),
('7.01.002','Sangria de Caixa','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),
('7.01.003','Suprimento de Caixa','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),

('7.02.000','Liquidação de Meios de Pagamento','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false),
('7.02.001','Recebimento de Cartões e PIX','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),
('7.02.002','Recebimento de Crediário','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),

('7.03.000','Sócios e Capital','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false),
('7.03.001','Aporte de Sócios','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true),
('7.03.002','Retirada de Sócios (além do pró-labore)','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true),

-- ===================== 8 · TRIBUTOS SOBRE O LUCRO ============================
('8.00.000','TRIBUTOS SOBRE O LUCRO','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',false),

('8.01.000','Tributos sobre o Lucro','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',false),
('8.01.001','IRPJ e CSLL','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',true)

) AS v(cod, descr, tipo, dre, cx, g_dre, g_dfc, analitica)
ON CONFLICT (id_tenant, id_plano_contas) DO NOTHING;

SET CONSTRAINTS ALL IMMEDIATE;


-- =============================================================================
-- CLONAR O PLANO PADRÃO PARA UM NOVO TENANT
-- =============================================================================
--
-- INSERT INTO cfg_plano_contas (
--     id_tenant, id_plano_contas, descricao, descricao_curta, tipo_movimento,
--     natureza, inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
--     aceita_lancamento, padrao_sistema, ativo
-- )
-- SELECT 2::smallint, id_plano_contas, descricao, descricao_curta, tipo_movimento,
--        natureza, inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
--        aceita_lancamento, padrao_sistema, ativo
--   FROM cfg_plano_contas
--  WHERE id_tenant = 1;
