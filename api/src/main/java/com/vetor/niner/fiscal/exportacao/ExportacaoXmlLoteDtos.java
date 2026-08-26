package com.vetor.niner.fiscal.exportacao;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** DTOs da Exportação de XML em Lote (`docs/telas/exportacao-xml-lote.md`). */
public final class ExportacaoXmlLoteDtos {

    private ExportacaoXmlLoteDtos() {
    }

    /**
     * Pré-conferência mostrada na tela <b>antes</b> do clique em baixar. Não toca no bucket: só
     * conta no banco.
     *
     * <p>{@code documentosSemXml} é o campo que existe para <b>não</b> deixar um silêncio: nota
     * autorizada cuja gravação no bucket falhou tem valor fiscal e ficaria de fora do pacote sem
     * nada avisar. Ver decisão 3 da spec.
     */
    /**
     * @param totalPartes    em quantos ZIPs o período vai sair. ⭐ Desde 2026-08-26 um período
     *                       grande é <b>particionado</b>, não recusado: a tela pede parte 1, 2, 3…
     *                       e salva um arquivo por parte.
     * @param ateIdDocumento teto de {@code id_documento_fiscal} <b>congelado agora</b>, que a tela
     *                       devolve em cada parte. ⚠️ É ele que impede uma nota emitida no meio do
     *                       download de deslocar a paginação e fazer um documento ser pulado, sem
     *                       nada avisar. {@code null} quando o período não tem nenhuma nota.
     */
    public record ResumoExportacaoXml(
            String nomeArquivo,
            long totalDocumentos,
            long documentosComXml,
            long documentosSemXml,
            long totalEventos,
            int limiteDocumentos,
            int totalPartes,
            Long ateIdDocumento) {
    }

    /**
     * Uma linha de {@code documento_fiscal} do período, já com a chave do objeto no bucket.
     * {@code xmlObjetoBucket} nulo = documento sem XML arquivado: entra no {@code relatorio.csv}
     * marcado, e não entra entre os XMLs.
     */
    public record DocumentoDoPacote(
            long idDocumentoFiscal,
            int modelo,
            Integer serie,
            Long numero,
            String chaveAcesso,
            String situacao,
            OffsetDateTime dataEmissao,
            BigDecimal valorTotal,
            String xmlObjetoBucket,
            String contraparte,
            String documentoContraparte) {
    }

    /**
     * Evento <b>autorizado</b> e arquivado de um documento do período (hoje só o 110111,
     * cancelamento). O {@code dataEmissao} é o da <b>nota</b>, não o do evento: é ela que decide
     * em qual pasta {@code AAAA-MM/} o par nota+evento fica junto.
     */
    public record EventoDoPacote(
            long idEvento,
            String chaveAcesso,
            String tipoEvento,
            int sequencia,
            OffsetDateTime dataEmissao,
            String xmlObjetoBucket) {
    }

    /** Nome e UF da empresa — a UF decide o fuso da pasta {@code AAAA-MM/} (decisão 10). */
    public record EmpresaDoPacote(String razaoSocial, String nomeFantasia, String uf) {
    }
}
