-- V019 — estoque (§3.3.4): saldo materializado, ledger e balanço.
-- P1: o ERP é a fonte da verdade do estoque. P3: produto_movimento_mestre é imutável;
-- produto_movimento_detalhe é corrigível desde 2026-07-16 (sem saldo_apos por linha; saldo
-- resultante fica só em produto_estoque, materializado e mantido por trigger).
-- Q2/ADR-004: `reservado` sobe no RECEBIDO do pedido; `disponivel` = qtd_estoque − reservado.
-- 2026-07-16: decisão fechada — quem mantém produto_estoque.qtd_estoque é a trigger
-- trg_produto_movimento_detalhe_estoque (fim deste arquivo), acionada por INSERT/UPDATE/DELETE
-- em produto_movimento_detalhe. Revisa a orientação anterior ("domínio Java, não trigger");
-- não existe mais SP_ATUALIZA_QUANTIDADE_ESTOQUE separada — a lógica está toda na trigger.

-- Saldo por variação × empresa.
CREATE TABLE produto_estoque (
  id_produto_estoque integer        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint       NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa         integer        NOT NULL,
  id_variacao        integer        NOT NULL,
  qtd_estoque        numeric(14,3)  NOT NULL DEFAULT 0,
  reservado          numeric(14,3)  NOT NULL DEFAULT 0,      -- Q2/ADR-004 (P1 anti-overselling)
  minimo             numeric(14,3)  NOT NULL DEFAULT 0,      -- alerta de estoque mínimo
  disponivel         numeric(14,3)  GENERATED ALWAYS AS (qtd_estoque - reservado) STORED,
  atualizado_em      timestamptz    NOT NULL DEFAULT now(),
  CONSTRAINT produto_estoque_uk UNIQUE (id_tenant, id_empresa, id_variacao),
  CONSTRAINT produto_estoque_reservado_ck CHECK (reservado >= 0),
  -- FKs compostas (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015). A trigger
  -- fn_atualiza_estoque_movimento faz UPSERT nesta tabela usando id_tenant/id_empresa/
  -- id_variacao vindos de produto_movimento_detalhe; sem essas FKs um movimento forjado com
  -- id_tenant de um tenant e id_empresa/id_variacao de outro criaria estoque "fantasma".
  CONSTRAINT produto_estoque_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT produto_estoque_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao)
);
CREATE INDEX produto_estoque_id_tenant_ix ON produto_estoque (id_tenant);
CREATE INDEX produto_estoque_variacao_ix  ON produto_estoque (id_tenant, id_variacao);

