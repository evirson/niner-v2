-- V087 — Ordem de Serviço (bloco S4 de docs/MODULOSERVICOS.md)
--
-- ⛔ ORDEM DE SERVIÇO E ORÇAMENTO SÃO COISAS DIFERENTES — reforço explícito do dono do produto em
-- 2026-08-28. Esta migration copia a **forma** do orçamento (V058), que é o padrão de tela que
-- funcionou neste projeto, mas não a entidade: tabela própria, numeração própria, estados próprios.
-- A alternativa de estender `orcamento` com uma coluna `tipo` foi considerada e **rejeitada** (§4.2
-- do estudo): o orçamento é IMUTÁVEL por regra, a OS é MUTÁVEL por natureza, e "imutável quando é
-- uma coisa, mutável quando é outra" é a espécie de regra que produz o bug de 2029.
--
-- O que ela reaproveita é o **mecanismo de virar venda**: a OS aprovada abre no PDV pelo F5, como o
-- orçamento abre, e vira venda pelo mesmo `PdvVendaService.efetivarVenda`.
--
-- ⭐ O DINHEIRO CONTINUA ENTRANDO POR UMA PORTA SÓ. Nada aqui grava `caixa_detalhe`,
-- `contas_receber`, o ledger de estoque ou documento fiscal — quem faz isso é o PDV, onde moram o
-- caixa aberto obrigatório, o split-tender, o desconto máximo, o limite de crédito, a cota do plano,
-- a papeleta e a emissão. Uma segunda porta de faturamento teria de reimplementar as sete (é a
-- lição de `feedback_teto_com_porta_ao_lado`: o limite existe num caminho e o vizinho não passa
-- por ele).
--
-- As decisões do dono do produto que este schema materializa (§0 do estudo):
--   DS14 — a OS nasce com SERVIÇO + PEÇAS juntos; o PDV puxa tudo e ainda acrescenta
--   DS15 — a peça da OS RESERVA estoque
--   DS16 — o preço é CONGELADO, como no orçamento
--   DS17 — a reserva NÃO expira: quem resolve OS parada é cancelá-la

-- ---------------------------------------------------------------------------------------------
-- 1. Os estados
-- ---------------------------------------------------------------------------------------------
-- ⚠️ São de EXECUÇÃO, não comerciais — é a diferença para o orçamento, cuja situação fala de
-- venda (VENDIDO/VENCIDO). Aqui o eixo é o trabalho físico: o carro entrou, foi aprovado, está na
-- bancada, ficou pronto, foi pago.

CREATE TYPE situacao_ordem_servico AS ENUM (
  'ABERTA',        -- registrada, ainda sem aprovação do cliente
  'APROVADA',      -- o cliente autorizou; é daqui que ela pode ir ao PDV
  'EM_EXECUCAO',   -- a loja começou o trabalho físico
  'CONCLUIDA',     -- pronta, aguardando o cliente pagar/retirar
  'FATURADA',      -- virou venda (id_venda preenchido) — estado final
  'CANCELADA'      -- desfeita; libera a reserva de estoque das peças
);

COMMENT ON TYPE situacao_ordem_servico IS
  'Estados de EXECUÇÃO de uma OS (não comerciais, ao contrário de situacao_orcamento). '
  'FATURADA e CANCELADA são finais. Ver docs/MODULOSERVICOS.md §4.2.';

-- ---------------------------------------------------------------------------------------------
-- 2. A ordem de serviço
-- ---------------------------------------------------------------------------------------------

