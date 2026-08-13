package com.vetor.niner.configuracao.geral;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** DTOs da configuração geral do tenant (docs/telas/configuracao-geral.md). */
public final class ConfiguracaoGeralDtos {

    private ConfiguracaoGeralDtos() {
    }

    /**
     * Corpo de atualização. Todos os campos são obrigatórios — a tabela não tem colunas
     * nullable (V023), então não existe "campo vazio" aqui, diferente dos cadastros.
     */
    public record ConfiguracaoGeralRequest(
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal percentualDescontoVenda,
            @NotNull @Min(0) Integer jurosCrediarioDias,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal jurosCrediario,
            @NotNull @Min(0) Integer multaCrediarioDias,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal multaCrediario,
            @NotNull Boolean cfgUsaCorGrade,
            @NotNull Boolean cfgPermiteQtdDecimal,
            @NotNull Boolean cfgExigeNumeroVendaDevolucao,
            @NotNull Boolean cfgRateiaFreteEntrada,
            @NotNull Boolean cfgReajustaPrecoEntrada,
            @NotBlank String idPlanoContasCompraMercadoria) {
    }

    public record ConfiguracaoGeralResponse(
            BigDecimal percentualDescontoVenda,
            int jurosCrediarioDias,
            BigDecimal jurosCrediario,
            int multaCrediarioDias,
            BigDecimal multaCrediario,
            boolean cfgUsaCorGrade,
            boolean cfgPermiteQtdDecimal,
            boolean cfgExigeNumeroVendaDevolucao,
            boolean cfgRateiaFreteEntrada,
            boolean cfgReajustaPrecoEntrada,
            String idPlanoContasCompraMercadoria,
            OffsetDateTime atualizadoEm) {
    }

    /** Só a flag de cor/grade, sem checagem de papel — usada por {@code catalogo.Produto} (o
     * campo Grade só aparece no formulário quando o tenant usa) e pela Emissão de Etiqueta. */
    public record UsaCorGradeResponse(boolean cfgUsaCorGrade) {
    }

    /** Só o percentual de desconto promocional, sem checagem de papel — usado pelo PDV (F5). */
    public record DescontoVendaResponse(BigDecimal percentualDescontoVenda) {
    }

    /**
     * Só a flag de quantidade decimal, sem checagem de papel — usada por PDV, Transferência de
     * Produtos e Histórico do Cliente pra saber como formatar/validar quantidade de produto
     * (3 casas quando {@code true}, inteiro quando {@code false}).
     */
    public record PermiteQtdDecimalResponse(boolean cfgPermiteQtdDecimal) {
    }

    /** Só a flag de exigência do número da venda na Devolução de Produtos, sem checagem de papel —
     *  usada pela própria tela (`vendas.devolucao`) pra saber se o campo "Número da Venda" é
     *  obrigatório antes mesmo de tentar gravar. */
    public record ExigeNumeroVendaDevolucaoResponse(boolean cfgExigeNumeroVendaDevolucao) {
    }

    /** Só a flag de rateio de frete/IPI/ICMS-ST no custo, sem checagem de papel — usada pela
     *  Entrada de Produtos por Compra pra decidir se o rateio entra em `valor_acrescimo`. */
    public record RateiaFreteEntradaResponse(boolean cfgRateiaFreteEntrada) {
    }

    /** Só a flag de reajuste automático de preço na entrada, sem checagem de papel — usada pela
     *  Entrada de Produtos por Compra pra decidir se atualiza `produto.preco_custo`/`preco_venda`. */
    public record ReajustaPrecoEntradaResponse(boolean cfgReajustaPrecoEntrada) {
    }

    /** Só o plano de contas padrão de compra, sem checagem de papel — usado pelo cadastro rápido
     *  de fornecedor embutido na Entrada de Produtos por Compra (`FornecedorQuickCreateModal`),
     *  acionado por qualquer papel que faz entrada, não só ADMIN. */
    public record PlanoContasCompraMercadoriaResponse(String idPlanoContasCompraMercadoria) {
    }
}