-- Ledger de movimentação — mestre (imutável, P3).
CREATE TABLE produto_movimento_mestre (
  id_movimento     integer        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant        smallint       NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa       integer        NOT NULL,
  tipo_movimento   tipo_movimento NOT NULL,
  data_movimento   timestamptz    NOT NULL DEFAULT now(),
  id_fornecedor    integer,
  id_venda         integer,
  id_transferencia integer,                     -- numero vindo de um gerador externo; sem FK (proposital)
  id_devolucao     integer,
  nota_fiscal      integer,                     -- numero da NF; integer (padronizado 2026-07-16, ver V026)
  id_usuario       integer,                     -- 2026-08-11 (Entrada de Produtos): quem confirmou o movimento;
                                                 -- nullable porque nenhum outro fluxo grava isso ainda
  chave_nfe        text,                        -- 2026-08-11: chave de acesso da NF-e (44 digitos), so' preenchida
                                                 -- na Entrada via XML; idempotencia do import (P2, ver UK abaixo)
  serie_nota       smallint,                    -- 2026-08-11: serie da NF, so' preenchida na Entrada via XML
  -- Cancelamento de Entrada (2026-08-12) — mesmo padrão de venda.cancelada/venda_devolucao.cancelada:
  -- colunas direto aqui (quem/quando/motivo, P3) em vez de tabela de log separada; este registro
  -- em si NUNCA é apagado nem tem os itens tocados — o estorno de estoque é um novo
  -- produto_movimento_mestre (tipo_movimento='CANCELAMENTO'), o resto do rastro de auditoria.
  -- Só usado hoje por tipo_movimento='COMPRA' (Entrada de Produtos por Compra).
  cancelado                boolean     NOT NULL DEFAULT false,
  data_cancelamento        timestamptz,
  id_usuario_cancelamento  integer,
  motivo_cancelamento      text,
  -- base para FK composta (2026-07-16, P8) de produto_movimento_detalhe.id_movimento.
  CONSTRAINT produto_movimento_mestre_id_uk UNIQUE (id_tenant, id_movimento),
  -- FKs compostas — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT produto_movimento_mestre_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT produto_movimento_mestre_fornecedor_fk FOREIGN KEY (id_tenant, id_fornecedor)
    REFERENCES fornecedor (id_tenant, id_fornecedor),
  CONSTRAINT produto_movimento_mestre_venda_fk FOREIGN KEY (id_tenant, id_venda)
    REFERENCES venda (id_tenant, id_venda),
  CONSTRAINT produto_movimento_mestre_devolucao_fk FOREIGN KEY (id_tenant, id_devolucao)
    REFERENCES venda_devolucao (id_tenant, id_devolucao),
  CONSTRAINT produto_movimento_mestre_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario),
  CONSTRAINT produto_movimento_mestre_usuario_cancelamento_fk FOREIGN KEY (id_tenant, id_usuario_cancelamento)
    REFERENCES usuario (id_tenant, id_usuario)
);
-- Idempotencia do import de XML (P2): a mesma nota nunca gera 2 movimentos pro mesmo tenant.
-- "AND cancelado = false" (2026-08-12, Cancelamento de Entrada) — uma entrada XML cancelada
-- libera a chave pra reimportar a mesma NF-e corrigida; o registro cancelado continua existindo
-- (P3), só sai de baixo desta constraint.
CREATE UNIQUE INDEX produto_movimento_mestre_chave_nfe_uk
  ON produto_movimento_mestre (id_tenant, chave_nfe) WHERE chave_nfe IS NOT NULL AND cancelado = false;
CREATE INDEX produto_movimento_mestre_id_tenant_ix ON produto_movimento_mestre (id_tenant);
CREATE INDEX produto_movimento_mestre_data_ix      ON produto_movimento_mestre (id_tenant, data_movimento);

-- Ledger de movimentação — detalhe (linha por SKU, P3; sem saldo resultante por linha).
CREATE TABLE produto_movimento_detalhe (
  id_movimento_detalhe integer        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant            smallint       NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_movimento         integer        NOT NULL,
  id_empresa           integer        NOT NULL,
  id_funcionario       integer,
  id_variacao          integer        NOT NULL,
  credito_debito       credito_debito NOT NULL,
  qtd_produto          numeric(14,3)  NOT NULL,
  preco_custo          numeric(12,2)  NOT NULL DEFAULT 0,
  preco_venda          numeric(12,2)  NOT NULL DEFAULT 0,
  valor_desconto       numeric(12,2)  NOT NULL DEFAULT 0,
  valor_acrescimo      numeric(12,2)  NOT NULL DEFAULT 0,
  produto_oferta       boolean        NOT NULL DEFAULT false,
  origem               text,                                -- 'venda manual' / 'canal ML' / ...
  -- FKs compostas (2026-07-16, P8) — mesma razão do comentário em produto_estoque acima:
  -- sem elas, um id_tenant "certo" podia vir acompanhado de id_empresa/id_variacao de outro
  -- tenant, e a trigger materializava esse cruzamento em produto_estoque.
  CONSTRAINT produto_movimento_detalhe_movimento_fk FOREIGN KEY (id_tenant, id_movimento)
    REFERENCES produto_movimento_mestre (id_tenant, id_movimento),
  CONSTRAINT produto_movimento_detalhe_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT produto_movimento_detalhe_funcionario_fk FOREIGN KEY (id_tenant, id_funcionario)
    REFERENCES funcionario (id_tenant, id_funcionario),
  CONSTRAINT produto_movimento_detalhe_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao)
);
CREATE INDEX produto_movimento_detalhe_id_tenant_ix  ON produto_movimento_detalhe (id_tenant);
CREATE INDEX produto_movimento_detalhe_id_movimento_ix ON produto_movimento_detalhe (id_movimento);
CREATE INDEX produto_movimento_detalhe_variacao_ix   ON produto_movimento_detalhe (id_tenant, id_variacao);

