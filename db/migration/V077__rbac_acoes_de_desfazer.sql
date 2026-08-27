-- V077 — Telas cuja ação "excluir" é DESFAZER (2026-08-27)
--
-- A V076 mediu as ações pelo método HTTP que a tela chama, e por isso marcou como "somente
-- acessar" telas cuja única operação é um POST de desfazer — cancelar venda, estornar crediário,
-- reabrir caixa. Agora que esses métodos declaram `@Acao(EXCLUIR)` no controller, a tela precisa
-- oferecer a caixa correspondente.
--
-- ⚠️ SEM ISTO A PERMISSÃO FICA IMPOSSÍVEL DE CONCEDER: o interceptor exigiria "excluir", a grade
-- não mostraria a caixa (tem_excluir = false) e o servidor descartaria a concessão vinda pela API.
-- O operador receberia 403 numa tela que o administrador jura ter liberado — e não haveria como
-- descobrir por quê olhando a tela de permissões. É o tipo de defeito que só aparece em uso.

UPDATE cfg_tela SET tem_excluir = true
 WHERE chave IN (
   'pesquisa-vendas',                  -- cancelar venda acontece aqui dentro
   'cancelamento-devolucao-produtos',
   'entrada-produtos-compra',          -- cancelar entrada
   'estoque.devolucao-compra',         -- cancelar devolução ao fornecedor
   'fechamento-caixa',                 -- reabrir caixa
   'recebimento-crediario',            -- estornar recebimento
   'estoque.contagem'                  -- desfazer/zerar contagem
 );

-- ⛔ NENHUM BACKFILL DE PERMISSÃO, e isto é decisão, não esquecimento.
--
-- Ligar a trava faz todo usuário sem grade receber 403 — e nenhuma grade existe hoje. Em um
-- sistema com clientes, isso derrubaria todos os operadores de uma vez, e a migration teria de
-- conceder a eles tudo o que já podiam fazer.
--
-- O Nainer está em HOMOLOGAÇÃO: publicado no VPS, sem nenhum cliente real (reafirmado pelo dono
-- do produto em 2026-08-27). Sem usuário existente, backfill seria trabalho inventado — e pior,
-- deixaria o comportamento de estreia diferente do definitivo, escondendo justamente o que ele
-- quer testar: usuário novo não enxerga nada até ser liberado.
--
-- ⚠️ NO DIA DA PRODUÇÃO isto muda: se já houver operadores em uso quando esta trava subir para
-- valer, é obrigatório conceder a grade cheia a eles ANTES, senão perdem o acesso sem aviso.
