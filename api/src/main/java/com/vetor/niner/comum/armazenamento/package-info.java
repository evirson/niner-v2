/**
 * Object storage das fotos de produto (ADR-013, {@code docs/infra/armazenamento-imagens.md}).
 * {@link com.vetor.niner.comum.armazenamento.ArmazenamentoDeArquivos} é a interface que o
 * resto do domínio consome (hoje só {@code catalogo.ProdutoImagemService}); o adapter de
 * verdade ({@link com.vetor.niner.comum.armazenamento.GcsArmazenamento}) fica isolado aqui —
 * trocar de provedor (ex.: Cloudflare R2, se o egress do GCS pesar na fatura) é um adapter
 * novo, não uma mudança no resto do sistema.
 */
package com.vetor.niner.comum.armazenamento;
