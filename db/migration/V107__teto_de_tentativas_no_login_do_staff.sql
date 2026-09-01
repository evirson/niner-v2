-- V107 — Teto de tentativas no login do backoffice (2026-09-01)
--
-- Pendência #40 da auditoria de segurança. O login do staff é a credencial mais valiosa do
-- produto — um token aud=plataforma alcança a lista de contas, os leads (LGPD), o CSRT por UF e a
-- configuração da plataforma — e era o único ponto de autenticação SEM teto de tentativas.
--
-- ⚠️ O limite por IP (LimiteRequisicaoFilter, 10/min) já cobria a rajada de UMA origem, mas não é
-- teto de conta: ele é por instância e em memória (P6, sem Redis), então reinício da API zera, e
-- várias origens dividem a mesma conta-alvo sem nunca estourar o balde de nenhuma delas.
--
-- ⚠️ As colunas ficam em plataforma.staff (bounded: uma linha por conta que existe) em vez de uma
-- tabela chaveada por e-mail digitado. Uma tabela por e-mail cobriria também o e-mail inexistente
-- — fechando o oráculo de "conta bloqueada" — mas cresceria sem teto com e-mail inventado. A
-- troca está declarada no javadoc de StaffService: o oráculo que sobra custa 5 tentativas sob
-- limite de IP, contra o oráculo de TEMPO que ele substitui, que custava 1 requisição e era grátis.

ALTER TABLE plataforma.staff
  ADD COLUMN IF NOT EXISTS tentativas_login smallint    NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS bloqueado_ate    timestamptz;

COMMENT ON COLUMN plataforma.staff.tentativas_login IS
  'Falhas de senha consecutivas. Zerado no login bem-sucedido; 5 falhas ligam bloqueado_ate (V107).';
COMMENT ON COLUMN plataforma.staff.bloqueado_ate IS
  'Ate quando o login desta conta e recusado com 429. NULL = liberada (V107).';

-- ⚠️ O UPDATE do contador roda FORA de transacao, de proposito: dentro dela o rollback da excecao
-- que informa o erro apagaria a tentativa, e o teto ficaria escrito na tela sem existir no banco
-- (foi exatamente o defeito do 2FA, corrigido em 2026-08-27). niner_app ja tem UPDATE na tabela;
-- este GRANT esta aqui para nao depender disso silenciosamente.
GRANT SELECT, UPDATE ON plataforma.staff TO niner_app;
