-- V054 — "Permite Quantidade de Estoque Negativo?" (2026-08-20).
--
-- Até aqui o Nainer NUNCA bloqueava movimentação por saldo insuficiente: era política do sistema,
-- e a razão era boa — no PDV o produto está na mão do operador, e travar a venda por causa de um
-- cadastro atrasado custaria a venda de algo que existe. A partir daqui isso vira **escolha do
-- lojista**, por tenant, com a trava desligada por padrão (`false` = não permite negativo), que é
-- o comportamento pedido pelo dono do produto.
--
-- POR QUE A REGRA MORA NA TRIGGER, E NÃO EM CADA SERVIÇO
-- O pedido foi "todas as rotinas que debitam estoque — vendas, transferências, devolução ao
-- fornecedor, e etc". O "e etc" é o problema: hoje debitam estoque o PDV, a transferência, a
-- devolução ao fornecedor, o cancelamento de entrada, o cancelamento de devolução de venda e o
-- balanço — e amanhã alguma rotina nova. Espalhar a checagem por serviço garante que uma delas
-- fique de fora, hoje ou daqui a seis meses.
--
-- `produto_movimento_detalhe` é o **único** caminho pelo qual `produto_estoque` se mexe: quem
-- passa por aqui é toda rotina que existe e toda que vier. É o mesmo raciocínio do RLS — a regra
-- que não pode falhar mora no banco.
--
-- ⚠️ O saldo é por (tenant, EMPRESA, variação), como o pedido diz: "e este estoque é por empresa".
-- Transferir 5 de uma empresa que tem 3 continua sendo bloqueado mesmo que a outra empresa tenha
-- 100.

-- ---------------------------------------------------------------------------------------------
-- 1. O parâmetro
-- ---------------------------------------------------------------------------------------------
ALTER TABLE cfg_geral
  ADD COLUMN IF NOT EXISTS cfg_permite_estoque_negativo boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN cfg_geral.cfg_permite_estoque_negativo IS
  'Ligado: movimentacao pode deixar produto_estoque.qtd_estoque negativo (comportamento do sistema ate 2026-08-20). Desligado (padrao): a trigger fn_atualiza_estoque_movimento recusa o debito. Vale por EMPRESA, nao por tenant.';

-- ---------------------------------------------------------------------------------------------
-- 2. Aplicar o delta no saldo — extraído da trigger, que fazia o mesmo UPSERT em 4 lugares
-- ---------------------------------------------------------------------------------------------
-- SECURITY INVOKER (padrão): a política RLS de produto_estoque continua valendo, exatamente como
-- valia quando o UPSERT estava inline.
CREATE OR REPLACE FUNCTION fn_aplica_delta_estoque(
    p_tenant integer, p_empresa integer, p_variacao integer, p_delta numeric)
RETURNS numeric
LANGUAGE plpgsql
AS $$
DECLARE
    v_saldo numeric(14,3);
BEGIN
    INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque)
    VALUES (p_tenant, p_empresa, p_variacao, p_delta)
    ON CONFLICT (id_tenant, id_empresa, id_variacao)
    DO UPDATE
       SET qtd_estoque   = produto_estoque.qtd_estoque + EXCLUDED.qtd_estoque,
           atualizado_em = now()
    RETURNING qtd_estoque INTO v_saldo;

    RETURN v_saldo;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- 3. A regra
-- ---------------------------------------------------------------------------------------------
-- ⚠️ O caminho comum não custa nada: saldo >= 0 sai na primeira linha, sem ler `cfg_geral` nem
-- montar mensagem. A configuração só é consultada quando o saldo JÁ ficou negativo — ou seja, uma
-- importação de 10 mil linhas de crédito não paga por esta regra.
CREATE OR REPLACE FUNCTION fn_exige_estoque_nao_negativo(
    p_tenant integer, p_empresa integer, p_variacao integer, p_saldo numeric, p_qtd numeric)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_permite  boolean;
    v_produto  text;
    v_empresa  text;
    v_antes    numeric(14,3);
