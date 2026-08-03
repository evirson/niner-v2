package com.vetor.niner.vendas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do PDV (docs/telas/pdv.md) — busca/leitura de produto e efetivação de venda. */
public final class PdvDtos {

    private PdvDtos() {
    }

    /** Estoque de uma variação numa empresa específica — `0` quando não há linha em `produto_estoque`. */
    public record EstoqueEmpresa(int codigoEmpresa, String nomeEmpresa, BigDecimal qtd) {
    }

    /**
     * Uma variação (`produto_barra`) — cada linha do resultado de busca/leitura é uma
     * variação, nunca um produto agrupado. `variacaoLinha`/`variacaoColuna` são `null` quando
     * o produto não usa variação.
     */
    public record PdvProdutoResponse(
            long idVariacao,
            String descricaoProduto,
            String variacaoLinha,
            String variacaoColuna,
            String sku,
            BigDecimal precoVenda,
            List<EstoqueEmpresa> estoquePorEmpresa,
            BigDecimal estoqueTotal,
            /** URL pública da primeira foto da galeria do produto (indice 0), {@code null} se não tiver foto. */
            String urlImagem) {
    }

    /** Preço nunca vem do cliente — só `idVariacao` + `qtd`; o servidor resolve o preço. */
    public record ItemVendaRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qtd) {
    }

    /** Resultado da busca de cliente (F6, 2026-07-28) — nome, CPF/CNPJ ou celular (`cliente.telefone`). */
    public record PdvClienteResponse(long idCliente, String nome, String cpfCnpj, String telefone) {
    }

    /**
     * Uma linha de pagamento (split-tender, 2026-07-28) — {@code valorPago} é o valor tendido
     * nessa forma de pagamento (o que efetivamente circula: dinheiro entregue, valor cobrado no
     * cartão). O quanto essa linha abate do saldo a pagar (a "cobertura") é calculado no
     * servidor a partir do desconto/acréscimo do {@code tipo_carteira} — nunca enviado pelo
     * cliente. {@code idDevolucao} (2026-08-03) é obrigatório só quando o {@code tipo_carteira}
     * escolhido é da categoria {@code VALE_MERCADORIA} — identifica qual vale
     * (`venda_devolucao.id_devolucao`) está sendo resgatado; o servidor ignora {@code
     * valorPago} nesse caso e usa o valor de fato do vale (mesmo princípio de nunca confiar em
     * valor vindo do cliente).
     */
    public record PagamentoRequest(
            @NotNull Long idCarteira,
            @NotNull @DecimalMin(value = "0.01") BigDecimal valorPago,
            @NotNull @Min(1) Integer numeroParcelas,
            Long idDevolucao) {
    }

    /**
     * Split-tender (2026-07-28): uma venda pode ter várias linhas de pagamento, cada uma com
     * seu próprio {@code tipo_carteira} e número de parcelas. A soma das "coberturas" das
     * linhas tem que fechar exatamente o líquido a pagar (produtos − desconto da venda) — ver
     * {@code PdvVendaService.efetivarVenda}. {@code descontoVenda} é o desconto em R$ que o
     * operador decidiu dar nesta venda (campo de digitação livre na tela — % ou R$, o front
     * converte para R$ antes de enviar); nunca pode passar do máximo configurado em {@code
     * cfg_geral.percentual_desconto_venda} — o servidor valida e rejeita se passar.
     * {@code idCliente}/{@code idFuncionario} (2026-07-28) são obrigatórios — toda venda do PDV
     * passou a exigir cliente e vendedor identificados, não é mais venda de balcão anônima.
     */
    public record EfetivarVendaRequest(
            @NotEmpty List<@Valid ItemVendaRequest> itens,
            @NotNull @DecimalMin(value = "0") BigDecimal descontoVenda,
            @NotEmpty List<@Valid PagamentoRequest> pagamentos,
            @NotNull Long idCliente,
            @NotNull Long idFuncionario) {
    }

    /** `paga` = true quando a parcela já nasce recebida (categoria AVISTA/CARTAO_DEBITO). */
    public record ParcelaGerada(int numeroParcela, OffsetDateTime dataVencimento, BigDecimal valorParcela, boolean paga) {
    }

    /** Uma linha de pagamento já efetivada, com as parcelas geradas em `contas_receber`. */
    public record PagamentoGerado(
            long idCarteira,
            String nomeCarteira,
            BigDecimal valorPago,
            List<ParcelaGerada> parcelas) {
    }

    /**
     * {@code valorTotalProdutos} é a soma bruta dos itens; {@code descontoVenda} é o desconto
     * que o operador aplicou (0 se nenhum, sempre ≤ o máximo de {@code
     * cfg_geral.percentual_desconto_venda}); {@code valorLiquido} é o que as linhas de
     * pagamento tiveram que cobrir juntas.
     */
    public record VendaEfetivadaResponse(
            long idVenda,
            BigDecimal valorTotalProdutos,
            BigDecimal descontoVenda,
            BigDecimal valorLiquido,
            List<PagamentoGerado> pagamentos) {
    }
}
