-- V092 — cancelar a venda passa a cancelar também a OS e o orçamento que a originaram.
--
-- ⛔ O QUE ESTAVA ERRADO (medido ao vivo em 2026-08-28, cancelando a venda 621 vinda da OS 3)
--
-- A venda era cancelada, o estoque voltava — e a OS continuava `FATURADA` apontando para uma venda
-- que não existe mais. Sem caminho de volta: `FATURADA` não se cancela (é a venda que tem caixa,
-- contas a receber e nota a reverter) e o F5 só oferece `CONCLUIDA`. O lojista teria de abrir outra
-- OS do zero, redigitando serviços, peças e executor de um trabalho já feito.
--
-- ⚠️ O ORÇAMENTO tem exatamente o mesmo defeito desde que existe: fica `VENDIDO` apontando para
-- venda cancelada. Não é regressão do módulo de serviços — só dói menos, porque um orçamento se
-- refaz em dois minutos e uma OS carrega o histórico da execução.
--
-- DECISÃO DO DONO DO PRODUTO (2026-08-29):
--   "Se for cancelada uma venda, que tem uma OS ou um ORÇAMENTO, cancela a venda e tb OS e ou
--    ORÇAMENTO."
--
-- Ou seja: o documento de origem é CANCELADO junto, não devolvido a "concluído/aberto". Isso
-- também responde a pergunta da reserva de estoque, que deixa de existir: documento cancelado não
-- reserva nada. (E o estoque já voltou ao livre no faturamento — `marcarFaturada` libera as
-- reservas e zera `qtd_reservada`; o cancelamento não pode liberar de novo.)
--
-- ---------------------------------------------------------------------------------------------
-- O QUE ESTA MIGRATION FAZ: só afrouxa dois CHECK. Nenhum dado é alterado.
--
-- Os dois CHECK amarram "estado × venda" com igualdade estrita, e é ela que impede o cancelamento
-- de guardar o rastro:
--
--   ordem_servico_faturada_ck  CHECK ((situacao = 'FATURADA') = (id_venda IS NOT NULL))
--   orcamento_efetivado_ck     CHECK ((situacao IN ('VENDIDO','VENDIDO_PARCIAL')) = (id_venda IS NOT NULL))
--
-- Com eles, cancelar exigiria `id_venda = NULL` — e aí a OS cancelada não saberia mais dizer QUAL
-- venda caiu. Isso é perda de auditoria (P3) num caminho que mexe em dinheiro e estoque: a pergunta
-- "por que esta OS foi cancelada?" tem de ser respondível pela própria linha.
--
-- ⚠️ A invariante que importa NÃO é afrouxada: quem está FATURADA/VENDIDO continua obrigado a ter
-- venda, e quem está ABERTA/CONCLUIDA/VENCIDO continua obrigado a NÃO ter. O que passa a ser
-- permitido é exatamente um caso — CANCELADA carregando a venda que foi cancelada.

ALTER TABLE ordem_servico DROP CONSTRAINT ordem_servico_faturada_ck;
ALTER TABLE ordem_servico ADD CONSTRAINT ordem_servico_faturada_ck CHECK (
  CASE situacao
    WHEN 'FATURADA'  THEN id_venda IS NOT NULL
    -- ⭐ O único caso novo: cancelada PODE carregar a venda que a originou (e carrega, quando o
    -- cancelamento veio de lá). Cancelada por outro motivo continua com id_venda nulo.
    WHEN 'CANCELADA' THEN true
    ELSE id_venda IS NULL
  END);

ALTER TABLE orcamento DROP CONSTRAINT orcamento_efetivado_ck;
ALTER TABLE orcamento ADD CONSTRAINT orcamento_efetivado_ck CHECK (
  CASE
    WHEN situacao IN ('VENDIDO', 'VENDIDO_PARCIAL') THEN id_venda IS NOT NULL
    WHEN situacao = 'CANCELADO' THEN true
    ELSE id_venda IS NULL
  END);

COMMENT ON CONSTRAINT ordem_servico_faturada_ck ON ordem_servico IS
  'FATURADA exige venda; ABERTA/EM_EXECUCAO/CONCLUIDA exigem venda nula. CANCELADA pode ter as '
  'duas coisas (V092): quando o cancelamento vem do Cancelamento de Venda, a OS guarda qual venda '
  'caiu — sem isso a linha cancelada não responderia "por quê".';

COMMENT ON CONSTRAINT orcamento_efetivado_ck ON orcamento IS
  'VENDIDO/VENDIDO_PARCIAL exigem venda; ABERTO/VENCIDO exigem venda nula. CANCELADO pode ter as '
  'duas coisas (V092), pelo mesmo motivo da ordem_servico.';
