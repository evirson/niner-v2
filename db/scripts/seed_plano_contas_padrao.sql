-- Script de carga do plano de contas padrão em cfg_plano_contas (V016) — NÃO é uma migration
-- Flyway. Diferente de cfg_produto_ncm (global, sem tenant), este plano é POR TENANT — é
-- dado de negócio de um tenant específico, não estrutura de schema, por isso fica fora das
-- migrations versionadas (banco em construção, 2026-07-31). Rodar como niner_owner:
--
--   docker exec -i niner-db psql -U niner_owner -d niner_db < db/scripts/seed_plano_contas_padrao.sql
--
-- >>> TROQUE O LITERAL 1 ABAIXO PELO id_tenant DESEJADO ANTES DE RODAR PARA OUTRO TENANT <<<
--
-- Plano padrão para varejo de calçados e confecções — ~200 contas em 8 grupos (1 Receitas,
-- 2 Deduções, 3 Custos Variáveis, 4 Custos/Despesas Fixas, 5 Financeiras/Empréstimos,
-- 6 Investimentos/CAPEX, 7 Movimentos Neutros, 8 Tributos sobre o Lucro). Grupo 9 fica
-- reservado para contas específicas do tenant/cliente — nunca ocupado por este script.
-- Dentro de cada grupo, subcontas .90–.99 e itens .900–.999 também ficam reservados ao
-- cliente, então uma atualização futura deste plano padrão nunca colide com customização.
-- Idempotente (ON CONFLICT DO NOTHING) — pode ser rodado de novo sem duplicar nem sobrescrever
-- contas já existentes (inclusive uma conta pré-existente que já tenha sido convertida pro
-- código de uma conta padrão, ver docs/telas/plano-contas.md, "Migração de dados 2026-07-31").

SET app.id_tenant = '1';
SET CONSTRAINTS ALL DEFERRED;

