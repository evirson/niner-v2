package com.vetor.niner.fiscal.documento;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da tela de Documentos Fiscais (§12, `fiscal.documentos`) — lista + consulta. */
public final class DocumentoFiscalListaDtos {

    private DocumentoFiscalListaDtos() {
    }

    /**
     * ⚠️ {@code serie}, {@code numero} e {@code chaveAcesso} são <b>nulos de verdade</b>, não por
     * descuido: um documento em {@code NAO_EMITIDO} morreu no bloqueio preventivo (F11) antes de
     * a numeração ser tirada e a chave montada. Eram primitivos (`int serie`, `long numero`) até
     * 2026-08-25, e o driver devolvia {@code 0} para a coluna NULL <b>em silêncio</b> — a lista
     * exibia "0/0" como se fosse número real de nota. Ver
     * {@code DocumentoFiscalConsultaService.getIntOuNulo}.
     */
    public record DocumentoFiscalItem(
            long idDocumentoFiscal, int modelo, Integer serie, Long numero, String chaveAcesso,
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

    // ---------------------------------------------------------------- DANFE (modelo 55, A4)

    /**
     * Tudo que o DANFE A4 imprime — §10.2/B9, layout conferido contra uma NF-e real trazida pelo
     * dono do produto. Sai <b>montado no servidor</b>, não remontado no front a partir do XML:
     * o que o DANFE mostra tem que ser o que a nota diz, e reinterpretar o XML no navegador
     * abriria espaço para o impresso divergir do documento (o mesmo princípio que fez o QR Code
     * do DANFCE ser extraído do {@code xml_assinado}, nunca reconstruído).
     *
     * <p>Só existe para <b>modelo 55</b>. A NFC-e (65) imprime o DANFCE térmico, que é outro
     * documento — ver {@code DanfceImprimir.tsx}.
     */
    public record DanfeResponse(
            long idDocumentoFiscal,
            String chaveAcesso,
            int modelo,
            int serie,
            long numero,
            String naturezaOperacao,
            /** 0 = entrada, 1 = saída — o DANFE marca um X no quadrado correspondente. */
            int tipoNf,
            String situacao,
            boolean homologacao,
            OffsetDateTime dataEmissao,
            OffsetDateTime dataAutorizacao,
            String protocolo,
            DanfeParticipante emitente,
            DanfeParticipante destinatario,
            List<DanfeItem> itens,
            DanfeTotais totais,
            String informacoesComplementares,
            /** Chave da nota referenciada (devolução) — impressa nas informações complementares
             *  quando existe, é o que amarra o documento à venda original aos olhos do fiscal. */
            String chaveReferenciada) {
    }

    public record DanfeParticipante(
            String nome, String cpfCnpj, String inscricaoEstadual, String enderecoLinha1,
            String enderecoLinha2, String municipio, String uf, String cep, String telefone) {
    }

    public record DanfeItem(
            int numeroItem, String codigoProduto, String descricao, String ncm, String cfop,
            int origemMercadoria, String cstOuCsosn, String unidadeComercial,
            BigDecimal quantidade, BigDecimal valorUnitario, BigDecimal valorTotal,
            BigDecimal baseCalculoIcms, BigDecimal valorIcms, BigDecimal aliquotaIcms) {
    }

    public record DanfeTotais(
            BigDecimal baseCalculoIcms, BigDecimal valorIcms, BigDecimal baseCalculoSt,
            BigDecimal valorIcmsSt, BigDecimal valorProdutos, BigDecimal valorFrete,
            BigDecimal valorSeguro, BigDecimal valorDesconto, BigDecimal valorOutros,
            BigDecimal valorPis, BigDecimal valorCofins, BigDecimal valorTotalTributos,
            BigDecimal valorTotal) {
    }
}
