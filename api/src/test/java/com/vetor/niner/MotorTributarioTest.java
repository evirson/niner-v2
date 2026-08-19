package com.vetor.niner;

import com.vetor.niner.fiscal.motor.MotorTributario;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Motor tributário (docs/MODULOFISCAL.md §8). <b>Sem Spring e sem Testcontainers de propósito</b>:
 * o motor não faz I/O, e é essa pureza que permite varrer o domínio inteiro por tabela em
 * milissegundos. Se um dia precisar de banco para calcular, o desenho quebrou.
 *
 * <p><b>DF37</b> — o produto atende só Simples Nacional (CRT 1 e 2) e MEI (CRT 4). O eixo da massa
 * de teste é o CRT; o regime de apuração deixou de existir junto com Lucro Real e Presumido, e com
 * ele foram embora a alíquota ad-valorem de PIS/COFINS e o IPI (ambos dentro do DAS). O que sobra
 * de tributo realmente calculado — e portanto de risco — é ICMS e IBS/CBS.
 */
class MotorTributarioTest {

    private final MotorTributario motor = new MotorTributario();

    // ------------------------------------------------------------------ tabela dos CRT atendidos

    /**
     * Uma venda idêntica (3 × R$ 10,00 − R$ 2,00 de desconto = base R$ 28,00) atravessando os três
     * CRT do produto. A linha do CRT 2 com CST é o caso torto de propósito: empresa do Simples com
     * excesso de sublimite, que recolhe ICMS <b>fora</b> do DAS e por isso destaca valor.
     */
    static Stream<Arguments> crtAtendidos() {
        return Stream.of(
                //        cenário                       | crt | regra              | icms  | pis  | cofins
                Arguments.of("CRT 1 Simples",              1, regraSimples("102"), "0.00", "0.00", "0.00"),
                Arguments.of("CRT 2 dentro do sublimite",  2, regraSimples("103"), "0.00", "0.00", "0.00"),
                Arguments.of("CRT 2 excesso de sublimite", 2, regraComCst(),       "5.32", "0.00", "0.00"),
                Arguments.of("CRT 4 MEI",                  4, regraSimples("102"), "0.00", "0.00", "0.00"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("crtAtendidos")
    void calculaVendaPadraoEmTodoCrtAtendido(String nome, int crt, RegraFiscal regra,
                                             String icms, String pis, String cofins) {
        ItemTributado item = umItem(motor.calcular(venda(item(regra)), ctx(crt)));

        assertThat(item.valorProduto()).isEqualByComparingTo("30.00");
        assertThat(item.valorDesconto()).isEqualByComparingTo("2.00");
        assertThat(item.icms().valor()).isEqualByComparingTo(icms);
        assertThat(item.pis().valor()).isEqualByComparingTo(pis);
        assertThat(item.cofins().valor()).isEqualByComparingTo(cofins);
    }

    /**
     * <b>Este é o teste de escopo do produto (DF37).</b> CRT 3 é Lucro Real ou Presumido, que o
     * Niner não atende — e a recusa precisa dizer isso, não "não implementado". A diferença
     * importa: quem lê "não implementado" espera que funcione um dia e cadastra CRT 1 para
     * destravar a tela, passando a emitir toda nota com CSOSN e PIS/COFINS zerado.
     */
    @Test
    void recusaCrt3PorEscopoDeProdutoENaoPorFaltaDeImplementacao() {
        assertThatThrownBy(() -> motor.calcular(venda(item(regraComCst())), ctx(3)))
                .isInstanceOf(TributacaoInvalidaException.class)
                .hasMessageContaining("fora do escopo do produto")
                .hasMessageContaining("Lucro Real e Lucro Presumido não são atendidos");
    }

    // ------------------------------------------------------------------ ICMS

    @Nested
    class Icms {

        @ParameterizedTest(name = "CSOSN {0}")
        @ValueSource(strings = {"102", "103", "300", "400"})
        void csosnSemDestaqueNaoEmiteBaseNemValor(String csosn) {
            ItemTributado item = umItem(motor.calcular(venda(item(regraSimples(csosn))), ctx(1)));

            assertThat(item.icms().csosn()).isEqualTo(csosn);
            assertThat(item.icms().cst()).isNull();   // campo a mais rejeita tanto quanto campo a menos
            assertThat(item.icms().baseCalculo()).isEqualByComparingTo("0.00");
            assertThat(item.icms().valor()).isEqualByComparingTo("0.00");
        }

        /**
         * ⚠️ Regressão real (2026-08-19, venda rejeitada pela SEFAZ, cStat 531 "Total da BC ICMS
         * difere do somatório dos itens"): {@code ICMSSN500} no XML sai só com {@code orig}+
         * {@code CSOSN} ({@link com.vetor.niner.fiscal.documento.MontadorXmlNfce#montarIcms} — o
         * grupo de ST retido é {@code minOccurs="0"} e o motor não o calcula), mas o motor calculava
         * uma base/valor não-zero a partir da alíquota da regra e isso ia parar no total da nota —
         * o total declarava uma base que nenhum item de fato carregava no XML. CSOSN 500 significa
         * "ICMS já retido lá atrás"; mesmo com alíquota cadastrada na regra, esta venda não abre
         * base nova nenhuma.
         */
        @Test
        void csosn500DeStRetidoNaoDestacaBaseNemValorMesmoComAliquotaNaRegra() {
            var icms = umItem(motor.calcular(venda(item(comAliquota(regraSimples("500"), "18.00"))), ctx(1))).icms();

            assertThat(icms.csosn()).isEqualTo("500");
            assertThat(icms.baseCalculo()).isEqualByComparingTo("0.00");
            assertThat(icms.valor()).isEqualByComparingTo("0.00");
        }

        @Test
        void reduzBaseDeCalculoAntesDeAplicarAliquota() {
            RegraFiscal regra = comReducaoBc(regraComCst(), "26.67", "18.00");

            var icms = umItem(motor.calcular(venda(item(regra)), ctx(2))).icms();

            assertThat(icms.baseCalculo()).isEqualByComparingTo("20.53");   // 28,00 × 73,33%
            assertThat(icms.valor()).isEqualByComparingTo("3.70");          // 20,53 × 18%
            assertThat(icms.percReducaoBc()).isEqualByComparingTo("26.67");
        }

        @Test
        void calculaFcpSobreAMesmaBaseDoIcms() {
            var icms = umItem(motor.calcular(venda(item(comFcp(regraComCst(), "2.00"))), ctx(2))).icms();

            assertThat(icms.valorFcp()).isEqualByComparingTo("0.56");       // 28,00 × 2%
        }

        /**
         * O CST de ICMS existe no perfil só por causa do CRT 2 (excesso de sublimite, ICMS
         * recolhido fora do Simples). Em CRT 1 e 4 ele é erro de cadastro, e barrá-lo aqui evita
         * uma rejeição da SEFAZ na frente do cliente.
         */
        @ParameterizedTest(name = "CRT {0}")
        @ValueSource(ints = {1, 4})
        void cstDeIcmsSoValeParaCrt2(int crt) {
            assertThatThrownBy(() -> motor.calcular(venda(item(regraComCst())), ctx(crt)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("emite com CSOSN")
                    .hasMessageContaining("Só o CRT 2");
        }

        /**
         * Achado testando ao vivo (2026-08-19): nada impedia salvar uma regra de CRT 2 com CST
         * tributado e alíquota em branco — a nota sairia autorizada com ICMS R$ 0,00, sem aviso
         * nenhum, e o erro só apareceria depois, na contabilidade. CST 00/10/20/51/70/90 destacam
         * imposto: sem alíquota informada, é erro de cadastro, não "alíquota zero por escolha".
         */
        @ParameterizedTest(name = "CST {0}")
        @ValueSource(strings = {"00", "10", "20", "51", "70", "90"})
        void cstTributadoComAliquotaZeradaEhRecusado(String cst) {
            RegraFiscal regra = comAliquota(comCst(regraComCst(), cst), null);

            assertThatThrownBy(() -> motor.calcular(venda(item(regra)), ctx(2)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("CST " + cst)
                    .hasMessageContaining("alíquota de ICMS");
        }

        /**
         * CST 60 é a exceção: o ICMS já foi retido antes por substituição tributária (mesma lógica
         * do CSOSN 500), então não há imposto novo nesta venda — alíquota zerada aqui é o caso
         * normal, não erro de cadastro.
         */
        @Test
        void cst60ComIcmsJaRetidoAceitaAliquotaZerada() {
            RegraFiscal regra = comAliquota(comCst(regraComCst(), "60"), null);

            var icms = umItem(motor.calcular(venda(item(regra)), ctx(2))).icms();

            assertThat(icms.cst()).isEqualTo("60");
            assertThat(icms.valor()).isEqualByComparingTo("0.00");
        }
    }

    // ------------------------------------------------------------------ recusas explícitas (F11)

    @Nested
    class RecusaEmVezDeChutar {

        @Test
        void itemSemRegraFiscalResolvida() {
            assertThatThrownBy(() -> motor.calcular(venda(item(null)), ctx(1)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("nenhuma regra fiscal casou");
        }

        @Test
        void regraSemCfop() {
            assertThatThrownBy(() -> motor.calcular(venda(item(trocarCfop(regraSimples("102"), null))), ctx(1)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("CFOP");
        }

        @Test
        void regraSemCsosnNemCst() {
            RegraFiscal semIcms = new RegraFiscal("5102", null, null, null, null, null,
                    "99", null, "99", null, null, null, null, null, null);

            assertThatThrownBy(() -> motor.calcular(venda(item(semIcms)), ctx(1)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("não define CSOSN nem CST");
        }

        @Test
        void csosn101ForaDoV1PorqueDependeDaAliquotaEfetivaDoDas() {
            assertThatThrownBy(() -> motor.calcular(venda(item(regraSimples("101"))), ctx(1)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("CSOSN 101");
        }

        /**
         * CST 01 é saída tributada normal de PIS/COFINS — só existe em Lucro Real e Presumido. Num
         * perfil do Simples ele destacaria o tributo <b>por cima do DAS</b>: cobrança em
         * duplicidade, e silenciosa, porque a nota seria autorizada normalmente.
         */
        @Test
        void cstDeSaidaTributadaNormalNaoExisteNesteProduto() {
            RegraFiscal hibrida = comContribuicao(regraSimples("102"), "01", null, null);

            assertThatThrownBy(() -> motor.calcular(venda(item(hibrida)), ctx(1)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("só existe em Lucro Real ou Presumido")
                    .hasMessageContaining("use CST 99");
        }

        @Test
        void operacaoSemItens() {
            assertThatThrownBy(() -> motor.calcular(
                    new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of()),
                    ctx(1)))
                    .isInstanceOf(TributacaoInvalidaException.class);
        }
    }

    // ------------------------------------------------------------------ PIS/COFINS (§8.3)

    @Nested
    class PisCofins {

        @Test
        void cst99ZeraTudoPorqueOTributoEstaDentroDoDas() {
            ItemTributado item = umItem(motor.calcular(venda(item(regraSimples("102"))), ctx(1)));

            assertThat(item.pis().cst()).isEqualTo("99");
            assertThat(item.pis().baseCalculo()).isEqualByComparingTo("0.00");
            assertThat(item.pis().aliquota()).isEqualByComparingTo("0.00");
            assertThat(item.cofins().valor()).isEqualByComparingTo("0.00");
        }

        /**
         * A exceção legítima ao CST 99: produto de tratamento próprio que o optante do Simples
         * segrega da receita — monofásico (bebida, combustível, autopeça) e alíquota zero (cesta
         * básica, medicamento). Aí a alíquota é do <b>produto</b>, e vem da regra.
         */
        @Test
        void cstDeTratamentoProprioUsaAAliquotaDaRegra() {
            RegraFiscal regra = comContribuicao(regraSimples("102"), "02", "1.00", "4.00");

            ItemTributado item = umItem(motor.calcular(venda(item(regra)), ctx(1)));

            assertThat(item.pis().aliquota()).isEqualByComparingTo("1.00");
            assertThat(item.pis().valor()).isEqualByComparingTo("0.28");
            assertThat(item.cofins().aliquota()).isEqualByComparingTo("4.00");
            assertThat(item.cofins().valor()).isEqualByComparingTo("1.12");
        }

        @Test
        void cstMonofasicoSemAliquotaZeraBaseEValor() {
            RegraFiscal regra = comContribuicao(regraSimples("102"), "04", null, null);

            ItemTributado item = umItem(motor.calcular(venda(item(regra)), ctx(1)));

            assertThat(item.pis().baseCalculo()).isEqualByComparingTo("0.00");
            assertThat(item.pis().valor()).isEqualByComparingTo("0.00");
            assertThat(item.cofins().valor()).isEqualByComparingTo("0.00");
        }

        @Test
        void regraSemCstDeContribuicao() {
            RegraFiscal regra = comContribuicao(regraSimples("102"), null, null, null);

            assertThatThrownBy(() -> motor.calcular(venda(item(regra)), ctx(1)))
                    .isInstanceOf(TributacaoInvalidaException.class)
                    .hasMessageContaining("não define CST de PIS");
        }
    }

    // ------------------------------------------------------------------ IBS/CBS (§8.5)

    @Nested
    class IbsCbs {

        @Test
        void calculaAliquotasDeTransicaoComIbsIntegralmenteEstadual() {
            var g = umItem(motor.calcular(
                    venda(item(comIbsCbs(regraSimples("102"), "000", "000001", "0", "0"))), ctx(1))).ibsCbs();

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
            var g = umItem(motor.calcular(
                    venda(item(comIbsCbs(regraSimples("102"), "200", "200052", "60", "60"))), ctx(1))).ibsCbs();

            assertThat(g.aliquotaCbs()).isEqualByComparingTo("0.36");     // 0,90 × 40%
            assertThat(g.valorCbs()).isEqualByComparingTo("0.10");
            assertThat(g.aliquotaIbsUf()).isEqualByComparingTo("0.04");
            assertThat(g.valorIbsUf()).isEqualByComparingTo("0.01");
        }

        /**
         * O gate do grupo é o <b>CST do item</b>, não o CRT do emitente (rejeição 1021 / regra
         * UB13-20). Depois da DF37 isso deixou de ser detalhe e virou a espinha do módulo:
         * <b>todas</b> as empresas do produto são Simples ou MEI, e um {@code if (crt == SIMPLES)}
         * aqui desligaria o IBS/CBS para a base inteira de clientes. A dispensa até 04/01/2027 é de
         * <b>transmitir</b>, não de calcular (DF4).
         */
        @ParameterizedTest(name = "CRT {0}")
        @ValueSource(ints = {1, 2, 4})
        void calculaEmTodoCrtAtendidoQuandoOPerfilTrazCst(int crt) {
            var g = umItem(motor.calcular(
                    venda(item(comIbsCbs(regraSimples("102"), "000", "000001", "0", "0"))), ctx(crt))).ibsCbs();

            assertThat(g.aplicavel()).isTrue();
            assertThat(g.valorCbs()).isEqualByComparingTo("0.25");
        }

        @Test
        void perfilSemCstGeraAvisoEmVezDeGrupoVazio() {
            var resultado = motor.calcular(venda(item(regraSimples("102"))), ctx(1));

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
        RegraFiscal regra = comAliquota(regraComCst(), "17.00");
        ItemOperacao a = new ItemOperacao(1, um("1"), um("0.15"), null, null, regra, null);
        ItemOperacao b = new ItemOperacao(2, um("1"), um("0.15"), null, null, regra, null);

        var totais = motor.calcular(
                new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(a, b)),
                ctx(2)).totais();

        assertThat(totais.valorIcms()).isEqualByComparingTo("0.06");
        assertThat(totais.valorProdutos()).isEqualByComparingTo("0.30");
        assertThat(totais.valorNota()).isEqualByComparingTo("0.30");
    }

    @Test
    void acrescimoEntraNaBaseEDescontoSai() {
        ItemOperacao item = new ItemOperacao(1, um("2"), um("50.00"), um("10.00"), um("4.00"), regraComCst(), null);

        var resultado = motor.calcular(
                new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(item)),
                ctx(2));

        // 100,00 − 10,00 + 4,00 = 94,00
        assertThat(umItem(resultado).icms().baseCalculo()).isEqualByComparingTo("94.00");
        assertThat(resultado.totais().valorNota()).isEqualByComparingTo("94.00");
    }

    @Test
    void gravaAVersaoDoMotorNoResultado() {
        var resultado = motor.calcular(venda(item(regraSimples("102"))), ctx(1));

        // F9: a nota tem que dizer com que motor ela saiu — perfil corrigido em 2027 não recalcula
        // nota de 2026, e sem a versão não há como explicar a diferença ao fisco.
        assertThat(resultado.versaoMotor()).isEqualTo(MotorTributario.VERSAO);
    }

    /**
     * {@code vTotTrib} (Lei 12.741) precisa de uma alíquota já resolvida (Nacional × Importado por
     * origem, NCM × {@code cfg_produto_ncm}) — resolução que é do {@code VendaFiscalAssembler}, não
     * do motor (§8.6). Sem NCM cadastrado no produto (ou NCM sem correspondência local), o item
     * chega sem alíquota resolvida (nulo) e o motor não inventa: fica zero, sem aviso.
     */
    @Test
    void semAliquotaDeTributoAproximadoResolvidaVTotTribFicaZero() {
        var resultado = motor.calcular(venda(item(regraSimples("102"))), ctx(1));

        assertThat(umItem(resultado).valorTotalTributos()).isEqualByComparingTo("0.00");
        assertThat(resultado.totais().valorTotalTributos()).isEqualByComparingTo("0.00");
    }

    /**
     * Com a alíquota já resolvida, o motor só multiplica pela base do item — igual a
     * ICMS/PIS/COFINS. Base do {@link #item(RegraFiscal)} é R$ 28,00 (3 × R$ 10,00 − R$ 2,00);
     * 28,00 × 13,45% = 3,766 → 3,77 (HALF_UP, mesma escala de tudo no motor).
     */
    @Test
    void calculaVTotTribAplicandoAAliquotaJaResolvidaSobreABaseDoItem() {
        ItemOperacao item = item(regraSimples("102"), um("13.45"));

        var resultado = motor.calcular(venda(item), ctx(1));

        assertThat(umItem(resultado).valorTotalTributos()).isEqualByComparingTo("3.77");
        assertThat(resultado.totais().valorTotalTributos()).isEqualByComparingTo("3.77");
    }

    // ------------------------------------------------------------------ fixtures

    private static ContextoFiscalEmpresa ctx(int crt) {
        return new ContextoFiscalEmpresa(crt, "PR");
    }

    private static OperacaoFiscal venda(ItemOperacao item) {
        return new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(item));
    }

    /** 3 × R$ 10,00 − R$ 2,00 de desconto ⇒ base de R$ 28,00 em todos os cenários. */
    private static ItemOperacao item(RegraFiscal regra) {
        return item(regra, null);
    }

    private static ItemOperacao item(RegraFiscal regra, BigDecimal aliquotaTributoAproximado) {
        return new ItemOperacao(1, um("3"), um("10.00"), um("2.00"), null, regra, aliquotaTributoAproximado);
    }

    private static ItemTributado umItem(TributacaoResultado resultado) {
        assertThat(resultado.itens()).hasSize(1);
        return resultado.itens().get(0);
    }

    /** O caso normal: CSOSN + PIS/COFINS dentro do DAS. */
    private static RegraFiscal regraSimples(String csosn) {
        return new RegraFiscal("5102", null, csosn, null, null, null,
                "99", null, "99", null, null, null, null, null, null);
    }

    /** Só o CRT 2 (excesso de sublimite) chega aqui: ICMS destacado, PIS/COFINS ainda no DAS. */
    private static RegraFiscal regraComCst() {
        return new RegraFiscal("5102", "00", null, um("19.00"), null, null,
                "99", null, "99", null, null, null, null, null, null);
    }

    private static RegraFiscal comReducaoBc(RegraFiscal r, String reducao, String aliquota) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), um(aliquota), um(reducao), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPis(), r.cstCofins(), r.aliquotaCofins(),
                r.cstIbsCbs(), r.cClassTrib(), r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal comAliquota(RegraFiscal r, String aliquota) {
        return comReducaoBc(r, null, aliquota);
    }

    private static RegraFiscal comFcp(RegraFiscal r, String fcp) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), um(fcp),
                r.cstPis(), r.aliquotaPis(), r.cstCofins(), r.aliquotaCofins(),
                r.cstIbsCbs(), r.cClassTrib(), r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal comCst(RegraFiscal r, String cst) {
        return new RegraFiscal(r.cfop(), cst, r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPis(), r.cstCofins(), r.aliquotaCofins(),
                r.cstIbsCbs(), r.cClassTrib(), r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal trocarCfop(RegraFiscal r, String cfop) {
        return new RegraFiscal(cfop, r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPis(), r.cstCofins(), r.aliquotaCofins(),
                r.cstIbsCbs(), r.cClassTrib(), r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal comContribuicao(RegraFiscal r, String cst, String aliqPis, String aliqCofins) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                cst, um(aliqPis), cst, um(aliqCofins),
                r.cstIbsCbs(), r.cClassTrib(), r.percReducaoIbs(), r.percReducaoCbs(), r.codigoBeneficio());
    }

    private static RegraFiscal comIbsCbs(RegraFiscal r, String cst, String cClassTrib,
                                         String redIbs, String redCbs) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPis(), r.cstCofins(), r.aliquotaCofins(),
                cst, cClassTrib, um(redIbs), um(redCbs), r.codigoBeneficio());
    }

    private static BigDecimal um(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }
}
