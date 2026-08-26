-- V070 — o M7: a fila de expedição (R5).
--
-- O pedido já vira venda (M6). O que falta é o trabalho **físico**: separar, embalar, despachar —
-- e o rastro de quem fez o quê (P3).
--
-- ============================================================================================
-- 1. O rastro da expedição
-- ============================================================================================
--
-- ⚠️ Colunas direto em `pedido`, não uma tabela de log separada — mesmo padrão de
-- `venda.cancelada`/`data_cancelamento`/`id_usuario_cancelamento` (V018) e de
-- `venda_devolucao` (V018): quem/quando/quem-fez já cabem aqui, e o histórico completo de
-- estados não é pergunta que alguém faça na fila de expedição.

ALTER TABLE pedido ADD COLUMN data_separacao       timestamptz;
ALTER TABLE pedido ADD COLUMN data_envio           timestamptz;
ALTER TABLE pedido ADD COLUMN codigo_rastreio      text;
ALTER TABLE pedido ADD COLUMN id_usuario_expedicao integer;

ALTER TABLE pedido
  ADD CONSTRAINT pedido_usuario_expedicao_fk FOREIGN KEY (id_tenant, id_usuario_expedicao)
  REFERENCES usuario (id_tenant, id_usuario);

COMMENT ON COLUMN pedido.codigo_rastreio IS
  'Codigo de rastreio informado no despacho. Opcional: em Mercado Envios quem gera a etiqueta (e o '
  'rastreio) e o proprio marketplace — o campo serve para envio proprio e para o lojista anotar.';

COMMENT ON COLUMN pedido.id_usuario_expedicao IS
  'Quem despachou (P3). Nulo enquanto o pedido nao for enviado.';

-- Índice da tela: a fila é "o que ainda não saiu", ordenada pela chegada.
CREATE INDEX pedido_fila_expedicao_ix
  ON pedido (id_tenant, status, criado_em)
  WHERE status IN ('PAGO', 'EM_SEPARACAO');

-- ============================================================================================
-- 2. ⛔ O estado local NÃO pode ser atropelado pela reimportação
-- ============================================================================================
--
-- ⭐ Este é o defeito que a V070 existe para impedir, e ele é silencioso.
--
-- `PedidoImportacaoRepositorio.salvarPedido` atualiza o status a cada chegada do pedido — e o
-- pedido chega muitas vezes (webhook a cada mudança + reenvio + polling a cada 15 min). Se o
-- lojista marcar EM_SEPARACAO às 10h e o polling rodar às 10h15 com o marketplace ainda dizendo
-- "paid", o status voltaria para PAGO: **o pedido reapareceria na fila de separação, já separado**,
-- e alguém o separaria de novo.
--
-- A regra, que vive na função abaixo para não depender de quem escreve o UPDATE:
--   * CANCELADO do canal SEMPRE vence — o comprador desistiu, e isso é fato do canal;
--   * fora isso, o canal só manda enquanto o trabalho físico não começou (RECEBIDO/PAGO);
--   * a partir de EM_SEPARACAO, quem manda é a loja.

CREATE OR REPLACE FUNCTION fn_status_pedido_do_canal(
  atual status_pedido, do_canal status_pedido) RETURNS status_pedido
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
           WHEN do_canal = 'CANCELADO' THEN 'CANCELADO'::status_pedido
           WHEN atual IN ('RECEBIDO', 'PAGO') THEN do_canal
           ELSE atual
         END;
$$;

COMMENT ON FUNCTION fn_status_pedido_do_canal(status_pedido, status_pedido) IS
  'Decide o status do pedido quando o canal reenvia o mesmo pedido. CANCELADO do canal sempre '
  'vence; fora isso o canal so manda ate o trabalho fisico comecar (RECEBIDO/PAGO). A partir de '
  'EM_SEPARACAO quem manda e a loja — senao o polling devolveria a fila um pedido ja separado, '
  'em silencio. Ver V070.';
