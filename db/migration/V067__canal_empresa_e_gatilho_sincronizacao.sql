-- V067 — o M3: quem AVISA que o saldo (ou o preço) mudou.
--
-- Duas coisas, e as duas são pré-requisito de publicar qualquer coisa no marketplace.
--
-- ============================================================================================
-- 1. `canal.id_empresa` — de qual loja é o estoque que vai para o anúncio
-- ============================================================================================
--
-- ⭐ Sem isto o M3 não tem como estar certo: `produto_estoque` é POR EMPRESA
-- (UNIQUE id_tenant, id_empresa, id_variacao) e `canal` era por TENANT. Um tenant com 5 empresas
-- não teria como responder "5 peças de qual filial?" — e publicar a soma seria pior: prometeria
-- ao comprador do marketplace um estoque que está espalhado em cinco endereços.
--
-- O estudo (§8.3) já assumia `canal → empresa` e deixou "confirmar no primeiro uso real". O
-- primeiro uso real chegou.

ALTER TABLE canal ADD COLUMN id_empresa integer;

-- ⚠️ Backfill precisa de NO FORCE: migration roda como `niner_owner`, e com FORCE ROW LEVEL
-- SECURITY (V024) nem o dono escapa da política — sem isto o UPDATE casaria ZERO linhas e o
-- Flyway anunciaria sucesso (mordeu de verdade na V057). `NO FORCE`, não `DISABLE`: libera só o
-- dono, e a política continua valendo para `niner_app`.
ALTER TABLE canal NO FORCE ROW LEVEL SECURITY;

-- Canal existente herda a MENOR empresa do tenant (a matriz, `codigo_empresa` 1 na prática).
-- Hoje isto casa zero linhas em dev e em produção — nenhum canal foi conectado ainda —, e é de
-- propósito que o `SET NOT NULL` abaixo venha em seguida: se algum canal existir e o backfill
-- não o alcançar, o deploy PARA aqui, ruidosamente, em vez de seguir com a coluna nula.
UPDATE canal c
   SET id_empresa = (SELECT min(e.id_empresa) FROM empresa e WHERE e.id_tenant = c.id_tenant)
 WHERE c.id_empresa IS NULL;

ALTER TABLE canal FORCE ROW LEVEL SECURITY;

ALTER TABLE canal ALTER COLUMN id_empresa SET NOT NULL;

-- FK composta (P8) — ver o comentário em usuario_empresa_fk (V015): sem ela, um canal forjado
-- com id_tenant de um e id_empresa de outro passaria.
ALTER TABLE canal
  ADD CONSTRAINT canal_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
  REFERENCES empresa (id_tenant, id_empresa);

COMMENT ON COLUMN canal.id_empresa IS
  'De qual empresa (filial) sai o estoque publicado neste canal. Obrigatorio porque produto_estoque '
  'e por empresa: sem isto, "5 pecas" nao responde "de qual loja". Ver V067 e MODULOMARKETPLACE §8.3.';

-- ============================================================================================
-- 2. Os gatilhos que enfileiram no outbox (P2)
-- ============================================================================================
--
-- ⛔ POR QUE NO BANCO, E NÃO NOS SERVIÇOS. É a mesma decisão do `cfg_permite_estoque_negativo`
-- (V054), e pelo mesmo motivo. Debitam ou creditam estoque: o PDV, a transferência, a devolução
-- ao fornecedor, a devolução de venda, o CANCELAMENTO DE ENTRADA (de que ninguém lembra), o
-- cancelamento de devolução, o balanço e a importação de estoque — mais o que vier. Espalhar o
-- "avise o canal" por serviço garante matematicamente que uma rotina fique de fora, e a que ficar
-- de fora vira anúncio prometendo estoque que não existe, em silêncio.
--
-- `produto_estoque` é a linha onde o saldo REALMENTE mora, qualquer que seja o caminho. É o ponto
-- mais estreito possível.
--
-- ⚠️ E o INSERT no outbox acontece na MESMA transação do movimento — que é o outbox pattern
-- inteiro (P2): ou o estoque muda e o evento existe, ou nenhum dos dois.

