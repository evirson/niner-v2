package com.vetor.niner;

import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.RegimeApuracao;
import com.vetor.niner.fiscal.motor.MotorTributario;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Motor tributário (docs/MODULOFISCAL.md §8). <b>Sem Spring e sem Testcontainers de propósito</b>:
 * o motor não faz I/O, e é essa pureza que permite varrer os quatro regimes por tabela em
 * milissegundos. Se um dia precisar de banco para calcular, o desenho quebrou.
 *
 * <p>O caso central é o par CRT 3 <b>Presumido × Real</b>: mesmo produto, mesmo perfil fiscal,
 * empresas diferentes, alíquotas de PIS/COFINS diferentes. É o teste que prova a DF36 — se a
 * alíquota viesse de {@code cfg_perfil_fiscal_regra} (que só distingue por CRT), as duas linhas
 * dariam o mesmo número e uma das empresas emitiria errado em silêncio.
 */
class MotorTributarioTest {

    private final MotorTributario motor = new MotorTributario();

    // ------------------------------------------------------------------ tabela dos 4 regimes

    /**
     * Uma venda idêntica (3 × R$ 10,00 − R$ 2,00 de desconto = base R$ 28,00) atravessando os
     * quatro regimes do produto. Cada comprador do ERP cai num deles, então nenhum é caso de borda.
     */
    static Stream<Arguments> regimes() {
        return Stream.of(
                // ctx                                   | csosn/cst | icms  | pis   | cofins
                Arguments.of("CRT 1 Simples", crt(1, RegimeApuracao.SIMPLES),
                        regraSimples("102"), "0.00", "0.00", "0.00"),
                Arguments.of("CRT 2 Simples c/ sublimite", crt(2, RegimeApuracao.SIMPLES),
                        regraSimples("103"), "0.00", "0.00", "0.00"),
                Arguments.of("CRT 3 Lucro Presumido", crt(3, RegimeApuracao.PRESUMIDO),
                        regraNormal(), "5.32", "0.18", "0.84"),
                Arguments.of("CRT 3 Lucro Real", crt(3, RegimeApuracao.REAL),
                        regraNormal(), "5.32", "0.46", "2.13"),
                Arguments.of("CRT 4 MEI", crt(4, RegimeApuracao.SIMPLES),
                        regraSimples("102"), "0.00", "0.00", "0.00"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("regimes")
    void calculaVendaPadraoNosQuatroRegimes(String nome, ContextoFiscalEmpresa ctx, RegraFiscal regra,
                                            String icms, String pis, String cofins) {
        ItemTributado item = umItem(motor.calcular(venda(item(regra)), ctx));

        assertThat(item.valorProduto()).isEqualByComparingTo("30.00");
        assertThat(item.valorDesconto()).isEqualByComparingTo("2.00");
        assertThat(item.icms().valor()).isEqualByComparingTo(icms);
        assertThat(item.pis().valor()).isEqualByComparingTo(pis);
        assertThat(item.cofins().valor()).isEqualByComparingTo(cofins);
    }

    /**
     * O coração da DF36 isolado: mudar <b>só</b> o regime de apuração — mesmo CRT, mesma regra
     * fiscal, mesmo item — tem que mudar PIS e COFINS. Se este teste passar com os dois valores
     * iguais, a alíquota voltou a sair do perfil do produto.
     */
    @Test
    void mesmoCrt3ComRegimesDiferentesGeraPisCofinsDiferentes() {
        ItemOperacao item = item(regraNormal());

        ItemTributado presumido = umItem(motor.calcular(venda(item), crt(3, RegimeApuracao.PRESUMIDO)));
        ItemTributado real = umItem(motor.calcular(venda(item), crt(3, RegimeApuracao.REAL)));

        assertThat(presumido.pis().aliquota()).isEqualByComparingTo("0.65");
        assertThat(presumido.cofins().aliquota()).isEqualByComparingTo("3.00");
        assertThat(real.pis().aliquota()).isEqualByComparingTo("1.65");
        assertThat(real.cofins().aliquota()).isEqualByComparingTo("7.60");
        assertThat(real.pis().valor()).isNotEqualByComparingTo(presumido.pis().valor());
        assertThat(real.cofins().valor()).isNotEqualByComparingTo(presumido.cofins().valor());
    }

    // ------------------------------------------------------------------ ICMS

    @Nested
    class Icms {

        @Test
        void csosnSemDestaqueNaoEmiteBaseNemValor() {
            ItemTributado item = umItem(motor.calcular(venda(item(regraSimples("102"))),
                    crt(1, RegimeApuracao.SIMPLES)));

            assertThat(item.icms().csosn()).isEqualTo("102");
            assertThat(item.icms().cst()).isNull();   // campo a mais rejeita tanto quanto campo a menos
            assertThat(item.icms().baseCalculo()).isEqualByComparingTo("0.00");
            assertThat(item.icms().valor()).isEqualByComparingTo("0.00");
        }

        @Test
        void reduzBaseDeCalculoAntesDeAplicarAliquota() {
            RegraFiscal regra = comReducaoBc(regraNormal(), "26.67", "18.00");

            var icms = umItem(motor.calcular(venda(item(regra)), crt(3, RegimeApuracao.PRESUMIDO))).icms();

            assertThat(icms.baseCalculo()).isEqualByComparingTo("20.53");   // 28,00 × 73,33%
            assertThat(icms.valor()).isEqualByComparingTo("3.70");          // 20,53 × 18%
            assertThat(icms.percReducaoBc()).isEqualByComparingTo("26.67");
        }

        @Test
        void calculaFcpSobreAMesmaBaseDoIcms() {
            RegraFiscal regra = comFcp(regraNormal(), "2.00");

            var icms = umItem(motor.calcular(venda(item(regra)), crt(3, RegimeApuracao.PRESUMIDO))).icms();

            assertThat(icms.valorFcp()).isEqualByComparingTo("0.56");       // 28,00 × 2%
        }
    }

    // ------------------------------------------------------------------ recusas explícitas (F11)

    @Nested
    class RecusaEmVezDeChutar {

        @Test
        void itemSemRegraFiscalResolvida() {
            assertThatThrownBy(() -> motor.calcular(venda(item(null)), crt(3, RegimeApuracao.PRESUMIDO)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("nenhuma regra fiscal casou");
        }

        @Test
        void regraSemCfop() {
            RegraFiscal semCfop = trocarCfop(regraNormal(), null);

            assertThatThrownBy(() -> motor.calcular(venda(item(semCfop)), crt(3, RegimeApuracao.PRESUMIDO)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("CFOP");
        }

        @Test
        void crtDoSimplesComCstDeRegimeNormal() {
            assertThatThrownBy(() -> motor.calcular(venda(item(regraNormal())), crt(1, RegimeApuracao.SIMPLES)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("exige CSOSN");
        }

        @Test
        void crtNormalComCsosnDoSimples() {
            assertThatThrownBy(() -> motor.calcular(venda(item(regraSimples("102"))),
                    crt(3, RegimeApuracao.PRESUMIDO)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("exige CST");
        }

        @Test
        void csosn101ForaDoV1PorqueDependeDaAliquotaEfetivaDoDas() {
            assertThatThrownBy(() -> motor.calcular(venda(item(regraSimples("101"))),
                    crt(1, RegimeApuracao.SIMPLES)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("CSOSN 101");
        }

        @Test
        void cstDeSaidaTributadaComEmpresaDoSimples() {
            // Perfil mal configurado: CST 01 de PIS/COFINS numa empresa do Simples. Sem este guard
            // o motor cairia no switch de regime e destacaria valor por cima do DAS.
            RegraFiscal hibrida = trocarCstContribuicoes(regraSimples("102"), "01");

            assertThatThrownBy(() -> motor.calcular(venda(item(hibrida)), crt(1, RegimeApuracao.SIMPLES)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("incompatível com regime SIMPLES");
        }

        @Test
        void contextoComCrtERegimeContraditorios() {
            assertThatThrownBy(() -> motor.calcular(venda(item(regraNormal())), crt(3, RegimeApuracao.SIMPLES)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("PRESUMIDO ou REAL");

            assertThatThrownBy(() -> motor.calcular(venda(item(regraSimples("102"))),
                    crt(1, RegimeApuracao.REAL)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("exige regime de apuração SIMPLES");
        }

        @Test
        void operacaoSemItens() {
            assertThatThrownBy(() -> motor.calcular(
                    new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of()),
                    crt(3, RegimeApuracao.PRESUMIDO)))
                    .isInstanceOf(TributacaoInvalidaException.class);
        }
    }

    // ------------------------------------------------------------------ PIS/COFINS fora do CST 01

    @Test
    void cstDiferenciadoUsaAAliquotaDoPerfilComoOverride() {
        // CST 02 (alíquota diferenciada): aí sim a alíquota é do PRODUTO, não do regime — é o
        // tratamento próprio dele que manda. O regime só decide quando o CST é o 01.
        RegraFiscal regra = comContribuicaoDiferenciada(regraNormal(), "02", "1.00", "4.00");

        ItemTributado item = umItem(motor.calcular(venda(item(regra)), crt(3, RegimeApuracao.REAL)));

        assertThat(item.pis().aliquota()).isEqualByComparingTo("1.00");
        assertThat(item.pis().valor()).isEqualByComparingTo("0.28");
        assertThat(item.cofins().aliquota()).isEqualByComparingTo("4.00");
        assertThat(item.cofins().valor()).isEqualByComparingTo("1.12");
    }

    @Test
    void cstMonofasicoZeraBaseEValor() {
        RegraFiscal regra = comContribuicaoDiferenciada(regraNormal(), "04", null, null);

        ItemTributado item = umItem(motor.calcular(venda(item(regra)), crt(3, RegimeApuracao.REAL)));

        assertThat(item.pis().baseCalculo()).isEqualByComparingTo("0.00");
        assertThat(item.pis().valor()).isEqualByComparingTo("0.00");
        assertThat(item.cofins().valor()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ IPI (DF15)

    @Nested
    class Ipi {

        @Test
        void varejoNaoEquiparadoNaoDestacaIpi() {
            RegraFiscal regra = comIpi(regraNormal(), "50", "5.00");

            var ipi = umItem(motor.calcular(venda(item(regra)), crt(3, RegimeApuracao.PRESUMIDO))).ipi();

            assertThat(ipi.cst()).isNull();
            assertThat(ipi.valor()).isEqualByComparingTo("0.00");
        }

        @Test
        void equiparadoIndustrialDestacaIpiESomaAoTotalDaNota() {
            RegraFiscal regra = comIpi(regraNormal(), "50", "5.00");
            var resultado = motor.calcular(venda(item(regra)),
                    new ContextoFiscalEmpresa(3, RegimeApuracao.PRESUMIDO, "PR", true));

            assertThat(umItem(resultado).ipi().valor()).isEqualByComparingTo("1.40");   // 28,00 × 5%
            assertThat(resultado.totais().valorIpi()).isEqualByComparingTo("1.40");
            assertThat(resultado.totais().valorNota()).isEqualByComparingTo("29.40");   // 28,00 + IPI
        }
    }

    // ------------------------------------------------------------------ IBS/CBS (§8.5)

    @Nested
    class IbsCbs {

        @Test
        void calculaAliquotasDeTransicaoComIbsIntegralmenteEstadual() {
            var g = umItem(motor.calcular(venda(item(comIbsCbs(regraNormal(), "000", "000001", "0", "0"))),
                    crt(3, RegimeApuracao.PRESUMIDO))).ibsCbs();

            assertThat(g.aplicavel()).isTrue();
            assertThat(g.aliquotaCbs()).isEqualByComparingTo("0.90");
            assertThat(g.valorCbs()).isEqualByComparingTo("0.25");        // 28,00 × 0,90%
            assertThat(g.aliquotaIbsUf()).isEqualByComparingTo("0.10");
            assertThat(g.valorIbsUf()).isEqualByComparingTo("0.03");
            assertThat(g.aliquotaIbsMun()).isEqualByComparingTo("0.00");  // só a partir de 2027
            assertThat(g.valorIbsMun()).isEqualByComparingTo("0.00");
        }

        @Test
        void aplicaAReducaoDoCclasstribSobreAAliquota() {
            var g = umItem(motor.calcular(venda(item(comIbsCbs(regraNormal(), "200", "200052", "60", "60"))),
                    crt(3, RegimeApuracao.PRESUMIDO))).ibsCbs();

            assertThat(g.aliquotaCbs()).isEqualByComparingTo("0.36");     // 0,90 × 40%
            assertThat(g.valorCbs()).isEqualByComparingTo("0.10");
            assertThat(g.aliquotaIbsUf()).isEqualByComparingTo("0.04");
            assertThat(g.valorIbsUf()).isEqualByComparingTo("0.01");
        }

        /**
         * O gate do grupo é o <b>CST do item</b>, não o CRT do emitente (rejeição 1021 / regra
         * UB13-20). Uma empresa do Simples com CST de IBS/CBS no perfil calcula normalmente — está
         * dispensada de <i>transmitir</i> até 04/01/2027, não de o sistema saber calcular (DF4).
         */
        @Test
        void empresaDoSimplesTambemCalculaQuandoOPerfilTrazCst() {
            RegraFiscal regra = comIbsCbs(regraSimples("102"), "000", "000001", "0", "0");

            var g = umItem(motor.calcular(venda(item(regra)), crt(1, RegimeApuracao.SIMPLES))).ibsCbs();

            assertThat(g.aplicavel()).isTrue();
            assertThat(g.valorCbs()).isEqualByComparingTo("0.25");
        }

        @Test
        void perfilSemCstGeraAvisoEmVezDeGrupoVazio() {
            var resultado = motor.calcular(venda(item(regraNormal())), crt(3, RegimeApuracao.PRESUMIDO));

            assertThat(umItem(resultado).ibsCbs().aplicavel()).isFalse();
            assertThat(resultado.avisos()).anyMatch(a -> a.contains("IBS/CBS"));
        }
    }

    // ------------------------------------------------------------------ totalização

    /**
     * O total é a <b>soma dos itens já arredondados</b>, não um cálculo sobre a base somada. Dois
     * itens de R$ 0,15 a 17% dão R$ 0,03 cada (0,0255 arredonda para cima) — R$ 0,06 no total;
     * calcular sobre os R$ 0,30 somados daria R$ 0,05. É exatamente esse centavo que a SEFAZ
     * confere e rejeita.
     */
    @Test
    void totalizaSomandoOsItensJaArredondados() {
        RegraFiscal regra = comAliquotaIcms(regraNormal(), "17.00");
        ItemOperacao a = new ItemOperacao(1, um("1"), um("0.15"), null, null, regra);
        ItemOperacao b = new ItemOperacao(2, um("1"), um("0.15"), null, null, regra);

        var totais = motor.calcular(
                new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(a, b)),
                crt(3, RegimeApuracao.PRESUMIDO)).totais();

        assertThat(totais.valorIcms()).isEqualByComparingTo("0.06");
        assertThat(totais.valorProdutos()).isEqualByComparingTo("0.30");
        assertThat(totais.valorNota()).isEqualByComparingTo("0.30");
    }

    @Test
    void acrescimoEntraNaBaseEDescontoSai() {
        RegraFiscal regra = regraNormal();
        ItemOperacao item = new ItemOperacao(1, um("2"), um("50.00"), um("10.00"), um("4.00"), regra);

        var resultado = motor.calcular(
                new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(item)),
                crt(3, RegimeApuracao.PRESUMIDO));

        // 100,00 − 10,00 + 4,00 = 94,00
        assertThat(umItem(resultado).icms().baseCalculo()).isEqualByComparingTo("94.00");
        assertThat(resultado.totais().valorNota()).isEqualByComparingTo("94.00");
    }

    @Test
    void gravaAVersaoDoMotorNoResultado() {
        var resultado = motor.calcular(venda(item(regraNormal())), crt(3, RegimeApuracao.PRESUMIDO));

        // F9: a nota tem que dizer com que motor ela saiu — perfil corrigido em 2027 não recalcula
        // nota de 2026, e sem a versão não há como explicar a diferença ao fisco.
        assertThat(resultado.versaoMotor()).isEqualTo(MotorTributario.VERSAO);
    }

    /**
     * {@code vTotTrib} (Lei 12.741) depende da tabela IBPT por NCM × UF, e {@code cfg_ibpt} está
     * vazia. Enquanto não houver carga, o motor devolve zero <b>e diz isso</b> — somar os tributos
     * calculados daria um número diferente do que a lei pede, impresso no cupom do consumidor.
     */
    @Test
    void valorTotalDeTributosFicaZeradoEnquantoAIbptNaoForCarregada() {
        var resultado = motor.calcular(venda(item(regraNormal())), crt(3, RegimeApuracao.PRESUMIDO));

        assertThat(umItem(resultado).valorTotalTributos()).isEqualByComparingTo("0.00");
        assertThat(resultado.totais().valorTotalTributos()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ fixtures

    private static ContextoFiscalEmpresa crt(int crt, RegimeApuracao regime) {
        return new ContextoFiscalEmpresa(crt, regime, "PR", false);
    }

    private static OperacaoFiscal venda(ItemOperacao item) {
        return new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(item));
    }

    /** 3 × R$ 10,00 − R$ 2,00 de desconto ⇒ base de R$ 28,00 em todos os cenários. */
    private static ItemOperacao item(RegraFiscal regra) {
        return new ItemOperacao(1, um("3"), um("10.00"), um("2.00"), null, regra);
    }

    private static ItemTributado umItem(TributacaoResultado resultado) {
        assertThat(resultado.itens()).hasSize(1);
        return resultado.itens().get(0);
    }

    private static RegraFiscal regraNormal() {
        return new RegraFiscal("5102", "00", null, um("19.00"), null, null,
                "01", null, "01", null, null, null, null, null, null, null, null);
    }

    private static RegraFiscal regraSimples(String csosn) {
        return new RegraFiscal("5102", null, csosn, null, null, null,
                "99", null, "99", null, null, null, null, null, null, null, null);
    }

    private static RegraFiscal comReducaoBc(RegraFiscal r, String reducao, String aliquota) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), um(aliquota), um(reducao), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPisOverride(), r.cstCofins(), r.aliquotaCofinsOverride(),
                r.cstIpi(), r.aliquotaIpi(), r.cstIbsCbs(), r.cClassTrib(),
                r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal comAliquotaIcms(RegraFiscal r, String aliquota) {
        return comReducaoBc(r, null, aliquota);
    }

    private static RegraFiscal comFcp(RegraFiscal r, String fcp) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), um(fcp),
                r.cstPis(), r.aliquotaPisOverride(), r.cstCofins(), r.aliquotaCofinsOverride(),
                r.cstIpi(), r.aliquotaIpi(), r.cstIbsCbs(), r.cClassTrib(),
                r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal trocarCfop(RegraFiscal r, String cfop) {
        return new RegraFiscal(cfop, r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPisOverride(), r.cstCofins(), r.aliquotaCofinsOverride(),
                r.cstIpi(), r.aliquotaIpi(), r.cstIbsCbs(), r.cClassTrib(),
                r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal trocarCstContribuicoes(RegraFiscal r, String cst) {
        return comContribuicaoDiferenciada(r, cst, null, null);
    }

    private static RegraFiscal comContribuicaoDiferenciada(RegraFiscal r, String cst,
                                                           String aliqPis, String aliqCofins) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                cst, um(aliqPis), cst, um(aliqCofins),
                r.cstIpi(), r.aliquotaIpi(), r.cstIbsCbs(), r.cClassTrib(),
                r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal comIpi(RegraFiscal r, String cst, String aliquota) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPisOverride(), r.cstCofins(), r.aliquotaCofinsOverride(),
                cst, um(aliquota), r.cstIbsCbs(), r.cClassTrib(),
                r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal comIbsCbs(RegraFiscal r, String cst, String cClassTrib,
                                         String redIbs, String redCbs) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPisOverride(), r.cstCofins(), r.aliquotaCofinsOverride(),
                r.cstIpi(), r.aliquotaIpi(), cst, cClassTrib,
                um(redIbs), um(redCbs), r.codigoBeneficio());
    }

    private static BigDecimal um(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }
}
