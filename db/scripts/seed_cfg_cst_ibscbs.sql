-- Carga de cfg_cst_ibscbs (V034) — CST do IBS/CBS, IT 2025.002 (RFB/CGIBS).
-- NAO e migration Flyway: tabela de referencia nacional, GLOBAL (sem id_tenant/RLS),
-- mantida por script, mesmo padrao de cfg_produto_ncm. Rodar como niner_owner:
--   docker exec -i niner-db psql -U niner_owner -d niner_db < db/scripts/seed_cfg_cst_ibscbs.sql
-- Gerado de .scratch/cClassTrib.xlsx (aba "CST"). Idempotente.

INSERT INTO cfg_cst_ibscbs (codigo_cst, descricao, ind_gibscbs, versao_nt) VALUES
  ('000', 'Tributação integral', true, 'IT 2025.002'),
  ('010', 'Tributação com alíquotas uniformes', false, 'IT 2025.002'),
  ('011', 'Tributação com alíquotas uniformes reduzidas', false, 'IT 2025.002'),
  ('200', 'Alíquota reduzida', true, 'IT 2025.002'),
  ('210', 'Redução de alíquota com redutor de base de cálculo', true, 'IT 2025.002'),
  ('220', 'Alíquota fixa', true, 'IT 2025.002'),
  ('222', 'Redução de base de cálculo', true, 'IT 2025.002'),
  ('221', 'Alíquota fixa proporcional', false, 'IT 2025.002'),
  ('400', 'Isenção', false, 'IT 2025.002'),
  ('410', 'Imunidade e não incidência', false, 'IT 2025.002'),
  ('510', 'Diferimento', true, 'IT 2025.002'),
  ('515', 'Diferimento com redução de alíquota', true, 'IT 2025.002'),
  ('550', 'Suspensão', true, 'IT 2025.002'),
  ('620', 'Tributação monofásica', false, 'IT 2025.002'),
  ('800', 'Transferência de crédito', false, 'IT 2025.002'),
  ('810', 'Ajuste de IBS na ZFM', false, 'IT 2025.002'),
  ('811', 'Ajustes', false, 'IT 2025.002'),
  ('820', 'Tributação em declaração de regime específico', false, 'IT 2025.002'),
  ('830', 'Exclusão de base de cálculo', true, 'IT 2025.002')
ON CONFLICT (codigo_cst) DO NOTHING;
