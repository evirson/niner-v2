package com.vetor.niner.cadastros.cliente;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTOs da categoria de cliente ({@code cfg_categoria_cliente}, docs/telas/cliente.md). */
public final class CategoriaClienteDtos {

    private CategoriaClienteDtos() {
    }

    public record CategoriaRequest(@NotBlank @Size(max = 120) String nomeCategoria) {
    }

    public record CategoriaResponse(long idCategoriaCliente, String nomeCategoria) {
    }
}
