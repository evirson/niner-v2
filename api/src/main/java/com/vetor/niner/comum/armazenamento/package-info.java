/**
 * Object storage — <b>dois</b>, com contratos separados de propósito.
 *
 * <p><b>Privado (ADR-014, {@code docs/infra/armazenamento-privado-minio.md}):</b>
 * {@link com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado} +
 * {@link com.vetor.niner.comum.armazenamento.S3ArmazenamentoPrivado} (MinIO/S3) guardam o que não
 * pode ser público — XML fiscal (imutável, guarda de 5 anos) e dado pessoal. Não existe
 * {@code urlPublica()} aqui: o arquivo sai pela API, autenticado. A
 * {@link com.vetor.niner.comum.armazenamento.AreaPrivada} escolhe bucket, prefixo e mutabilidade
 * de uma vez só.
 *
 * <p><b>Público (ADR-013, {@code docs/infra/armazenamento-imagens.md}):</b> fotos de produto.
 * {@link com.vetor.niner.comum.armazenamento.ArmazenamentoDeArquivos} é a interface que o
 * resto do domínio consome (hoje só {@code catalogo.ProdutoImagemService}); o adapter de
 * verdade ({@link com.vetor.niner.comum.armazenamento.GcsArmazenamento}) fica isolado aqui —
 * trocar de provedor (ex.: Cloudflare R2, se o egress do GCS pesar na fatura) é um adapter
 * novo, não uma mudança no resto do sistema.
 */
package com.vetor.niner.comum.armazenamento;
