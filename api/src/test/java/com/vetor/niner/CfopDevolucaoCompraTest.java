package com.vetor.niner;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * De-para do CFOP da devolução de compra (fechado com o dono do produto em 2026-08-20).
 *
 * <p>O CFOP é a única coisa que a devolução <b>não</b> copia da nota de entrada, e o motivo é que
 * copiar estaria errado: {@code 6101} é "venda de produção do estabelecimento" — do fornecedor.
 * Repetido na nossa nota, ele declararia que <b>nós</b> vendemos produção própria.
 *
 * <p>Acesso por reflexão porque {@code CfopDevolucaoCompra} é package-private no pacote fiscal, e
 * o valor de prendê-lo num teste é maior que o de abrir a visibilidade da classe só para isso.
 */
class CfopDevolucaoCompraTest {

    private static String cfop(String origem) throws Exception {
        Class<?> classe = Class.forName("com.vetor.niner.fiscal.documento.CfopDevolucaoCompra");
        Method m = classe.getDeclaredMethod("de", String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, origem);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** O primeiro dígito acompanha o do fornecedor: 5 dentro do estado, 6 interestadual — se a
     *  mercadoria veio de fora, a volta também cruza a divisa. */
    @Test
    void primeiroDigitoAcompanhaOSentidoDaOperacao() throws Exception {
        assertThat(cfop("5102")).isEqualTo("5202");
        assertThat(cfop("6102")).isEqualTo("6202");
    }

    /** 202 (comercialização), não 201 (industrialização): o Nainer atende varejo, que compra para
     *  revender. */
    @Test
    void vendaComumViraDevolucaoParaComercializacao() throws Exception {
        assertThat(cfop("5101")).isEqualTo("5202");
        assertThat(cfop("5102")).isEqualTo("5202");
        assertThat(cfop("5103")).isEqualTo("5202");
        assertThat(cfop("5104")).isEqualTo("5202");
    }

    /** Operação com substituição tributária tem código próprio de devolução (x.411) — mandar a
     *  volta de uma nota com ST como x.202 apagaria o imposto retido. */
    @Test
    void substituicaoTributariaTemCodigoProprio() throws Exception {
        assertThat(cfop("5401")).isEqualTo("5411");
        assertThat(cfop("5403")).isEqualTo("5411");
        assertThat(cfop("6405")).isEqualTo("6411");
    }

    /**
     * ⚠️ CFOP fora do de-para <b>falha na montagem</b> (F11), em vez de chutar um código.
     *
     * <p>Remessa, consignação e industrialização por encomenda têm regra de devolução própria; um
     * palpite aqui produziria uma nota que a SEFAZ pode até autorizar — e que declararia uma
     * operação que não aconteceu, o que é pior do que não emitir.
     */
    @Test
    void cfopForaDoDeParaFalhaComMotivoPorExtenso() {
        assertThatThrownBy(() -> cfop("5901"))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("5901")
                .hasMessageContaining("remessa");

        assertThatThrownBy(() -> cfop("abc"))
                .isInstanceOf(MontagemInvalidaException.class);
        assertThatThrownBy(() -> cfop(null))
                .isInstanceOf(MontagemInvalidaException.class);
    }
}
