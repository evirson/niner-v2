package com.vetor.niner.estoque.balanco;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da Rotina de Contagem de Estoque (ver {@code package-info.java} pro resumo de escopo). */
public final class BalancoEstoqueDtos {

    private BalancoEstoqueDtos() {
    }

    public record RegistrarContagemRequest(@NotNull Long idVariacao, @NotNull BigDecimal qtd) {
    }

    public record AjustarContagemRequest(@NotNull BigDecimal qtdContada) {
    }

    /** Uma linha por produto contado — {@code qtdContada} é a soma de todas as leituras ativas. */
    public record LinhaContagem(
            long idVariacao,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            String sku,
            BigDecimal qtdContada) {
    }

    public record LinhaDiferenca(
            long idVariacao,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            String sku,
            BigDecimal qtdEstoque,
            BigDecimal qtdContada,
            BigDecimal diferenca) {
    }

    /** {@code existeContagemAtiva = false} quando não há nenhuma linha ativa de balanço pra essa
     *  empresa (ninguém começou a contar de novo, ou a contagem nunca começou) — nesse caso
     *  {@code linhas} vem sempre vazia, mas por um motivo diferente de "contagem bate com o
     *  estoque": não há contagem nenhuma pra comparar. */
    public record DiferencasResponse(boolean existeContagemAtiva, List<LinhaDiferenca> linhas) {
    }

    public record EfetivacaoResponse(long idMovimento, int totalProdutosAjustados, OffsetDateTime dataEfetivacao) {
    }

    /** {@code existe = false} quando não há nenhuma efetivação pra desfazer (nunca efetivou, ou
     *  a única existente já foi desfeita). */
    public record UltimaEfetivacaoResponse(boolean existe, Long idMovimento, OffsetDateTime dataEfetivacao, Integer totalProdutos) {
    }
}
