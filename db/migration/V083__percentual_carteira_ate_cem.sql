-- V083 — Percentual de desconto/acréscimo da carteira vai de 0 a 100 (2026-08-27)
--
-- ⭐ ACHADO DA AUDITORIA DE SEGURANÇA: as colunas são `numeric(5,2)` — aceitam até 999,99 — e a
-- única validação em Java recusava o negativo. O PDV calcula a cobertura do pagamento como
-- `valorPago × (1 + perc_desconto/100)`, então **999,99% numa forma de pagamento fazia R$ 10
-- fecharem uma venda de R$ 109,99**.
--
-- ⚠️ E contornava inteiramente o desconto máximo dos Parâmetros do Sistema: aquele teto é
-- revalidado no servidor com cuidado, mas vigia o campo `desconto_venda` — este caminho não passa
-- por ele. Um teto que existe num campo e não no irmão não é um teto.
--
-- A tela Tipos de Carteira **não** é exclusiva do administrador e tem "alterar": quem recebia essa
-- tela alcançava o desconto de todas as vendas da loja.
--
-- ⚠️ `NOT VALID` de propósito, pelo mesmo motivo da V049 (`empresa.estado`): constraint que varre
-- linha existente derruba deploy. As linhas de hoje são conferidas logo abaixo, e o CHECK vale
-- daqui para a frente. Se alguma linha antiga estiver fora da faixa, o `VALIDATE` manual é o passo
-- consciente de quem for corrigi-la.

ALTER TABLE tipo_carteira
  ADD CONSTRAINT tipo_carteira_perc_desconto_ck
  CHECK (perc_desconto IS NULL OR (perc_desconto >= 0 AND perc_desconto <= 100)) NOT VALID;

ALTER TABLE tipo_carteira
  ADD CONSTRAINT tipo_carteira_perc_acrescimo_ck
  CHECK (perc_acrescimo IS NULL OR (perc_acrescimo >= 0 AND perc_acrescimo <= 100)) NOT VALID;

-- Conferência do que já existe. Nenhuma linha deve aparecer; se aparecer, é dado a corrigir antes
-- de validar as constraints — e é melhor descobrir aqui do que numa venda.
DO $$
DECLARE fora integer;
BEGIN
  ALTER TABLE tipo_carteira NO FORCE ROW LEVEL SECURITY;
  SELECT count(*) INTO fora FROM tipo_carteira
   WHERE perc_desconto > 100 OR perc_acrescimo > 100 OR perc_desconto < 0 OR perc_acrescimo < 0;
  ALTER TABLE tipo_carteira FORCE ROW LEVEL SECURITY;
  IF fora > 0 THEN
    RAISE WARNING 'V083: % carteira(s) com percentual fora de 0..100 — corrija antes de VALIDATE', fora;
  END IF;
END $$;

-- ⚠️ `NO FORCE ROW LEVEL SECURITY` acima não é preciosismo: migration roda como `niner_owner`, e
-- com FORCE RLS (V024) nem o dono escapa da política. Sem tenant no contexto, o count viria ZERO
-- em silêncio e a conferência não conferiria nada — mesmo defeito da V057.
