package com.vetor.niner;

import com.vetor.niner.fiscal.nfse.MontadorXmlDps;
import com.vetor.niner.fiscal.nfse.MontadorXmlDps.DadosDps;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MontadorXmlDps} — cada teste aqui prende uma regra que <b>custou uma rejeição do SEFIN
 * real</b> (docs/MODULONFSE.md §2.6 e §2.7). O nome de cada um cita o código de erro que ele evita,
 * porque daqui a seis meses o motivo de uma linha "estranha" no montador vai estar só aqui.
 *
 * <p>⚠️ Estes testes provam a <b>montagem</b>, não a aceitação. Quem prova aceitação é o SEFIN, e
 * isso foi feito uma vez em produção — a NFS-e 7308. Um teste que confere o próprio resultado não
 * substitui aquilo; ele impede que a montagem volte a divergir do que já foi aceito.
 */
class MontadorXmlDpsTest {

    private final MontadorXmlDps montador = new MontadorXmlDps();

    /** Base: exatamente a DPS que o SEFIN autorizou em 2026-08-31 (Vetor, Curitiba, ME/EPP). */
    private DadosDps base() {
        return new DadosDps(
                "DPS410690222212025400018600001000000002001000",
                true,
                OffsetDateTime.of(2026, 8, 31, 10, 30, 0, 0, ZoneOffset.ofHours(-3)),
                "Nainer-1.0",
                1,
                2_001_000L,
                LocalDate.of(2026, 8, 1),
                4106902,
                "22120254000186",
                "07156092",
                false,                              // Curitiba/produção PROÍBE a IM
                MontadorXmlDps.SIMPLES_ME_EPP,
                new BigDecimal("6.00"),
                null, null,                          // sem tomador
                4106902,
                "010501",
                null,
                "Emissao de teste do sistema Nainer",
                new BigDecimal("1.00"),
                BigDecimal.ZERO,
                new BigDecimal("5.00"),
                false);
    }