-- Balanço/contagem de estoque. Sem UNIQUE em (id_tenant, id_empresa, id_variacao) de propósito:
-- cada leitura de código de barras insere uma linha nova (ledger, não upsert) — "quantidade
-- contada" de um produto é a SOMA das linhas ativas dele, mesmo espírito de
-- produto_movimento_detalhe. `id_movimento` (2026-08-04, Rotina de Contagem de Estoque) é NULL
-- enquanto a linha está no balanço ATIVO (ainda não efetivada); ao efetivar, em vez de apagar as
-- linhas, elas são marcadas com o id do produto_movimento_mestre (tipo_movimento AJUSTE) gerado
-- — isso é o que permite desfazer a última efetivação depois: apaga o ledger daquele movimento
-- (a trigger reverte o estoque sozinha) e libera essas linhas de volta pro balanço ativo
-- (id_movimento = NULL de novo), sem precisar de tabela de snapshot separada.
CREATE TABLE produto_balanco (
  id_balanco    bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant     smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa    integer       NOT NULL,
  id_variacao   integer       NOT NULL,
  data_balanco  timestamptz   NOT NULL DEFAULT now(),
  qtd_contagem  numeric(14,3) NOT NULL DEFAULT 0,
  id_movimento  integer,
  -- FKs compostas (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT produto_balanco_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT produto_balanco_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao),
  CONSTRAINT produto_balanco_movimento_fk FOREIGN KEY (id_tenant, id_movimento)
    REFERENCES produto_movimento_mestre (id_tenant, id_movimento)
);
CREATE INDEX produto_balanco_id_tenant_ix ON produto_balanco (id_tenant);
-- Cobre tanto "soma do balanço ativo por empresa/variação" (id_movimento IS NULL) quanto
-- "todas as linhas de uma efetivação" (id_movimento = X, usado pelo desfazer).
CREATE INDEX produto_balanco_empresa_movimento_ix ON produto_balanco (id_tenant, id_empresa, id_movimento);

-- Transferência de produtos entre empresas do mesmo tenant (2026-07-28, spec pendente vira
-- realidade — id_transferencia em produto_movimento_mestre já existia como placeholder pra
-- isso). Uma transferência gera DOIS produto_movimento_mestre (um por empresa, tipo_movimento
-- 'TRANSFERENCIA'): o da origem com detalhe 'D' (sai), o do destino com detalhe 'C' (entra),
-- ambos com o mesmo id_transferencia (esta tabela) — o valor concreto do "gerador externo"
-- mencionado no comentário daquela coluna. Sem FK de produto_movimento_mestre.id_transferencia
-- pra cá — mantido "proposital" como já estava documentado, pra não acoplar o ledger genérico
-- a uma tabela de negócio específica.
CREATE TABLE produto_transferencia (
  id_transferencia   integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa_origem  integer     NOT NULL,
  id_empresa_destino integer     NOT NULL,
  id_usuario         integer     NOT NULL,
  data_transferencia timestamptz NOT NULL DEFAULT now(),
  observacoes        text,
  CONSTRAINT produto_transferencia_id_uk UNIQUE (id_tenant, id_transferencia),
  CONSTRAINT produto_transferencia_empresas_diferentes_ck CHECK (id_empresa_origem <> id_empresa_destino),
  -- FKs compostas (P8) — mesmo motivo de usuario_empresa_fk (V015).
  CONSTRAINT produto_transferencia_origem_fk FOREIGN KEY (id_tenant, id_empresa_origem)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT produto_transferencia_destino_fk FOREIGN KEY (id_tenant, id_empresa_destino)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT produto_transferencia_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario)
);
CREATE INDEX produto_transferencia_id_tenant_ix ON produto_transferencia (id_tenant);
CREATE INDEX produto_transferencia_data_ix      ON produto_transferencia (id_tenant, data_transferencia);

COMMENT ON TABLE produto_transferencia IS 'Cabeçalho de transferência de estoque entre empresas do tenant. Sujeita a RLS (V024).';

COMMENT ON TABLE  produto_estoque IS 'Saldo materializado por variação × empresa (RLS). disponivel = qtd_estoque − reservado.';
COMMENT ON COLUMN produto_estoque.reservado IS 'Reserva anti-overselling; sobe no RECEBIDO do pedido (Q2/ADR-004).';
COMMENT ON TABLE  produto_movimento_detalhe IS 'Ledger de movimentação (grava origem, sem saldo_apos por linha). Corrigível via UPDATE/DELETE (2026-07-16) — trg_produto_movimento_detalhe_estoque recalcula produto_estoque.qtd_estoque a cada mudança.';

