package com.vetor.niner.vendas.devolucao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs de Devolução de Produtos (docs/telas/devolucao-produtos.md). */
public final class DevolucaoProdutoDtos {

    private DevolucaoProdutoDtos() {
    }

    public record ItemDevolucaoRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qtd) {
    }

    /** {@code numeroVenda} é opcional — só usado para resolver o vendedor (ver package-info). */
    public record EfetivarDevolucaoRequest(
            Long numeroVenda,
            @NotEmpty List<@Valid ItemDevolucaoRequest> itens) {
    }

    public record VendedorDaVendaResponse(long numeroVenda, Long idFuncionario, String nomeFuncionario) {
    }

    public record ItemDevolucaoResponse(
            long idVariacao,
            String descricaoProduto,
            String variacaoLinha,
            String variacaoColuna,
            BigDecimal qtd,
            BigDecimal precoVenda) {
    }

    /** {@code idDevolucao} (`venda_devolucao.id_devolucao`) É o número do vale-mercadoria
     *  impresso pro cliente — toda devolução gera um (2026-08-03); {@code valorVale} é a soma
     *  dos itens devolvidos, o crédito que o vale vale. */
    public record DevolucaoEfetivadaResponse(
            long idMovimento,
            long idDevolucao,
            BigDecimal valorVale,
            OffsetDateTime dataMovimento,
            Long idFuncionario,
            String nomeFuncionario,
            List<ItemDevolucaoResponse> itens) {
    }

    /** Consulta de um vale-mercadoria já emitido — usada tanto pra reimprimir quanto pelo PDV
     *  na hora de resgatar (`PagamentoRequest.idDevolucao`, categoria VALE_MERCADORIA). */
    public record ValeMercadoriaResponse(
            long idDevolucao,
            BigDecimal valorVale,
            boolean valeUsado,
            OffsetDateTime dataDevolucao,
            Long idVendaCredito,
            Long idVendaDebito) {
    }
}
