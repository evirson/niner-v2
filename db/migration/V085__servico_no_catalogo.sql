-- V085 — o item que não é mercadoria (bloco S1 de docs/MODULOSERVICOS.md)
--
-- Pedido do dono do produto em 2026-08-28: a petshop que o ERP atende vende produto mas não cobre
-- banho e tosa; a oficina tem as peças cobertas e a mão de obra não. Estudo completo, com as 13
-- decisões e as fontes: `docs/MODULOSERVICOS.md`.
--
-- ⭐ POR QUE SERVIÇO MORA EM `produto`, E NÃO NUMA TABELA PRÓPRIA (DS1)
--
-- Porque **este sistema não tem tabela de itens de venda**. O item da venda É a linha do ledger de
-- estoque (`produto_movimento_detalhe`), e 33 arquivos Java leem de lá — DRE, Lucratividade,
-- Comissões, Relatório de Vendas, papeleta, histórico do cliente, CRM e o montador da NFC-e.
-- Um item que não gere linha no ledger **não existe** para nenhum deles, e não com erro: em
-- silêncio, que é a forma que este repositório já provou ser a mais cara.
--
-- Uma tabela `servico` separada é mais bonita no diagrama e obrigaria a reescrever esses 33
-- leitores como UNION. Entre um nome torto (`produto` passa a significar "coisa vendável") e duas
-- apurações de lucro divergindo, o nome torto é mais barato — e é reversível por RENAME no dia em
-- que valer a pena. Racional completo em `docs/MODULOSERVICOS.md` §3.4.
--
-- ⛔ F12 APLICADO: nada aqui muda o comportamento de quem não usa serviço. A coluna nasce com
-- DEFAULT, o parâmetro nasce DESLIGADO, e nenhuma tela existente passa a exigir campo novo.

-- ---------------------------------------------------------------------------------------------
-- 1. O tipo
-- ---------------------------------------------------------------------------------------------

CREATE TYPE tipo_item AS ENUM ('MERCADORIA', 'SERVICO');

COMMENT ON TYPE tipo_item IS
  'O que a linha do catálogo é: MERCADORIA (tem estoque, entra na NFC-e/NF-e) ou SERVICO (não tem '
  'estoque, é a mão de obra da oficina e o banho da petshop; documento fiscal é NFS-e, ver '
  'docs/MODULOSERVICOS.md §5).';

