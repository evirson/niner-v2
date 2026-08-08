package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.TamanhoDtos.TamanhoResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** DTOs de grade ({@code cfg_grade}) — curva de tamanhos nomeada (ex.: "Grade 36-44"), até 20
 * tamanhos em ordem (V017). */
public final class GradeDtos {

    private GradeDtos() {
    }

    /** {@code idsTamanho} é a lista de {@code idTamanho} na ordem escolhida pelo usuário — o
     * servidor mapeia a posição na lista (0..19) pros slots {@code id_tamanho1..20}, então o
     * cliente não escolhe o número do slot. Máximo 20 (limite físico da tabela). */
    public record GradeRequest(
            @NotBlank @Size(max = 120) String descricao,
            @Size(max = 20) List<Long> idsTamanho) {
    }

    public record GradeResponse(long idGrade, String descricao, List<TamanhoResponse> tamanhos) {
    }
}
