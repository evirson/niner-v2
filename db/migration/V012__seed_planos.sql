-- V012 — Seed dos planos iniciais.
-- 🔴 VALORES PROVISÓRIOS (decisão D1 em aberto — ver docs/PLANO-DE-NEGOCIO.md).
-- Idempotente por nome; o admin pode ajustar preços/limites depois pelo backoffice.

INSERT INTO plataforma.plano
  (nome, descricao, ciclo_padrao, preco_mensal, preco_anual, ativo,
   limite_canais, limite_produtos, limite_usuarios, limite_pedidos_mes)
VALUES
  ('Essencial',    'Loja fisica + 1 canal online.',   'MENSAL',  99.00,  990.00, true, 1,   500,  2,   300),
  ('Profissional', 'Multicanal (ML + Shopee).',       'MENSAL', 249.00, 2490.00, true, 3,  5000,  5,  2000),
  ('Escala',       'Todos os canais e maior volume.', 'MENSAL', 599.00, 5990.00, true, 5, 50000, 15, 10000)
ON CONFLICT (nome) DO NOTHING;
