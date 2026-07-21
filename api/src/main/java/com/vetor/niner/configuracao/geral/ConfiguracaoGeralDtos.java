package com.vetor.niner.configuracao.geral;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** DTOs da configuração geral do tenant (docs/telas/configuracao-geral.md). */
public final class ConfiguracaoGeralDtos {

    private ConfiguracaoGeralDtos() {
    }

    /**
     * Corpo de atualização. Todos os campos são obrigatórios — a tabela não tem colunas
     * nullable (V023), então não existe "campo vazio" aqui, diferente dos cadastros.
     */
    public record ConfiguracaoGeralRequest(
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal percentualDescontoVenda,
            @NotNull @Min(0) Integer jurosCrediarioDias,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal jurosCrediario,
            @NotNull @Min(0) Integer multaCrediarioDias,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal multaCrediario,
            @NotNull Boolean cfgUsaVarianteLinha,
            @NotNull Boolean cfgUsaVarianteColuna) {
    }

    public record ConfiguracaoGeralResponse(
            BigDecimal percentualDescontoVenda,
            int jurosCrediarioDias,
            BigDecimal jurosCrediario,
            int multaCrediarioDias,
            BigDecimal multaCrediario,
            boolean cfgUsaVarianteLinha,
            boolean cfgUsaVarianteColuna,
            OffsetDateTime atualizadoEm) {
    }
}