BEGIN
    IF p_saldo >= 0 THEN
        RETURN;
    END IF;

    -- Sem linha em cfg_geral, bloqueia: é o mesmo default da coluna, e o lado seguro do erro.
    SELECT COALESCE(cfg_permite_estoque_negativo, false) INTO v_permite
      FROM cfg_geral WHERE id_tenant = p_tenant;
    IF COALESCE(v_permite, false) THEN
        RETURN;
    END IF;

    -- Daqui para baixo é caminho de falha: pode custar consulta, ninguém paga por isso no fluxo
    -- normal. A mensagem precisa dizer QUAL produto e QUAL empresa — um erro de "estoque
    -- insuficiente" sem o item obriga o operador a caçar linha por linha.
    SELECT COALESCE(p.descricao, 'produto ' || p_variacao)
           || COALESCE(' ' || NULLIF(co.descricao, ''), '')
           || COALESCE(' ' || NULLIF(ta.descricao, ''), '')
           || COALESCE(' (' || pb.sku || ')', '')
      INTO v_produto
      FROM produto_barra pb
      LEFT JOIN produto p  ON p.id_tenant  = pb.id_tenant AND p.id_produto  = pb.id_produto
      LEFT JOIN cfg_cor co ON co.id_tenant = pb.id_tenant AND co.id_cor     = pb.id_cor AND co.id_cor <> 1
      LEFT JOIN cfg_tamanho ta ON ta.id_tenant = pb.id_tenant AND ta.id_tamanho = pb.id_tamanho AND ta.id_tamanho <> 1
     WHERE pb.id_tenant = p_tenant AND pb.id_variacao = p_variacao;

    SELECT COALESCE(NULLIF(e.nome_fantasia, ''), e.razao_social)
      INTO v_empresa
      FROM empresa e WHERE e.id_tenant = p_tenant AND e.id_empresa = p_empresa;

    v_antes := p_saldo + p_qtd;

    -- ERRCODE 23514 (check_violation) para o driver classificar como violação de integridade; o
    -- prefixo ESTOQUE_NEGATIVO| é o que faz `GlobalExceptionHandler` devolver ESTA mensagem em vez
    -- da genérica de FK ("registro em uso por outro cadastro"), que não teria nada a ver.
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = format(
            'ESTOQUE_NEGATIVO|Estoque insuficiente de %s em %s: há %s e a operação precisa de %s. '
            || 'Para permitir saldo negativo, ligue "Permite quantidade de estoque negativo" em '
            || 'Parâmetros do Sistema → Estoque.',
            COALESCE(v_produto, 'produto ' || p_variacao),
            COALESCE(v_empresa, 'empresa ' || p_empresa),
            trim(trailing '.' from to_char(v_antes, 'FM999999990.999')),
            trim(trailing '.' from to_char(p_qtd,  'FM999999990.999')));
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- 4. A trigger, agora conferindo
-- ---------------------------------------------------------------------------------------------
-- Mesma mecânica de V019 (C soma, D subtrai; UPDATE desfaz o efeito antigo e aplica o novo;
-- DELETE desfaz) — o que mudou é que o UPSERT saiu para `fn_aplica_delta_estoque` e cada saldo
-- final passa por `fn_exige_estoque_nao_negativo`.
CREATE OR REPLACE FUNCTION fn_atualiza_estoque_movimento()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_saldo     numeric(14,3);
    v_saldo_old numeric(14,3);
BEGIN

    IF TG_OP = 'INSERT' THEN

        v_saldo := fn_aplica_delta_estoque(NEW.id_tenant, NEW.id_empresa, NEW.id_variacao,
                       CASE WHEN NEW.credito_debito = 'C' THEN NEW.qtd_produto ELSE -NEW.qtd_produto END);
        PERFORM fn_exige_estoque_nao_negativo(NEW.id_tenant, NEW.id_empresa, NEW.id_variacao,
                    v_saldo, NEW.qtd_produto);
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        -- desfaz o efeito da linha antiga
        v_saldo_old := fn_aplica_delta_estoque(OLD.id_tenant, OLD.id_empresa, OLD.id_variacao,
                           CASE WHEN OLD.credito_debito = 'C' THEN -OLD.qtd_produto ELSE OLD.qtd_produto END);

        -- aplica o efeito da linha nova
        v_saldo := fn_aplica_delta_estoque(NEW.id_tenant, NEW.id_empresa, NEW.id_variacao,
                       CASE WHEN NEW.credito_debito = 'C' THEN NEW.qtd_produto ELSE -NEW.qtd_produto END);

        -- ⚠️ A conferência vem DEPOIS dos dois passos, nunca no meio: desfazer um crédito derruba
        -- o saldo momentaneamente e o passo seguinte o recompõe. Corrigir a quantidade de um item
        -- de entrada de 10 para 12, com 5 já vendidos, passa por -5 no caminho e termina em 7 —
        -- conferir no meio reprovaria uma correção perfeitamente válida.
        IF (OLD.id_tenant, OLD.id_empresa, OLD.id_variacao)
             IS DISTINCT FROM (NEW.id_tenant, NEW.id_empresa, NEW.id_variacao) THEN
            -- Chaves diferentes: o saldo da antiga já é final, nenhum passo posterior a toca.
            PERFORM fn_exige_estoque_nao_negativo(OLD.id_tenant, OLD.id_empresa, OLD.id_variacao,
                        v_saldo_old, OLD.qtd_produto);
        END IF;
        PERFORM fn_exige_estoque_nao_negativo(NEW.id_tenant, NEW.id_empresa, NEW.id_variacao,
                    v_saldo, NEW.qtd_produto);
        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        v_saldo := fn_aplica_delta_estoque(OLD.id_tenant, OLD.id_empresa, OLD.id_variacao,
                       CASE WHEN OLD.credito_debito = 'C' THEN -OLD.qtd_produto ELSE OLD.qtd_produto END);
        -- Apagar um CRÉDITO tira estoque (é o caso do Cancelamento de Entrada) — e aí a regra vale
        -- igual. Apagar um DÉBITO devolve estoque e nunca reprova.
        PERFORM fn_exige_estoque_nao_negativo(OLD.id_tenant, OLD.id_empresa, OLD.id_variacao,
                    v_saldo, OLD.qtd_produto);
        RETURN OLD;

    END IF;

    RETURN NULL;

END;
$$;

-- `niner_app` executa as duas funções pela trigger; sem EXECUTE explícito a movimentação inteira
-- para. Mesmo cuidado da V050 com `prefixos_ean_reservados()`.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_app') THEN
    GRANT EXECUTE ON FUNCTION fn_aplica_delta_estoque(integer, integer, integer, numeric) TO niner_app;
    GRANT EXECUTE ON FUNCTION fn_exige_estoque_nao_negativo(integer, integer, integer, numeric, numeric) TO niner_app;
  END IF;
END $$;
