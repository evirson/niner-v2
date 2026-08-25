package com.vetor.niner;

import com.vetor.niner.canais.PrecoDoCanal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preço por canal (docs/MODULOMARKETPLACE.md §8.4) — decisão do dono do produto em 2026-08-25:
 * o preço do marketplace é próprio e pode ser <b>maior ou menor</b> que o da loja física.
 *
 * <p>Teste puro, sem Spring: é aritmética de dinheiro (P7), e o que ele precisa provar é
 * arredondamento e sinal — não fiação.
 */
class PrecoDoCanalTest {

    private static BigDecimal reais(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void percentualPositivoSobeOPreco() {
        // Caso típico: cobrir a comissão do marketplace.
        assertThat(PrecoDoCanal.derivar(reais("100.00"), reais("18.00")))
                .isEqualByComparingTo("118.00");
    }

    @Test
    void percentualNegativoDesceOPreco() {
        // ⭐ O caso que o dono do produto fez questão de citar: "ou MENOR que a loja física".
        // Um lojista pode aceitar margem menor no canal para ganhar volume ou girar estoque parado.
        assertThat(PrecoDoCanal.derivar(reais("100.00"), reais("-10.00")))
                .isEqualByComparingTo("90.00");
    }

    @Test
    void percentualZeroRepeteOPrecoDaLoja() {
        assertThat(PrecoDoCanal.derivar(reais("79.90"), BigDecimal.ZERO))
                .isEqualByComparingTo("79.90");
    }

    /**
     * ⚠️ Arredonda UMA vez, no fim. Arredondar o fator antes de multiplicar espalharia o erro
     * proporcionalmente ao preço — invisível num produto de R$ 10, visível num de R$ 800.
     */
    @Test
    void arredondaUmaVezNoFimComDuasCasas() {
        // 33,33 × 1,15 = 38,3295 -> 38,33 (HALF_UP)
        assertThat(PrecoDoCanal.derivar(reais("33.33"), reais("15.00")))
                .isEqualByComparingTo("38.33")
                .satisfies(v -> assertThat(v.scale()).as("dinheiro sempre com 2 casas (P7)").isEqualTo(2));
    }

    @Test
    void usaArredondamentoComercialHalfUp() {
        // 10,00 × 1,125 = 11,25 exato; 10,01 × 1,125 = 11,26125 -> 11,26.
        // O empate clássico: 0,005 sobe (HALF_UP), não "para o par" (HALF_EVEN).
        assertThat(PrecoDoCanal.derivar(reais("1.00"), reais("0.50")))
                .isEqualByComparingTo("1.01");
    }

    @Test
    void percentualNuloEquivaleAZero() {
        assertThat(PrecoDoCanal.derivar(reais("50.00"), null)).isEqualByComparingTo("50.00");
    }

    @Test
    void semPrecoNaLojaNaoInventaPreco() {
        // Produto sem preço de venda não vira anúncio a R$ 0,00 — publicar zero num marketplace
        // é pior que não publicar.
        assertThat(PrecoDoCanal.derivar(null, reais("10.00"))).isNull();
    }

    // ------------------------------------------------------------------ preço manual

    @Test
    void precoDerivadoAcompanhaALojaEPrecoManualNao() {
        assertThat(PrecoDoCanal.acompanhaLoja(false)).as("derivado acompanha").isTrue();
        assertThat(PrecoDoCanal.acompanhaLoja(true)).as("digitado pelo lojista NÃO acompanha").isFalse();
    }

    // ------------------------------------------------------------------ defasagem (aviso, R7)

    @Test
    void precoManualDefasadoAlemDaToleranciaEAvisado() {
        // Loja reajustou para 200; o anúncio ficou com 100 digitado à mão. O lojista pode estar
        // vendendo abaixo do custo no marketplace sem saber.
        assertThat(PrecoDoCanal.defasado(reais("100.00"), reais("200.00"), BigDecimal.ZERO, reais("10")))
                .isTrue();
    }

    @Test
    void diferencaDentroDaToleranciaNaoViraRuido() {
        assertThat(PrecoDoCanal.defasado(reais("102.00"), reais("100.00"), BigDecimal.ZERO, reais("10")))
                .isFalse();
    }

    @Test
    void naoAvisaQuandoNaoHaComOQueComparar() {
        assertThat(PrecoDoCanal.defasado(null, reais("100.00"), BigDecimal.ZERO, reais("10"))).isFalse();
        assertThat(PrecoDoCanal.defasado(reais("10.00"), null, BigDecimal.ZERO, reais("10"))).isFalse();
    }
}
