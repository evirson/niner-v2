-- V016 — Cadastros básicos referenciados pelo estoque/vendas: cliente, fornecedor,
-- funcionario (§3.3.8 do legado). Todos com id_tenant (P8). Dinheiro em NUMERIC (P7).

-- categoria de cliente (§3.3.8 do legado). Cria antes de `cliente` por causa da FK.
CREATE TABLE cfg_categoria_cliente (
  id_categoria_cliente  integer  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant             smallint NOT NULL REFERENCES plataforma.tenant (id_tenant),
  nome_categoria        text     NOT NULL,
  CONSTRAINT cfg_categoria_cliente_uk UNIQUE (id_tenant, nome_categoria),
  -- base para FK composta (2026-07-16, P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT cfg_categoria_cliente_id_uk UNIQUE (id_tenant, id_categoria_cliente)
);
CREATE INDEX cfg_categoria_cliente_id_tenant_ix ON cfg_categoria_cliente (id_tenant);

CREATE TABLE cliente (
  id_cliente           integer    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant            smallint   NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_categoria_cliente integer    NOT NULL,
  nome                 text       NOT NULL,
  fisica_juridica      boolean    NOT NULL DEFAULT true,     -- true = pessoa física
  cpf_cnpj             text,
  rg_ie                text,
  data_nascimento      date,                                 -- obrigatório p/ pessoa física, ver CHECK
  genero               genero_cliente,                       -- obrigatório p/ pessoa física, ver CHECK
  email                text,
  telefone             text,
  whatsapp             text,
  instagram            text,
  facebook             text,
  tiktok               text,
  endereco             text,
  numero               text,
  complemento          text,                                 -- apto/bloco/sala etc. (2026-07-20)
  bairro               text,
  cidade               text,
  estado               text,
  cep                  text,
  limite_credito       numeric(12,2) NOT NULL DEFAULT 0,     -- crediário é Fase 2; campo fica pronto
  ativo                boolean       NOT NULL DEFAULT true,
  criado_em            timestamptz   NOT NULL DEFAULT now(),
  atualizado_em        timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT cliente_documento_uk UNIQUE (id_tenant, cpf_cnpj),
  -- genero obrigatório só para pessoa física; PJ não tem gênero. data_nascimento é sempre
  -- opcional (2026-07-21) — quando preenchida, a validade (não pode ser hoje/futuro) fica
  -- por conta da aplicação, não do banco.
  CONSTRAINT cliente_dados_pessoais_ck CHECK (
    NOT fisica_juridica OR genero IS NOT NULL
  ),
  -- FK composta (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT cliente_categoria_fk FOREIGN KEY (id_tenant, id_categoria_cliente)
    REFERENCES cfg_categoria_cliente (id_tenant, id_categoria_cliente),
  -- base para FK composta de venda.id_cliente (V018).
  CONSTRAINT cliente_id_cliente_uk UNIQUE (id_tenant, id_cliente)
);
CREATE INDEX cliente_id_tenant_ix  ON cliente (id_tenant);
CREATE INDEX cliente_nome_ix       ON cliente (id_tenant, nome);
CREATE INDEX cliente_categoria_ix  ON cliente (id_tenant, id_categoria_cliente);

-- plano de contas GERENCIAL (§3.3.7 do legado — revisado por completo em 2026-07-31: DRE e
-- fluxo de caixa deixam de ser só flags e viram classificação de verdade, com hierarquia de
-- 4 níveis via máscara fixa "9.99.999.999" (conta.subconta.item.subitem). Substitui o desenho
-- de 2026-07-21 (código texto livre + 3 flags soltas) — ver docs/telas/plano-contas.md.
--
-- PRINCÍPIO CENTRAL: inclui_dre e inclui_fluxo_caixa são INDEPENDENTES — isso é o que impede
-- a duplicação entre resultado e caixa. Ex.: a COMPRA de mercadoria entra no fluxo de caixa
-- (3.03) e NUNCA no DRE; o CMV entra no DRE (3.01) e NUNCA no fluxo de caixa — assim o
-- estoque não é contado duas vezes. Mesmo princípio para amortização de principal (caixa
-- sim, DRE não) e depreciação (DRE sim, caixa não — o desembolso já ocorreu no CAPEX).
CREATE TABLE cfg_plano_contas (
  id_tenant            smallint              NOT NULL REFERENCES plataforma.tenant (id_tenant),

  -- Máscara fixa de 8 caracteres: 9.99.999 (grupo.família.conta — encurtada de 9.99.999.999
  -- em 2026-08-13, pedido do dono do produto: foco em pequenos negócios, 4 níveis era
  -- complexidade demais). Largura fixa garante ordenação lexicográfica correta e prefix-match
  -- ('1.01.%' pega toda a subárvore) sem função de normalização.
  id_plano_contas      text                  NOT NULL,

  descricao            text                  NOT NULL,
  descricao_curta      text,                             -- p/ cupom, PDV, relatório estreito

  tipo_movimento       tipo_movimento_conta  NOT NULL,    -- CREDITO/DEBITO/NEUTRO
  natureza             natureza_conta        NOT NULL,    -- SINTETICA (agrupa) / ANALITICA (recebe lançamento)

  -- Nível derivado da máscara: zeros à direita marcam o corte hierárquico.
  -- 1 = grupo (X.00.000), 2 = família (X.YY.000), 3 = conta (X.YY.ZZZ).
  nivel smallint GENERATED ALWAYS AS (
    CASE
      WHEN substring(id_plano_contas from 3 for 2) = '00'  THEN 1
      WHEN substring(id_plano_contas from 6 for 3) = '000' THEN 2
      ELSE 3
    END
  ) STORED,

  -- Pai derivado — elimina a possibilidade de árvore inconsistente por digitação.
  id_plano_contas_pai text GENERATED ALWAYS AS (
    CASE
      WHEN substring(id_plano_contas from 3 for 2) = '00'  THEN NULL
      WHEN substring(id_plano_contas from 6 for 3) = '000'
           THEN substring(id_plano_contas from 1 for 1) || '.00.000'
      ELSE substring(id_plano_contas from 1 for 4) || '.000'
    END
  ) STORED,

  inclui_dre           boolean               NOT NULL,
  inclui_fluxo_caixa   boolean               NOT NULL,

  grupo_dre            grupo_dre_conta       NOT NULL DEFAULT 'NAO_APLICA',
  grupo_dfc            grupo_dfc_conta       NOT NULL DEFAULT 'NAO_APLICA',

  -- +1 soma no resultado, -1 subtrai, 0 neutro — evita CASE espalhado nas queries de apuração.
  sinal                smallint              NOT NULL,

  -- Só analíticas recebem lançamento; sintéticas existem para totalizar/agrupar.
  aceita_lancamento    boolean               NOT NULL,

  -- 2026-08-13: removidos exige_centro_custo/exige_contraparte/exige_documento (travas de
  -- lançamento nunca implementadas) e id_conta_contabil/id_plano_referencial (de-para
  -- SPED/RFB) — complexidade sem uso para o público-alvo; nada disso alimenta DRE/DFC.

  padrao_sistema       boolean               NOT NULL DEFAULT false,  -- conta do template: não pode ser excluída
  ativo                boolean               NOT NULL DEFAULT true,
  observacao           text,

  criado_em            timestamptz           NOT NULL DEFAULT now(),  -- 2026-07-21 (auditoria, convenção do domínio)
  atualizado_em        timestamptz           NOT NULL DEFAULT now(),

  CONSTRAINT cfg_plano_contas_pk PRIMARY KEY (id_tenant, id_plano_contas),

  -- Hierarquia fechada dentro do tenant. DEFERRABLE p/ permitir carga em lote (pai e filho na
  -- mesma transação) sem depender da ordem das linhas.
  CONSTRAINT cfg_plano_contas_pai_fk FOREIGN KEY (id_tenant, id_plano_contas_pai)
    REFERENCES cfg_plano_contas (id_tenant, id_plano_contas)
    ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,

  CONSTRAINT cfg_plano_contas_mascara_ck
    CHECK (id_plano_contas ~ '^[1-9]\.[0-9]{2}\.[0-9]{3}$'),

  -- Sintética nunca recebe lançamento; analítica sempre recebe.
  CONSTRAINT cfg_plano_contas_lancamento_ck
    CHECK (aceita_lancamento = (natureza = 'ANALITICA')),

  CONSTRAINT cfg_plano_contas_sinal_ck CHECK (sinal IN (-1, 0, 1)),

  -- Coerência entre tipo de movimento e sinal do resultado.
  CONSTRAINT cfg_plano_contas_tipo_sinal_ck
    CHECK ( (tipo_movimento = 'CREDITO' AND sinal =  1)
         OR (tipo_movimento = 'DEBITO'  AND sinal = -1)
         OR (tipo_movimento = 'NEUTRO'  AND sinal =  0) ),

  -- Conta neutra jamais entra no DRE — trava anti-duplicação.
  CONSTRAINT cfg_plano_contas_neutro_ck
    CHECK (tipo_movimento <> 'NEUTRO' OR inclui_dre = false),

  -- Se entra no DRE precisa de linha; se não entra, não pode ter linha (idem DFC).
  CONSTRAINT cfg_plano_contas_grupo_dre_ck CHECK (inclui_dre = (grupo_dre <> 'NAO_APLICA')),
  CONSTRAINT cfg_plano_contas_grupo_dfc_ck CHECK (inclui_fluxo_caixa = (grupo_dfc <> 'NAO_APLICA')),

  CONSTRAINT cfg_plano_contas_descricao_ck CHECK (length(btrim(descricao)) BETWEEN 3 AND 120)
);

-- Navegação da árvore (expandir filhos de um nó).
CREATE INDEX cfg_plano_contas_pai_ix ON cfg_plano_contas (id_tenant, id_plano_contas_pai);

-- Combo/autocomplete: só o que pode receber lançamento e está ativo.
CREATE INDEX cfg_plano_contas_lancavel_ix
    ON cfg_plano_contas (id_tenant, id_plano_contas) WHERE aceita_lancamento AND ativo;

-- Apuração de DRE e DFC.
CREATE INDEX cfg_plano_contas_dre_ix ON cfg_plano_contas (id_tenant, grupo_dre, id_plano_contas) WHERE inclui_dre;
CREATE INDEX cfg_plano_contas_dfc_ix ON cfg_plano_contas (id_tenant, grupo_dfc, id_plano_contas) WHERE inclui_fluxo_caixa;

-- Busca textual tolerante a acento e a fragmento no meio da palavra — substitui o índice
-- B-tree antigo em (id_tenant, descricao), que só servia igualdade/prefixo exato.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- unaccent() é STABLE (não IMMUTABLE) — o Postgres recusa usá-la direto num índice de
-- expressão. Wrapper IMMUTABLE (padrão conhecido pra esse caso — o dicionário referenciado
-- é fixo em tempo de definição, então a função é de fato determinística).
CREATE OR REPLACE FUNCTION unaccent_imutavel(text) RETURNS text AS $$
    SELECT public.unaccent('public.unaccent', $1)
$$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

CREATE INDEX cfg_plano_contas_descricao_trgm_ix
    ON cfg_plano_contas USING gin (lower(unaccent_imutavel(descricao)) gin_trgm_ops);

-- Irmãos não podem ter a mesma descrição.
CREATE UNIQUE INDEX cfg_plano_contas_irmao_uq
    ON cfg_plano_contas (id_tenant, coalesce(id_plano_contas_pai, ''), lower(btrim(descricao)));

CREATE OR REPLACE FUNCTION trg_set_atualizado_em() RETURNS trigger AS $$
BEGIN
    NEW.atualizado_em := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cfg_plano_contas_atualizado_em_tg
    BEFORE UPDATE ON cfg_plano_contas
    FOR EACH ROW EXECUTE FUNCTION trg_set_atualizado_em();

-- Impede excluir conta que tem filhos, e protege conta padrão do sistema (inative-a, em vez
-- de excluir). Rede de segurança em nível de banco — o serviço já pré-checa o mesmo antes de
-- tentar o DELETE, pra devolver uma mensagem amigável em vez de deixar a trigger disparar.
CREATE OR REPLACE FUNCTION trg_cfg_plano_contas_guarda() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.padrao_sistema THEN
            RAISE EXCEPTION 'Conta % é padrão do sistema e não pode ser excluída. Inative-a.',
                OLD.id_plano_contas;
        END IF;
        IF EXISTS (SELECT 1 FROM cfg_plano_contas f
                    WHERE f.id_tenant = OLD.id_tenant
                      AND f.id_plano_contas_pai = OLD.id_plano_contas) THEN
            RAISE EXCEPTION 'Conta % possui contas filhas.', OLD.id_plano_contas;
        END IF;
        RETURN OLD;
    END IF;

    -- Não deixa ativar filha sob pai inativo.
    IF NEW.ativo AND NEW.id_plano_contas_pai IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM cfg_plano_contas p
                        WHERE p.id_tenant = NEW.id_tenant
                          AND p.id_plano_contas = NEW.id_plano_contas_pai
                          AND p.ativo) THEN
            RAISE EXCEPTION 'Conta pai % está inativa.', NEW.id_plano_contas_pai;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER cfg_plano_contas_guarda_tg
    AFTER INSERT OR UPDATE OR DELETE ON cfg_plano_contas
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trg_cfg_plano_contas_guarda();

-- RLS de cfg_plano_contas é aplicada centralmente em V024 (arquivo único, P8) — não repetir aqui.

-- Árvore navegável (caminho completo + profundidade) — usada pela apuração e por qualquer
-- tela futura de navegação hierárquica.
CREATE OR REPLACE VIEW vw_plano_contas_arvore AS
WITH RECURSIVE t AS (
    SELECT c.*, c.descricao::text AS caminho, 0 AS profundidade
      FROM cfg_plano_contas c
     WHERE c.id_plano_contas_pai IS NULL
    UNION ALL
    SELECT f.*, t.caminho || ' > ' || f.descricao, t.profundidade + 1
      FROM cfg_plano_contas f
      JOIN t ON t.id_tenant = f.id_tenant
            AND t.id_plano_contas = f.id_plano_contas_pai
)
SELECT * FROM t;

CREATE TABLE fornecedor (
  id_fornecedor      integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_plano_contas    text        NOT NULL,
  razao_social       text        NOT NULL,
  nome_fantasia      text,
  cnpj               text,
  inscricao_estadual text,
  email              text,
  telefone           text,
  endereco           text,
  numero             text,
  bairro             text,
  cidade             text,
  estado             text,
  cep                text,
  ativo              boolean     NOT NULL DEFAULT true,
  criado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_em      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fornecedor_cnpj_uk UNIQUE (id_tenant, cnpj),
  -- base para FK composta (2026-07-16, P8) — ver comentário em empresa_id_empresa_uk (V014).
  CONSTRAINT fornecedor_id_fornecedor_uk UNIQUE (id_tenant, id_fornecedor),
  -- FK composta (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT fornecedor_plano_contas_fk FOREIGN KEY (id_tenant, id_plano_contas)
    REFERENCES cfg_plano_contas (id_tenant, id_plano_contas)
);
CREATE INDEX fornecedor_id_tenant_ix ON fornecedor (id_tenant);

CREATE TABLE funcionario (
  id_funcionario integer      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant      smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa     integer,
  nome           text         NOT NULL,
  cpf            text,
  telefone       text,
  cargo          text,
  perc_comissao  numeric(5,2) NOT NULL DEFAULT 0,
  ativo          boolean      NOT NULL DEFAULT true,
  criado_em      timestamptz  NOT NULL DEFAULT now(),
  atualizado_em  timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT funcionario_cpf_uk UNIQUE (id_tenant, id_funcionario),
  -- FK composta (2026-07-16, P8) — ver comentário em usuario_empresa_fk (V015).
  CONSTRAINT funcionario_empresa_fk FOREIGN KEY (id_tenant, id_empresa)
    REFERENCES empresa (id_tenant, id_empresa)
);
CREATE INDEX funcionario_id_tenant_ix ON funcionario (id_tenant);

COMMENT ON TABLE cfg_categoria_cliente IS 'Categoria de cliente (RLS). Referenciada por cliente.id_categoria_cliente (NOT NULL).';
COMMENT ON TABLE cliente               IS 'Cliente do lojista (RLS). limite_credito preparado para crediário (Fase 2).';
COMMENT ON TABLE  cfg_plano_contas IS
    'Plano de contas gerencial (RLS). Máscara 9.99.999.999 (conta.subconta.item.subitem), hierarquia de 4 níveis. Referenciada por fornecedor/contas_pagar/caixa_detalhe/conta_corrente_movimento.id_plano_contas (NOT NULL).';
COMMENT ON COLUMN cfg_plano_contas.inclui_dre IS
    'Afeta o resultado por COMPETÊNCIA. Falso para compra de estoque, CAPEX, amortização de principal e movimentos neutros.';
COMMENT ON COLUMN cfg_plano_contas.inclui_fluxo_caixa IS
    'Afeta a variação de disponibilidades no DFC consolidado. Falso para CMV, depreciação, provisões e transferências entre contas próprias.';
COMMENT ON COLUMN cfg_plano_contas.nivel IS
    'Derivado da máscara. 1=grupo 2=família 3=conta.';
COMMENT ON TABLE fornecedor            IS 'Fornecedor do lojista (RLS).';
COMMENT ON TABLE funcionario           IS 'Funcionário do lojista (RLS). Referenciado no ledger de estoque/venda.';


-- ---------------------------------------------------------------------------------------------
-- cfg_plano_contas_padrao (2026-08-14) — MODELO GLOBAL do plano de contas padrão, sem id_tenant
-- e sem RLS (mesma exceção documentada de cfg_produto_ncm e cfg_ean_gerador: é estrutura de
-- produto, não dado de um lojista).
--
-- Por que existir: até aqui o plano padrão de 76 contas era um script manual em
-- db/scripts/seed_plano_contas_padrao.sql, e o SignupService semeava só 3 contas (a árvore
-- mínima da conta de compra). O efeito prático apareceu no Relatório de DRE: um tenant novo via
-- receita e CMV, mas NENHUMA despesa, porque não tinha conta de despesa cadastrada. Com o modelo
-- aqui, o signup copia o plano inteiro numa linha de SQL e uma atualização futura do padrão vira
-- migration, não script solto.
--
-- `sinal` e `aceita_lancamento` continuam derivados (CREDITO=+1, DEBITO=-1; analítica aceita
-- lançamento), exatamente como o script fazia — não são colunas deste modelo.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE cfg_plano_contas_padrao (
  id_plano_contas    text                 PRIMARY KEY,
  descricao          text                 NOT NULL,
  tipo_movimento     tipo_movimento_conta NOT NULL,
  inclui_dre         boolean              NOT NULL,
  inclui_fluxo_caixa boolean              NOT NULL,
  grupo_dre          grupo_dre_conta      NOT NULL,
  grupo_dfc          grupo_dfc_conta      NOT NULL,
  analitica          boolean              NOT NULL
);
COMMENT ON TABLE cfg_plano_contas_padrao IS 'Modelo do plano de contas padrão copiado para cada tenant no signup. GLOBAL (sem id_tenant/RLS), como cfg_produto_ncm.';
-- Só leitura para a app: o modelo é mantido por migration, nunca pelo lojista.
GRANT SELECT ON cfg_plano_contas_padrao TO niner_app;

INSERT INTO cfg_plano_contas_padrao
  (id_plano_contas, descricao, tipo_movimento, inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, analitica)
VALUES

-- ============================ 1 · RECEITAS ===================================
-- Entram na DRE (receita) e no fluxo de caixa (operacional).
('1.00.000','RECEITAS','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false),

('1.01.000','Receita de Vendas','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false),
('1.01.001','Venda de Mercadorias — Loja','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true),
('1.01.002','Venda de Mercadorias — Marketplace/Online','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true),

('1.02.000','Outras Receitas','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',false),
('1.02.001','Juros e Multas de Crediário Recebidos','CREDITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true),
('1.02.002','Outras Receitas Operacionais','CREDITO',true,true,'RECEITA_BRUTA','OPERACIONAL',true),

-- ==================== 2 · DEDUÇÕES DA RECEITA BRUTA ==========================
('2.00.000','DEDUÇÕES DA RECEITA BRUTA','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false),

('2.01.000','Impostos sobre Vendas','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false),
('2.01.001','Simples Nacional / Impostos sobre Vendas','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true),

('2.02.000','Devoluções e Descontos','DEBITO',true,true,'DEDUCOES','OPERACIONAL',false),
('2.02.001','Devoluções de Vendas','DEBITO',true,true,'DEDUCOES','OPERACIONAL',true),
-- Desconto concedido reduz a receita na DRE mas nunca movimenta caixa (o dinheiro simplesmente
-- não entrou) — só DRE.
('2.02.002','Descontos Concedidos','DEBITO',true,false,'DEDUCOES','NAO_APLICA',true),

-- ========================= 3 · CUSTOS VARIÁVEIS ==============================
('3.00.000','CUSTOS VARIÁVEIS','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false),

-- CMV: só DRE (o desembolso de caixa acontece na COMPRA, família 3.03) — anti-duplicação.
('3.01.000','Custo das Mercadorias Vendidas','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',false),
('3.01.001','CMV — Custo das Mercadorias Vendidas','DEBITO',true,false,'CUSTO_VARIAVEL','NAO_APLICA',true),

('3.02.000','Custos Diretos sobre Vendas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',false),
('3.02.001','Comissões sobre Vendas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),
('3.02.002','Taxas de Cartão e PIX','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),
('3.02.003','Comissões de Marketplace','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),
('3.02.004','Fretes sobre Vendas / Entregas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),
('3.02.005','Embalagens e Sacolas','DEBITO',true,true,'CUSTO_VARIAVEL','OPERACIONAL',true),

-- Compra de mercadoria: só fluxo de caixa (na DRE quem aparece é o CMV) — anti-duplicação.
-- 3.03.001 é a conta padrão de cfg_geral.id_plano_contas_compra_mercadoria (Entrada de
-- Produtos por Compra / contas a pagar geradas por ela).
('3.03.000','Compras de Mercadoria','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',false),
('3.03.001','Compra de Mercadoria para Revenda','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true),
('3.03.002','Fretes sobre Compras','DEBITO',false,true,'NAO_APLICA','OPERACIONAL',true),

-- ===================== 4 · CUSTOS E DESPESAS FIXAS ===========================
('4.00.000','CUSTOS E DESPESAS FIXAS','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),

('4.01.000','Ocupação e Estrutura','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),
('4.01.001','Aluguel e Condomínio','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.002','Energia Elétrica','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.003','Água','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.004','Internet e Telefone','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.005','Manutenção e Reparos','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.01.006','Segurança e Monitoramento','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),

('4.02.000','Pessoal','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),
('4.02.001','Salários e Ordenados','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.02.002','Encargos Sociais (FGTS/INSS)','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.02.003','Pró-labore','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.02.004','Vale-transporte e Alimentação','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.02.005','Férias e 13º Salário','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),

('4.03.000','Despesas Administrativas','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),
('4.03.001','Contabilidade','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.03.002','Softwares e Sistemas','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.03.003','Material de Expediente e Limpeza','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.03.004','Veículos e Combustível','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.03.005','Outras Despesas Administrativas','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),

('4.04.000','Comercial e Marketing','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',false),
('4.04.001','Publicidade e Anúncios','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),
('4.04.002','Brindes e Promoções','DEBITO',true,true,'DESPESA_FIXA','OPERACIONAL',true),

-- Depreciação: só DRE (o desembolso já aconteceu no investimento, grupo 6) — anti-duplicação.
('4.05.000','Depreciação e Amortização','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',false),
('4.05.001','Depreciação de Móveis e Equipamentos','DEBITO',true,false,'DEPRECIACAO','NAO_APLICA',true),

-- ================== 5 · FINANCEIRAS E EMPRÉSTIMOS ============================
('5.00.000','FINANCEIRAS E EMPRÉSTIMOS','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',false),

('5.01.000','Despesas Financeiras','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',false),
('5.01.001','Juros e Encargos Pagos','DEBITO',true,true,'RESULTADO_FINANCEIRO','FINANCIAMENTO',true),
('5.01.002','Tarifas Bancárias','DEBITO',true,true,'RESULTADO_FINANCEIRO','OPERACIONAL',true),

-- Amortização de principal: só caixa (juros, esses sim, entram na DRE em 5.01.001).
-- Captação: dinheiro que ENTRA (crédito), só caixa.
('5.02.000','Empréstimos','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',false),
('5.02.001','Amortização de Principal de Empréstimos','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true),
('5.02.002','Captação de Empréstimos','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true),

-- ==================== 6 · INVESTIMENTOS E IMOBILIZADO ========================
-- Só fluxo de caixa (investimento); na DRE aparece via depreciação (4.05).
('6.00.000','INVESTIMENTOS E IMOBILIZADO','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false),

('6.01.000','Imobilizado','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',false),
('6.01.001','Compra de Móveis, Máquinas e Equipamentos','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true),
('6.01.002','Reformas e Instalações','DEBITO',false,true,'NAO_APLICA','INVESTIMENTO',true),
('6.01.003','Venda de Imobilizado','CREDITO',false,true,'NAO_APLICA','INVESTIMENTO',true),

-- ==================== 7 · MOVIMENTOS NEUTROS E SÓCIOS ========================
-- Contas NEUTRAS não entram nem na DRE nem no fluxo de caixa — são movimentações internas
-- (transferência entre contas próprias, sangria/suprimento, liquidação de recebíveis) que,
-- se contadas, duplicariam valores. Sócios (7.03) entram só no caixa (financiamento).
('7.00.000','MOVIMENTOS NEUTROS E SÓCIOS','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false),

('7.01.000','Transferências entre Contas Próprias','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false),
('7.01.001','Transferência entre Contas','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),
('7.01.002','Sangria de Caixa','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),
('7.01.003','Suprimento de Caixa','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),

('7.02.000','Liquidação de Meios de Pagamento','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false),
('7.02.001','Recebimento de Cartões e PIX','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),
('7.02.002','Recebimento de Crediário','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',true),

('7.03.000','Sócios e Capital','NEUTRO',false,false,'NAO_APLICA','NAO_APLICA',false),
('7.03.001','Aporte de Sócios','CREDITO',false,true,'NAO_APLICA','FINANCIAMENTO',true),
('7.03.002','Retirada de Sócios (além do pró-labore)','DEBITO',false,true,'NAO_APLICA','FINANCIAMENTO',true),

-- ===================== 8 · TRIBUTOS SOBRE O LUCRO ============================
('8.00.000','TRIBUTOS SOBRE O LUCRO','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',false),

('8.01.000','Tributos sobre o Lucro','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',false),
('8.01.001','IRPJ e CSLL','DEBITO',true,true,'TRIBUTO_LUCRO','OPERACIONAL',true);
