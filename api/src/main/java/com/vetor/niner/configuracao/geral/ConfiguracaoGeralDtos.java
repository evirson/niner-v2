package com.vetor.niner.configuracao.geral;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
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
            @NotNull Boolean cfgPermiteEstoqueNegativo,
            /** Dias somados a hoje para SUGERIR a validade do orcamento (V058). */
            @NotNull @Min(1) @Max(365) Integer cfgDiasValidadeOrcamento,
            @NotNull Boolean cfgExigeNumeroVendaDevolucao,
            @NotNull Boolean cfgRateiaFreteEntrada,
            @NotNull Boolean cfgReajustaPrecoEntrada,
            @NotNull Boolean cfgConsisteValorContasPagar,
            @NotBlank String idPlanoContasCompraMercadoria,
            @NotNull Boolean cfgEmiteFiscalAposVenda,
            /**
             * Liga o modulo de servicos (V085). DESLIGADO por padrao, por decisao do dono do
             * produto em 2026-08-28: empresa de servico e minoria da base.
             * <p>Nulo = mantem o valor atual, para cliente antigo que nao manda o campo (F12).
             */
            Boolean cfgUsaServicos,
            /**
             * Liga a exigência de sangrar o excedente antes de fechar o caixa (V095).
             * <b>LIGADO</b> por padrão — decisão do dono do produto em 2026-08-29, escolhendo entre
             * três desenhos: <i>"o fechamento exige sangrar até o fundo"</i>.
             * <p>⚠️ Nulo = mantém o valor atual, pelo mesmo motivo do campo acima (F12).
             */
            Boolean cfgExigeSangriaFechamento) {
    }

    public record ConfiguracaoGeralResponse(
            BigDecimal percentualDescontoVenda,
            int jurosCrediarioDias,
            BigDecimal jurosCrediario,
            int multaCrediarioDias,
            BigDecimal multaCrediario,
            boolean cfgUsaCorGrade,
            boolean cfgPermiteQtdDecimal,
            boolean cfgPermiteEstoqueNegativo,
            int cfgDiasValidadeOrcamento,
            boolean cfgExigeNumeroVendaDevolucao,
            boolean cfgRateiaFreteEntrada,
            boolean cfgReajustaPrecoEntrada,
            boolean cfgConsisteValorContasPagar,
            String idPlanoContasCompraMercadoria,
            boolean cfgEmiteFiscalAposVenda,
            boolean cfgUsaServicos,
            boolean cfgExigeSangriaFechamento,
            OffsetDateTime atualizadoEm) {
    }

    /** Só a flag de cor/grade, sem checagem de papel — usada por {@code catalogo.Produto} (o
     * campo Grade só aparece no formulário quando o tenant usa) e pela Emissão de Etiqueta. */
    /** Só a flag do módulo de serviços — mesma razão do {@code UsaCorGradeResponse}: o cadastro de
     *  produto precisa saber se o seletor Mercadoria/Serviço aparece, e isso não é de ADMIN. */
    public record UsaServicosResponse(boolean cfgUsaServicos) {
    }

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

    public record DiasValidadeOrcamentoResponse(int cfgDiasValidadeOrcamento) {
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

    /** Só a flag de consistência do valor das contas a pagar da entrada (2026-08-14), sem checagem
     *  de papel — usada pela Entrada de Produtos por Compra pra decidir se a soma das duplicatas
     *  é obrigada a bater com o total dos produtos lançados. */
    public record ConsisteValorContasPagarResponse(boolean cfgConsisteValorContasPagar) {
    }

    /** Só o plano de contas padrão de compra, sem checagem de papel — usado pelo cadastro rápido
     *  de fornecedor embutido na Entrada de Produtos por Compra (`FornecedorQuickCreateModal`),
     *  acionado por qualquer papel que faz entrada, não só ADMIN. */
    public record PlanoContasCompraMercadoriaResponse(String idPlanoContasCompraMercadoria) {
    }

    /** Só a flag de emissão fiscal automática pós-venda (2026-08-19), sem checagem de papel —
     *  usada pelo popup de papeleta do PDV (`ComprovantePapeletaModal`) pra decidir se dispara a
     *  emissão da NFC-e sozinha ou mostra o botão de emissão manual. */
    public record EmiteFiscalAposVendaResponse(boolean cfgEmiteFiscalAposVenda) {
    }
}