INSERT INTO cfg_plano_contas (
    id_tenant, id_plano_contas, descricao, tipo_movimento, natureza,
    inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
    aceita_lancamento, exige_contraparte, padrao_sistema
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
    v.contraparte,
    true
FROM (VALUES

-- ============================ 1 · RECEITAS ===================================
('1.00.000.000','RECEITAS','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),

('1.01.000.000','Receita Bruta de Vendas','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.01.001.000','Venda de Calçados','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.01.001.001','Calçados Masculinos','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.001.002','Calçados Femininos','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.001.003','Calçados Infantis','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.001.004','Tênis e Linha Esportiva','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.001.005','Sandálias e Chinelos','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.001.006','Botas e Coturnos','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.001.007','Calçados de Segurança e Profissionais','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),

('1.01.002.000','Venda de Confecções','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.01.002.001','Vestuário Masculino','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.002.002','Vestuário Feminino','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.002.003','Vestuário Infantil e Bebê','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.002.004','Moda Íntima e Pijamas','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.002.005','Moda Praia e Fitness','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.002.006','Jeanswear','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.002.007','Uniformes e Linha Corporativa','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),

('1.01.003.000','Venda de Acessórios','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.01.003.001','Bolsas, Mochilas e Carteiras','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.003.002','Cintos','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.003.003','Meias, Palmilhas e Cadarços','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.003.004','Bonés, Chapéus e Toucas','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.003.005','Bijuterias e Relógios','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.003.006','Produtos de Conservação (graxa, impermeabilizante)','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),

('1.01.004.000','Venda por Canal Digital','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.01.004.001','E-commerce Próprio','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.004.002','Marketplace','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.004.003','Venda Assistida por WhatsApp / Social','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),

('1.01.005.000','Receita de Serviços','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.01.005.001','Ajuste e Costura','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.005.002','Personalização, Bordado e Estamparia','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.01.005.003','Conserto de Calçados','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),

('1.02.000.000','Outras Receitas Operacionais','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.02.001.000','Verbas e Bonificações de Fornecedor','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.02.001.001','Bonificação em Mercadoria','CREDITO',true,false,'RECEITA_BRUTA','NAO_APLICA',true,false),
('1.02.001.002','Verba de Propaganda Cooperada','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.02.001.003','Rebate por Volume de Compra','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.02.002.000','Receitas Acessórias','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false,false),
('1.02.002.001','Frete Cobrado do Cliente','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.02.002.002','Embalagem para Presente','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.02.002.003','Sublocação e Aluguel de Espaço','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.02.002.004','Venda de Sucata, Caixas e Cabides','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.02.002.005','Sobra de Caixa','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true,false),
('1.02.002.006','Indenização de Seguro Recebida','CREDITO',true,true,'NAO_OPERACIONAL','OPERACIONAL',true,false),

('1.03.000.000','Receitas Financeiras','CREDITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',false,false),
('1.03.001.000','Juros e Rendimentos Ativos','CREDITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',false,false),
('1.03.001.001','Rendimento de Aplicação Financeira','CREDITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('1.03.001.002','Juros e Multa por Atraso de Clientes','CREDITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('1.03.001.003','Descontos Obtidos de Fornecedores','CREDITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('1.03.001.004','Arredondamento e Diferença a Maior','CREDITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),

-- ==================== 2 · DEDUÇÕES DA RECEITA BRUTA ==========================
('2.00.000.000','DEDUÇÕES DA RECEITA BRUTA','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false,false),

('2.01.000.000','Impostos sobre Vendas','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false,false),
('2.01.001.000','Simples Nacional','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false,false),
('2.01.001.001','DAS - Simples Nacional','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.01.002.000','Regime Normal','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false,false),
('2.01.002.001','ICMS sobre Vendas','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.01.002.002','ICMS-ST e DIFAL','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.01.002.003','PIS sobre Faturamento','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.01.002.004','COFINS sobre Faturamento','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.01.002.005','ISS sobre Serviços','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),

('2.02.000.000','Devoluções, Trocas e Descontos','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false,false),
('2.02.001.000','Devoluções de Venda','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false,false),
('2.02.001.001','Devolução de Calçados','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.02.001.002','Devolução de Confecções','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.02.001.003','Devolução de Acessórios','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.02.001.004','Cancelamento de Venda','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.02.002.000','Descontos e Abatimentos Concedidos','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false,false),
('2.02.002.001','Desconto Comercial no PDV','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.02.002.002','Abatimento por Avaria ou Defeito','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.02.002.003','Resgate de Cupom e Voucher','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),
('2.02.002.004','Resgate de Cashback e Fidelidade','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true,false),

-- ======================= 3 · CUSTOS VARIÁVEIS ================================
('3.00.000.000','CUSTOS VARIÁVEIS','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false,false),

-- 3.01 -> DRE sim, caixa NÃO (a saída de dinheiro acontece em 3.03)
('3.01.000.000','Custo das Mercadorias Vendidas','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',false,false),
('3.01.001.000','CMV por Linha de Produto','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',false,false),
('3.01.001.001','CMV Calçados','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.01.001.002','CMV Confecções','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.01.001.003','CMV Acessórios','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.01.001.004','CMV Serviços (materiais aplicados)','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.01.002.000','Ajustes e Perdas de Estoque','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',false,false),
('3.01.002.001','Quebra e Avaria de Estoque','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.01.002.002','Perda por Furto e Diferença de Inventário','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.01.002.003','Provisão para Obsolescência de Coleção','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.01.002.004','Baixa de Mercadoria para Uso e Consumo','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.01.002.005','Baixa de Mercadoria para Brinde e Doação','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),

('3.02.000.000','Custos Diretos sobre Vendas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false,false),
('3.02.001.000','Comissões e Premiações','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false,false),
('3.02.001.001','Comissão de Vendedores','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.001.002','Comissão de Gerência e Supervisão','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.001.003','Premiação por Meta e Campanha','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.001.004','Encargos sobre Comissões','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),

('3.02.002.000','Taxas de Meios de Pagamento','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false,false),
('3.02.002.001','Taxa de Cartão de Crédito','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.002.002','Taxa de Cartão de Débito','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.002.003','Taxa de PIX e QR Code','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.002.004','Taxa de Antecipação de Recebíveis','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.002.005','Custo de Crediário Próprio e Carnê','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.002.006','Aluguel de Maquininha (POS)','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.002.007','Consulta a Birô de Crédito (SPC/Serasa)','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),

('3.02.003.000','Comissões de Canais Digitais','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false,false),
('3.02.003.001','Comissão de Marketplace','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.003.002','Taxa de Gateway e Subadquirente','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.003.003','Comissão de Afiliados e Influenciadores','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),

('3.02.004.000','Logística de Saída','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false,false),
('3.02.004.001','Frete sobre Vendas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.004.002','Frete de Logística Reversa e Troca','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.004.003','Sacolas e Embalagens','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),
('3.02.004.004','Etiquetas, Tags e Antifurto','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),

('3.02.005.000','Perdas com Crediário','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',false,false),
('3.02.005.001','Provisão para Devedores Duvidosos','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.02.005.002','Perda Efetiva com Inadimplência','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true,false),
('3.02.005.003','Custo de Cobrança e Protesto','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true,false),

-- 3.03 -> caixa SIM, DRE NÃO. É aqui que o dinheiro da mercadoria sai.
('3.03.000.000','Compras e Custos de Aquisição de Estoque','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',false,false),
('3.03.001.000','Aquisição de Mercadoria','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',false,false),
('3.03.001.001','Compra de Mercadoria para Revenda','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('3.03.001.002','Frete sobre Compras','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('3.03.001.003','Seguro sobre Compras','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('3.03.001.004','Despesas Aduaneiras e Importação','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('3.03.001.005','ICMS-ST Pago na Compra','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('3.03.001.006','Devolução de Compras','CREDITO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('3.03.002.000','Insumos de Serviço','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',false,false),
('3.03.002.001','Aviamentos, Linhas e Materiais de Ajuste','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true,false),

-- ==================== 4 · CUSTOS E DESPESAS FIXAS ============================
('4.00.000.000','CUSTOS E DESPESAS FIXAS','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),

('4.01.000.000','Ocupação e Estrutura','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.01.001.000','Locação','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.01.001.001','Aluguel de Loja','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.001.002','Aluguel Percentual sobre Faturamento','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.001.003','Condomínio','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.001.004','Fundo de Promoção de Shopping','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.001.005','Aluguel de Depósito e Estoque','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.001.006','13º Aluguel e Encargos Sazonais','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.002.000','Utilidades','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.01.002.001','Energia Elétrica','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.002.002','Água e Esgoto','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.002.003','Gás','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.002.004','Internet, Telefonia e Dados','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.003.000','Conservação e Segurança','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.01.003.001','Manutenção Predial e Pequenos Reparos','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.003.002','Material de Limpeza e Higiene','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.003.003','Segurança e Monitoramento','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.003.004','Dedetização e Controle de Pragas','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.003.005','Seguro Predial e de Conteúdo','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.01.003.006','IPTU e Taxas Municipais do Imóvel','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),

('4.02.000.000','Pessoal','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.02.001.000','Remuneração','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.02.001.001','Salários e Ordenados','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.001.002','Pró-labore','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.001.003','Horas Extras e Adicionais','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.001.004','Férias','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.001.005','13º Salário','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.001.006','Rescisões e Verbas Indenizatórias','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.001.007','Estagiários e Jovem Aprendiz','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.001.008','Serviços de Terceiros Pessoa Física (RPA)','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.002.000','Encargos Sociais','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.02.002.001','INSS Patronal','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.002.002','FGTS','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.002.003','Multa de FGTS na Rescisão','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.002.004','Provisão de Férias e 13º','DEBITO',true,false,'DESPESA_FIXA','NAO_APLICA',true,false),
('4.02.002.005','Provisão para Contingência Trabalhista','DEBITO',true,false,'DESPESA_FIXA','NAO_APLICA',true,false),
('4.02.003.000','Benefícios','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.02.003.001','Vale-Transporte','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.003.002','Vale-Refeição e Alimentação','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.003.003','Plano de Saúde e Odontológico','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.003.004','Seguro de Vida em Grupo','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.003.005','Uniformes e EPI','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.004.000','Desenvolvimento e Clima','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.02.004.001','Treinamento de Vendas e Atendimento','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.004.002','Recrutamento e Seleção','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.004.003','Medicina do Trabalho (ASO, PCMSO, PGR)','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.02.004.004','Confraternização e Endomarketing','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),

('4.03.000.000','Administrativas','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.03.001.000','Serviços Profissionais','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.03.001.001','Honorários Contábeis','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.001.002','Honorários Advocatícios','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.001.003','Consultoria e Assessoria','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.001.004','Serviços de Terceiros Pessoa Jurídica','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.002.000','Despesas de Escritório','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.03.002.001','Material de Escritório e Papelaria','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.002.002','Correios, Malotes e Cartório','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.002.003','Copa e Cozinha','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.002.004','Viagens, Hospedagem e Alimentação','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.002.005','Combustível e Manutenção de Veículos','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.002.006','Estacionamento, Pedágio e Aplicativos','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.002.007','Quebra de Caixa','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.003.000','Tecnologia','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.03.003.001','Licença de ERP e Software (SaaS)','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.003.002','Hospedagem, Domínio e Plataforma de E-commerce','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.003.003','Certificado Digital','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.003.004','Manutenção de TI e Equipamentos','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.003.005','Emissor Fiscal, SAT e Impressora Fiscal','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.004.000','Tributos e Taxas Não Vinculados à Venda','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.03.004.001','Alvará, Licenças e Bombeiros','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.004.002','Vigilância Sanitária e Órgãos Reguladores','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.004.003','Contribuição Sindical e Associações','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.004.004','IPVA, Licenciamento e Seguro de Veículos','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.004.005','Multas e Infrações','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.03.004.006','Taxa de Direitos Autorais (ECAD)','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),

('4.04.000.000','Comercial e Marketing','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.04.001.000','Mídia e Divulgação','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.04.001.001','Mídia Paga Digital (Meta, Google, TikTok)','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.001.002','Mídia Offline (rádio, outdoor, carro de som)','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.001.003','Material Gráfico e Impressos','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.001.004','Fotografia e Produção de Conteúdo','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.001.005','Agência e Social Media','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.002.000','Ponto de Venda e Relacionamento','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false,false),
('4.04.002.001','Vitrinismo e Ambientação','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.002.002','Manequins, Displays e Visual Merchandising','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.002.003','Eventos, Desfiles e Feiras','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.002.004','Brindes e Ações Promocionais','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.002.005','Programa de Fidelidade e CRM','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),
('4.04.002.006','Viagem de Compras e Feiras de Moda','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true,false),

-- 4.05 -> DRE sim, caixa NÃO. O desembolso já ocorreu no grupo 6.
('4.05.000.000','Depreciação e Amortização','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',false,false),
('4.05.001.000','Depreciação e Amortização do Período','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',false,false),
('4.05.001.001','Depreciação de Móveis, Araras e Expositores','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true,false),
('4.05.001.002','Depreciação de Máquinas e Equipamentos','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true,false),
('4.05.001.003','Depreciação de Informática','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true,false),
('4.05.001.004','Depreciação de Veículos','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true,false),
('4.05.001.005','Amortização de Benfeitorias em Imóvel de Terceiros','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true,false),
('4.05.001.006','Amortização de Software e Intangíveis','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true,false),
('4.05.001.007','Amortização do Ponto Comercial','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true,false),

-- ========== 5 · FINANCEIRAS, EMPRÉSTIMOS E AMORTIZAÇÕES ======================
('5.00.000.000','FINANCEIRAS, EMPRÉSTIMOS E AMORTIZAÇÕES','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',false,false),

-- 5.01 -> só JUROS e TARIFAS vão ao DRE
('5.01.000.000','Despesas Financeiras','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',false,false),
('5.01.001.000','Juros Passivos','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',false,false),
('5.01.001.001','Juros sobre Empréstimo Bancário','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',true,false),
('5.01.001.002','Juros sobre Capital de Giro','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',true,false),
('5.01.001.003','Juros de Cheque Especial e Conta Garantida','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',true,false),
('5.01.001.004','Juros sobre Financiamento de Bens','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',true,false),
('5.01.001.005','Juros e Multa por Atraso a Fornecedores','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('5.01.001.006','Juros de Parcelamento Tributário','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('5.01.001.007','Juros sobre Desconto de Duplicatas','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',true,false),
('5.01.002.000','Tarifas Bancárias','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',false,false),
('5.01.002.001','Manutenção de Conta e Pacote de Serviços','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('5.01.002.002','TED, DOC e PIX','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('5.01.002.003','Emissão e Registro de Boleto','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('5.01.002.004','Cobrança, Protesto e Negativação','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('5.01.003.000','Encargos e Tributos Financeiros','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',false,false),
('5.01.003.001','IOF','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',true,false),
('5.01.003.002','IRRF sobre Aplicação Financeira','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('5.01.003.003','Variação Monetária e Cambial Passiva','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),
('5.01.003.004','Arredondamento e Diferença a Menor','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true,false),

-- 5.02 -> AMORTIZAÇÃO DE PRINCIPAL: caixa SIM, DRE NÃO.
--         Lançar aqui evita contar duas vezes (juros já estão em 5.01).
('5.02.000.000','Amortização de Principal','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',false,false),
('5.02.001.000','Pagamento de Principal de Dívidas','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',false,false),
('5.02.001.001','Amortização de Empréstimo Bancário','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.02.001.002','Amortização de Capital de Giro','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.02.001.003','Amortização de Financiamento de Veículo','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.02.001.004','Amortização de Financiamento de Equipamento (FINAME)','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.02.001.005','Amortização de Leasing / Arrendamento','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.02.001.006','Amortização de Parcelamento Tributário (principal)','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('5.02.001.007','Amortização de Empréstimo de Sócios','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.02.001.008','Liquidação Antecipada de Dívida','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),

-- 5.03 -> CAPTAÇÃO: entra dinheiro mas NÃO é receita.
('5.03.000.000','Captação de Recursos','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',false,false),
('5.03.001.000','Ingresso de Dívida','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',false,false),
('5.03.001.001','Empréstimo Bancário Recebido','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.03.001.002','Capital de Giro Recebido','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.03.001.003','Financiamento de Bens Recebido','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.03.001.004','Empréstimo de Sócios Recebido','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.03.001.005','Desconto de Duplicatas - Valor Principal','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('5.03.001.006','Antecipação de Recebíveis - Valor Principal','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),

-- ================= 6 · INVESTIMENTOS E IMOBILIZADO (CAPEX) ===================
-- Sai dinheiro, mas NÃO é despesa. Vira DRE depois, via 4.05 (depreciação).
('6.00.000.000','INVESTIMENTOS E IMOBILIZADO','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false,false),

('6.01.000.000','Aquisição de Imobilizado','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false,false),
('6.01.001.000','Bens de Loja e Operação','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false,false),
('6.01.001.001','Móveis, Araras, Gôndolas e Expositores','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.01.001.002','Máquinas e Equipamentos','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.01.001.003','Computadores, PDV e Periféricos','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.01.001.004','Ar-condicionado e Instalações','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.01.001.005','Sistema de Segurança e CFTV','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.01.001.006','Veículos','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.01.002.000','Obras e Benfeitorias','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false,false),
('6.01.002.001','Reforma e Benfeitoria em Imóvel de Terceiros','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.01.002.002','Projeto Arquitetônico e Fachada','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.01.002.003','Implantação de Nova Loja','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),

('6.02.000.000','Intangível e Pré-Operacional','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false,false),
('6.02.001.000','Ativos Intangíveis','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false,false),
('6.02.001.001','Software e Licenças Perpétuas','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.02.001.002','Registro de Marca e Propriedade Intelectual','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.02.001.003','Ponto Comercial e Luvas','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.02.001.004','Taxa de Franquia e Adesão','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.02.001.005','Despesas Pré-Operacionais','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),

('6.03.000.000','Alienação de Ativos','CREDITO',false,true,'NAO_APLICA','INVESTIMENTO',false,false),
('6.03.001.000','Venda de Bens do Ativo','CREDITO',false,true,'NAO_APLICA','INVESTIMENTO',false,false),
('6.03.001.001','Venda de Imobilizado - Valor Recebido','CREDITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),
('6.03.001.002','Venda de Veículo - Valor Recebido','CREDITO',false,true,'NAO_APLICA','INVESTIMENTO',true,false),

-- Só o RESULTADO da alienação vai ao DRE; o caixa já entrou em 6.03.
('6.04.000.000','Resultado na Alienação de Ativos','DEBITO',true,false,'NAO_OPERACIONAL','NAO_APLICA',false,false),
('6.04.001.000','Ganhos e Perdas de Capital','DEBITO',true,false,'NAO_OPERACIONAL','NAO_APLICA',false,false),
('6.04.001.001','Ganho na Alienação de Imobilizado','CREDITO',true,false,'NAO_OPERACIONAL','NAO_APLICA',true,false),
('6.04.001.002','Perda na Alienação ou Baixa de Imobilizado','DEBITO',true,false,'NAO_OPERACIONAL','NAO_APLICA',true,false),

-- ================== 7 · MOVIMENTOS NEUTROS DE CAIXA ==========================
-- Nunca entram no DRE (trava por CHECK). Vários também não entram no DFC
-- consolidado porque se anulam entre si (transferência, liquidação de cartão).
('7.00.000.000','MOVIMENTOS NEUTROS','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false,false),

('7.01.000.000','Transferências entre Contas Próprias','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false,false),
('7.01.001.000','Movimentação Interna','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false,false),
('7.01.001.001','Transferência entre Contas Bancárias','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,true),
('7.01.001.002','Sangria de Caixa para Banco/Cofre','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,true),
('7.01.001.003','Suprimento e Troco de Caixa','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,true),
('7.01.001.004','Transferência entre Lojas / Filiais','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,true),
('7.01.001.005','Aplicação Financeira (saída)','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,true),
('7.01.001.006','Resgate de Aplicação Financeira (entrada)','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,true),
('7.01.001.007','Depósito em Trânsito','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,true),

('7.02.000.000','Liquidação de Meios de Pagamento','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false,false),
('7.02.001.000','Repasse de Adquirentes e Plataformas','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false,false),
('7.02.001.001','Recebimento de Operadora de Cartão','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),
('7.02.001.002','Recebimento de Gateway / Subadquirente','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),
('7.02.001.003','Repasse de Marketplace','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),
('7.02.001.004','Estorno e Chargeback de Cartão','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),
('7.02.001.005','Cancelamento de Transação no PDV','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),

('7.03.000.000','Sócios e Capital','NEUTRO',false,true,'NAO_APLICA','FINANCIAMENTO',false,false),
('7.03.001.000','Movimentação de Sócios','NEUTRO',false,true,'NAO_APLICA','FINANCIAMENTO',false,false),
('7.03.001.001','Aporte de Capital dos Sócios','NEUTRO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('7.03.001.002','Distribuição de Lucros','NEUTRO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('7.03.001.003','Retirada de Sócios','NEUTRO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('7.03.001.004','Redução de Capital','NEUTRO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),
('7.03.001.005','Despesa Particular de Sócio (a reembolsar)','NEUTRO',false,true,'NAO_APLICA','FINANCIAMENTO',true,false),

('7.04.000.000','Valores de Terceiros e em Trânsito','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',false,false),
('7.04.001.000','Adiantamentos','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',false,false),
('7.04.001.001','Adiantamento a Fornecedor','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.001.002','Adiantamento a Funcionário e Vale','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.001.003','Adiantamento de Cliente e Sinal','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.001.004','Venda de Vale-Presente (não resgatado)','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.002.000','Garantias e Retenções','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',false,false),
('7.04.002.001','Caução e Depósito de Garantia','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.002.002','Depósito Judicial','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.002.003','Tributos Retidos de Terceiros a Recolher','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.002.004','Descontos em Folha a Repassar','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.003.000','Reembolsos e Consignação','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',false,false),
('7.04.003.001','Reembolso de Despesas a Receber','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),
('7.04.003.002','Repasse de Mercadoria em Consignação','NEUTRO',false,true,'NAO_APLICA','OPERACIONAL',true,false),

('7.05.000.000','Ajustes e Implantação','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false,false),
('7.05.001.000','Conciliação e Abertura','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false,false),
('7.05.001.001','Saldo Inicial de Implantação','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),
('7.05.001.002','Ajuste de Conciliação Bancária','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),
('7.05.001.003','Estorno de Lançamento','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),
('7.05.001.004','Transitória a Classificar','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true,false),

-- ==================== 8 · TRIBUTOS SOBRE O LUCRO =============================
('8.00.000.000','TRIBUTOS SOBRE O LUCRO','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',false,false),
('8.01.000.000','Tributos sobre o Resultado','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',false,false),
('8.01.001.000','Apuração','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',false,false),
('8.01.001.001','IRPJ','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',true,false),
('8.01.001.002','CSLL','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',true,false),
('8.01.001.003','IRPJ e CSLL Diferidos','DEBITO',true,false,'TRIBUTO_LUCRO','NAO_APLICA',true,false)

) AS v(cod, descr, tipo, dre, cx, g_dre, g_dfc, analitica, contraparte)
ON CONFLICT (id_tenant, id_plano_contas) DO NOTHING;

SET CONSTRAINTS ALL IMMEDIATE;


-- =============================================================================
-- CLONAR O PLANO PADRÃO PARA UM NOVO TENANT
-- =============================================================================
--
-- INSERT INTO cfg_plano_contas (
--     id_tenant, id_plano_contas, descricao, descricao_curta, tipo_movimento,
--     natureza, inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
--     aceita_lancamento, exige_centro_custo, exige_contraparte, exige_documento,
--     padrao_sistema, ativo
-- )
-- SELECT 2::smallint, id_plano_contas, descricao, descricao_curta, tipo_movimento,
--        natureza, inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
--        aceita_lancamento, exige_centro_custo, exige_contraparte, exige_documento,
--        padrao_sistema, ativo
--   FROM cfg_plano_contas
--  WHERE id_tenant = 1;
