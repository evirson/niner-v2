package com.vetor.niner.fiscal.certificado;

import java.time.OffsetDateTime;

/** DTOs do Certificado Digital (docs/telas/fiscal-certificado.md). Write-only de verdade: nenhum
 * response aqui carrega o arquivo nem a senha, em campo algum. */
public final class FiscalCertificadoDtos {

    private FiscalCertificadoDtos() {
    }

    /** Badge calculado na hora — nunca gravado, sempre derivado de {@code validoAte}/{@code ativo}. */
    public enum SituacaoCertificado {
        ATIVO, VENCE_EM_BREVE, VENCIDO, SUBSTITUIDO
    }

    public record FiscalCertificadoResponse(
            long idCertificado,
            long idEmpresa,
            String cnpjTitular,
            String razaoSocialTitular,
            OffsetDateTime validoDe,
            OffsetDateTime validoAte,
            String impressaoDigital,
            boolean ativo,
            SituacaoCertificado situacao,
            Long diasParaVencer,
            OffsetDateTime criadoEm) {
    }

    public record FiscalCertificadoUsoResponse(
            long idUso,
            String finalidade,
            Long idDocumentoFiscal,
            Long idUsuario,
            OffsetDateTime ocorridoEm) {
    }
}
