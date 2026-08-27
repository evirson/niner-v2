-- V080 — Revogação de sessão (2026-08-27)
--
-- Pedido do dono do produto, depois da auditoria de segurança: "se a senha for trocada, ou o
-- usuário desativado, tem que efetuar o logoff do respectivo usuário o mais breve possível — algo
-- que não deixe o sistema lento e não consuma muitos recursos do servidor".
--
-- O problema: o JWT vale 8 horas, não tem `jti` e não havia lista de revogação. Desativar,
-- excluir ou trocar a senha NÃO derrubava a sessão — um funcionário demitido continuava operando
-- até o token vencer sozinho.
--
-- ⭐ POR QUE ISTO NÃO CUSTA NADA: já existe UMA consulta por requisição autenticada — a do horário
-- de acesso (`HorarioAcessoFilter`, 2026-08-11). Esta coluna entra na MESMA consulta, que passa a
-- responder as três perguntas de uma vez (o usuário existe e está ativo? está dentro da janela? a
-- sessão dele ainda vale?). Zero consulta nova, zero cache para invalidar, zero infraestrutura —
-- e o logoff acontece na requisição seguinte, que é o "mais breve possível" possível sem
-- WebSocket.
--
-- ⚠️ Guardar o INSTANTE (e não um contador) permite comparar direto com o `iat` do JWT, que já
-- está assinado dentro do token: nada de tabela de sessões nem de estado no servidor (P6).

ALTER TABLE usuario
  ADD COLUMN IF NOT EXISTS sessao_valida_desde timestamptz NOT NULL DEFAULT now();

COMMENT ON COLUMN usuario.sessao_valida_desde IS
  'Token emitido ANTES deste instante é recusado (V080). Avança ao trocar a senha, ao desativar e '
  'ao excluir/inativar o usuário — é assim que o logoff acontece sem lista de revogação.';

-- Nenhum índice: a coluna é lida sempre junto do `id_usuario`, que é a PK.
