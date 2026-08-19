-- V037 — Modelo comercial por VOLUME DE VENDAS (ADR-015).
--
-- Sai o trial de 60 dias (D2) e saem os 3 planos por funcionalidade (D1, seed V012); entra o
-- plano Gratuito SEM PRAZO limitado a vendas/mês, e faixas pagas geradas por FÓRMULA a partir
-- de parametro_comercial. Preço passa a ser DADO: mudar preço é UPDATE + SELECT da função,
-- nunca deploy.
--
-- Tabelas de plataforma são GLOBAIS (P9) — sem id_tenant, sem RLS. Dinheiro em NUMERIC (P7).

-- ---------------------------------------------------------------------------------------------
-- Parâmetros comerciais da Vetor — singleton (id = 1).
-- 🔴 VALORES PROVISÓRIOS: o dono do produto ainda vai calibrar preco_base, fator_faixa,
-- preco_maximo, vendas_maximo e tolerancia_vendas. Depois de mudar, rode:
--     UPDATE plataforma.parametro_comercial SET preco_base = ... WHERE id = 1;
--     SELECT plataforma.gerar_faixas_planos();
-- ---------------------------------------------------------------------------------------------
CREATE TABLE plataforma.parametro_comercial (
  id                  smallint      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  vendas_gratuito_mes integer       NOT NULL DEFAULT 100    CHECK (vendas_gratuito_mes > 0),
  -- vendas extras aceitas DEPOIS de estourar a cota, antes de bloquear (ADR-015): a venda 101
  -- não pode parar a loja com cliente no balcão. Parâmetro da Vetor, não do lojista.
  tolerancia_vendas   integer       NOT NULL DEFAULT 20     CHECK (tolerancia_vendas >= 0),
  preco_base          numeric(12,2) NOT NULL DEFAULT 99.00  CHECK (preco_base > 0),
  passo_vendas        integer       NOT NULL DEFAULT 500    CHECK (passo_vendas > 0),
  -- 1.000 = crescimento LINEAR (faixa n custa n × preco_base); < 1 atenua o incremento de cada
  -- faixa (soma de PG: 1 + f + f² + … + f^(n-1)).
  fator_faixa         numeric(4,3)  NOT NULL DEFAULT 1.000  CHECK (fator_faixa > 0 AND fator_faixa <= 1),
  preco_maximo        numeric(12,2) NOT NULL DEFAULT 990.00 CHECK (preco_maximo > 0),
  vendas_maximo       integer       NOT NULL DEFAULT 10000  CHECK (vendas_maximo > 0),
  desconto_anual      numeric(4,3)  NOT NULL DEFAULT 0.150  CHECK (desconto_anual >= 0 AND desconto_anual < 1),
  atualizado_em       timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON TABLE plataforma.parametro_comercial IS
  'Parametros do modelo comercial (ADR-015). Singleton. As faixas de plano sao GERADAS daqui por plataforma.gerar_faixas_planos().';

INSERT INTO plataforma.parametro_comercial (id) VALUES (1);

-- ---------------------------------------------------------------------------------------------
-- plano ganha a dimensão medida e a identidade da faixa.
-- Os limites estruturais (canais/produtos/usuarios/pedidos_mes) FICAM na tabela, mas passam a
-- ser NULL = ilimitado em todos os planos: o produto não vende funcionalidade, vende volume.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE plataforma.plano
  ADD COLUMN limite_vendas_mes integer  CHECK (limite_vendas_mes IS NULL OR limite_vendas_mes > 0),
  ADD COLUMN faixa_ordem       smallint CHECK (faixa_ordem IS NULL OR faixa_ordem >= 0),
  ADD COLUMN gratuito          boolean  NOT NULL DEFAULT false;

COMMENT ON COLUMN plataforma.plano.limite_vendas_mes IS 'Vendas emitidas/mes incluidas no plano (soma todas as empresas do tenant). NULL = ilimitado.';
COMMENT ON COLUMN plataforma.plano.faixa_ordem       IS 'Ordem da faixa gerada: 0 = Gratuito, n = passo x n vendas/mes. NULL = plano legado (V012), fora da geracao.';
COMMENT ON COLUMN plataforma.plano.gratuito          IS 'true no plano Gratuito (preco zero, sem prazo de validade).';

-- upsert da geração é POR FAIXA (não por nome): o nome muda quando o passo muda.
CREATE UNIQUE INDEX plano_faixa_ordem_uk ON plataforma.plano (faixa_ordem) WHERE faixa_ordem IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Gerador das faixas. Regras que NÃO são negociáveis:
--   · NUNCA faz DELETE — plataforma.assinatura tem FK para plano e o preço histórico importa.
--     Faixa que sai da configuração é DESATIVADA (ativo = false), nunca apagada.
--   · Faixa em uso por assinatura viva continua ativa mesmo que saia do range configurado
--     (senão o cliente ficaria apontando para plano inativo).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION plataforma.gerar_faixas_planos()
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
  p        plataforma.parametro_comercial%ROWTYPE;
  faixas   integer;
  n        integer;
  termo    numeric(20,8);
  soma     numeric(20,8);
  mensal   numeric(12,2);
  anual    numeric(12,2);
  vendas   integer;
  geradas  integer := 0;
BEGIN
  SELECT * INTO p FROM plataforma.parametro_comercial WHERE id = 1;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'parametro_comercial nao configurado';
  END IF;

  -- faixa 0 = Gratuito (preço zero, sem prazo).
  INSERT INTO plataforma.plano (nome, descricao, ciclo_padrao, preco_mensal, preco_anual, ativo,
                                limite_vendas_mes, faixa_ordem, gratuito)
  VALUES ('Gratuito',
          'Até ' || p.vendas_gratuito_mes || ' vendas/mês. Sem prazo de validade, sem cartão, com todas as funções liberadas.',
          'MENSAL', 0, 0, true, p.vendas_gratuito_mes, 0, true)
  ON CONFLICT (faixa_ordem) WHERE faixa_ordem IS NOT NULL DO UPDATE
    SET nome = EXCLUDED.nome, descricao = EXCLUDED.descricao, preco_mensal = 0, preco_anual = 0,
        ativo = true, limite_vendas_mes = EXCLUDED.limite_vendas_mes, gratuito = true;
  geradas := 1;

  faixas := ceil(p.vendas_maximo::numeric / p.passo_vendas);
  termo  := 1;
  soma   := 0;

  FOR n IN 1..faixas LOOP
    IF n > 1 THEN
      termo := termo * p.fator_faixa;
    END IF;
    soma   := soma + termo;                                   -- 1 + f + f² + … + f^(n-1)
    mensal := LEAST(p.preco_maximo, round(p.preco_base * soma, 2));
    anual  := round(mensal * 12 * (1 - p.desconto_anual), 2);
    vendas := p.passo_vendas * n;

    INSERT INTO plataforma.plano (nome, descricao, ciclo_padrao, preco_mensal, preco_anual, ativo,
                                  limite_vendas_mes, faixa_ordem, gratuito)
    VALUES ('Até ' || vendas || ' vendas/mês',
            'Faixa ' || n || ': até ' || vendas || ' vendas emitidas por mês, somando todos os CNPJs.',
            'MENSAL', mensal, anual, true, vendas, n, false)
    ON CONFLICT (faixa_ordem) WHERE faixa_ordem IS NOT NULL DO UPDATE
      SET nome = EXCLUDED.nome, descricao = EXCLUDED.descricao,
          preco_mensal = EXCLUDED.preco_mensal, preco_anual = EXCLUDED.preco_anual,
          ativo = true, limite_vendas_mes = EXCLUDED.limite_vendas_mes, gratuito = false;
    geradas := geradas + 1;
  END LOOP;

  -- faixas que saíram da configuração: desativa (nunca apaga), exceto se houver assinatura viva.
  UPDATE plataforma.plano pl
     SET ativo = false
   WHERE pl.faixa_ordem > faixas
     AND NOT EXISTS (SELECT 1 FROM plataforma.assinatura a
                      WHERE a.id_plano = pl.id_plano AND a.status <> 'CANCELADA');

  RETURN geradas;
END;
$$;

COMMENT ON FUNCTION plataforma.gerar_faixas_planos() IS
  'Regera as faixas de plano a partir de parametro_comercial (ADR-015). Faz UPDATE/INSERT por faixa_ordem e NUNCA DELETE (FK de assinatura + preco historico).';

-- ---------------------------------------------------------------------------------------------
-- Planos antigos (V012) saem de cena: desativados, nunca apagados (assinatura aponta para eles).
-- Ficam com faixa_ordem NULL, fora da geração.
-- ---------------------------------------------------------------------------------------------
UPDATE plataforma.plano SET ativo = false WHERE nome IN ('Essencial', 'Profissional', 'Escala');

-- Todo plano passa a ter ferramenta ilimitada — o que se cobra é volume (ADR-015).
UPDATE plataforma.plano
   SET limite_canais = NULL, limite_produtos = NULL, limite_usuarios = NULL, limite_pedidos_mes = NULL;

SELECT plataforma.gerar_faixas_planos();

-- niner_app só LÊ os parâmetros comerciais pela API do tenant; quem escreve é o backoffice
-- (mesma role hoje, mas o REVOKE deixa a intenção explícita e o dia do split fica barato).
GRANT SELECT ON plataforma.parametro_comercial TO niner_app;