CREATE TABLE ordem_servico (
  id_ordem_servico  integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant         smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa        integer     NOT NULL,
  id_cliente        integer     NOT NULL,
  -- Quem ATENDEU (abriu a OS). Quem EXECUTA cada serviço vai no item — numa oficina o consultor
  -- que recebe o carro não é o mecânico que trabalha nele, e a comissão é de quem executa.
  id_funcionario    integer     NOT NULL,
  id_usuario        integer     NOT NULL,
  -- ⭐ O objeto do serviço: a placa do carro, o nome do animal. Sem ele a oficina não acha a OS de
  -- ontem — é por aqui que o balcão procura, não pelo número.
  objeto_servico    text        NOT NULL,
  data_abertura     timestamptz NOT NULL DEFAULT now(),
  data_conclusao    timestamptz,
  valor_desconto    numeric(12,2) NOT NULL DEFAULT 0,
  observacao        text,
  situacao          situacao_ordem_servico NOT NULL DEFAULT 'ABERTA',
  id_venda          integer,
  data_faturamento  timestamptz,
  data_cancelamento timestamptz,
  id_usuario_cancelamento integer,
  motivo_cancelamento text,
  criado_em         timestamptz NOT NULL DEFAULT now(),
  atualizado_em     timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT ordem_servico_id_uk UNIQUE (id_tenant, id_ordem_servico),
  CONSTRAINT ordem_servico_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa),
  CONSTRAINT ordem_servico_cliente_fk FOREIGN KEY (id_tenant, id_cliente)
    REFERENCES cliente (id_tenant, id_cliente),
  CONSTRAINT ordem_servico_funcionario_fk FOREIGN KEY (id_tenant, id_funcionario)
    REFERENCES funcionario (id_tenant, id_funcionario),
  CONSTRAINT ordem_servico_usuario_fk FOREIGN KEY (id_tenant, id_usuario)
    REFERENCES usuario (id_tenant, id_usuario),
  CONSTRAINT ordem_servico_venda_fk FOREIGN KEY (id_tenant, id_venda)
    REFERENCES venda (id_tenant, id_venda),
  CONSTRAINT ordem_servico_desconto_ck CHECK (valor_desconto >= 0),

  -- Os dois CHECK que amarram estado × dado, no mesmo espírito do orçamento (V058): estado que
  -- afirma um fato sem o dado que o comprova é como a linha fica mentindo em silêncio.
  CONSTRAINT ordem_servico_faturada_ck
    CHECK ((situacao = 'FATURADA') = (id_venda IS NOT NULL)),
  CONSTRAINT ordem_servico_cancelada_ck
    CHECK (situacao <> 'CANCELADA'
           OR (motivo_cancelamento IS NOT NULL AND data_cancelamento IS NOT NULL))
);

CREATE INDEX ordem_servico_id_tenant_ix ON ordem_servico (id_tenant);
CREATE INDEX ordem_servico_cliente_ix   ON ordem_servico (id_tenant, id_cliente);
CREATE INDEX ordem_servico_data_ix      ON ordem_servico (id_tenant, data_abertura DESC);
-- Busca pelo objeto (placa/animal) sem diferenciar maiúsculas — é como o balcão procura.
CREATE INDEX ordem_servico_objeto_ix    ON ordem_servico (id_tenant, upper(objeto_servico));
-- As que ainda podem ir ao PDV: é a consulta do F5.
CREATE INDEX ordem_servico_faturavel_ix ON ordem_servico (id_tenant, id_cliente)
  WHERE situacao IN ('APROVADA', 'EM_EXECUCAO', 'CONCLUIDA');

COMMENT ON TABLE ordem_servico IS
  'Ordem de Serviço — o trabalho que leva tempo (oficina, banho e tosa). ⛔ NÃO é orçamento: aquele '
  'é imutável e comercial, esta é mutável e de execução. Nasce com serviço E peças (DS14), reserva '
  'o estoque das peças (DS15), congela o preço (DS16) e vira venda pelo F5 do PDV — nunca por uma '
  'tela própria de faturamento. Ver docs/MODULOSERVICOS.md §4.2.';
COMMENT ON COLUMN ordem_servico.objeto_servico IS
  'O que está sendo trabalhado: placa do veículo, nome do animal, número de série do aparelho. '
  'Obrigatório porque é por ele que o balcão acha a OS — o número da OS quase ninguém decora.';
COMMENT ON COLUMN ordem_servico.id_funcionario IS
  'Quem ATENDEU (abriu a OS). Quem EXECUTA cada serviço está em ordem_servico_item.id_funcionario: '
  'na oficina o consultor que recebe o carro não é o mecânico, e a comissão é de quem executa.';
COMMENT ON COLUMN ordem_servico.id_venda IS
  'A venda gerada no PDV. NULL enquanto não faturada. É a trava de idempotência: o CHECK '
  'ordem_servico_faturada_ck garante que FATURADA e id_venda andem sempre juntos.';

-- ---------------------------------------------------------------------------------------------
-- 3. Os itens — serviço e peça na MESMA tabela (DS14)
-- ---------------------------------------------------------------------------------------------
-- ⭐ Uma tabela só, porque os dois são `produto_barra` desde a V085. Separar em `os_servico` e
-- `os_peca` reintroduziria pela porta dos fundos a divisão que a DS1 rejeitou — e obrigaria a
-- montar a venda a partir de duas fontes.

