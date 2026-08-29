-- V097 — conserta a migração de concessões da V096, que saiu VAZIA em silêncio.
--
-- ⛔ O DEFEITO (o mesmo da V089, de novo)
--
-- A V096 fez `UPDATE usuario_permissao SET excluir = true WHERE chave_tela = 'orcamentos' AND
-- incluir = true` para que quem já podia cancelar orçamento continuasse podendo. Migration roda
-- como `niner_owner` — e `usuario_permissao` tem `FORCE ROW LEVEL SECURITY`, que **não poupa nem
-- o dono da tabela**. Sem `app.id_tenant` no contexto, a política casa zero linhas.
--
-- Medido neste banco, e é a diferença entre as duas leituras que prova:
--     SET app.id_tenant=1; SELECT count(*) FROM usuario_permissao;  -->  4
--     (sem SET)            SELECT count(*) FROM usuario_permissao;  -->  0
--
-- O Flyway anunciou **sucesso**, porque não tem como saber que zero era o número errado. Em
-- produção, com concessões reais, o resultado seria pior que nada: a V096 passou a EXIGIR a ação
-- `excluir` no controller, e sem a migração o vendedor que cancelava orçamento ontem levaria 403
-- hoje — tirado em silêncio, exatamente o que a V096 dizia estar evitando.
--
-- ⛔ **Não editar a V096**: migration aplicada não se edita (o checksum quebra o deploy, e o erro
-- não reproduz na máquina de quem editou, porque banco recriado do zero roda o arquivo novo).
-- Conserto é sempre para frente, e idempotente para valer nos dois mundos.
--
-- ⚠️ `NO FORCE`, não `DISABLE`: libera só o dono; a política continua valendo para `niner_app`.

ALTER TABLE usuario_permissao NO FORCE ROW LEVEL SECURITY;

UPDATE usuario_permissao
   SET excluir = true
 WHERE chave_tela = 'orcamentos' AND incluir = true AND excluir = false;

ALTER TABLE usuario_permissao FORCE ROW LEVEL SECURITY;

-- ⚠️ E o resultado se CONFERE, senão a migration não foi verificada — foi torcida. Aqui dá para
-- ir além do `SELECT` manual: se sobrar alguma linha com `incluir` e sem `excluir`, o UPDATE não
-- alcançou o que devia e a migration falha em vez de mentir.
DO $$
DECLARE faltando integer;
BEGIN
    ALTER TABLE usuario_permissao NO FORCE ROW LEVEL SECURITY;
    SELECT count(*) INTO faltando
      FROM usuario_permissao
     WHERE chave_tela = 'orcamentos' AND incluir = true AND excluir = false;
    ALTER TABLE usuario_permissao FORCE ROW LEVEL SECURITY;
    IF faltando > 0 THEN
        RAISE EXCEPTION 'V097: % concessões de orçamento ficaram sem a ação excluir', faltando;
    END IF;
END $$;