-- ---------------------------------------------------------------------------------------------
-- 2. `produto` ganha o tipo
-- ---------------------------------------------------------------------------------------------
-- ⚠️ `GRANT` por coluna NÃO é necessário aqui — medido no catálogo antes de escrever esta
-- migration: `produto`, `produto_barra` e `cfg_geral` têm ACL de TABELA (`niner_app=arwd` em
-- `pg_class.relacl`) e ZERO colunas com ACL própria, então coluna nova nasce acessível.
-- ⚠️ A V072 afirma o contrário sobre `empresa` (*"niner_app não tem privilégio no nível da
-- TABELA"*) e a afirmação é falsa — `empresa` também tem ACL de tabela; aquele
-- `GRANT (id_ramo)` foi redundante, não errado. Registrado aqui porque a suíte NÃO pegaria a
-- diferença: o Testcontainers conecta como superusuário do container.

ALTER TABLE produto
  ADD COLUMN tipo_item tipo_item NOT NULL DEFAULT 'MERCADORIA';

COMMENT ON COLUMN produto.tipo_item IS
  'MERCADORIA (padrão, todo o catálogo existente) ou SERVICO. Decide se a linha tem estoque, se '
  'entra na NFC-e e se aparece nas telas de estoque. Ver docs/MODULOSERVICOS.md §3.6 para a lista '
  'completa de quem filtra por ele.';

-- ---------------------------------------------------------------------------------------------
-- 3. `produto_barra` recebe o tipo DENORMALIZADO — e isso é decisão de desempenho, não descuido
-- ---------------------------------------------------------------------------------------------
-- A trigger de estoque roda em CADA linha de movimento e tem em mãos apenas `id_variacao`.
-- Perguntar o tipo em `produto` custaria um JOIN no caminho mais quente do sistema — o mesmo
-- caminho sobre o qual a V054 registrou: *"o caminho comum não pode pagar por isso, senão uma
-- importação de 10 mil linhas pagaria a conta"*.
--
-- ⚠️ Coluna denormalizada precisa de quem a mantenha em dia. Aqui a resposta é dura de propósito:
-- **o tipo de um item é imutável depois de criado** (ver a trigger da seção 5). Não há caminho de
-- atualização a sincronizar, porque não há atualização.

ALTER TABLE produto_barra
  ADD COLUMN tipo_item tipo_item NOT NULL DEFAULT 'MERCADORIA';

COMMENT ON COLUMN produto_barra.tipo_item IS
  'Cópia de produto.tipo_item, mantida pela trigger tg_produto_barra_herda_tipo_item. Existe para '
  'a trigger de estoque decidir sem JOIN — ela só tem id_variacao em mãos e roda em cada linha de '
  'movimento. Como o tipo é imutável (V085 §5), a cópia nunca sai de sincronia.';

-- Backfill: nenhum. Todo o catálogo existente é mercadoria, que é exatamente o DEFAULT.
-- ⚠️ Se um dia esta migration precisar ler dado de tenant, lembre que `FORCE ROW LEVEL SECURITY`
-- vale até para o dono e o SELECT sai VAZIO em silêncio (ver CLAUDE.md / V057).

-- ---------------------------------------------------------------------------------------------
-- 4. `produto_servico` — o que só serviço tem
-- ---------------------------------------------------------------------------------------------
-- Extensão 1:1, não colunas em `produto`: são campos que não fazem sentido nenhum para 100% do
-- catálogo de uma loja de calçados, e o padrão do projeto para isso é tabela filha
-- (`contas_receber_detalhe` é 1:1 com `contas_receber` pelo mesmo motivo).
--
-- ⚠️ Os campos FISCAIS (código da LC 116, alíquota de ISS, retenção, local de incidência) NÃO
-- entram aqui ainda — são o bloco S5, e dependem do contador e da lista oficial carregada da
-- fonte, nunca digitada de memória (a regra do NCM, das 27 UFs e dos 28 ramos).

CREATE TABLE produto_servico (
  id_produto     integer      NOT NULL,
  id_tenant      smallint     NOT NULL REFERENCES plataforma.tenant (id_tenant),
  duracao_minutos integer,
  perc_comissao  numeric(5,2),
  criado_em      timestamptz  NOT NULL DEFAULT now(),
  atualizado_em  timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT produto_servico_pk PRIMARY KEY (id_tenant, id_produto),
  CONSTRAINT produto_servico_produto_fk FOREIGN KEY (id_tenant, id_produto)
    REFERENCES produto (id_tenant, id_produto),
  CONSTRAINT produto_servico_duracao_ck    CHECK (duracao_minutos IS NULL OR duracao_minutos > 0),
  CONSTRAINT produto_servico_comissao_ck   CHECK (perc_comissao IS NULL
                                                  OR (perc_comissao >= 0 AND perc_comissao <= 100))
);

CREATE INDEX produto_servico_id_tenant_ix ON produto_servico (id_tenant);

COMMENT ON TABLE produto_servico IS
  'O que só serviço tem. 1:1 com produto (linha existe apenas quando produto.tipo_item = SERVICO). '
  'Os campos fiscais de ISS/LC 116 entram no bloco S5 — ver docs/MODULOSERVICOS.md §5.4.';
COMMENT ON COLUMN produto_servico.duracao_minutos IS
  'Duração típica, para a agenda que ainda não existe (S8) e para o operador dimensionar o dia. '
  'Nulo = não informado; nunca é usado para bloquear nada.';
COMMENT ON COLUMN produto_servico.perc_comissao IS
  'Comissão DESTE serviço, que sobrepõe funcionario.perc_comissao quando preenchida (DS5). Nulo = '
  'usa a do funcionário, como hoje. É o padrão de mercado nos verticais de petshop, e o Nainer já '
  'guarda o funcionário POR LINHA do movimento — o que quase nenhum concorrente faz.';

-- ⚠️ CHECK 0–100 em percentual não é enfeite: a auditoria de 2026-08-27 achou
-- `tipo_carteira.perc_desconto` sem teto, e 999,99% fechava venda de R$ 1.000 com R$ 91 (V083).

-- RLS — obrigatória e não automática. A spec do orçamento registra que quase passou sem.
ALTER TABLE produto_servico ENABLE ROW LEVEL SECURITY;
ALTER TABLE produto_servico FORCE  ROW LEVEL SECURITY;

CREATE POLICY produto_servico_tenant ON produto_servico
  USING      (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());

GRANT SELECT, INSERT, UPDATE, DELETE ON produto_servico TO niner_app;

-- ---------------------------------------------------------------------------------------------
-- 5. O tipo é IMUTÁVEL, e a cópia se mantém sozinha
-- ---------------------------------------------------------------------------------------------
-- Por que imutável: virar um produto com estoque e histórico de venda em serviço (ou o contrário)
-- deixaria `produto_estoque` com saldo de algo que não tem saldo, o Kardex com um item que sumiu
-- do relatório, e a NFC-e de ontem descrevendo mercadoria que hoje é mão de obra. Nenhum desses
-- daria erro — todos ficariam errados em silêncio.
-- Quem se enganou no cadastro inativa e cria outro, que é o mesmo caminho de correção que o
-- projeto já usa para venda e para orçamento ("corrige-se cancelando").

CREATE OR REPLACE FUNCTION fn_produto_barra_herda_tipo_item()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    SELECT p.tipo_item INTO NEW.tipo_item
      FROM produto p
     WHERE p.id_tenant = NEW.id_tenant
       AND p.id_produto = NEW.id_produto;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_produto_barra_herda_tipo_item() IS
  'Copia produto.tipo_item para a variação no INSERT. BEFORE, para gravar de uma vez em vez de '
  'reescrever a linha depois. Ver V085 §3 para por que a cópia existe.';

CREATE TRIGGER tg_produto_barra_herda_tipo_item
  BEFORE INSERT ON produto_barra
  FOR EACH ROW EXECUTE FUNCTION fn_produto_barra_herda_tipo_item();

CREATE OR REPLACE FUNCTION fn_produto_tipo_item_imutavel()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tipo_item IS DISTINCT FROM OLD.tipo_item THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'TIPO_ITEM_IMUTAVEL|Não é possível trocar mercadoria por serviço (nem o '
                   || 'contrário) depois de cadastrado: o histórico de estoque, os relatórios e as '
                   || 'notas já emitidas descrevem o item como ele era. Inative este cadastro e '
                   || 'crie outro.';
    END IF;
    RETURN NEW;
END;
$$;

-- ⚠️ ERRCODE 23514 + prefixo: sem o prefixo, o GlobalExceptionHandler responderia a genérica de FK
-- ("registro em uso por outro cadastro") a quem tentou editar um produto — mensagem sobre exclusão
-- para uma edição. Mesma armadilha que a V054 documentou com ESTOQUE_NEGATIVO|.

CREATE TRIGGER tg_produto_tipo_item_imutavel
  BEFORE UPDATE OF tipo_item ON produto
  FOR EACH ROW EXECUTE FUNCTION fn_produto_tipo_item_imutavel();

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_app') THEN
    EXECUTE 'GRANT EXECUTE ON FUNCTION fn_produto_barra_herda_tipo_item() TO niner_app';
    EXECUTE 'GRANT EXECUTE ON FUNCTION fn_produto_tipo_item_imutavel()   TO niner_app';
  END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 6. O parâmetro — opt-in, e o padrão é decisão dele
-- ---------------------------------------------------------------------------------------------
-- Palavras do dono do produto (2026-08-28): *"por padrão o módulo de serviço vai precisar ligar ele
-- pra funcionar, pois as empresas de serviço são menos que as de comércio"*.
-- ⭐ É o mesmo critério do cfg_usa_cor_grade: o padrão serve a MAIORIA da base, e quem é exceção
-- liga. E é `DEFAULT` de migration, que é caro de inverter depois — a V054 nasceu com o padrão
-- trocado e precisou da V055 para consertar.

ALTER TABLE cfg_geral
  ADD COLUMN cfg_usa_servicos boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN cfg_geral.cfg_usa_servicos IS
  'Liga o módulo de serviços (Parâmetros do Sistema). DESLIGADO por padrão, por decisão do dono do '
  'produto em 2026-08-28: empresa de serviço é minoria da base, e a loja de calçados não deve '
  'ganhar um seletor Mercadoria/Serviço que nunca vai usar. Desligado, o cadastro de produto se '
  'comporta exatamente como antes (F12).';
