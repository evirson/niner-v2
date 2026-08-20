package com.vetor.niner.estoque.devolucaocompra;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da Devolução de Produtos Comprados (devolução ao fornecedor) — ver docs/telas/devolucao-compra.md. */
public final class DevolucaoCompraDtos {

    private DevolucaoCompraDtos() {
    }

    /**
     * Entrada que <b>pode</b> gerar devolução. A listagem já vem filtrada: só COMPRA não cancelada,
     * com XML arquivado e tributação por item gravada — ver
     * {@code DevolucaoCompraService#listarEntradas} para o porquê de cada condição.
     */
    public record EntradaElegivelResponse(
            long idMovimento,
            OffsetDateTime dataMovimento,
            long idEmpresa,
            String nomeEmpresa,
            Long idFornecedor,
            String nomeFornecedor,
            String cnpjFornecedor,
            Integer notaFiscal,
            Integer serieNota,
            String chaveNfe,
            BigDecimal valorTotal,
            int qtdItens,
            /** Já houve devolução (parcial) desta entrada — a tela marca a linha. */
            boolean temDevolucao) {
    }

    public record PaginaEntradasElegiveis(List<EntradaElegivelResponse> itens, int pagina, int limite, long total) {
    }

    /**
     * Item da entrada com os três limites que decidem quanto pode voltar ao fornecedor.
     *
     * @param qtdComprada  o que a nota trouxe
     * @param qtdDevolvida o que já voltou em devoluções não canceladas
     * @param qtdSaldo     {@code comprada - devolvida} — o limite da NOTA
     * @param qtdEstoque   o que existe hoje em {@code produto_estoque} desta empresa
     * @param qtdMaxima    o menor entre saldo e estoque — é este que a tela usa. Decisão do dono do
     *                     produto (2026-08-20): <b>só devolve o que ainda está em estoque</b>; se a
     *                     mercadoria já foi vendida, não há o que mandar de volta
     * @param valorUnitario o valor unitário da NOTA DO FORNECEDOR, não o custo do movimento — os
     *                     dois divergem quando o rateio de frete está ligado, e a nota de devolução
     *                     tem de espelhar a de origem
     */
    public record ItemDevolvivelResponse(
            long idVariacao,
            String sku,
            String descricao,
            String variacaoCor,
            String variacaoTamanho,
            String codigoFornecedor,
            String cfopEntrada,
            BigDecimal qtdComprada,
            BigDecimal qtdDevolvida,
            BigDecimal qtdSaldo,
            BigDecimal qtdEstoque,
            BigDecimal qtdMaxima,
            BigDecimal valorUnitario) {
    }

    public record ItemDevolucaoCompraRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001", message = "Quantidade a devolver tem de ser maior que zero.")
            BigDecimal qtd) {
    }

    public record EfetivarDevolucaoCompraRequest(
            @NotNull Long idMovimentoOrigem,
            @NotEmpty(message = "Selecione ao menos um produto para devolver.")
            List<@Valid ItemDevolucaoCompraRequest> itens,
            @Size(max = 500) String observacao) {
    }

    public record DevolucaoCompraEfetivadaResponse(
            long idMovimento,
            long idMovimentoOrigem,
            OffsetDateTime dataMovimento,
            long idEmpresa,
            Long idFornecedor,
            String nomeFornecedor,
            Integer notaFiscalOrigem,
            BigDecimal valorTotal,
            List<ItemDevolvidoResponse> itens,
            /** Nula quando o fiscal esta desligado para a empresa (F12) - a devolucao vale
             *  do mesmo jeito, sem nota. Situacao != AUTORIZADO significa que a mercadoria
             *  NAO pode viajar ainda. */
            NotaFiscalDevolucaoCompraResponse nota) {
    }

    /** Desfecho da emissao, como o operador precisa ler - inclui o que fazer quando nao deu certo. */
    public record NotaFiscalDevolucaoCompraResponse(String situacao, long idDocumentoFiscal,
                                                    String chaveAcesso, String protocolo, String cStat,
                                                    String mensagem) {
    }

    public record ItemDevolvidoResponse(long idVariacao, String sku, String descricao,
                                        BigDecimal qtd, BigDecimal valorUnitario, BigDecimal valorTotal) {
    }

    public record CancelarDevolucaoCompraRequest(
            @NotBlank(message = "Informe o motivo do cancelamento.") @Size(max = 500) String motivo) {
    }

    /**
     *  protocoloCancelamentoNota protocolo do evento 110111 quando havia NF-e autorizada e a
     *        SEFAZ aceitou o cancelamento; nulo quando nao havia nota. Se a SEFAZ tivesse recusado,
     *        esta resposta nao existiria - o cancelamento inteiro teria falhado com 409.
     */
    public record DevolucaoCompraCanceladaResponse(long idMovimento, OffsetDateTime dataCancelamento,
                                                   String motivo, String protocoloCancelamentoNota) {
    }
}
