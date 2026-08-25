-- V064 — preço por canal (decisão do dono do produto, 2026-08-25):
--   "às vezes o usuário pode ter um preço diferente para o marketplace, que pode ser MAIOR que o
--    preço de venda na loja física, ou MENOR que a loja física"
--
-- Ver docs/MODULOMARKETPLACE.md §8.4. O preço publicado já tinha casa: `anuncio.preco` (V020).
-- O que faltava era (a) como ele NASCE sem o lojista digitar centenas de preços e (b) o que
-- acontece com ele quando o preço da loja muda.
--
-- ⚠️ Por que DUAS colunas e não uma:
--   * `canal.perc_preco` é a REGRA do canal — o gerador. Aceita negativo de propósito: o dono do
--     produto disse "maior OU MENOR que a loja física". Um lojista pode aceitar margem menor no
--     marketplace para ganhar volume ou girar estoque parado.
--   * `anuncio.preco_manual` marca que o lojista DIGITOU aquele preço. Sem essa marca, a próxima
--     mudança de preço na loja sobrescreveria a decisão dele em silêncio.
--
-- É a lição de `feedback_efeito_derivado_congela_valor_parcial`, do próprio projeto: guardar por
-- "o usuário editou", nunca por "eu já calculei". A diferença aparece no dia em que o lojista
-- reajusta a tabela da loja e descobre que os preços que ele ajustou à mão no ML sumiram.

ALTER TABLE canal
  ADD COLUMN perc_preco numeric(6,2) NOT NULL DEFAULT 0;

-- Piso em -100: um acréscimo de -100% zeraria o preço, e abaixo disso ficaria negativo. Teto
-- generoso (não é papel do banco decidir a margem do lojista), mas finito para pegar dedo escorregado
-- (digitar 1500 quando queria 15,00).
ALTER TABLE canal
  ADD CONSTRAINT canal_perc_preco_ck CHECK (perc_preco > -100 AND perc_preco <= 1000);

ALTER TABLE anuncio
  ADD COLUMN preco_manual boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN canal.perc_preco IS
  'Percentual aplicado sobre produto.preco_venda para NASCER o preco do anuncio neste canal. '
  'Aceita NEGATIVO (preco menor que o da loja fisica) — decisao do dono do produto em 2026-08-25. '
  'Zero = mesmo preco da loja. E so o gerador: a verdade publicada e anuncio.preco.';

COMMENT ON COLUMN anuncio.preco_manual IS
  'true = o lojista DIGITOU este preco; reajuste na loja NAO o sobrescreve. '
  'false = preco derivado de canal.perc_preco; acompanha o preco da loja. '
  'Ver docs/MODULOMARKETPLACE.md §8.4.';

COMMENT ON COLUMN anuncio.preco IS
  'Preco efetivamente publicado no canal. Nasce de produto.preco_venda * (1 + canal.perc_preco/100) '
  'e vira fixo quando preco_manual = true.';
