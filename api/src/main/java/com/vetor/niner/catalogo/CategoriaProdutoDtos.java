package com.vetor.niner.catalogo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTOs da categoria de produto ({@code cfg_categoria_produto}, docs/telas/produto.md). */
public final class CategoriaProdutoDtos {

    private CategoriaProdutoDtos() {
    }

    public record CategoriaRequest(@NotBlank @Size(max = 120) String nomeCategoria) {
    }

    public record CategoriaResponse(long idCategoria, String nomeCategoria) {
    }
}
