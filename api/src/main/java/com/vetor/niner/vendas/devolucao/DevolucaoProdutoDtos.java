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

    /**
     * @param precoUnitario preço da <b>linha da venda</b> que está sendo devolvida — <b>opcional</b>.
     *
     * <p>⚠️ Entrou em 2026-08-22 (auditoria, item 2) porque a mesma variação passou a poder aparecer
     * <b>duas vezes na mesma venda</b> com preços diferentes: o congelado do orçamento, que a loja
     * honrou, e o do dia, das unidades que o cliente resolveu levar na hora. Sem identificar a
     * linha, a devolução usava a <b>média ponderada</b> das duas — um valor que a venda nunca
     * praticou. Devolvendo 1 peça de uma venda de 1×R$ 80 + 1×R$ 120, o vale saía R$ 100: ou a loja
     * pagava R$ 20 a mais, ou o cliente perdia R$ 20. E o mesmo R$ 100 ia para a NF-e 55, declarando
     * unitário que não bate com nenhum item da NFC-e original.
     *
     * <p><b>Por que opcional, e não obrigatório:</b> devolução <b>sem</b> venda de origem (permitida
     * quando o tenant não exige o número da venda) não tem linha para apontar, e o contrato antigo
     * continua válido. Quando vem, identifica a linha exata; quando não vem, o comportamento é o de
     * antes — média, e o limite de quantidade somando todas as linhas da variação.
     */
    public record ItemDevolucaoRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qtd,
            BigDecimal precoUnitario) {
    }

    /** {@code numeroVenda} é opcional — só usado para resolver o vendedor (ver package-info). */
    public record EfetivarDevolucaoRequest(
            Long numeroVenda,
            @NotEmpty List<@Valid ItemDevolucaoRequest> itens) {
    }

    /** Item vendido numa venda, com quanto ainda pode ser devolvido dela — {@code qtdDisponivelDevolucao}
     *  já desconta devoluções anteriores (não canceladas) da mesma venda. Usado pra restringir o
     *  que a tela permite lançar quando o número da venda é informado (2026-08-11): a tela só
     *  aceita produtos presentes nesta lista, até o limite de {@code qtdDisponivelDevolucao}.
     *  {@code precoUnitario}/{@code valorTotal} (2026-08-19) — o preço que o cliente PAGOU
     *  naquela venda (média ponderada de {@code produto_movimento_detalhe.preco_venda}, pro caso
     *  raro de a mesma variação aparecer em mais de uma linha da venda com preços diferentes),
     *  nunca o preço atual do cadastro — ver "Restrição a produtos vendidos" na spec pro porquê:
     *  o vale-mercadoria e a futura NF-e de devolução têm que refletir o valor real da venda, que
     *  pode já ter mudado no cadastro desde então. Alimenta a grid de seleção da tela (item 1.2
     *  da revisão 2026-08-19). */
    public record ItemVendaOrigemResponse(
            long idVariacao,
            String sku,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            BigDecimal qtdVendida,
            BigDecimal qtdDisponivelDevolucao,
            /** ⚠️ BRUTO — chave de linha que casa com a venda. O que o cliente pagou é
             *  {@code precoUnitario − descontoUnitario}. */
            BigDecimal precoUnitario,
            /** Desconto por unidade desta linha da venda (2026-08-29). */
            BigDecimal descontoUnitario,
            /** ⚠️ LÍQUIDO — {@code (precoUnitario − descontoUnitario) × qtdVendida}. Era bruto, e a
             *  tela anunciava um valor maior do que o vale que ela mesma ia emitir. */
            BigDecimal valorTotal) {
    }

    public record VendedorDaVendaResponse(
            long numeroVenda, Long idFuncionario, String nomeFuncionario, List<ItemVendaOrigemResponse> itens) {
    }

    /**
     * {@code sku}/{@code valorTotal} (2026-08-07) — mesmas colunas de {@code ItemComprovanteVenda}
     * (PdvDtos), pra a papeleta do vale-mercadoria usar a mesma tabela de itens da papeleta de
     * venda.
     *
     * <p>⚠️ <b>{@code precoVenda} é BRUTO e {@code valorTotal} é LÍQUIDO</b> (2026-08-29). Este
     * javadoc afirmava que {@code valorTotal} era bruto, e virou mentira no dia em que o vale
     * passou a valer o que o cliente PAGOU — foi essa frase que fez o defeito passar em revisão:
     * o comprovante imprimia <i>1 x 100,00 … 90,00</i>, uma conta que não fecha, no papel que vai
     * para a mão do cliente. {@code precoVenda} continua bruto de propósito — é a <b>chave de
     * linha</b> que casa com a linha da venda, e mudá-lo liberaria devolver duas vezes.
     * {@code valorDesconto} é o que explica a diferença, e é por ele que o comprovante fecha.
     */
    public record ItemDevolucaoResponse(
            long idVariacao,
            String sku,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            BigDecimal qtd,
            BigDecimal precoVenda,
            /** Desconto rateado desta linha (o mesmo gravado em {@code produto_movimento_detalhe}). */
            BigDecimal valorDesconto,
            BigDecimal valorTotal) {
    }

    /** {@code idDevolucao} (`venda_devolucao.id_devolucao`) É o número do vale-mercadoria
     *  impresso pro cliente — toda devolução gera um (2026-08-03); {@code valorVale} é a soma
     *  dos itens devolvidos, o crédito que o vale vale.
     *
     *  <p>{@code notaFiscal} (2026-08-19, B9) é {@code null} quando não havia nota a emitir —
     *  fiscal desligado, devolução sem venda de origem, ou venda sem NFC-e autorizada. Preenchido,
     *  diz como terminou a emissão da NF-e de entrada; ver {@link NotaFiscalDevolucaoResponse}. */
    public record DevolucaoEfetivadaResponse(
            long idMovimento,
            long idDevolucao,
            BigDecimal valorVale,
            OffsetDateTime dataMovimento,
            Long idFuncionario,
            String nomeFuncionario,
            List<ItemDevolucaoResponse> itens,
            NotaFiscalDevolucaoResponse notaFiscal) {
    }

    /**
     * Desfecho da NF-e de devolução (modelo 55, entrada) que acompanha a devolução — §10.2, B9.
     *
     * <p>⚠️ Um resultado <b>diferente de AUTORIZADO não desfaz a devolução</b>: a mercadoria voltou
     * fisicamente ao estoque e o vale-mercadoria já é do cliente. A nota fica registrada em
     * Documentos Fiscais com a situação real, para ser reprocessada — mesmo princípio do F3 que
     * já vale na venda ("fiscal nunca bloqueia a operação de balcão").
     */
    public record NotaFiscalDevolucaoResponse(
            String situacao,
            long idDocumentoFiscal,
            String chaveAcesso,
            String protocolo,
            String cStat,
            String mensagem) {
    }

    /** Consulta de um vale-mercadoria já emitido — usada tanto pra reimprimir quanto pelo PDV
     *  na hora de resgatar (`PagamentoRequest.idDevolucao`, categoria VALE_MERCADORIA). */
    public record ValeMercadoriaResponse(
            long idDevolucao,
            BigDecimal valorVale,
            boolean valeUsado,
            boolean cancelada,
            OffsetDateTime dataDevolucao,
            Long idVendaCredito,
            Long idVendaDebito) {
    }
}
