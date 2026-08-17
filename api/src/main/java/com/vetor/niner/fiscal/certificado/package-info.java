/**
 * Certificado Digital A1 do lojista (docs/telas/fiscal-certificado.md, bloco B2 de
 * docs/MODULOFISCAL.md §17.1) — o segredo de <b>terceiro</b> mais sensível do produto (F7).
 *
 * <p>Write-only de verdade: o {@code .pfx} sobe para o bucket fiscal <b>privado</b> (DF21,
 * 2026-08-17 — nunca o bucket de fotos de produto, que é de leitura pública), e a senha é
 * cifrada (AES-256-GCM, {@code comum.seguranca.SegredoCifrador}) com a chave mestra fora do
 * banco. Nenhum endpoint devolve o arquivo nem a senha, em campo nenhum, nem para ADMIN.
 * Certificado antigo nunca é apagado — só marcado {@code ativo = false} — porque a auditoria
 * (F9) precisa saber qual certificado assinou qual nota.
 */
package com.vetor.niner.fiscal.certificado;
