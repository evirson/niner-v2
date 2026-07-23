package com.vetor.niner.catalogo;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** DTOs da galeria de fotos de produto (docs/infra/armazenamento-imagens.md, ADR-013). */
public final class ProdutoImagemDtos {

    private ProdutoImagemDtos() {
    }

    /** {@code url} já vem pronta (montada no servidor, P4 — o front nunca monta URL sozinho). */
    public record ImagemResponse(long idImagem, String url, int indice) {
    }

    /** Nova ordem, pela lista completa de {@code idImagem} — mesmo princípio de "substitui
     * tudo" de {@code produto_categoria}, só que aqui é sempre update (linhas já existem). */
    public record ReordenarImagensRequest(@NotEmpty List<Long> idsImagem) {
    }
}
