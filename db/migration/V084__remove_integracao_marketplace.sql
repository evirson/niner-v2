-- V084 — remove a implementação da integração com marketplaces (Mercado Livre).
--
-- Decisão do dono do produto em 2026-08-28: *"vamos mudar o projeto de integração com
-- marketplaces, então preciso que você remova tudo o que foi feito pra integração com o Mercado
-- Livre; a integração vai ficar em implementações futuras"*.
--
-- O que esta migration desfaz: **exatamente** o que as V063–V070 acrescentaram (blocos M0–M7,
-- feitos em 2026-08-26). Nada além disso.
--
-- ⚠️ O que ela deliberadamente NÃO remove, e por quê:
--
--  1. As tabelas `canal`, `anuncio`, `pedido`, `pedido_item`, `outbox_evento` e
--     `webhook_recebido` — são de **V020–V022 (2026-07-16)**, parte do desenho original da spec
--     (§3.3.5/§3.3.6), criadas meses antes da integração e dormentes até 26/08. Voltam ao estado
--     em que estavam: vazias e sem nenhum código apontando para elas. Apagá-las destruiria
--     schema que a spec prevê e que terá de ser recriado quando a integração voltar.
--
--  2. O valor `MARKETPLACE` do enum `origem_venda` (V063) — o Postgres não remove valor de enum
--     sem recriar o tipo inteiro, que `venda.origem` usa. Medido antes de decidir: **nenhuma**
--     venda com essa origem (344 vendas, todas PDV). O valor fica inerte; nada o emite mais.
--     Recriar o tipo custaria reescrever a coluna de uma tabela de venda por um ganho nulo.
--
--  3. Os tipos `tipo_canal`, `status_sync` e `status_pedido` (V013) — mesma razão do item 1.
--
-- ⚠️ Ordem: primeiro as TRIGGERS (o que toca caminho quente de estoque/preço), depois o resto.
--    Quem lê estoque e preço não pode continuar pagando por um gatilho de integração que já não
--    tem para onde enfileirar.

-- ---------------------------------------------------------------------------------------------
-- 1. Gatilhos de sincronização (V067) — os que rodavam em TODA gravação de estoque e de preço
-- ---------------------------------------------------------------------------------------------

DROP TRIGGER  IF EXISTS tg_enfileira_sincronizacao_estoque ON produto_estoque;
DROP TRIGGER  IF EXISTS tg_enfileira_sincronizacao_preco   ON produto;
DROP FUNCTION IF EXISTS fn_enfileira_sincronizacao_estoque();
DROP FUNCTION IF EXISTS fn_enfileira_sincronizacao_preco();

-- Função de arbitragem de status do pedido (V070).
DROP FUNCTION IF EXISTS fn_status_pedido_do_canal(status_pedido, status_pedido);

-- ---------------------------------------------------------------------------------------------
-- 2. Tabelas criadas exclusivamente para a integração (V065, V068)
-- ---------------------------------------------------------------------------------------------

DROP TABLE IF EXISTS plataforma.oauth_estado_canal;   -- `state` do OAuth do canal (M1)
DROP TABLE IF EXISTS plataforma.canal_externo;        -- mapa (marketplace, vendedor) -> tenant (M5)

-- ---------------------------------------------------------------------------------------------
-- 3. Colunas e índices acrescentados a tabelas que já existiam
-- ---------------------------------------------------------------------------------------------

-- V064 — preço por canal
ALTER TABLE canal   DROP COLUMN IF EXISTS perc_preco;      -- leva junto canal_perc_preco_ck
ALTER TABLE anuncio DROP COLUMN IF EXISTS preco_manual;

-- V066 — variação do anúncio no canal. A UNIQUE volta a ser a de V020: (id_canal, id_externo).
DROP INDEX IF EXISTS anuncio_canal_variacao_erp_uk;
ALTER TABLE anuncio DROP CONSTRAINT IF EXISTS anuncio_canal_externo_uk;
ALTER TABLE anuncio DROP COLUMN IF EXISTS id_externo_variacao;
ALTER TABLE anuncio
  ADD CONSTRAINT anuncio_canal_externo_uk UNIQUE (id_canal, id_externo);

-- V067 — empresa de origem do estoque publicado + índice de apoio do gatilho
DROP INDEX IF EXISTS anuncio_variacao_canal_ix;
ALTER TABLE canal DROP COLUMN IF EXISTS id_empresa;        -- leva junto canal_empresa_fk

-- V068 — colunas de webhook de canal
DROP INDEX IF EXISTS webhook_recebido_pendente_ix;
ALTER TABLE webhook_recebido DROP COLUMN IF EXISTS topico;
ALTER TABLE webhook_recebido DROP COLUMN IF EXISTS recurso;
ALTER TABLE webhook_recebido DROP COLUMN IF EXISTS payload;
ALTER TABLE webhook_recebido DROP COLUMN IF EXISTS tentativas;

-- V069 — pedido vira venda
DROP INDEX IF EXISTS pedido_venda_ix;
ALTER TABLE pedido DROP COLUMN IF EXISTS id_venda;         -- leva junto pedido_venda_fk
ALTER TABLE pedido DROP COLUMN IF EXISTS estoque_reservado;
ALTER TABLE canal  DROP COLUMN IF EXISTS id_carteira;      -- leva junto canal_carteira_fk

-- V070 — fila de expedição
DROP INDEX IF EXISTS pedido_fila_expedicao_ix;
ALTER TABLE pedido DROP COLUMN IF EXISTS data_separacao;
ALTER TABLE pedido DROP COLUMN IF EXISTS data_envio;
ALTER TABLE pedido DROP COLUMN IF EXISTS codigo_rastreio;
ALTER TABLE pedido DROP COLUMN IF EXISTS id_usuario_expedicao;  -- leva pedido_usuario_expedicao_fk

-- ---------------------------------------------------------------------------------------------
-- 4. Catálogo de telas (RBAC, V073–V078)
-- ---------------------------------------------------------------------------------------------
-- As telas somem do produto, então saem do catálogo. `usuario_permissao` tem ON DELETE CASCADE,
-- então concessões já feitas somem junto — é o certo: permissão para tela inexistente é grade
-- oferecendo o que o sistema não tem.
--
-- ⚠️ Isto é obrigatório, não cosmético: `AcoesPorTelaConferemTest` varre os controllers e compara
-- com `cfg_tela`; tela catalogada sem controller reprova o build (e é justamente a regra que
-- pegou o caixa travado da V076).

DELETE FROM cfg_tela WHERE chave IN ('canais', 'expedicao');

COMMENT ON TABLE canal IS
  'Canal de venda (marketplace). Tabela do desenho original (V020) — a implementação da '
  'integração com o Mercado Livre foi REMOVIDA em 2026-08-28 (V084) e voltou para Implementações '
  'Futuras. Nenhum código escreve aqui hoje. Ver docs/MODULOMARKETPLACE.md.';
