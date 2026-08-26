-- V069 — o M6: o pedido de marketplace vira `venda`, e a reserva segura o estoque antes disso.
--
-- É a decisão nº 1 da §8 do estudo — a que mais muda o desenho —, e o modelo do legado já
-- acomodava quase tudo (ver V063). O que faltava eram DOIS vínculos.
--
-- ============================================================================================
-- 1. `pedido.id_venda` — a marca de que este pedido já virou venda
-- ============================================================================================
--
-- ⛔ É a **trava de idempotência da conversão**, não um enfeite de navegação. O mesmo pedido chega
-- muitas vezes de propósito: o webhook notifica cada mudança de estado, o ML reenvia o que julga
-- não entregue, e o polling traz os recentes a cada 15 min. Converter duas vezes criaria duas
-- vendas, debitaria estoque duas vezes e consumiria cota duas vezes (P2).
--
-- A conversão só acontece com `id_venda IS NULL`, num `UPDATE` condicional — não num `SELECT`
-- antes do `INSERT`, que teria janela entre a leitura e a escrita.

ALTER TABLE pedido ADD COLUMN id_venda integer;

ALTER TABLE pedido
  ADD CONSTRAINT pedido_venda_fk FOREIGN KEY (id_tenant, id_venda)
  REFERENCES venda (id_tenant, id_venda);

CREATE INDEX pedido_venda_ix ON pedido (id_tenant, id_venda);

COMMENT ON COLUMN pedido.id_venda IS
  'A venda gerada por este pedido (M6). NULL = ainda nao convertido. E a trava de idempotencia da '
  'conversao: o mesmo pedido chega muitas vezes (webhook + reenvio + polling), e converter duas '
  'vezes criaria duas vendas, debitaria estoque duas vezes e consumiria cota duas vezes. Ver V069.';

-- ============================================================================================
-- 2. `canal.id_carteira` — por onde o dinheiro do marketplace entra no financeiro
-- ============================================================================================
--
-- ⭐ Decisão do dono do produto (§8, item 4): *"a taxa do ML entra como `tipo_carteira`"*. Uma
-- carteira com `taxa_administradora` (a comissão do canal) e `prazo_pagamento` (dias até liquidar),
-- categoria CARTAO_CREDITO — e então **DRE, Lucratividade e Fluxo de Caixa funcionam pelo caminho
-- que já existe**, sem uma linha de código nova nesses relatórios.
--
-- É por isso que a venda de marketplace **não entra no caixa**: o dinheiro do ML não passa pela
-- gaveta, cai na conta dias depois. Ela nasce como uma parcela em aberto em `contas_receber`,
-- exatamente como uma venda no cartão.
--
-- ⚠️ Nullable de propósito: canal criado antes desta migration (nenhum, hoje) fica sem carteira, e
-- a conversão **recusa com mensagem** em vez de inventar um destino para o dinheiro.

ALTER TABLE canal ADD COLUMN id_carteira integer;

ALTER TABLE canal
  ADD CONSTRAINT canal_carteira_fk FOREIGN KEY (id_tenant, id_carteira)
  REFERENCES tipo_carteira (id_tenant, id_carteira);

COMMENT ON COLUMN canal.id_carteira IS
  'Tipo de carteira que representa o dinheiro deste canal: taxa_administradora = comissao do '
  'marketplace, prazo_pagamento = dias ate liquidar. E o que faz DRE/Lucratividade/Fluxo de Caixa '
  'enxergarem a venda de marketplace sem codigo novo (decisao do dono do produto, §8 item 4).';

-- ============================================================================================
-- 3. Reserva de estoque (ADR-004) — o ledger da reserva
-- ============================================================================================
--
-- `produto_estoque.reservado` existe desde a V019 e nunca foi usado. A reserva sobe quando o
-- pedido chega e desce quando ele vira venda (o estoque sai de verdade) ou é cancelado.
--
-- ⭐ E ela fecha o laço do anti-overselling sozinha: `disponivel` é coluna GERADA
-- (`qtd_estoque - reservado`), o M3 publica `disponivel`, e o gatilho da V067 dispara em qualquer
-- UPDATE de `produto_estoque` — inclusive de `reservado`. Ou seja: **reservar um pedido republica
-- automaticamente o saldo menor no anúncio**, sem ninguém pedir.
--
-- ⚠️ A coluna abaixo evita a reserva dobrada, que é o mesmo risco da conversão: o pedido chega
-- muitas vezes, e reservar a cada chegada travaria o estoque inteiro de um produto vendido uma vez.

ALTER TABLE pedido ADD COLUMN estoque_reservado boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN pedido.estoque_reservado IS
  'true = a reserva deste pedido ja subiu em produto_estoque.reservado. Evita reserva dobrada: o '
  'mesmo pedido chega muitas vezes (webhook + reenvio + polling), e reservar a cada chegada '
  'travaria o estoque inteiro de um produto vendido uma vez so. Ver V069.';
