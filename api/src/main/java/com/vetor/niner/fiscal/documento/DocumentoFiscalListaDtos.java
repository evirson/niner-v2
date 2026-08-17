package com.vetor.niner.fiscal.documento;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da tela de Documentos Fiscais (§12, `fiscal.documentos`) — lista + consulta. */
public final class DocumentoFiscalListaDtos {

    private DocumentoFiscalListaDtos() {
    }

    public record DocumentoFiscalItem(
            long idDocumentoFiscal, int modelo, int serie, long numero, String chaveAcesso,
            String tipoOperacao, String situacao, int tipoEmissao, String ambiente,
            OffsetDateTime dataEmissao, OffsetDateTime dataAutorizacao, String protocolo,
            BigDecimal valorTotal, Long idVenda, String nomeCliente,
            // §11.4: link público de consulta pela chave — mesma URL do QR Code (v3.00), extraída
            // do XML já assinado. null quando a nota não chegou a ser autorizada (não tem qrCode).
            String urlConsultaPublica) {
    }

    public record PaginaDocumentosFiscais(
            List<DocumentoFiscalItem> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    public record XmlDocumentoFiscalResponse(long idDocumentoFiscal, String chaveAcesso, String xml) {
    }

    /** Resultado de consultar a situação atual direto na SEFAZ (NFeConsultaProtocolo4) — não
     *  confundir com {@code status_sefaz}/{@code motivo_sefaz} gravados na autorização; aqui é
     *  uma pergunta nova, feita agora, útil pra conferir se algo mudou (ex.: cancelamento feito
     *  por outro canal, ou uma nota que ficou {@code TRANSMITINDO} por falha de comunicação). */
    public record ConsultaSefazResponse(String cStat, String xMotivo, String protocolo) {
    }

    /** Resultado de {@code POST /documentos/{id}/reprocessar} — situação final do documento
     *  depois da consulta (e, se preciso, da retransmissão). */
    public record ReprocessamentoResponse(String situacao, String protocolo, String cStat, String mensagem) {
    }
}
