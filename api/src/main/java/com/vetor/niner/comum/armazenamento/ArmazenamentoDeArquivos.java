package com.vetor.niner.comum.armazenamento;

/**
 * Contrato de object storage (ADR-013). O caminho do objeto é sempre derivado internamente
 * — {@code tenants/{id_tenant}/produtos/{id_produto}/{uuid}.{extensao}}, com
 * {@code id_tenant} vindo do {@code TenantContext} (P8, nunca de parâmetro) e {@code uuid}
 * aleatório (o bucket é público, então o caminho não pode ser enumerável).
 */
public interface ArmazenamentoDeArquivos {

    /**
     * Grava os bytes já normalizados e devolve a <b>chave</b> gerada (não a URL completa —
     * é isso que {@code produto_imagem.imagem} guarda). Lança {@link IllegalStateException}
     * se não houver tenant no contexto (nunca grava em caminho sem tenant).
     */
    String gravar(long idProduto, byte[] conteudo, String extensao);

    /** Apaga o objeto pela chave. Idempotente — objeto já ausente não é erro. */
    void apagar(String chave);

    /** Monta a URL pública a partir da chave — o front nunca monta essa URL sozinho (P4). */
    String urlPublica(String chave);
}
