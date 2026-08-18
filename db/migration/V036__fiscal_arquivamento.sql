-- V036 — Arquivamento do XML fiscal no bucket privado (DF21 fechada, ADR-014).
-- Ver docs/HANDOFF-ARQUIVAMENTO-XML.md §6.3 e §6.2.

-- Fila de pendências (job de recuperação) — um índice parcial por tabela, cada uma com sua
-- própria coluna de "quando" (documento_fiscal usa data_autorizacao; evento e inutilização não
-- têm essa coluna, então usam criado_em, que é quando a tentativa foi registrada).
CREATE INDEX documento_fiscal_pendente_arquivo_ix
    ON documento_fiscal (id_tenant, data_autorizacao)
 WHERE situacao = 'AUTORIZADO' AND xml_objeto_bucket IS NULL;

CREATE INDEX documento_fiscal_evento_pendente_arquivo_ix
    ON documento_fiscal_evento (id_tenant, criado_em)
 WHERE autorizado = true AND xml_objeto_bucket IS NULL;

CREATE INDEX fiscal_inutilizacao_pendente_arquivo_ix
    ON fiscal_inutilizacao (id_tenant, criado_em)
 WHERE autorizado = true AND xml_objeto_bucket IS NULL;

-- xml_hash muda de significado no momento em que o documento é arquivado (handoff §6.2, item 2):
-- antes do arquivamento é o hash do XML ASSINADO (gravado por DocumentoFiscalRepositorio.
-- gravarAssinado); depois, é o hash do nfeProc que foi de fato parar no bucket. A troca só é
-- possível enquanto xml_objeto_bucket ainda está NULL — o trigger documento_fiscal_imutavel_tg
-- (V035) trava os dois juntos assim que o bucket deixa de ser NULL.
COMMENT ON COLUMN documento_fiscal.xml_hash IS
  'SHA-256. Antes do arquivamento (xml_objeto_bucket NULL): hash do XML ASSINADO. Depois de arquivado: hash do nfeProc gravado no bucket — a troca de significado e permitida pelo trigger de imutabilidade porque so ocorre enquanto xml_objeto_bucket ainda esta NULL.';
