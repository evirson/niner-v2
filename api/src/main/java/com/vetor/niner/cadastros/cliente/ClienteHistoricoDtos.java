package com.vetor.niner.cadastros.cliente;

import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTOs do histórico do cliente (2026-07-23, pedido do dono do produto): compras (venda física,
 * R9), parcelas (contas a receber) e resumo das parcelas de crediário. Somente leitura — não
 * existe ainda um fluxo de lançamento de venda/baixa de parcela; a tela mostra o que houver.
 */
public final class ClienteHistoricoDtos {

    private ClienteHistoricoDtos() {
    }

    /**
     * {@code valor} não é gravado — é somado do ledger de estoque (produto_movimento_detalhe).
     * {@code qtdProdutos}, mesma origem (soma de {@code qtd_produto} das linhas de débito).
     */
    public record VendaHistorico(
            long idVenda, int codigoEmpresa, OffsetDateTime dataVenda, BigDecimal valor, BigDecimal qtdProdutos) {
    }

    /**
     * Um produto vendido numa compra (linha de débito do ledger de estoque, 2026-07-27, pedido
     * do dono do produto — painel "Produtos da Compra"). {@code variacaoCor}/{@code
     * variacaoTamanho} são {@code null} quando o produto não usa grade. {@code precoVenda} é o
     * preço unitário líquido já considerando desconto/acréscimo da linha: {@code
     * ((qtd*preçoVenda - desconto + acréscimo) / qtd)}.
     */
    public record ItemVendaHistorico(
            long idVenda,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            BigDecimal qtdVendida,
            BigDecimal precoVenda) {
    }

    /**
     * {@code codigoEmpresaPagamento}/{@code diasAtraso} são {@code null} quando não aplicável:
     * a loja de pagamento só existe depois de uma baixa de parcela (ainda não implementada) e
     * dias de atraso só faz sentido pra parcela vencida (paga com atraso, ou em aberto e já
     * vencida).
     */
    public record ParcelaHistorico(
            long idContaReceber,
            long idVenda,
            int codigoEmpresaVenda,
            OffsetDateTime dataVenda,
            int numeroParcela,
            OffsetDateTime dataVencimento,
            OffsetDateTime dataPagamento,
            BigDecimal valorAPagar,
            BigDecimal valorPago,
            Integer codigoEmpresaPagamento,
            Long diasAtraso,
            String nomeCarteira,
            CategoriaCarteira categoriaCarteira) {
    }

    /** Vencidas/Total trazem juros+multa ({@code valor_juros}); A Vencer não (ainda não venceu). */
    public record ResumoParcelasComJuros(BigDecimal valorTotal, BigDecimal valorJurosMulta, long numeroParcelas) {
    }

    public record ResumoParcelasSemJuros(BigDecimal valorTotal, long numeroParcelas) {
    }

    /**
     * Resumo das parcelas de crediário em aberto (categoria {@code CREDIARIO} apenas — cartão
     * e à vista ficam fora). {@code total} = {@code vencidas} + {@code aVencer}; parcelas já
     * pagas não entram em nenhum dos três (o resumo é sobre saldo em aberto).
     */
    public record ResumoCrediario(
            ResumoParcelasComJuros vencidas, ResumoParcelasSemJuros aVencer, ResumoParcelasComJuros total) {
    }

    public record ClienteHistoricoResponse(
            List<VendaHistorico> compras,
            List<ItemVendaHistorico> produtos,
            List<ParcelaHistorico> parcelas,
            ResumoCrediario resumoCrediario) {
    }
}
