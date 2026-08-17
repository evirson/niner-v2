package com.vetor.niner.fiscal.configuracao;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da configuração fiscal por empresa (docs/telas/fiscal-configuracao.md). */
public final class FiscalConfigDtos {

    private FiscalConfigDtos() {
    }

    /**
     * Corpo de gravação. Campos opcionais são <b>boxed</b> de propósito: um {@code boolean}
     * primitivo em record de request quebra com 400 quando o JSON omite a chave — o
     * {@code ObjectMapper} do Spring lança {@code MismatchedInputException} onde um mapper avulso
     * aceitaria o default (achado em 2026-08-11, ver feedback_jackson_record_primitivo_e_problemdetails).
     *
     * <p>{@code cscToken} é <b>write-only</b>: nunca volta no response. Enviar {@code null} (ou
     * omitir) <b>preserva</b> o token já gravado — apagá-lo exige {@code removerCsc = true}. Sem
     * essa distinção, um PUT que só muda a série zeraria o CSC em silêncio.
     */
    public record FiscalConfigRequest(
            @NotNull @Min(1) @Max(4) Integer crt,
            @NotNull RegimeApuracao regimeApuracao,
            @NotNull Boolean emiteNfce,
            @NotNull Boolean emiteNfe,
            @NotNull AmbienteFiscal ambiente,
            @NotNull @Min(1) @Max(999) Integer serieNfce,
            @NotNull @Min(1) @Max(999) Integer serieNfe,
            @NotNull @Min(1) @Max(999) Integer serieContingencia,
            @NotNull Boolean equiparadoIndustrial,
            @Size(max = 20) String inscricaoEstadualSt,
            @Size(max = 20) String suframa,
            @Size(max = 60) String cscId,
            String cscToken,
            Boolean removerCsc) {
    }

    /**
     * {@code configurado} distingue "empresa sem linha em {@code fiscal_config_empresa}" de
     * "empresa configurada com os defaults". O GET responde 200 nos dois casos — 404 obrigaria a
     * tela a separar "empresa inexistente" de "fiscal ainda não ligado", que é ruído sem ganho.
     */
    public record FiscalConfigResponse(
            long idEmpresa,
            String razaoSocialEmpresa,
            boolean configurado,
            int crt,
            RegimeApuracao regimeApuracao,
            boolean emiteNfce,
            boolean emiteNfe,
            AmbienteFiscal ambiente,
            int serieNfce,
            int serieNfe,
            int serieContingencia,
            boolean equiparadoIndustrial,
            String inscricaoEstadualSt,
            String suframa,
            String cscId,
            boolean cscConfigurado,
            String versaoTabelaIbpt,
            boolean serieNfceBloqueada,
            boolean serieNfeBloqueada,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Uma empresa do tenant no seletor do topo da tela. */
    public record EmpresaFiscalResponse(
            long idEmpresa,
            String razaoSocial,
            boolean configurado,
            boolean emiteNfce,
            boolean emiteNfe) {
    }

    /**
     * Uma precondição do F11 que impede ligar um gate de emissão. {@code telaDeCorrecao} é a
     * chave de tela que resolve a pendência — a tela usa pra oferecer o link, e a Conformidade
     * Fiscal (docs/telas/fiscal-conformidade.md) reaproveita o mesmo vocabulário.
     */
    public record PendenciaAtivacao(String codigo, String descricao, String telaDeCorrecao) {
    }

    /** Corpo do 409 quando um gate não pode ser ligado. */
    public record AtivacaoRecusadaResponse(String detail, List<PendenciaAtivacao> pendencias) {
    }

    /** Espelha o ENUM {@code regime_apuracao} (V035). */
    public enum RegimeApuracao {
        SIMPLES, PRESUMIDO, REAL
    }

    /** Espelha o ENUM {@code ambiente_fiscal} (V035). */
    public enum AmbienteFiscal {
        HOMOLOGACAO, PRODUCAO
    }
}
