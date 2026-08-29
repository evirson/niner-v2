package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Pagamento;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O {@code vPag} do XML tem de fechar com o {@code vNF} — achado de auditoria em 2026-08-29.
 *
 * <p><b>O defeito:</b> {@code buscarItens} filtra {@code tipo_item = 'MERCADORIA'} (certo — NFC-e é
 * documento de ICMS, e mão de obra é fato gerador de ISS), mas {@code buscarPagamentos} somava
 * {@code contas_receber} da venda <b>inteira</b>. Numa OS de oficina com R$ 200 de mão de obra +
 * R$ 100 de peça pagos em dinheiro, o XML saía com {@code vNF = 100,00} e {@code vPag = 300,00}.
 *
 * <p>⚠️ <b>E o pior caso não é a SEFAZ rejeitar: é autorizar</b> — uma nota declarando R$ 300 pagos
 * contra R$ 100 de mercadoria, com o erro aparecendo só numa fiscalização. Venda mista é o caso
 * <b>normal</b> de oficina e petshop (a OS nasce com serviço E peças), e com
 * {@code cfg_emite_fiscal_apos_venda} ligado a emissão é automática: ninguém olharia o XML.
 *
 * <p>⚠️ <b>Por que o teste que existia não pegou:</b> {@code ServicoNoCatalogoTest.servicoNaoEntra
 * NosItensDaNotaFiscal} reexecuta o <b>SQL</b> do assembler e confere que só a mercadoria volta.
 * Ele prova a consulta e não olha totais nem pagamentos — passava verde com o defeito presente.
 */
class PagamentoDaNotaFechaComOTotalTest {

    private static Pagamento dinheiro(String valor) {
        return new Pagamento("01", new BigDecimal(valor), null, null);
    }

    private static BigDecimal soma(List<Pagamento> pagamentos) {
        return pagamentos.stream().map(Pagamento::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** ⭐ O caso da oficina: R$ 200 de mão de obra + R$ 100 de peça, tudo em dinheiro. */
    @Test
    void vendaMistaDeclaraSoAParteDeMercadoria() {
        List<Pagamento> ajustados = VendaFiscalAssembler.ratearParaOTotalDaNota(
                List.of(dinheiro("300.00")), new BigDecimal("100.00"));

        assertThat(soma(ajustados)).isEqualByComparingTo("100.00");
        assertThat(ajustados).hasSize(1);
        assertThat(ajustados.getFirst().codigoMeioPagamento()).isEqualTo("01");
    }

    /** Split-tender: o rateio é proporcional e a soma fecha no centavo. */
    @Test
    void oRateioEhProporcionalEFechaExatamente() {
        // R$ 300 pagos (R$ 180 + R$ 120) para uma nota de R$ 100.
        List<Pagamento> ajustados = VendaFiscalAssembler.ratearParaOTotalDaNota(
                List.of(dinheiro("180.00"), dinheiro("120.00")), new BigDecimal("100.00"));

        assertThat(soma(ajustados)).isEqualByComparingTo("100.00");
        assertThat(ajustados.get(0).valor()).isEqualByComparingTo("60.00");
        assertThat(ajustados.get(1).valor()).isEqualByComparingTo("40.00");
    }

    /**
     * ⭐ O resto do arredondamento vai na ÚLTIMA forma — o que garante o fechamento no centavo.
     *
     * <p>Três formas iguais para R$ 100: 33,33 + 33,33 + 33,34. Sem o resto na última, o XML sairia
     * com R$ 99,99 contra R$ 100,00 — e a regra de conferência do modelo 65 não perdoa um centavo.
     */
    @Test
    void oRestoDoArredondamentoVaiNaUltimaForma() {
        List<Pagamento> ajustados = VendaFiscalAssembler.ratearParaOTotalDaNota(
                List.of(dinheiro("100.00"), dinheiro("100.00"), dinheiro("100.00")),
                new BigDecimal("100.00"));

        assertThat(soma(ajustados)).isEqualByComparingTo("100.00");
        assertThat(ajustados.get(2).valor()).isEqualByComparingTo("33.34");
    }

    /**
     * ⭐ O caso NEGATIVO, e o mais importante: <b>venda sem serviço nenhum passa intacta</b>.
     *
     * <p>É a esmagadora maioria das vendas do produto. Reprocessá-las introduziria risco de
     * arredondamento onde não havia problema nenhum — sem este teste, uma implementação que
     * "arredondasse tudo por precaução" passaria nos três testes acima.
     */
    @Test
    void vendaSemServicoPassaIntacta() {
        List<Pagamento> originais = List.of(dinheiro("70.00"), dinheiro("30.00"));

        List<Pagamento> ajustados = VendaFiscalAssembler.ratearParaOTotalDaNota(
                originais, new BigDecimal("100.00"));

        assertThat(ajustados).isSameAs(originais);   // nem uma cópia: o mesmo objeto
    }

    /** Venda sem pagamento (crediário puro ainda não lançado) não divide por zero. */
    @Test
    void semPagamentoNaoDividePorZero() {
        List<Pagamento> vazio = List.of();
        assertThat(VendaFiscalAssembler.ratearParaOTotalDaNota(vazio, new BigDecimal("100.00")))
                .isSameAs(vazio);
    }
}
