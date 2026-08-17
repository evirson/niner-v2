package com.vetor.niner;

import com.vetor.niner.fiscal.documento.ChaveAcesso;
import com.vetor.niner.fiscal.documento.MontadorXmlNfce;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.*;
import com.vetor.niner.fiscal.documento.ValidadorXsd;
import com.vetor.niner.fiscal.motor.MotorTributario;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Montagem do XML da NFC-e (bloco B5) — <b>sem Spring e sem Testcontainers</b>, igual ao motor:
 * o montador não faz I/O, e o validador só precisa do XSD que está no classpath.
 *
 * <p><b>O teste que vale é a validação contra o XSD oficial da SEFAZ</b>, não uma comparação com
 * string esperada. Um `assertEquals` de XML testaria a minha própria suposição; o XSD é a norma.
 * Todo caso aqui monta uma nota realista, roda o motor de verdade (não um mock — o contrato entre
 * B4 e B5 é justamente o que precisa ser exercitado) e valida o XML resultante.
 */
class MontadorXmlNfceTest {

    private final MotorTributario motor = new MotorTributario();
    private final MontadorXmlNfce montador = new MontadorXmlNfce();
    private final ValidadorXsd validador = new ValidadorXsd();

    /** Meio-dia fixo: fixture ancorada, nunca `now()` — ver feedback_testes_frageis_por_relogio. */
    private static final OffsetDateTime EMISSAO =
            OffsetDateTime.of(2026, 8, 17, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

    /**
     * ⚠️ <b>Esqueleto de assinatura, só para o schema.</b> O XSD declara
     * {@code <xs:element ref="ds:Signature"/>} <b>sem</b> {@code minOccurs="0"} dentro do
     * {@code TNFe}: um XML de NF-e só é completo perante o schema depois de assinado, e não
     * existe variante do tipo que dispense a assinatura.
     *
     * <p>Assinar é o <b>B6</b>. Para o B5 poder provar hoje que toda a estrutura está correta —
     * ordem dos elementos, grupos por CST/CSOSN, patterns decimais, QR Code — o teste completa o
     * documento com uma {@code Signature} <b>bem formada e criptograficamente sem valor</b>.
     * O que este teste cobre é tudo menos a assinatura; o que o B6 acrescentar entra no teste dele.
     *
     * <p>O {@code Reference URI} tem que ser {@code #NFe<chave>} de verdade: o XSD da NF-e
     * restringe esse atributo a {@code minLength=2}, ou seja, <b>proíbe</b> o {@code URI=""} que
     * assinaria "o documento todo" — é o schema forçando a assinatura a apontar para o
     * {@code infNFe} pelo {@code Id}. Bom saber antes do B6.
     */
    private static String comAssinaturaFalsa(String xml, String chave) {
        String signature = """
                <Signature xmlns="http://www.w3.org/2000/09/xmldsig#"><SignedInfo>\
                <CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>\
                <SignatureMethod Algorithm="http://www.w3.org/2000/09/xmldsig#rsa-sha1"/>\
                <Reference URI="#NFe%s"><Transforms>\
                <Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>\
                <Transform Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>\
                </Transforms><DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>\
                <DigestValue>AAAA</DigestValue></Reference></SignedInfo>\
                <SignatureValue>AAAA</SignatureValue>\
                <KeyInfo><X509Data><X509Certificate>AAAA</X509Certificate></X509Data></KeyInfo>\
                </Signature>""".formatted(chave);
        return xml.replace("</NFe>", signature + "</NFe>");
    }

    /** Valida a estrutura montada pelo B5, completando com a assinatura que o B6 vai gerar. */
    private void validarEstrutura(XmlMontado montado) {
        validador.validarNfe(comAssinaturaFalsa(montado.xml(), montado.chaveAcesso()));
    }

    // ------------------------------------------------------------------ o caso central

    @Test
    void notaSimplesDoBalcaoEhValidaPeloXsdOficial() {
        XmlMontado montado = montarVendaSimples(1, "102", null);

        assertThatCode(() -> validarEstrutura(montado)).doesNotThrowAnyException();
        assertThat(montado.chaveAcesso()).hasSize(44).containsOnlyDigits();
        assertThat(montado.xml())
                .contains("<mod>65</mod>")
                .contains("<CSOSN>102</CSOSN>")
                .contains("<CRT>1</CRT>");
    }

    /**
     * O caso normal do produto (DF37): Simples/MEI ⇒ PIS/COFINS CST 99. O XSD manda o 99 no
     * grupo {@code PISOutr}/{@code COFINSOutr} (que exige vBC+alíquota+valor), <b>não</b> no
     * {@code PISNT} que o PoC do B0 usou com CST 07. É o tipo de detalhe que só o schema conta.
     */
    @Test
    void cst99DePisCofinsVaiNoGrupoOutrComValoresZerados() {
        XmlMontado montado = montarVendaSimples(1, "102", null);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<PIS><PISOutr><CST>99</CST><vBC>0.00</vBC><pPIS>0.00</pPIS><vPIS>0.00</vPIS></PISOutr></PIS>")
                .contains("<COFINS><COFINSOutr><CST>99</CST>")
                .doesNotContain("PISNT");
    }

    @ParameterizedTest(name = "CSOSN {0}")
    @ValueSource(strings = {"102", "103", "300", "400"})
    void osQuatroCsosnSemDestaqueUsamOMesmoGrupoIcmssn102(String csosn) {
        XmlMontado montado = montarVendaSimples(1, csosn, null);

        validarEstrutura(montado);
        assertThat(montado.xml()).contains("<ICMSSN102><orig>0</orig><CSOSN>" + csosn + "</CSOSN></ICMSSN102>");
    }

    /** CSOSN 500 (ST retido) — muito comum em confecção e calçado. O bloco de ST retido é
     *  `<xs:sequence minOccurs="0">` no XSD, então sai só orig+CSOSN e o schema aceita. */
    @Test
    void csosn500DeStRetidoEhValidoSemOsCamposDeStQueOMotorNaoCalcula() {
        XmlMontado montado = montarVendaSimples(1, "500", null);

        validarEstrutura(montado);
        assertThat(montado.xml()).contains("<ICMSSN500><orig>0</orig><CSOSN>500</CSOSN></ICMSSN500>");
    }

    /** CRT 2 (excesso de sublimite) é o único que emite com CST — o ICMS sai destacado. */
    @Test
    void crt2ComCstDestacaIcmsNoGrupoIcms00() {
        RegraFiscal regra = new RegraFiscal("5102", "00", null, um("19.00"), null, null,
                "99", null, "99", null, null, null, null, null, null);
        XmlMontado montado = montar(2, regra, null);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<ICMS00><orig>0</orig><CST>00</CST><modBC>3</modBC>")
                .contains("<vICMS>5.32</vICMS>")     // 28,00 × 19%
                .contains("<CRT>2</CRT>");
    }

    // ------------------------------------------------------------------ reforma tributária

    /**
     * Grupo {@code IBSCBS} da NT 2025.002-RTC. O XSD que está no repositório <b>já traz</b> os
     * tipos da reforma (`DFeTiposBasicos_v1.00.xsd`: TTribNFe/TCIBS) — então dá pra validar o
     * grupo de verdade, não só confiar que ele sai bem formado.
     */
    @Test
    void grupoIbsCbsDaReformaEhValidoPeloXsd() {
        RegraFiscal regra = comIbsCbs(regraSimples("102"), "000", "000001");
        XmlMontado montado = montar(1, regra, null);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<IBSCBS><CST>000</CST><cClassTrib>000001</cClassTrib>")
                .contains("<gIBSUF><pIBSUF>0.1000</pIBSUF><vIBSUF>0.03</vIBSUF></gIBSUF>")
                .contains("<gCBS><pCBS>0.9000</pCBS><vCBS>0.25</vCBS></gCBS>")
                .contains("<vIBS>0.03</vIBS>");
    }

    @Test
    void semCstDeIbsCbsNoPerfilOGrupoNaoEhGeradoEOXmlSegueValido() {
        XmlMontado montado = montarVendaSimples(1, "102", null);

        validarEstrutura(montado);
        assertThat(montado.xml()).doesNotContain("IBSCBS");
    }

    // ------------------------------------------------------------------ destinatário

    @Test
    void vendaAnonimaOmiteOGrupoDestInteiro() {
        XmlMontado montado = montarVendaSimples(1, "102", null);

        validarEstrutura(montado);
        assertThat(montado.xml()).doesNotContain("<dest>");
    }

    @Test
    void vendaComCpfEmiteODestinatarioComCpf() {
        Destinatario cliente = new Destinatario("111.444.777-35", "MARIA SILVA", 9, 4106902, "CURITIBA", "PR");
        XmlMontado montado = montarVendaSimples(1, "102", cliente);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<dest><CPF>11144477735</CPF>")
                .contains("<indIEDest>9</indIEDest>");
    }

    // ------------------------------------------------------------------ homologação

    /** Em homologação a frase obrigatória vai no {@code xProd} do PRIMEIRO item (MOC) — foi assim
     *  que a nota do B0 foi autorizada. Os demais itens mantêm a descrição real. */
    @Test
    void emHomologacaoAFraseVaiNoPrimeiroItemESoNele() {
        XmlMontado montado = montarComDoisItens(AmbienteSefaz.HOMOLOGACAO);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<xProd>NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL</xProd>")
                .contains("<xProd>SEGUNDO PRODUTO</xProd>");
    }

    @Test
    void emProducaoADescricaoRealDoProdutoEhMantida() {
        XmlMontado montado = montarComDoisItens(AmbienteSefaz.PRODUCAO);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<xProd>PRIMEIRO PRODUTO</xProd>")
                .doesNotContain("HOMOLOGACAO");
    }

    // ------------------------------------------------------------------ QR Code e chave

    /**
     * QR Code v3.00: {@code ?p=<chave44>|3|<tpAmb>}. Faltar o {@code |<tpAmb>} foi a causa dos
     * três {@code cStat 225} do B0 — o pattern do XSD é quem manda, e é ele que este teste
     * exercita ao validar o documento inteiro.
     */
    @Test
    void qrCodeSegueOFormatoV300ComTpAmb() {
        XmlMontado montado = montarVendaSimples(1, "102", null);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<qrCode><![CDATA[http://www.fazenda.pr.gov.br/nfce/qrcode?p="
                        + montado.chaveAcesso() + "|3|2]]></qrCode>");
    }

    /** A chave do B0, autorizada de verdade pela SEFAZ-PR (protocolo 141260001531993) — se o
     *  cálculo do DV mudar, este teste avisa. */
    @Test
    void reproduzODigitoVerificadorDaChaveAutorizadaNoB0() {
        String chaveB0 = "41260837829453000135650010000000051323005118";

        assertThat(ChaveAcesso.digitoVerificador(chaveB0.substring(0, 43)))
                .isEqualTo(chaveB0.charAt(43));
    }

    @Test
    void chaveDeAcessoTemOLayoutDoMoc() {
        String chave = ChaveAcesso.montar(41, EMISSAO, "37829453000135", 65, 1, 5, 1, 13230051);

        assertThat(chave).hasSize(44);
        assertThat(chave).startsWith("41" + "2608" + "37829453000135" + "65" + "001" + "000000005" + "1");
    }

    // ------------------------------------------------------------------ recusas explícitas (F11)

    @Test
    void pagamentoQueNaoFechaComOTotalEhRecusadoAntesDeIrParaASefaz() {
        NotaParaMontar nota = notaBase(1, regraSimples("102"), null, AmbienteSefaz.HOMOLOGACAO,
                List.of(new Pagamento("01", um("99.99"), null, null)), null);

        assertThatThrownBy(() -> montador.montar(nota))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("não fecha com o total");
    }

    @Test
    void formaDePagamentoSemCodigoTpagEhRecusadaComMensagemQueEnsinaOCaminho() {
        NotaParaMontar nota = notaBase(1, regraSimples("102"), null, AmbienteSefaz.HOMOLOGACAO,
                List.of(new Pagamento(null, um("28.00"), null, null)), null);

        assertThatThrownBy(() -> montador.montar(nota))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("Tipo de Carteira");
    }

    @Test
    void notaSemPagamentoEhRecusada() {
        NotaParaMontar nota = notaBase(1, regraSimples("102"), null, AmbienteSefaz.HOMOLOGACAO, List.of(), null);

        assertThatThrownBy(() -> montador.montar(nota))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("forma de pagamento");
    }

    /**
     * O grupo {@code PISNT} (CST 04-09) não tem campo de valor no XSD. Se o perfil fiscal for
     * configurado com CST 04 e alíquota &gt; 0, o motor calcula um valor que o XML não teria onde
     * colocar — e ele sumiria em silêncio, com a nota sendo autorizada. Recusa explícita.
     */
    @Test
    void cstDeGrupoSemValorComValorCalculadoEhRecusadoEmVezDePerderONumero() {
        RegraFiscal regra = new RegraFiscal("5102", null, "102", null, null, null,
                "04", um("1.65"), "04", um("7.60"), null, null, null, null, null);
        NotaParaMontar nota = notaBase(1, regra, null, AmbienteSefaz.HOMOLOGACAO, pagamentoDe("28.00"), null);

        assertThatThrownBy(() -> montador.montar(nota))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("seria perdido");
    }

    @Test
    void csosnDeStIncompletoEhRecusadoComMotivo() {
        NotaParaMontar nota = notaBase(1, regraSimples("202"), null, AmbienteSefaz.HOMOLOGACAO,
                pagamentoDe("28.00"), null);

        assertThatThrownBy(() -> montador.montar(nota))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("ST");
    }

    // ------------------------------------------------------------------ texto e formatação

    /**
     * Nome de produto vem do cadastro do lojista e pode ter {@code &} ("SABÃO P&G") — sem escape
     * o XML quebra, e quebra só na nota daquele item: bug que só aparece em produção, no caixa.
     */
    @Test
    void descricaoComEComercialNaoQuebraOXml() {
        NotaParaMontar base = notaBase(1, regraSimples("102"), null, AmbienteSefaz.PRODUCAO,
                pagamentoDe("28.00"), null);
        ItemNota comEComercial = new ItemNota(1, "P1", null, "SABAO P&G <PROMO>", "61091000", null,
                "UN", um("3"), um("10.00"), null, null, null);
        NotaParaMontar nota = new NotaParaMontar(base.ambiente(), base.serie(), base.numero(),
                base.codigoNumerico(), base.emissao(), base.naturezaOperacao(), base.tipoEmissao(),
                base.emitente(), base.destinatario(), List.of(comEComercial), base.itensTributados(),
                base.totais(), base.pagamentos(), base.troco(), base.informacoesComplementares(),
                base.responsavelTecnico(), base.urls(), base.versaoAplicativo());

        XmlMontado montado = montador.montar(nota);

        assertThatCode(() -> validarEstrutura(montado)).doesNotThrowAnyException();
        assertThat(montado.xml()).contains("SABAO P&amp;G &lt;PROMO&gt;");
    }

    @Test
    void produtoSemGtinSaiComoSemGtinENuncaComOSkuInterno() {
        XmlMontado montado = montarVendaSimples(1, "102", null);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<cEAN>SEM GTIN</cEAN>")
                .contains("<cEANTrib>SEM GTIN</cEANTrib>");
    }

    @Test
    void descontoDoItemViraVDescNoXml() {
        XmlMontado montado = montarVendaSimples(1, "102", null);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<vProd>30.00</vProd>")
                .contains("<vDesc>2.00</vDesc>");
    }

    // ------------------------------------------------------------------ fixtures

    private XmlMontado montarVendaSimples(int crt, String csosn, Destinatario destinatario) {
        return montar(crt, regraSimples(csosn), destinatario);
    }

    private XmlMontado montar(int crt, RegraFiscal regra, Destinatario destinatario) {
        return montador.montar(notaBase(crt, regra, destinatario, AmbienteSefaz.HOMOLOGACAO,
                null, null));
    }

    private XmlMontado montarComDoisItens(AmbienteSefaz ambiente) {
        RegraFiscal regra = regraSimples("102");
        ItemOperacao op1 = new ItemOperacao(1, um("3"), um("10.00"), um("2.00"), null, regra);
        ItemOperacao op2 = new ItemOperacao(2, um("1"), um("5.00"), null, null, regra);
        TributacaoResultado calculo = motor.calcular(
                new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(op1, op2)),
                new ContextoFiscalEmpresa(1, "PR"));

        List<ItemNota> itens = List.of(
                new ItemNota(1, "P1", null, "PRIMEIRO PRODUTO", "61091000", null, "UN", um("3"), um("10.00"), null, null, null),
                new ItemNota(2, "P2", null, "SEGUNDO PRODUTO", "61091000", null, "UN", um("1"), um("5.00"), null, null, null));

        return montador.montar(new NotaParaMontar(ambiente, 1, 5, 13230051, EMISSAO,
                "VENDA AO CONSUMIDOR", 1, emitente(1), null, itens, calculo.itens(), calculo.totais(),
                List.of(new Pagamento("01", calculo.totais().valorNota(), null, null)), null, null,
                respTec(), urls(), "Niner 1.0"));
    }

    /** Uma venda de 3 × R$ 10,00 − R$ 2,00 = R$ 28,00 — a mesma base do teste do motor. */
    private NotaParaMontar notaBase(int crt, RegraFiscal regra, Destinatario destinatario,
                                    AmbienteSefaz ambiente, List<Pagamento> pagamentos, BigDecimal troco) {
        ItemOperacao operacao = new ItemOperacao(1, um("3"), um("10.00"), um("2.00"), null, regra);
        TributacaoResultado calculo = motor.calcular(
                new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(operacao)),
                new ContextoFiscalEmpresa(crt, "PR"));

        ItemNota item = new ItemNota(1, "P1", null, "PRODUTO DE TESTE", "61091000", null,
                "UN", um("3"), um("10.00"), null, null, null);

        List<Pagamento> pags = pagamentos != null
                ? pagamentos
                : List.of(new Pagamento("01", calculo.totais().valorNota(), null, null));

        return new NotaParaMontar(ambiente, 1, 5, 13230051, EMISSAO, "VENDA AO CONSUMIDOR", 1,
                emitente(crt), destinatario, List.of(item), calculo.itens(), calculo.totais(),
                pags, troco, null, respTec(), urls(), "Niner 1.0");
    }

