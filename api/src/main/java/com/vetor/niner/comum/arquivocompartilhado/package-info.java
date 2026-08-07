/**
 * Compartilhamento efêmero de arquivo por link público (ex.: comprovante enviado por WhatsApp,
 * ver {@code docs/telas/recebimento-crediario.md}). Guarda o conteúdo só em memória (nunca em
 * banco/disco/object storage) por um tempo curto e configurável ({@code
 * niner.arquivo-compartilhado.expiracao-horas}, padrão 24h) — decisão deliberada de não usar o
 * object storage do ADR-013 aqui: aquele bucket é para foto de produto, é público e cobra por
 * armazenamento/operação; este cache não tem custo de infraestrutura nova e nunca precisa
 * sobreviver a um restart da API (é conteúdo descartável por natureza).
 */
package com.vetor.niner.comum.arquivocompartilhado;
