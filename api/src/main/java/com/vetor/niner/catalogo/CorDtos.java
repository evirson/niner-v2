package com.vetor.niner.catalogo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTOs de cor ({@code cfg_cor}) — catálogo do tenant, criado embutido na Emissão de Etiqueta
 * (válvula de escape enquanto a Entrada de Produtos, onde a cor nasce de verdade, não existe). */
public final class CorDtos {

    private CorDtos() {
    }

    public record CorRequest(@NotBlank @Size(max = 60) String descricao) {
    }

    public record CorResponse(long idCor, String descricao) {
    }
}
