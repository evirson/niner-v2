package com.vetor.niner;

import com.vetor.niner.fiscal.nfse.IdDps;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IdDps} — classe pura, sem Spring e sem banco: recebe números, devolve texto.
 *
 * <p>O caso de referência não é inventado: é a DPS que o Nainer <b>emitiu de verdade</b> contra o
 * Sefin Nacional em 2026-08-31 (docs/MODULONFSE.md §2.7), com o CNPJ da Vetor em Curitiba. Um
 * teste de formatação escrito a partir do próprio código provaria só que ele concorda consigo
 * mesmo — este compara com o que o servidor do governo aceitou.
 */
class IdDpsTest {

    private final IdDps idDps = new IdDps();

    /** Município 4106902 (Curitiba), CNPJ da Vetor, série 1, nDPS 2001000. */
    private static final String ID_DA_EMISSAO_REAL =
            "DPS410690222212025400018600001000000002001000";

    @Test
    void montaOIdQueOSefinAceitouEmProducao() {
        String id = idDps.montar(4106902, "22120254000186", 1, 2_001_000L);

        assertThat(id).isEqualTo(ID_DA_EMISSAO_REAL);
        assertThat(id).hasSize(IdDps.TAMANHO);
        assertThat(id).matches("DPS\\d{42}");
    }

    @Test
    void aceitaCnpjMascarado() {
        // A tela e a importação entregam CNPJ formatado; o Id não pode mudar por causa disso.
        assertThat(idDps.montar(4106902, "22.120.254/0001-86", 1, 2_001_000L))
                .isEqualTo(ID_DA_EMISSAO_REAL);
    }

    @Test
    void serieENumeroVaoComZeroAEsquerda() {
        String id = idDps.montar(4106902, "22120254000186", 1, 1L);

        // Série ocupa 5 posições e o número 15 — sem o preenchimento o Id sai curto e o SEFIN
        // responde E1235 ("falha no esquema"), que não menciona o Id e manda o diagnóstico longe.
        assertThat(id).endsWith("00001" + "000000000000001");
        assertThat(id).hasSize(IdDps.TAMANHO);
    }

    @Test
    void oMaiorNumeroPossivelAindaCabeEm45() {
        String id = idDps.montar(4106902, "22120254000186", 99_999, 999_999_999_999_999L);

        assertThat(id).hasSize(IdDps.TAMANHO);
    }

    // ---- o par negativo: um guarda só vale se recusar o que deve -------------------------------

    @Test
    void recusaMunicipioQueNaoTem7Digitos() {
        assertThatThrownBy(() -> idDps.montar(410690, "22120254000186", 1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7 dígitos");
    }

    @Test
    void recusaCnpjIncompleto() {
        assertThatThrownBy(() -> idDps.montar(4106902, "2212025400018", 1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("14 dígitos");
    }

    @Test
    void recusaSerieENumeroForaDaFaixa() {
        assertThatThrownBy(() -> idDps.montar(4106902, "22120254000186", 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> idDps.montar(4106902, "22120254000186", 100_000, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> idDps.montar(4106902, "22120254000186", 1, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- evento ------------------------------------------------------------------------------

    @Test
    void montaOIdDoEventoDeCancelamento() {
        // A chave devolvida pelo SEFIN na emissão real de 2026-08-31.
        String chave = "41069022222120254000186000000000730826087756429072";

        String id = idDps.montarIdEvento(chave, "101101");

        assertThat(id).isEqualTo("PRE" + chave + "101101");
        assertThat(id).hasSize(59);
        // O pattern do XSD (TSIdPedRegEvt). Errar aqui volta como E1235, não como "Id inválido".
        assertThat(id).matches("PRE\\d{56}");
    }

    @Test
    void recusaChaveQueNaoTem50Digitos() {
        assertThatThrownBy(() -> idDps.montarIdEvento("123", "101101"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50 dígitos");
    }
}
