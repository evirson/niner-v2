-- V058 — Orçamento de Venda (2026-08-20). Spec: docs/telas/orcamento.md
--
-- O consumidor pergunta o preço e não fecha na hora. Hoje o vendedor anota num papel; o cliente
-- volta três dias depois e ninguém sabe quem atendeu nem se o preço ainda vale.
--
-- ⚠️ ORÇAMENTO NÃO É VENDA NEM DOCUMENTO FISCAL
-- Não movimenta estoque (nenhuma linha em `produto_movimento_*`), não reserva nada, não gera
-- `contas_receber`, não passa por `caixa_detalhe`, não vai à SEFAZ e NÃO consome cota do plano
-- (ADR-015: a única dimensão cobrada é venda emitida — cobrar pela captação desincentivaria a
-- venda que de fato é cobrada). É por isso que ele NÃO ganha um valor em `tipo_movimento`: aquele
-- enum é de operação que move mercadoria de verdade (lição da V052).

-- ---------------------------------------------------------------------------------------------
-- 1. Os cinco estados
-- ---------------------------------------------------------------------------------------------
-- Quatro deles são FINAIS: só `ABERTO` sai do lugar.
--
-- ⚠️ `VENDIDO_PARCIAL` é final, e é decisão explícita do dono do produto: "como o cliente voltou,
-- faz uma venda nova e pronto". O que sobrou de um orçamento parcial NUNCA é vendido por ele — não
-- há saldo por item nem segunda venda vinculada. A venda de retorno é solta.
--
-- ⚠️ `VENCIDO` é estado PRÓPRIO, separado de `CANCELADO`. O pedido original dizia "cancela
-- automaticamente com o motivo CLIENTE NÃO VOLTOU"; virou estado próprio porque num relatório as
-- perguntas são diferentes — *quantos o vendedor cancelou* × *quantos morreram esperando o
-- cliente* — e com um estado só elas se misturam. É a métrica de sucesso da rotina.
--
-- Criar o tipo e usá-lo na MESMA migration é permitido; o que não pode é `ALTER TYPE ... ADD
-- VALUE` e usar o valor novo na mesma transação (lição da V052/V053).
CREATE TYPE situacao_orcamento AS ENUM ('ABERTO', 'VENDIDO', 'VENDIDO_PARCIAL', 'CANCELADO', 'VENCIDO');

-- ---------------------------------------------------------------------------------------------
-- 2. Cabeçalho
-- ---------------------------------------------------------------------------------------------
CREATE TABLE orcamento (
  id_orcamento            integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant               smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa              integer      NOT NULL,
  id_cliente              integer      NOT NULL,
  -- VENDEDOR (cadastro `funcionario`) e QUEM DIGITOU (`usuario`) são cadastros distintos no
  -- Nainer, como a venda já separa. P3 exige saber quem digitou.
  id_funcionario          integer      NOT NULL,
  id_usuario              integer      NOT NULL,
  data_orcamento          timestamptz  NOT NULL DEFAULT now(),
  -- ⚠️ `date`, não `timestamptz`: validade é um DIA CIVIL da loja ("vale até 05/09"), não um
  -- instante. Também simplifica a comparação de fuso — `AT TIME ZONE` sobre `date` é sem sentido,
  -- e o guarda de build (`ComparacaoDeDataNoFusoCertoTest`) exclui colunas genuinamente `date`.
  data_validade           date         NOT NULL,
  valor_desconto          numeric(12,2) NOT NULL DEFAULT 0,
  observacao              text,
  situacao                situacao_orcamento NOT NULL DEFAULT 'ABERTO',
  -- Efetivação
  id_venda                integer,
  data_efetivacao         timestamptz,
  -- Cancelamento — as mesmas 4 colunas de `venda`/`venda_devolucao`, sem tabela de log separada
  data_cancelamento       timestamptz,
  id_usuario_cancelamento integer,
  motivo_cancelamento     text,
  criado_em               timestamptz  NOT NULL DEFAULT now(),
  atualizado_em           timestamptz  NOT NULL DEFAULT now(),

  CONSTRAINT orcamento_id_uk UNIQUE (id_tenant, id_orcamento),
  CONSTRAINT orcamento_desconto_ck CHECK (valor_desconto >= 0),
  -- Coerência de estado: efetivado tem venda, não-efetivado não tem; cancelado tem motivo.
  CONSTRAINT orcamento_efetivado_ck CHECK (
    (situacao IN ('VENDIDO', 'VENDIDO_PARCIAL')) = (id_venda IS NOT NULL)),
  CONSTRAINT orcamento_cancelado_ck CHECK (
    situacao <> 'CANCELADO' OR (motivo_cancelamento IS NOT NULL AND data_cancelamento IS NOT NULL)),

  -- FKs COMPOSTAS com id_tenant (P8): FK simples não valida que a linha referenciada é do mesmo
  -- tenant. Todas as chaves únicas necessárias já existem — nenhuma migration antiga é tocada.
  CONSTRAINT orcamento_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT orcamento_cliente_fk FOREIGN KEY (id_tenant, id_cliente)
    REFERENCES cliente (id_tenant, id_cliente),
  CONSTRAINT orcamento_funcionario_fk FOREIGN KEY (id_tenant, id_funcionario)
    REFERENCES funcionario (id_tenant, id_funcionario),
  CONSTRAINT orcamento_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario),
  CONSTRAINT orcamento_usuario_cancelamento_fk FOREIGN KEY (id_tenant, id_usuario_cancelamento)
    REFERENCES usuario (id_tenant, id_usuario),
  -- ⚠️ FK de verdade para `venda`. O projeto tem histórico de vínculo SEM FK "de propósito"
  -- (`caixa_detalhe.id_conta_pagar` e irmãos), e foi assim que um excluir() passou meses deixando
  -- movimento órfão sem o banco reclamar. Aqui não há motivo para abrir mão dela.
  CONSTRAINT orcamento_venda_fk FOREIGN KEY (id_tenant, id_venda)
    REFERENCES venda (id_tenant, id_venda)
);