-- Trigger que mantém produto_estoque.qtd_estoque sincronizado com o ledger
-- produto_movimento_detalhe. credito_debito = 'C' soma, 'D' subtrai. INSERT aplica o efeito;
-- UPDATE desfaz o efeito da linha antiga e aplica o da nova (cobre mudança de
-- id_empresa/id_variacao/credito_debito/qtd_produto); DELETE desfaz o efeito. Roda como
-- niner_app (SECURITY INVOKER, padrão do Postgres) — a política RLS de produto_estoque
-- continua valendo, sem risco de vazar saldo entre tenants. Faz UPSERT: se ainda não existir
-- linha em produto_estoque para o (id_tenant, id_empresa, id_variacao), cria na hora — P1
-- exige que o saldo nunca fique "invisível" por falta de linha pré-cadastrada.
CREATE OR REPLACE FUNCTION fn_atualiza_estoque_movimento()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_delta numeric(14,3);
BEGIN

    IF TG_OP = 'INSERT' THEN

        v_delta := CASE
                       WHEN NEW.credito_debito = 'C' THEN  NEW.qtd_produto
                       ELSE                               -NEW.qtd_produto
                   END;

        INSERT INTO produto_estoque
            (id_tenant, id_empresa, id_variacao, qtd_estoque)
        VALUES
            (NEW.id_tenant, NEW.id_empresa, NEW.id_variacao, v_delta)
        ON CONFLICT (id_tenant, id_empresa, id_variacao)
        DO UPDATE
           SET qtd_estoque   = produto_estoque.qtd_estoque + EXCLUDED.qtd_estoque,
               atualizado_em = now();

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN

        -- desfaz o efeito da linha antiga
        v_delta := CASE
                       WHEN OLD.credito_debito = 'C' THEN -OLD.qtd_produto
                       ELSE                                OLD.qtd_produto
                   END;

        INSERT INTO produto_estoque
            (id_tenant, id_empresa, id_variacao, qtd_estoque)
        VALUES
            (OLD.id_tenant, OLD.id_empresa, OLD.id_variacao, v_delta)
        ON CONFLICT (id_tenant, id_empresa, id_variacao)
        DO UPDATE
           SET qtd_estoque   = produto_estoque.qtd_estoque + EXCLUDED.qtd_estoque,
               atualizado_em = now();

        -- aplica o efeito da linha nova
        v_delta := CASE
                       WHEN NEW.credito_debito = 'C' THEN  NEW.qtd_produto
                       ELSE                               -NEW.qtd_produto
                   END;

        INSERT INTO produto_estoque
            (id_tenant, id_empresa, id_variacao, qtd_estoque)
        VALUES
            (NEW.id_tenant, NEW.id_empresa, NEW.id_variacao, v_delta)
        ON CONFLICT (id_tenant, id_empresa, id_variacao)
        DO UPDATE
           SET qtd_estoque   = produto_estoque.qtd_estoque + EXCLUDED.qtd_estoque,
               atualizado_em = now();

        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN

        v_delta := CASE
                       WHEN OLD.credito_debito = 'C' THEN -OLD.qtd_produto
                       ELSE                                OLD.qtd_produto
                   END;

        INSERT INTO produto_estoque
            (id_tenant, id_empresa, id_variacao, qtd_estoque)
        VALUES
            (OLD.id_tenant, OLD.id_empresa, OLD.id_variacao, v_delta)
        ON CONFLICT (id_tenant, id_empresa, id_variacao)
        DO UPDATE
           SET qtd_estoque   = produto_estoque.qtd_estoque + EXCLUDED.qtd_estoque,
               atualizado_em = now();

        RETURN OLD;

    END IF;

    RETURN NULL;

END;
$$;

CREATE TRIGGER trg_produto_movimento_detalhe_estoque
    AFTER INSERT OR UPDATE OR DELETE ON produto_movimento_detalhe
    FOR EACH ROW
    EXECUTE FUNCTION fn_atualiza_estoque_movimento();
