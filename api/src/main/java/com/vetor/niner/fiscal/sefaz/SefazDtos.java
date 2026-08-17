package com.vetor.niner.fiscal.sefaz;

/** DTOs do transporte com a SEFAZ (docs/MODULOFISCAL.md §9.4, bloco B6). */
public final class SefazDtos {

    private SefazDtos() {
    }

    /** Serviços do webservice — cada um é uma coluna de URL em {@code cfg_uf_autorizador}. */
    public enum ServicoSefaz {
        AUTORIZACAO("url_autorizacao"),
        RET_AUTORIZACAO("url_ret_autorizacao"),
        STATUS_SERVICO("url_status_servico"),
        RECEPCAO_EVENTO("url_recepcao_evento"),
        INUTILIZACAO("url_inutilizacao"),
        CONSULTA_PROTOCOLO("url_consulta_protocolo");

        private final String coluna;

        ServicoSefaz(String coluna) {
            this.coluna = coluna;
        }

        public String coluna() {
            return coluna;
        }
    }

    /**
     * O que a UF define para (modelo, ambiente) — F10: "a UF é dado, não código". Endpoint, prazo
     * legal e as URLs de consulta pública saem daqui, nunca de um {@code if (uf == "PR")}.
     */
    public record Autorizador(
            String uf,
            int codigoUfIbge,
            String nome,
            String urlQrCode,
            String urlConsultaPublica,
            Integer prazoCancelamentoMinutos,
            Integer prazoContingenciaHoras) {
    }

    /**
     * Resposta crua da SEFAZ, já com os campos que decidem o fluxo extraídos. {@code corpoXml} é
     * gravado <b>sempre</b> em {@code documento_fiscal.status_sefaz} — inclusive na falha, porque
     * é a única prova do que a SEFAZ realmente respondeu (F9).
     */
    public record RespostaSefaz(int httpStatus, String cStat, String xMotivo, String protocolo,
                                String chaveAcesso, String corpoXml) {

        /** 100 = autorizado · 150 = autorizado fora do prazo (também é sucesso). */
        public boolean autorizado() {
            return "100".equals(cStat) || "150".equals(cStat);
        }

        /** 103/105 = lote em processamento — a resposta definitiva vem por consulta de recibo. */
        public boolean emProcessamento() {
            return "103".equals(cStat) || "105".equals(cStat);
        }
    }

    /**
     * Falha de <b>comunicação</b> (timeout, DNS, TLS, serviço fora do ar) — distinta de rejeição
     * da SEFAZ, que vem com {@code cStat} e é resposta legítima.
     *
     * <p>A distinção não é cosmética: rejeição significa "a nota está errada, corrija"; falha de
     * comunicação significa "a nota pode estar certa, tente de novo ou entre em contingência"
     * (§9.7). Tratar as duas igual levaria a emitir a mesma nota duas vezes ou a jogar fora uma
     * nota válida.
     */
    public static class FalhaDeComunicacaoException extends RuntimeException {
        public FalhaDeComunicacaoException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