CREATE TABLE ordem_servico_item (
  id_ordem_servico_item integer  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant        smallint      NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_ordem_servico integer       NOT NULL,
  id_variacao      integer       NOT NULL,
  qtd_produto      numeric(14,3) NOT NULL,
  -- ⭐ DS16 — preço CONGELADO na inclusão. O cliente aprovou R$ 800 de mão de obra e a loja honra,
  -- ainda que a tabela suba antes de faturar.
  -- ⚠️ E é ele que torna NORMAL o que era exceção: o mesmo item pode aparecer duas vezes na venda,
  -- com preços diferentes (o aprovado × o acrescentado na hora). A chave de linha, em Java e no
  -- front, é (item, preço) — nunca só o item. Ver feedback_duas_linhas_mesmo_produto_na_venda.
  preco_venda      numeric(12,2) NOT NULL,
  -- Quem EXECUTA este item. Nulo = ninguém atribuído ainda (peça normalmente fica nulo).
  id_funcionario   integer,
  -- ⭐ DS15 — a peça reserva estoque. Serviço nunca reserva (não tem saldo, V086).
  -- A coluna diz o que ESTA linha reservou, para o cancelamento devolver exatamente isso mesmo que
  -- a quantidade mude depois — reserva liberada "pelo valor de agora" deixa resto pendurado.
  qtd_reservada    numeric(14,3) NOT NULL DEFAULT 0,
  criado_em        timestamptz   NOT NULL DEFAULT now(),

  CONSTRAINT ordem_servico_item_os_fk FOREIGN KEY (id_tenant, id_ordem_servico)
    REFERENCES ordem_servico (id_tenant, id_ordem_servico),
  CONSTRAINT ordem_servico_item_variacao_fk FOREIGN KEY (id_tenant, id_variacao)
    REFERENCES produto_barra (id_tenant, id_variacao),
  CONSTRAINT ordem_servico_item_funcionario_fk FOREIGN KEY (id_tenant, id_funcionario)
    REFERENCES funcionario (id_tenant, id_funcionario),
  CONSTRAINT ordem_servico_item_qtd_ck   CHECK (qtd_produto > 0),
  CONSTRAINT ordem_servico_item_preco_ck CHECK (preco_venda >= 0),
  CONSTRAINT ordem_servico_item_reserva_ck CHECK (qtd_reservada >= 0)
);

CREATE INDEX ordem_servico_item_id_tenant_ix ON ordem_servico_item (id_tenant);
CREATE INDEX ordem_servico_item_os_ix        ON ordem_servico_item (id_tenant, id_ordem_servico);

COMMENT ON TABLE ordem_servico_item IS
  'Itens da OS — serviço E peça na mesma tabela (DS14), porque os dois são produto_barra desde a '
  'V085. Separar em duas reintroduziria a divisão que a DS1 rejeitou.';
COMMENT ON COLUMN ordem_servico_item.qtd_reservada IS
  'Quanto ESTA linha reservou em produto_estoque.reservado. Guardado por linha, e não recalculado '
  'na hora de liberar, porque a quantidade do item muda: liberar "pelo valor de agora" deixaria '
  'resto pendurado na reserva para sempre. Serviço fica sempre 0 — não tem saldo (V086).';

-- ---------------------------------------------------------------------------------------------
-- 4. RLS — obrigatória e não automática
-- ---------------------------------------------------------------------------------------------
-- A spec do orçamento registra que isto quase passou em branco lá. Tabela de domínio sem política
-- não dá erro: dá vazamento.

ALTER TABLE ordem_servico      ENABLE ROW LEVEL SECURITY;
ALTER TABLE ordem_servico      FORCE  ROW LEVEL SECURITY;
ALTER TABLE ordem_servico_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE ordem_servico_item FORCE  ROW LEVEL SECURITY;

CREATE POLICY ordem_servico_rls ON ordem_servico
  USING      (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

CREATE POLICY ordem_servico_item_rls ON ordem_servico_item
  USING      (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

GRANT SELECT, INSERT, UPDATE, DELETE ON ordem_servico      TO niner_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ordem_servico_item TO niner_app;

-- ---------------------------------------------------------------------------------------------
-- 5. O catálogo de telas (RBAC)
-- ---------------------------------------------------------------------------------------------
-- ⚠️ Obrigatório e travado por teste: `AcoesPorTelaConferemTest` varre os controllers, deriva a
-- ação de cada endpoint pela regra do interceptor e compara com `cfg_tela`. Tela sem linha aqui
-- = permissão impossível de conceder; ação divergente = caixa que não governa nada. Foi assim que
-- a V076 travou o PDV inteiro.
--
-- Ordens de Serviço fica em **Frente de Loja**: quem abre a OS é quem atende o balcão, não o
-- administrador. Mesmo raciocínio que colocou a Fila de Expedição lá quando o marketplace existia.

INSERT INTO cfg_tela (chave, nome, grupo, subgrupo, ordem, admin_apenas,
                      tem_incluir, tem_alterar, tem_excluir)
VALUES ('ordens-servico', 'Ordens de Serviço', 'Frente de Loja', NULL, 3, false,
        true, true, true)
ON CONFLICT (chave) DO NOTHING;
