-- V013 — Tipos ENUM do domínio do lojista (§3.3.1: conjuntos estáveis viram ENUM).
-- Domínio do tenant vive no schema public; RLS de tenant é aplicado no arquivo final.

-- Canais de venda (marketplaces + e-commerce próprio).
CREATE TYPE tipo_canal AS ENUM ('MERCADO_LIVRE', 'SHOPEE', 'AMAZON', 'ECOMMERCE');

-- Saúde da conexão do canal.
CREATE TYPE status_canal AS ENUM ('CONECTADO', 'DESCONECTADO', 'ERRO');

-- Situação de sincronização de um anúncio (de-para SKU ↔ canal).
CREATE TYPE status_sync AS ENUM ('OK', 'PENDENTE', 'ERRO');

-- Fila única de expedição (R5). Reserva ocorre já no RECEBIDO (Q2/ADR-004).
CREATE TYPE status_pedido AS ENUM
  ('RECEBIDO', 'PAGO', 'EM_SEPARACAO', 'ENVIADO', 'ENTREGUE', 'CANCELADO');

-- Tipos de movimentação de estoque (legado 1..5 + operações de reserva do ADR-004).
-- CANCELAMENTO (2026-07-30): estorno de venda (Cancelamento de Venda) — devolve ao estoque a
-- quantidade de cada item, distinto de DEVOLUCAO (troca/devolução via venda_devolucao, ainda
-- não implementada) porque não é uma nova operação comercial, é a reversão total da venda.
CREATE TYPE tipo_movimento AS ENUM
  ('COMPRA', 'TRANSFERENCIA', 'DEVOLUCAO', 'AJUSTE', 'VENDA', 'RESERVA', 'LIBERACAO_RESERVA',
   'CANCELAMENTO');

-- Natureza do lançamento no ledger de estoque.
CREATE TYPE credito_debito AS ENUM ('C', 'D');

-- Operação da venda física (legado tipo_operacao V/D).
CREATE TYPE tipo_operacao_venda AS ENUM ('VENDA', 'DEVOLUCAO');

-- Situação de um evento no outbox (P2: async + retry + dead-letter).
CREATE TYPE status_outbox AS ENUM ('PENDENTE', 'PROCESSADO', 'ERRO', 'DEAD_LETTER');

-- Gênero do cliente (legado: M/F/O). Usado em cliente.genero (V016).
CREATE TYPE genero_cliente AS ENUM ('MASCULINO', 'FEMININO', 'OUTROS');

-- Natureza do lançamento no plano de contas (legado: C/D/N, por extenso desde 2026-07-16).
-- Usado em cfg_plano_contas.tipo_movimento (V016). Diferente de credito_debito (ledger de
-- estoque); 'NEUTRO' cobre conta sintética/agrupadora que não recebe lançamento direto.
-- Sem acento desde 2026-07-31 (revisão do plano de contas gerencial) — identificadores
-- acentuados complicavam o DTO Java (String + allowlist em vez de enum); valores existentes
-- migrados via ALTER TYPE ... RENAME VALUE (dado preservado, só o rótulo mudou).
CREATE TYPE tipo_movimento_conta AS ENUM ('CREDITO', 'DEBITO', 'NEUTRO');

-- Se a conta é sintética (agrupadora, não recebe lançamento) ou analítica (recebe lançamento
-- direto). Novo em 2026-07-31, plano de contas gerencial (docs/telas/plano-contas.md).
CREATE TYPE natureza_conta AS ENUM ('SINTETICA', 'ANALITICA');

-- Linha do DRE gerencial que a conta ocupa (permite montar a demonstração sem hardcode de
-- código). 'NAO_APLICA' é obrigatório quando inclui_dre = false (CHECK em cfg_plano_contas).
CREATE TYPE grupo_dre_conta AS ENUM (
    'RECEITA_BRUTA', 'DEDUCOES', 'CUSTO_VARIAVEL', 'DESPESA_FIXA', 'DEPRECIACAO',
    'RESULTADO_FINANCEIRO', 'NAO_OPERACIONAL', 'TRIBUTO_LUCRO', 'NAO_APLICA'
);

-- Bloco do DFC (demonstração de fluxo de caixa, método direto) que a conta ocupa.
-- 'NAO_APLICA' é obrigatório quando inclui_fluxo_caixa = false (CHECK em cfg_plano_contas).
CREATE TYPE grupo_dfc_conta AS ENUM ('OPERACIONAL', 'INVESTIMENTO', 'FINANCIAMENTO', 'NAO_APLICA');
