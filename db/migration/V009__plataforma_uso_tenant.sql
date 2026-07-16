-- V009 — uso_tenant: contadores para enforcement dos limites do plano (R19).
-- Uma linha por tenant. Estruturais (canais/produtos/usuarios) = totais correntes;
-- pedidos = contador da competência corrente (reseta quando o mês vira).

CREATE TABLE plataforma.uso_tenant (
  id_tenant           smallint    PRIMARY KEY REFERENCES plataforma.tenant (id_tenant),
  qtd_canais          integer     NOT NULL DEFAULT 0 CHECK (qtd_canais      >= 0),
  qtd_produtos        integer     NOT NULL DEFAULT 0 CHECK (qtd_produtos    >= 0),
  qtd_usuarios        integer     NOT NULL DEFAULT 0 CHECK (qtd_usuarios    >= 0),
  competencia_pedidos date        NOT NULL DEFAULT date_trunc('month', now())::date,
  qtd_pedidos_mes     integer     NOT NULL DEFAULT 0 CHECK (qtd_pedidos_mes >= 0),
  atualizado_em       timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE  plataforma.uso_tenant IS 'Uso corrente do tenant vs. limites do plano (R19). Atualizado por eventos de dominio.';
COMMENT ON COLUMN plataforma.uso_tenant.competencia_pedidos IS 'Mes de referencia de qtd_pedidos_mes; ao virar o mes, reseta o contador.';
