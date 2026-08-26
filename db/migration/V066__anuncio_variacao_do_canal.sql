-- V066 — o de-para do R6 precisa descer até a VARIAÇÃO do anúncio (bloco M2).
--
-- ⭐ O problema que isto conserta: `anuncio` (V020) guardava só `id_externo`, o id do ITEM no
-- marketplace. Num anúncio com variações — que numa loja de roupa é a REGRA, não a exceção, já
-- que este ERP tem cor e grade — um item só carrega N variações, e cada uma precisa apontar para
-- uma variação diferente do ERP. Sem esta coluna, o vínculo só conseguia dizer "o anúncio MLB123
-- é o produto X", e o saldo publicado seria o de um tamanho só.
--
-- O adapter JÁ falava essa linguagem desde 2026-08-25 (`SaldoAnuncio.idExternoVariacao`); quem
-- não sabia expressá-la era a tabela.
--
-- ⚠️ NULL = anúncio simples, sem variações. E é por isso que a UNIQUE precisa de
-- `NULLS NOT DISTINCT`: no padrão do Postgres dois NULL são DIFERENTES entre si, então
-- (canal, MLB123, NULL) poderia ser inserido duas vezes e o mesmo anúncio simples ficaria
-- vinculado a dois produtos — exatamente o que a restrição existe para impedir. Disponível desde
-- o PG 15; este projeto roda 18.

ALTER TABLE anuncio
  ADD COLUMN id_externo_variacao text;

COMMENT ON COLUMN anuncio.id_externo_variacao IS
  'Id da variacao DENTRO do anuncio, no marketplace (ML: variations[].id). NULL = anuncio simples. '
  'Nao confundir com id_variacao, que e a variacao do ERP: o R6 e justamente ligar uma na outra.';

ALTER TABLE anuncio DROP CONSTRAINT anuncio_canal_externo_uk;

ALTER TABLE anuncio
  ADD CONSTRAINT anuncio_canal_externo_uk
  UNIQUE NULLS NOT DISTINCT (id_canal, id_externo, id_externo_variacao);

-- ⛔ Uma variacao do ERP nao pode alimentar DOIS anuncios do mesmo canal.
--
-- Motivo, e ele e a promessa central do produto (P1, "zero overselling"): o saldo publicado e o
-- da variacao. Duas linhas apontando para a mesma variacao com 5 pecas publicariam 5 em cada
-- anuncio — prometendo 10 ao comprador, com 5 no estoque. O marketplace pune cancelamento com
-- reputacao, que e o ativo do lojista.
--
-- ⚠️ Isto FECHA um caso de uso real (o lojista que anuncia o mesmo produto duas vezes para ganhar
-- alcance). Fechar e a escolha segura enquanto nao existe uma regra de RATEIO decidida pelo dono
-- do produto — inventar o rateio aqui seria decidir por ele. Quando houver decisao, este indice
-- e uma linha para remover.
CREATE UNIQUE INDEX anuncio_canal_variacao_erp_uk
  ON anuncio (id_tenant, id_canal, id_variacao);

COMMENT ON INDEX anuncio_canal_variacao_erp_uk IS
  'Uma variacao do ERP alimenta no maximo UM anuncio por canal — senao o mesmo saldo seria '
  'publicado duas vezes e o lojista prometeria o dobro do que tem (P1). Ver V066.';
