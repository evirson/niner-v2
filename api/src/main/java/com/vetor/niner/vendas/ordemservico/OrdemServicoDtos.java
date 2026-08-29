package com.vetor.niner.vendas.ordemservico;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contrato da Ordem de Serviço (bloco S4, {@code docs/MODULOSERVICOS.md} §4.2).
 *
 * <p>⛔ <b>OS não é orçamento.</b> Reforço do dono do produto em 2026-08-28: são entidades
 * separadas. O que se reaproveita do orçamento é a <b>forma</b> da tela e o <b>mecanismo</b> de
 * virar venda pelo F5 do PDV — nunca a entidade nem a regra (o orçamento é imutável; a OS muda até
 * ser faturada, porque o mecânico abre o motor e acha mais serviço).
 */
public final class OrdemServicoDtos {

    private OrdemServicoDtos() {
    }

    /**
     * Item da OS — serviço <b>ou</b> peça, na mesma lista (DS14).
     *
     * <p>⚠️ {@code precoVenda} é <b>opcional</b> de propósito: em branco, o servidor resolve pelo
     * cadastro. Aceitar preço do cliente sem conferir deixaria quem chama a API escolher quanto
     * custa o serviço — o mesmo cuidado que a Devolução de Produtos precisou tomar com o preço do
     * vale-mercadoria.
     */
    public record ItemRequest(
            @NotNull Long idVariacao,
            @NotNull @Positive BigDecimal qtdProduto,
            @PositiveOrZero BigDecimal precoVenda,
            /** Quem executa ESTE item. Nulo é normal em peça, e comum em serviço ainda não atribuído. */
            Long idFuncionario) {
    }

    public record OrdemServicoRequest(
            @NotNull Long idCliente,
            /** Quem ATENDEU. Quem executa cada serviço vai no item. */
            @NotNull Long idFuncionario,
            /** Placa, nome do animal, número de série — é por aqui que o balcão acha a OS. */
            @NotBlank @Size(max = 120) String objetoServico,
            @Size(max = 500) String observacao,
            @DecimalMin("0") BigDecimal valorDesconto,
            @NotEmpty @Valid List<ItemRequest> itens) {
    }

    /** Só o motivo — mesmo par "ADMIN + motivo obrigatório" do Cancelamento de Venda. */
    public record CancelamentoRequest(@NotBlank @Size(max = 200) String motivo) {
    }

    public record ItemResponse(
            long idOrdemServicoItem,
            long idVariacao,
            String sku,
            String descricaoProduto,
            /**
             * Cor e tamanho da variação, nulos quando o produto não tem grade.
             *
             * <p>⚠️ Sem eles, duas peças do MESMO produto em cores diferentes saem idênticas na
             * tela — a OS mostraria duas linhas "CHIN FEM HAVAIANAS" e ninguém saberia qual é
             * qual. Achado abrindo a tela: a linha nasce com a variação (vem da pesquisa de
             * produto) e a perdia ao recarregar, porque a resposta não a trazia de volta.
             */
            String variacaoCor,
            String variacaoTamanho,
            /** {@code MERCADORIA} ou {@code SERVICO} — a tela mostra os dois blocos separados. */
            String tipoItem,
            BigDecimal qtdProduto,
            BigDecimal precoVenda,
            BigDecimal total,
            Long idFuncionario,
            String nomeFuncionario,
            /** Quanto esta linha reservou de estoque. Serviço é sempre 0 (não tem saldo). */
            BigDecimal qtdReservada,
            /**
             * Duração cadastrada do serviço, em minutos — nula em peça e em serviço sem duração.
             *
             * <p>⚠️ Serve para a tela somar a <b>previsão de conclusão</b> da OS, que é o que o
             * balcão responde quando o cliente pergunta "fica pronto quando?". ⛔ Não é agenda:
             * ninguém reserva horário, ninguém checa conflito, e a soma ignora o fato de dois
             * mecânicos poderem trabalhar em paralelo — é uma estimativa, e a tela a chama assim.
             */
            Integer duracaoMinutos) {
    }

    public record OrdemServicoResponse(
            long idOrdemServico,
            long idEmpresa,
            long idCliente,
            String nomeCliente,
            /**
             * Documento e telefone do cliente + razão social da empresa: existem para a VIA
             * IMPRESSA da OS e para o envio por WhatsApp. ⚠️ Sem o telefone aqui, o popup de envio
             * abriria com o campo vazio e o operador teria de digitar de cabeça o número de quem
             * está na frente dele — que é exatamente o erro que a via impressa quer evitar.
             */
            String documentoCliente,
            String telefoneCliente,
            String nomeEmpresa,
            long idFuncionario,
            String nomeFuncionario,
            String objetoServico,
            String observacao,
            String situacao,
            OffsetDateTime dataAbertura,
            OffsetDateTime dataConclusao,
            BigDecimal valorDesconto,
            BigDecimal totalServicos,
            BigDecimal totalPecas,
            BigDecimal total,
            List<ItemResponse> itens,
            Long idVenda,
            OffsetDateTime dataFaturamento,
            OffsetDateTime dataCancelamento,
            String motivoCancelamento,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Linha da grade — sem itens, para a listagem não carregar o que ninguém está olhando. */
    public record LinhaListagem(
            long idOrdemServico,
            String nomeCliente,
            String objetoServico,
            String situacao,
            OffsetDateTime dataAbertura,
            BigDecimal total,
            Long idVenda) {
    }

    public record PaginaOrdensServico(
            List<LinhaListagem> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }
}