    @Test
    void montaODocumentoNaOrdemDoSchema() {
        String xml = montador.montar(base());

        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xml).contains("<DPS xmlns=\"http://www.sped.fazenda.gov.br/nfse\" versao=\"1.01\">");
        // A ordem importa: elemento fora de lugar volta como E1235.
        assertThat(indice(xml, "<tpAmb>")).isLessThan(indice(xml, "<dhEmi>"));
        assertThat(indice(xml, "<dhEmi>")).isLessThan(indice(xml, "<serie>"));
        assertThat(indice(xml, "<prest>")).isLessThan(indice(xml, "<serv>"));
        assertThat(indice(xml, "<serv>")).isLessThan(indice(xml, "<valores>"));
    }

    /** E0121 / E0128 — com tpEmit=1 o SEFIN já tem os dados do emitente. */
    @Test
    void prestadorNaoLevaNomeNemEndereco() {
        String xml = montador.montar(base());

        String prest = entre(xml, "<prest>", "</prest>");
        assertThat(prest).contains("<CNPJ>22120254000186</CNPJ>");
        assertThat(prest).doesNotContain("<xNome>");
        assertThat(prest).doesNotContain("<end>");
        assertThat(prest).doesNotContain("<enderNac>");
    }

    /** E0120 (mandou onde não devia) × E0116 (o CNC não reconheceu a que foi mandada). */
    @Test
    void inscricaoMunicipalSoVaiQuandoOMunicipioPede() {
        assertThat(montador.montar(base())).doesNotContain("<IM>");

        DadosDps comIm = comEnviarIm(base(), true);
        assertThat(montador.montar(comIm)).contains("<IM>07156092</IM>");
    }

    /**
     * E1235 — "the element 'toma' has incomplete content. Expected: CAEPF, IM, xNome".
     * Ou o bloco vai completo, ou não vai: meio bloco derruba a DPS inteira.
     */
    @Test
    void tomadorSemNomeNaoGeraBlocoPelaMetade() {
        DadosDps soCpf = comTomador(base(), "19534563838", null);
        assertThat(montador.montar(soCpf)).doesNotContain("<toma>");

        DadosDps completo = comTomador(base(), "19534563838", "FULANO DE TAL");
        String xml = montador.montar(completo);
        assertThat(xml).contains("<toma><CPF>19534563838</CPF><xNome>FULANO DE TAL</xNome></toma>");
    }

    @Test
    void tomadorPessoaJuridicaSaiComoCnpj() {
        DadosDps pj = comTomador(base(), "22.120.254/0001-86", "EMPRESA X");
        assertThat(montador.montar(pj)).contains("<CNPJ>22120254000186</CNPJ><xNome>EMPRESA X");
    }

    /** E0617 — alíquota informada para quem apura pelo Simples sem retenção. */
    @Test
    void naoInformaAliquotaNaApuracaoPeloSimplesSemRetencao() {
        String xml = montador.montar(base());

        assertThat(xml).doesNotContain("<pAliq>");
        assertThat(xml).contains("<tribISSQN>1</tribISSQN><tpRetISSQN>1</tpRetISSQN>");
    }

    /** Com retenção pelo tomador a alíquota volta a fazer sentido, e a ordem do XSD se mantém. */
    @Test
    void informaAliquotaQuandoHaRetencao() {
        String xml = montador.montar(comRetencao(base(), true));

        assertThat(xml).contains("<tribISSQN>1</tribISSQN><pAliq>5.00</pAliq><tpRetISSQN>2</tpRetISSQN>");
    }

    /** E0712 — "Para ME/EPP o indicador de valor total de tributos não pode ser informado". */
    @Test
    void meEppUsaPTotTribSnENuncaIndTotTrib() {
        String xml = montador.montar(base());

        assertThat(xml).contains("<totTrib><pTotTribSN>6.00</pTotTribSN></totTrib>");
        assertThat(xml).doesNotContain("<indTotTrib>");
    }

    /**
     * O par negativo do E0712: sem a alíquota efetiva não existe emissão para optante, e o
     * montador tem de dizer isso com a mensagem que aciona — não estourar um NPE lá na frente.
     */
    @Test
    void optanteSemAliquotaEfetivaFalhaComMensagemAcionavel() {
        DadosDps semAliquota = comAliquotaSimples(base(), null);

        assertThatThrownBy(() -> montador.montar(semAliquota))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NFSE_SEM_ALIQUOTA_SIMPLES|")
                .hasMessageContaining("PGDAS-D");
    }

    /** E0008 — o SEFIN compara o dhEmi com o relógio de Brasília. */
    @Test
    void dhEmiSaiNoFusoDeBrasiliaPreservandoOInstante() {
        // Mesmo instante, declarado em UTC: tem de sair como 10:30-03:00, não 13:30.
        DadosDps emUtc = comEmissao(base(),
                OffsetDateTime.of(2026, 8, 31, 13, 30, 0, 0, ZoneOffset.UTC));

        assertThat(montador.montar(emUtc)).contains("<dhEmi>2026-08-31T10:30:00-03:00</dhEmi>");
    }

    @Test
    void descontoIncondicionadoEntraParaReduzirABaseDoIss() {
        String semDesconto = montador.montar(base());
        assertThat(semDesconto).doesNotContain("<vDescCondIncond>");

        String comDesconto = montador.montar(comDesconto(base(), new BigDecimal("0.30")));
        assertThat(comDesconto).contains("<vDescCondIncond><vDescIncond>0.30</vDescIncond></vDescCondIncond>");
    }

    /** TString do schema recusa caractere de controle e espaço nas pontas. */
    @Test
    void saneiaADescricaoDoServico() {
        DadosDps sujo = comDescricao(base(), "  BANHO\ne  TOSA  <P&G>  ");

        assertThat(montador.montar(sujo))
                .contains("<xDescServ>BANHO e TOSA &lt;P&amp;G&gt;</xDescServ>");
    }

    // ---- ajudantes: record não tem wither, então recompõe-se o que muda ----------------------

    private static int indice(String xml, String trecho) {
        int i = xml.indexOf(trecho);
        assertThat(i).as("trecho %s deveria existir no XML", trecho).isNotNegative();
        return i;
    }

    private static String entre(String xml, String abre, String fecha) {
        return xml.substring(xml.indexOf(abre), xml.indexOf(fecha) + fecha.length());
    }

    private static DadosDps refazer(DadosDps d, boolean enviarIm, BigDecimal aliqSimples,
                                    String docTomador, String nomeTomador, OffsetDateTime emissao,
                                    BigDecimal desconto, boolean retido, String descricao) {
        return new DadosDps(d.idDps(), d.ambienteProducao(), emissao, d.versaoAplicativo(),
                d.serie(), d.numeroDps(), d.competencia(), d.codigoMunicipioIbge(),
                d.cnpjEmitente(), d.inscricaoMunicipal(), enviarIm, d.optaSimples(), aliqSimples,
                docTomador, nomeTomador, d.codigoMunicipioPrestacao(),
                d.codigoTributacaoNacional(), d.codigoTributacaoMunicipal(), descricao,
                d.valorServicos(), desconto, d.aliquotaIss(), retido);
    }

    private DadosDps comEnviarIm(DadosDps d, boolean v) {
        return refazer(d, v, d.aliquotaSimplesEfetiva(), d.documentoTomador(), d.nomeTomador(),
                d.emitidoEm(), d.valorDesconto(), d.issRetido(), d.descricaoServico());
    }

    private DadosDps comTomador(DadosDps d, String doc, String nome) {
        return refazer(d, d.enviarInscricaoMunicipal(), d.aliquotaSimplesEfetiva(), doc, nome,
                d.emitidoEm(), d.valorDesconto(), d.issRetido(), d.descricaoServico());
    }

    private DadosDps comAliquotaSimples(DadosDps d, BigDecimal v) {
        return refazer(d, d.enviarInscricaoMunicipal(), v, d.documentoTomador(), d.nomeTomador(),
                d.emitidoEm(), d.valorDesconto(), d.issRetido(), d.descricaoServico());
    }

    private DadosDps comEmissao(DadosDps d, OffsetDateTime v) {
        return refazer(d, d.enviarInscricaoMunicipal(), d.aliquotaSimplesEfetiva(),
                d.documentoTomador(), d.nomeTomador(), v, d.valorDesconto(), d.issRetido(),
                d.descricaoServico());
    }

    private DadosDps comDesconto(DadosDps d, BigDecimal v) {
        return refazer(d, d.enviarInscricaoMunicipal(), d.aliquotaSimplesEfetiva(),
                d.documentoTomador(), d.nomeTomador(), d.emitidoEm(), v, d.issRetido(),
                d.descricaoServico());
    }

    private DadosDps comRetencao(DadosDps d, boolean v) {
        return refazer(d, d.enviarInscricaoMunicipal(), d.aliquotaSimplesEfetiva(),
                d.documentoTomador(), d.nomeTomador(), d.emitidoEm(), d.valorDesconto(), v,
                d.descricaoServico());
    }

    private DadosDps comDescricao(DadosDps d, String v) {
        return refazer(d, d.enviarInscricaoMunicipal(), d.aliquotaSimplesEfetiva(),
                d.documentoTomador(), d.nomeTomador(), d.emitidoEm(), d.valorDesconto(),
                d.issRetido(), v);
    }
}