CREATE OR REPLACE FUNCTION fn_enfileira_sincronizacao_estoque() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  -- Saldo que não mudou não gera evento. `IS DISTINCT FROM` e não `<>` porque `<>` com NULL
  -- devolve NULL, que num IF é falso — e o evento seria enfileirado à toa.
  IF TG_OP = 'UPDATE'
     AND NEW.qtd_estoque IS NOT DISTINCT FROM OLD.qtd_estoque
     AND NEW.reservado   IS NOT DISTINCT FROM OLD.reservado THEN
    RETURN NEW;
  END IF;

  -- ⚠️ O caminho comum não paga quase nada: a loja típica não tem canal conectado, e este EXISTS
  -- sai no primeiro índice. Enfileirar sempre encheria o outbox de eventos que ninguém consome.
  IF NOT EXISTS (
        SELECT 1
          FROM anuncio a
          JOIN canal  c ON c.id_tenant = a.id_tenant AND c.id_canal = a.id_canal
         WHERE a.id_tenant   = NEW.id_tenant
           AND a.id_variacao = NEW.id_variacao
           AND c.id_empresa  = NEW.id_empresa
           AND c.status      = 'CONECTADO') THEN
    RETURN NEW;
  END IF;

  INSERT INTO outbox_evento (id_tenant, tipo, agregado_id, payload)
  VALUES (NEW.id_tenant, 'ESTOQUE_ATUALIZADO',
          NEW.id_variacao || ':' || NEW.id_empresa,
          jsonb_build_object('idVariacao', NEW.id_variacao, 'idEmpresa', NEW.id_empresa));

  RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_enfileira_sincronizacao_estoque() IS
  'Enfileira ESTOQUE_ATUALIZADO na MESMA transacao que mexeu no saldo (outbox pattern, P2). '
  'Mora no banco porque sao 8+ rotinas que mexem em estoque e a que ficasse de fora viraria '
  'anuncio mentindo saldo, em silencio. Ver V067.';

CREATE TRIGGER tg_enfileira_sincronizacao_estoque
  AFTER INSERT OR UPDATE ON produto_estoque
  FOR EACH ROW EXECUTE FUNCTION fn_enfileira_sincronizacao_estoque();

-- --------------------------------------------------------------------------------------------
-- Preço: o gatilho só AVISA; quem calcula é o Java.
--
-- ⭐ Decisão que vale explicar: seria fácil recalcular `anuncio.preco` aqui mesmo, em SQL. Não
-- fazemos. A regra do preço do canal (`PrecoDoCanal`) tem arredondamento HALF_UP, arredonda UMA
-- vez no fim, e recusa preço nulo — escrevê-la de novo em plpgsql criaria duas implementações da
-- mesma regra de dinheiro (P7), que divergem no dia em que só uma for corrigida. O gatilho
-- enfileira; o manipulador em Java recalcula com a única implementação que existe.
--
-- ⚠️ E só avisa quando `preco_venda` muda. `preco_oferta` NÃO dispara nada, de propósito: o preço
-- do anúncio ignora a oferta da loja (decisão do dono do produto, §8.6). Promoção de fim de semana
-- no balcão não pode derrubar o preço no marketplace.

CREATE OR REPLACE FUNCTION fn_enfileira_sincronizacao_preco() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.preco_venda IS NOT DISTINCT FROM OLD.preco_venda THEN
    RETURN NEW;
  END IF;

  -- Só interessa se existe anúncio DERIVADO (preco_manual = false) num canal conectado: preço
  -- digitado pelo lojista não acompanha reajuste, e reenviá-lo seria desfazer a decisão dele.
  IF NOT EXISTS (
        SELECT 1
          FROM anuncio a
          JOIN produto_barra pb ON pb.id_tenant = a.id_tenant AND pb.id_variacao = a.id_variacao
          JOIN canal c          ON c.id_tenant = a.id_tenant AND c.id_canal = a.id_canal
         WHERE a.id_tenant     = NEW.id_tenant
           AND pb.id_produto   = NEW.id_produto
           AND a.preco_manual  = false
           AND c.status        = 'CONECTADO') THEN
    RETURN NEW;
  END IF;

  INSERT INTO outbox_evento (id_tenant, tipo, agregado_id, payload)
  VALUES (NEW.id_tenant, 'PRECO_ATUALIZADO', NEW.id_produto::text,
          jsonb_build_object('idProduto', NEW.id_produto));

  RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_enfileira_sincronizacao_preco() IS
  'Enfileira PRECO_ATUALIZADO quando produto.preco_venda muda. NAO recalcula o preco aqui: a regra '
  'do canal (PrecoDoCanal) mora no Java e duplica-la em plpgsql criaria duas versoes da mesma '
  'regra de dinheiro. preco_oferta nao dispara nada, de proposito (§8.6). Ver V067.';

CREATE TRIGGER tg_enfileira_sincronizacao_preco
  AFTER UPDATE ON produto
  FOR EACH ROW EXECUTE FUNCTION fn_enfileira_sincronizacao_preco();

-- Índice que o EXISTS do gatilho de estoque usa para sair barato.
CREATE INDEX IF NOT EXISTS anuncio_variacao_canal_ix ON anuncio (id_tenant, id_variacao, id_canal);
