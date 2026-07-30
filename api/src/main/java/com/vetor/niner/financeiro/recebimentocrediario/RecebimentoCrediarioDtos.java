package com.vetor.niner.financeiro.recebimentocrediario;

import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do Recebimento de Crediário (docs/telas — spec fornecida pelo dono do produto, 2026-07-29). */
public final class RecebimentoCrediarioDtos {

    private RecebimentoCrediarioDtos() {
    }

    public record ClienteCrediarioResponse(long idCliente, String nome, String cpfCnpj, String telefone) {
    }

    /**
     * {@code multa}/{@code juros}/{@code totalAPagar} são sempre recalculados na hora (nunca
     * persistidos). {@code codigoEmpresa} é o código de exibição (não o id) da empresa onde a
     * venda foi feita (2026-07-29, layout novo). {@code totalParcelas} é o total de parcelas do
     * mesmo plano de pagamento (mesma venda + mesma carteira, não a venda inteira — uma venda
     * pode ter mais de uma forma de pagamento com contagens de parcela independentes) — usado
     * pra exibir "Nº PC" como "02/05".
     */
    public record ParcelaCrediarioResponse(
            long idContaReceber,
            long idVenda,
            int codigoEmpresa,
            OffsetDateTime dataVenda,
            int numeroParcela,
            int totalParcelas,
            OffsetDateTime dataVencimento,
            BigDecimal valorOriginal,
            BigDecimal multa,
            BigDecimal juros,
            BigDecimal totalAPagar) {
    }

    public record CarteiraDisponivelResponse(long idCarteira, String nomeCarteira, CategoriaCarteira categoriaCarteira) {
    }

    public record PagamentoRecebimentoRequest(
            @NotNull Long idCarteira,
            @NotNull @DecimalMin(value = "0.01") BigDecimal valorPago) {
    }

    public record EfetivarRecebimentoRequest(
            @NotNull Long idCliente,
            @NotEmpty List<Long> idsContaReceber,
            @NotEmpty List<@Valid PagamentoRecebimentoRequest> pagamentos) {
    }

    public record RecebimentoEfetivadoResponse(long idLoteRecebimento, int qtdParcelas, BigDecimal valorTotal) {
    }

    /**
     * Um lote de recebimento já efetivado, candidato a estorno (2026-07-29). {@code
     * formasPagamento} é a lista de nomes de carteira usadas naquele lote, separados por vírgula
     * (derivada de {@code caixa_detalhe}) — só pra contexto na listagem, não afeta o estorno.
     */
    public record LoteRecebimentoResponse(
            long idLoteRecebimento,
            String nomeCliente,
            OffsetDateTime dataRecebimento,
            BigDecimal valorTotal,
            int qtdParcelas,
            String formasPagamento) {
    }

    public record EstornoEfetivadoResponse(long idLoteRecebimento, int qtdParcelas, BigDecimal valorTotal) {
    }

    /**
     * Uma parcela dentro de um lote de recebimento, só para visualização antes de decidir
     * estornar (2026-07-29) — {@code totalParcelas} é o total do mesmo plano de pagamento
     * (venda+carteira), mesma leitura de {@link ParcelaCrediarioResponse#totalParcelas()}.
     */
    public record ParcelaDoLoteResponse(
            long idContaReceber,
            long idVenda,
            int numeroParcela,
            int totalParcelas,
            OffsetDateTime dataVencimento,
            BigDecimal valorRecebido) {
    }

    /**
     * Comprovante de pagamento de crediário (2026-07-30, impressão térmica 80mm) — uma linha por
     * parcela do lote, {@code multaJuros} congelado no valor cobrado na hora do recebimento
     * (nunca recalculado depois).
     */
    public record ParcelaComprovanteResponse(
            long idVenda,
            int numeroParcela,
            int totalParcelas,
            OffsetDateTime dataVencimento,
            BigDecimal valorParcela,
            BigDecimal multaJuros,
            BigDecimal valorAPagar) {
    }

    /** Uma forma de pagamento usada no lote — soma de todos os lançamentos daquela carteira. */
    public record PagamentoComprovanteResponse(String nomeCarteira, BigDecimal valorPago) {
    }

    public record ComprovanteRecebimentoResponse(
            long idLoteRecebimento,
            long idCaixa,
            String nomeEmpresa,
            String nomeCliente,
            OffsetDateTime dataPagamento,
            List<ParcelaComprovanteResponse> parcelas,
            BigDecimal valorTotalAPagar,
            List<PagamentoComprovanteResponse> pagamentos) {
    }
}