CREATE INDEX orcamento_id_tenant_ix  ON orcamento (id_tenant);
CREATE INDEX orcamento_data_ix       ON orcamento (id_tenant, data_orcamento DESC);
CREATE INDEX orcamento_cliente_ix    ON orcamento (id_tenant, id_cliente);
-- Índice do job de vencimento: só o que ainda pode vencer.
CREATE INDEX orcamento_a_vencer_ix   ON orcamento (id_tenant, data_validade)
  WHERE situacao = 'ABERTO';

COMMENT ON TABLE orcamento IS
  'Orcamento de venda (frente de loja). NAO movimenta estoque, NAO reserva, NAO gera financeiro e NAO consome cota do plano. Imutavel apos emitido: corrige-se cancelando e emitindo outro.';
COMMENT ON COLUMN orcamento.situacao IS
  'ABERTO e o unico estado nao-final. VENDIDO_PARCIAL e final: o que sobrou nunca e vendido por este orcamento. VENCIDO e separado de CANCELADO porque as duas perguntas sao diferentes no relatorio.';
COMMENT ON COLUMN orcamento.data_validade IS
  'Dia civil da loja. Comparar SEMPRE contra (now() AT TIME ZONE <fuso da loja>)::date — em UTC o orcamento venceria as 21h de Brasilia.';

-- ---------------------------------------------------------------------------------------------
-- 3. Itens
-- ---------------------------------------------------------------------------------------------
CREATE TABLE orcamento_item (
  id_orcamento_item integer       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant         smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_orcamento      integer       NOT NULL,
  id_variacao       integer       NOT NULL,
  qtd_produto       numeric(14,3) NOT NULL,
  -- ⚠️ Preco CONGELADO na emissao (P7). E o que faz a data de validade significar alguma coisa:
  -- "este preco vale ate tal dia". A DESCRICAO nao e congelada — resolve por JOIN, como a papeleta
  -- e o DANFE ja fazem; congelar texto criaria uma segunda verdade sobre o cadastro.
  preco_venda       numeric(12,2) NOT NULL,

  CONSTRAINT orcamento_item_qtd_ck   CHECK (qtd_produto > 0),
  CONSTRAINT orcamento_item_preco_ck CHECK (preco_venda >= 0),
  CONSTRAINT orcamento_item_orcamento_fk FOREIGN KEY (id_tenant, id_orcamento)
    REFERENCES orcamento (id_tenant, id_orcamento),
  CONSTRAINT orcamento_item_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao)
);

CREATE INDEX orcamento_item_id_tenant_ix ON orcamento_item (id_tenant);
CREATE INDEX orcamento_item_orcamento_ix ON orcamento_item (id_tenant, id_orcamento);

COMMENT ON TABLE orcamento_item IS
  'Itens do orcamento, com preco congelado. Ordem de exibicao = ordem de insercao (ORDER BY id_orcamento_item), mesmo padrao do ledger — sem coluna de sequencia.';

-- ---------------------------------------------------------------------------------------------
-- 4. Validade padrão é DADO, não código
-- ---------------------------------------------------------------------------------------------
ALTER TABLE cfg_geral
  ADD COLUMN IF NOT EXISTS cfg_dias_validade_orcamento smallint NOT NULL DEFAULT 15;

ALTER TABLE cfg_geral
  ADD CONSTRAINT cfg_geral_dias_validade_orcamento_ck
  CHECK (cfg_dias_validade_orcamento BETWEEN 1 AND 365);

COMMENT ON COLUMN cfg_geral.cfg_dias_validade_orcamento IS
  'Dias somados a hoje para SUGERIR a validade do orcamento na emissao. O operador pode sobrescrever caso a caso.';

-- ---------------------------------------------------------------------------------------------
-- 5. RLS — obrigatório, e NÃO é automático
-- ---------------------------------------------------------------------------------------------
-- ⚠️ O guarda-corpo da V024 (que reprova tabela com id_tenant sem RLS) rodou NAQUELE momento e não
-- roda de novo. Toda tabela de tenant criada depois faz este bloco por conta própria — é o que
-- V030/V031/V033/V051 fizeram.
ALTER TABLE orcamento      ENABLE ROW LEVEL SECURITY;
ALTER TABLE orcamento      FORCE  ROW LEVEL SECURITY;
ALTER TABLE orcamento_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE orcamento_item FORCE  ROW LEVEL SECURITY;

CREATE POLICY orcamento_rls ON orcamento
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

CREATE POLICY orcamento_item_rls ON orcamento_item
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

-- ⚠️ Sem estes GRANTs a rotina inteira responde erro de permissão em runtime — e a suíte NÃO pega,
-- porque o Testcontainers conecta como superusuário. Caso novo obrigatório em
-- `PrivilegiosNinerAppTest` (foi assim que o Cancelamento de Entrada passou com 7 testes verdes e
-- quebrou ao vivo).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_app') THEN
    -- UPDATE existe porque a situação muda (efetivar/cancelar/vencer); DELETE NÃO é concedido:
    -- orçamento não se apaga, se cancela (P3).
    GRANT SELECT, INSERT, UPDATE ON orcamento      TO niner_app;
    GRANT SELECT, INSERT         ON orcamento_item TO niner_app;
  END IF;
END $$;
