package com.vetor.niner.vendas.orcamento;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do Orçamento de Venda — ver docs/telas/orcamento.md. */
public final class OrcamentoDtos {

    private OrcamentoDtos() {
    }

    /**
     * Os cinco estados. <b>Só {@code ABERTO} não é final</b> — ver R5 da spec.
     *
     * <p>⚠️ {@code VENDIDO_PARCIAL} é final por decisão explícita do dono do produto: o que sobrou
     * nunca é vendido por este orçamento ("como o cliente voltou, faz uma venda nova e pronto").
     * E {@code VENCIDO} é separado de {@code CANCELADO} porque num relatório as perguntas são
     * diferentes: <i>quantos o vendedor cancelou</i> × <i>quantos morreram esperando o cliente</i>.
     */
    public enum SituacaoOrcamento {
        ABERTO, VENDIDO, VENDIDO_PARCIAL, CANCELADO, VENCIDO
    }

    // ------------------------------------------------------------------ emissão

    /** ⚠️ Sem preço: o servidor resolve pelo {@code idVariacao}, como o PDV faz. A tela nunca
     *  manda preço — nem na emissão, nem na efetivação. */
    public record ItemOrcamentoRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001", message = "Quantidade tem de ser maior que zero.")
            BigDecimal qtd) {
    }

    public record EmitirOrcamentoRequest(
            @NotNull Long idCliente,
            @NotNull Long idFuncionario,
            /** Ausente = hoje + `cfg_geral.cfg_dias_validade_orcamento` (R11). */
            LocalDate dataValidade,
            @DecimalMin("0") BigDecimal valorDesconto,
            @Size(max = 500) String observacao,
            @NotEmpty(message = "Informe ao menos um produto.")
            List<@Valid ItemOrcamentoRequest> itens) {
    }

    // ------------------------------------------------------------------ leitura

    /**
     * Um item, já com os dois avisos que só a consulta consegue dar.
     *
     * @param precoAtual      preço do cadastro HOJE — a tela compara com {@code precoVenda} e avisa
     *                        quando divergem (R3). Informa, não decide: quem escolhe honrar o preço
     *                        congelado é o operador.
     * @param produtoInativo  produto inativado depois da emissão. ⚠️ O PDV recusa vender produto
     *                        inativo, e o orçamento <b>não afrouxa</b> essa regra (R7) — avisar na
     *                        consulta evita que o operador descubra com o cliente na frente.
     * @param qtdEstoque      saldo da empresa, só como informação: orçamento não reserva nem
     *                        bloqueia por estoque (R8).
     */
    public record ItemOrcamentoResponse(
            long idOrcamentoItem,
            long idVariacao,
            String sku,
            String descricao,
            String variacaoCor,
            String variacaoTamanho,
            BigDecimal qtd,
            BigDecimal precoVenda,
            BigDecimal valorTotal,
            BigDecimal precoAtual,
            boolean produtoInativo,
            BigDecimal qtdEstoque) {
    }

    public record OrcamentoResponse(
            long idOrcamento,
            OffsetDateTime dataOrcamento,
            LocalDate dataValidade,
            SituacaoOrcamento situacao,
            long idEmpresa,
            String nomeEmpresa,
            long idCliente,
            String nomeCliente,
            String documentoCliente,
            String telefoneCliente,
            long idFuncionario,
            String nomeFuncionario,
            String nomeUsuario,
            String observacao,
            BigDecimal subtotal,
            BigDecimal valorDesconto,
            BigDecimal valorTotal,
            Long idVenda,
            OffsetDateTime dataEfetivacao,
            OffsetDateTime dataCancelamento,
            String nomeUsuarioCancelamento,
            String motivoCancelamento,
            List<ItemOrcamentoResponse> itens) {
    }

    /** Linha da grade de pesquisa — sem os itens, que só o detalhe carrega. */
    public record OrcamentoResumoResponse(
            long idOrcamento,
            OffsetDateTime dataOrcamento,
            LocalDate dataValidade,
            SituacaoOrcamento situacao,
            String nomeCliente,
            String nomeFuncionario,
            BigDecimal valorTotal,
            Long idVenda,
            int qtdItens) {
    }

    public record PaginaOrcamentos(List<OrcamentoResumoResponse> itens, int pagina, int limite, long total) {
    }

    // ------------------------------------------------------------------ cancelamento

    public record CancelarOrcamentoRequest(
            @NotBlank(message = "Informe o motivo do cancelamento.") @Size(max = 500) String motivo) {
    }

    public record OrcamentoCanceladoResponse(long idOrcamento, OffsetDateTime dataCancelamento, String motivo) {
    }
}
