-- V068 — o M5: receber a notificação de pedido do marketplace.
--
-- ============================================================================================
-- 1. `plataforma.canal_externo` — de quem é a notificação que acabou de chegar
-- ============================================================================================
--
-- ⭐ Mesmo problema do `state` do OAuth (V065), com a mesma forma de solução — e vale registrar
-- que ele volta sempre que algo de fora bate na porta: **o webhook chega sem JWT e sem
-- TenantContext**. O corpo do Mercado Livre traz o `user_id` do VENDEDOR, e é só isso.
--
-- Descobrir de qual tenant é esse vendedor exigiria varrer `canal` — que tem RLS, e devolveria
-- **zero linha em silêncio** (é o defeito que deixou os jobs do fiscal inertes até 2026-08-19).
-- Fazer um laço por todos os tenants a cada notificação resolveria, mas custa uma varredura por
-- webhook e cresce com a base de clientes.
--
-- Este mapa é global (sem RLS) e responde em um índice: (tipo, conta_externa) → (tenant, canal).
--
-- ⚠️ NÃO guarda segredo nenhum. `conta_externa` é o id PÚBLICO do vendedor no marketplace — o
-- mesmo que já aparece na tela de Canais. O token continua cifrado em `canal.credenciais`, sob
-- RLS, onde sempre esteve.
--
-- ⚠️ UNIQUE por (tipo, conta_externa): a mesma conta de Mercado Livre não pode estar conectada
-- em dois tenants ao mesmo tempo. Se estivesse, uma notificação de venda seria ambígua — e o
-- palpite erraria metade das vezes, importando o pedido de um lojista na loja de outro (P8).

CREATE TABLE plataforma.canal_externo (
  tipo          text        NOT NULL,
  conta_externa text        NOT NULL,
  id_tenant     smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_canal      integer     NOT NULL,
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT canal_externo_pk PRIMARY KEY (tipo, conta_externa)
);

CREATE INDEX canal_externo_canal_ix ON plataforma.canal_externo (id_tenant, id_canal);

COMMENT ON TABLE plataforma.canal_externo IS
  'Mapa (marketplace, id do vendedor la) -> (tenant, canal). Existe porque o webhook chega SEM '
  'TenantContext e precisa descobrir de quem e a notificacao; `canal` tem RLS e devolveria zero '
  'linha em silencio. Sem RLS de proposito, e sem segredo nenhum: conta_externa e id publico. '
  'Escrito/apagado junto com canal.credenciais. Ver V068 e MODULOMARKETPLACE §2.3.';

GRANT SELECT, INSERT, UPDATE, DELETE ON plataforma.canal_externo TO niner_app;

-- ============================================================================================
-- 2. `webhook_recebido` precisa saber O QUE foi notificado
-- ============================================================================================
--
-- A tabela (V022) guardava só o id do evento — bastava para não processar duas vezes, mas não
-- para processar UMA. O Mercado Livre manda `topic` (orders_v2, items, shipments) e `resource`
-- (`/orders/2000003508897546`), e é o `resource` que diz qual pedido buscar.
--
-- ⚠️ O corpo cru vai junto (`payload`), e não é luxo: quando a primeira notificação real chegar
-- num formato diferente do que a documentação descreve — e vai, é o precedente do XSD que passou
-- e da SEFAZ que recusou —, é o único lugar onde dá para ver o que de fato veio.

ALTER TABLE webhook_recebido ADD COLUMN topico  text;
ALTER TABLE webhook_recebido ADD COLUMN recurso text;
ALTER TABLE webhook_recebido ADD COLUMN payload jsonb;
ALTER TABLE webhook_recebido ADD COLUMN tentativas integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN webhook_recebido.recurso IS
  'Caminho do recurso no canal (ex.: /orders/2000003508897546). E por ele que o worker sabe o que '
  'buscar — o webhook NAO decide nada e nao confia no corpo: ele consulta a API do canal (P2).';

COMMENT ON COLUMN webhook_recebido.tentativas IS
  'Quantas vezes o worker tentou processar. Serve para parar de insistir num recurso que o canal '
  'nunca devolve, em vez de girar para sempre.';

-- Fila do worker: o que chegou e ainda não foi processado, mais antigo primeiro.
CREATE INDEX webhook_recebido_pendente_ix
  ON webhook_recebido (id_tenant, recebido_em)
  WHERE processado_em IS NULL;