    private static List<Pagamento> pagamentoDe(String valor) {
        return List.of(new Pagamento("01", um(valor), null, null));
    }

    private static Emitente emitente(int crt) {
        return new Emitente("37829453000135", "MITRYUSCASH LTDA", null, "9122793165", crt,
                "RUA MARIO CHALBAUD BISCAIA", "25", null, "NOVO MUNDO",
                4106902, "CURITIBA", "PR", "81050240", null);
    }

    private static ResponsavelTecnico respTec() {
        return new ResponsavelTecnico("37829453000135", "SUPORTE VETOR SISTEMAS",
                "suporte@vetorsistemas.com.br", "4133334444");
    }

    private static UrlsConsultaUf urls() {
        return new UrlsConsultaUf("http://www.fazenda.pr.gov.br/nfce/qrcode",
                "http://www.fazenda.pr.gov.br/nfce/consulta");
    }

    private static RegraFiscal regraSimples(String csosn) {
        return new RegraFiscal("5102", null, csosn, null, null, null,
                "99", null, "99", null, null, null, null, null, null);
    }

    private static RegraFiscal comIbsCbs(RegraFiscal r, String cst, String cClassTrib) {
        return new RegraFiscal(r.cfop(), r.cstIcms(), r.csosn(), r.aliquotaIcms(), r.percReducaoBc(), r.aliquotaFcp(),
                r.cstPis(), r.aliquotaPis(), r.cstCofins(), r.aliquotaCofins(),
                cst, cClassTrib, null, null, r.codigoBeneficio());
    }

    private static BigDecimal um(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }
}
