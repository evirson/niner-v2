-- V086 — serviço não mexe no estoque (bloco S1, DS2 de docs/MODULOSERVICOS.md)
--
-- ⭐ POR QUE ISTO MORA NA TRIGGER E NÃO NOS SERVIÇOS JAVA
--
-- É a mesma decisão da V054 (`cfg_permite_estoque_negativo`) e pelo mesmo motivo, escrito no
-- CLAUDE.md: mexem em estoque **oito rotinas** — PDV, transferência, devolução ao fornecedor,
-- devolução de venda, **cancelamento de entrada** (de que ninguém lembra), cancelamento de
-- devolução, balanço e importação — mais o que vier. Espalhar a checagem por serviço garante
-- **matematicamente** que uma fique de fora, e a que ficar de fora cria saldo de estoque para um
-- banho de cachorro, em silêncio.
--
-- `produto_movimento_detalhe` é o ÚNICO caminho por onde `produto_estoque` se mexe.
--
-- ⚠️ O QUE MUDA E O QUE NÃO MUDA
--
-- O movimento CONTINUA sendo gravado — é ele que faz o item existir para a DRE, a Lucratividade, as
-- Comissões, o Relatório de Vendas, a papeleta e o histórico do cliente (§3.4 do estudo: 33 leitores
-- dependem disso). O que não acontece é o efeito em `produto_estoque`: nem UPSERT, nem saldo, nem a
-- checagem de negativo.
--
-- ⚠️ Os TRÊS ramos precisam do curto-circuito. Só o INSERT deixaria o DELETE de um item de serviço
-- de venda cancelada **creditar estoque de um serviço** — linha que nasceria do nada, com saldo
-- positivo, num item que nunca teve saldo.
--
-- ⚠️ Custo no caminho quente: ZERO consulta nova. O tipo vem de `produto_barra.tipo_item`, coluna
-- denormalizada na V085 exatamente para isto — a trigger só tem `id_variacao` em mãos, e um JOIN
-- com `produto` aqui seria pago por toda importação de 10 mil linhas.

CREATE OR REPLACE FUNCTION fn_item_e_servico(p_tenant smallint, p_variacao integer)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT EXISTS (SELECT 1
                   FROM produto_barra pb
                  WHERE pb.id_tenant   = p_tenant
                    AND pb.id_variacao = p_variacao
                    AND pb.tipo_item   = 'SERVICO');
$$;

COMMENT ON FUNCTION fn_item_e_servico(smallint, integer) IS
  'A variação é de serviço? Lê a coluna denormalizada de produto_barra (V085) — sem JOIN, porque '
  'roda no caminho mais quente do sistema. Usada pela trigger de estoque para não dar saldo a quem '
  'não tem saldo. Ver docs/MODULOSERVICOS.md §3.5.';

CREATE OR REPLACE FUNCTION fn_atualiza_estoque_movimento()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_saldo     numeric(14,3);
    v_saldo_old numeric(14,3);
BEGIN

    IF TG_OP = 'INSERT' THEN

        -- V086: serviço não tem saldo. Sai antes do UPSERT, então nem linha em produto_estoque
        -- chega a nascer.
        IF fn_item_e_servico(NEW.id_tenant, NEW.id_variacao) THEN
            RETURN NEW;
        END IF;

        v_saldo := fn_aplica_delta_estoque(NEW.id_tenant, NEW.id_empresa, NEW.id_variacao,
                       CASE WHEN NEW.credito_debito = 'C' THEN NEW.qtd_produto ELSE -NEW.qtd_produto END);
        PERFORM fn_exige_estoque_nao_negativo(NEW.id_tenant, NEW.id_empresa, NEW.id_variacao,
                    v_saldo, NEW.qtd_produto);
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        -- ⚠️ Aqui a pergunta é feita para as DUAS pontas, e não uma vez só: a linha pode ter mudado
        -- de variação. Como o tipo é imutável (V085) mas a VARIAÇÃO da linha pode mudar, o par
        -- (serviço → mercadoria) é possível numa correção de item, e cada lado tem de ser tratado
        -- com a sua própria natureza.
        IF NOT fn_item_e_servico(OLD.id_tenant, OLD.id_variacao) THEN
            -- desfaz o efeito da linha antiga
            v_saldo_old := fn_aplica_delta_estoque(OLD.id_tenant, OLD.id_empresa, OLD.id_variacao,
                               CASE WHEN OLD.credito_debito = 'C' THEN -OLD.qtd_produto ELSE OLD.qtd_produto END);
        END IF;

        IF NOT fn_item_e_servico(NEW.id_tenant, NEW.id_variacao) THEN
            -- aplica o efeito da linha nova
            v_saldo := fn_aplica_delta_estoque(NEW.id_tenant, NEW.id_empresa, NEW.id_variacao,
                           CASE WHEN NEW.credito_debito = 'C' THEN NEW.qtd_produto ELSE -NEW.qtd_produto END);
        END IF;

        -- ⚠️ A conferência vem DEPOIS dos dois passos, nunca no meio: desfazer um crédito derruba
        -- o saldo momentaneamente e o passo seguinte o recompõe. Corrigir a quantidade de um item
        -- de entrada de 10 para 12, com 5 já vendidos, passa por -5 no caminho e termina em 7 —
        -- conferir no meio reprovaria uma correção perfeitamente válida. (Regra da V054, intacta.)
        IF (OLD.id_tenant, OLD.id_empresa, OLD.id_variacao)
             IS DISTINCT FROM (NEW.id_tenant, NEW.id_empresa, NEW.id_variacao)
           AND v_saldo_old IS NOT NULL THEN
            -- Chaves diferentes: o saldo da antiga já é final, nenhum passo posterior a toca.
            PERFORM fn_exige_estoque_nao_negativo(OLD.id_tenant, OLD.id_empresa, OLD.id_variacao,
                        v_saldo_old, OLD.qtd_produto);
        END IF;
        IF v_saldo IS NOT NULL THEN
            PERFORM fn_exige_estoque_nao_negativo(NEW.id_tenant, NEW.id_empresa, NEW.id_variacao,
                        v_saldo, NEW.qtd_produto);
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        -- ⚠️ Sem este ramo, apagar o item de serviço de uma venda cancelada CREDITARIA estoque de um
        -- serviço — criando a linha em produto_estoque que a venda nunca criou.
        IF fn_item_e_servico(OLD.id_tenant, OLD.id_variacao) THEN
            RETURN OLD;
        END IF;

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

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_app') THEN
    EXECUTE 'GRANT EXECUTE ON FUNCTION fn_item_e_servico(smallint, integer) TO niner_app';
  END IF;
END $$;
