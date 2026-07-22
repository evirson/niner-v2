package com.vetor.niner.catalogo;

import java.math.BigDecimal;

/** DTO da referência de NCM ({@code cfg_produto_ncm}, tabela global — ver package-info). */
public final class NcmDtos {

    private NcmDtos() {
    }

    public record NcmResponse(String codigoNcm, String descricaoNcm, BigDecimal aliquotaIbpt) {
    }
}
