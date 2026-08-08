package com.vetor.niner.catalogo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTOs de tamanho ({@code cfg_tamanho}) — catálogo do tenant, criado embutido no modal
 * "Gerenciar Grades" da tela de Produto. */
public final class TamanhoDtos {

    private TamanhoDtos() {
    }

    public record TamanhoRequest(@NotBlank @Size(max = 60) String descricao) {
    }

    public record TamanhoResponse(long idTamanho, String descricao) {
    }
}
