package com.vetor.niner;

import com.vetor.niner.fiscal.documento.AssinadorXmlNfe;
import com.vetor.niner.fiscal.documento.AssinadorXmlNfe.AssinaturaInvalidaException;
import com.vetor.niner.fiscal.documento.MontadorXmlNfce;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.*;
import com.vetor.niner.fiscal.documento.ValidadorXsd;
import com.vetor.niner.fiscal.motor.MotorTributario;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Assinatura XMLDSig do XML da NFC-e (bloco B6) — <b>sem Spring, sem rede, sem Testcontainers</b>.
 *
 * <p><b>O teste forte aqui não é "tem uma tag Signature"</b>: é validar a assinatura de volta,
 * criptograficamente, com {@code XMLSignature.validate()} — que confere o digest do
 * {@code infNFe} <b>e</b> a assinatura sobre o {@code SignedInfo}. É o mesmo que a SEFAZ faz.
 * Um teste que só procurasse a tag passaria com uma assinatura corrompida.
 *
 * <p>O certificado é autoassinado, gerado no setup via {@code keytool} (bundlado no JDK) — nunca
 * um certificado real versionado no repositório (F7).
 */
class AssinadorXmlNfeTest {

    private static final String SENHA = "senha-teste-123";
    private static final String CNPJ = "37829453000135";

    private static final OffsetDateTime EMISSAO =
            OffsetDateTime.of(2026, 8, 17, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

    private static KeyStore keystore;

    private final MotorTributario motor = new MotorTributario();
    private final MontadorXmlNfce montador = new MontadorXmlNfce();
    private final AssinadorXmlNfe assinador = new AssinadorXmlNfe();
    private final ValidadorXsd validador = new ValidadorXsd();

    @BeforeAll
    static void gerarCertificado() throws Exception {
        Path dir = Files.createTempDirectory("niner-assinatura");
        Path arquivo = dir.resolve("teste.pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();

        List<String> comando = new ArrayList<>(List.of(
                keytool, "-genkeypair", "-alias", "assinatura", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365",
                "-dname", "CN=MITRYUSCASH LTDA:" + CNPJ + ", O=TESTE, C=BR",
                "-keystore", arquivo.toString(), "-storetype", "PKCS12",
                "-storepass", SENHA, "-keypass", SENHA));
        Process p = new ProcessBuilder(comando).redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool falhou: " + saida);
        }

        keystore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(arquivo)) {
            keystore.load(in, SENHA.toCharArray());
        }
    }

    // ------------------------------------------------------------------ o teste que vale

    /**
     * Assina e <b>valida a assinatura de volta</b> — digest do {@code infNFe} e assinatura do
     * {@code SignedInfo}. É o que a SEFAZ faz ao receber; se isto passa, a nota não é recusada
     * por assinatura.
     */
    @Test
    void xmlAssinadoPassaNaValidacaoCriptograficaDaPropriaAssinatura() throws Exception {
        XmlMontado montado = montarNota();

        String assinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, SENHA);

        assertThat(validarAssinatura(assinado)).as("assinatura confere").isTrue();
    }

    /** Assinado, o documento fica completo perante o XSD — o que faltava no B5 era só isto. */
    @Test
    void xmlAssinadoEhValidoPeloXsdOficialSemRemendo() {
        XmlMontado montado = montarNota();

        String assinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, SENHA);

        validador.validarNfe(assinado);   // sem esqueleto de assinatura: agora é a de verdade
    }

    /**
     * A {@code Reference} tem que apontar para o {@code Id} do {@code infNFe}. Se apontasse para
     * outra coisa (ou para nada), a SEFAZ rejeitaria — e o `setIdAttribute` é o que faz isso
     * resolver.
     */
    @Test
    void aReferenciaApontaParaOIdDoInfNFe() throws Exception {
        XmlMontado montado = montarNota();

        String assinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, SENHA);

        assertThat(assinado).contains("URI=\"#NFe" + montado.chaveAcesso() + "\"");
    }

    /** O certificado do assinante vai junto, em {@code KeyInfo/X509Data} — é como a SEFAZ sabe
     *  quem assinou sem ter o certificado previamente. */
    @Test
    void oCertificadoDoAssinanteVaiNoKeyInfo() throws Exception {
        XmlMontado montado = montarNota();

        String assinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, SENHA);

        Document doc = parse(assinado);
        NodeList certs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "X509Certificate");
        assertThat(certs.getLength()).isEqualTo(1);
        assertThat(certs.item(0).getTextContent()).isNotBlank();
    }

    /** RSA-SHA1 + C14N inclusive — o que o MOC exige hoje. Se um dia migrar para SHA-256, este
     *  teste é o que avisa que a constante mudou. */
    @Test
    void usaRsaSha1EC14nInclusiveComoOMocExige() {
        XmlMontado montado = montarNota();

        String assinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, SENHA);

        assertThat(assinado)
                .contains("http://www.w3.org/2000/09/xmldsig#rsa-sha1")
                .contains("http://www.w3.org/TR/2001/REC-xml-c14n-20010315")
                .contains("http://www.w3.org/2000/09/xmldsig#enveloped-signature");
    }

    // ------------------------------------------------------------------ o que a assinatura protege

    /**
     * <b>Este é o teste que prova que a assinatura serve para alguma coisa.</b> Alterar um centavo
     * do valor <b>depois</b> de assinar tem que invalidar a assinatura — é exatamente o ataque
     * contra o qual ela existe. Se este passasse, a assinatura seria decorativa.
     */
    @Test
    void adulterarOValorDepoisDeAssinarInvalidaAAssinatura() throws Exception {
        XmlMontado montado = montarNota();
        String assinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, SENHA);
        assertThat(validarAssinatura(assinado)).as("antes da adulteração").isTrue();

        String adulterado = assinado.replace("<vNF>28.00</vNF>", "<vNF>18.00</vNF>");
        assertThat(adulterado).isNotEqualTo(assinado);

        assertThat(validarAssinatura(adulterado)).as("depois de mexer no total").isFalse();
    }

    /** Reindentar/reformatar depois de assinar também quebra — a canonicalização já fixou os
     *  bytes. É por isso que o serializador não reindenta. */
    @Test
    void reformatarOXmlDepoisDeAssinarInvalidaAAssinatura() throws Exception {
        XmlMontado montado = montarNota();
        String assinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, SENHA);

        String reformatado = assinado.replace("</ide>", "</ide>\n  ");

        assertThat(validarAssinatura(reformatado)).isFalse();
    }

    // ------------------------------------------------------------------ recusas explícitas

    @Test
    void chaveQueNaoBateComOIdDoXmlEhRecusada() {
        XmlMontado montado = montarNota();
        String outraChave = "41260837829453000135650010000000991323005118";

        assertThatThrownBy(() -> assinador.assinar(montado.xml(), outraChave, keystore, SENHA))
                .isInstanceOf(AssinaturaInvalidaException.class)
                .hasMessageContaining("apontando para outra chave");
    }

    @Test
    void xmlSemInfNFeEhRecusado() {
        assertThatThrownBy(() -> assinador.assinar(
                "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"></NFe>", "x", keystore, SENHA))
                .isInstanceOf(AssinaturaInvalidaException.class)
                .hasMessageContaining("sem elemento infNFe");
    }

    @Test
    void senhaErradaDoKeystoreFalhaComMensagemDeAssinatura() {
        XmlMontado montado = montarNota();

        assertThatThrownBy(() -> assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, "errada"))
                .isInstanceOf(AssinaturaInvalidaException.class);
    }

    // ------------------------------------------------------------------ auxiliares

    /** Valida a assinatura como a SEFAZ valida: digest da referência + assinatura do SignedInfo. */
    private static boolean validarAssinatura(String xml) throws Exception {
        Document doc = parse(xml);
        NodeList assinaturas = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (assinaturas.getLength() == 0) {
            return false;
        }
        Element infNFe = (Element) doc.getElementsByTagNameNS(MontadorXmlNfce.NS, "infNFe").item(0);
        if (infNFe != null) {
            infNFe.setIdAttribute("Id", true);   // o validador precisa resolver a Reference também
        }

        DOMValidateContext ctx = new DOMValidateContext(
                new SomenteChaveDoCertificado(), assinaturas.item(0));
        ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx);
    }

    /** Usa a chave pública do próprio {@code X509Certificate} embutido — é assim que o receptor
     *  valida sem conhecer o assinante de antemão. */
    private static class SomenteChaveDoCertificado extends javax.xml.crypto.KeySelector {
        @Override
        public javax.xml.crypto.KeySelectorResult select(
                javax.xml.crypto.dsig.keyinfo.KeyInfo keyInfo,
                javax.xml.crypto.KeySelector.Purpose purpose,
                javax.xml.crypto.AlgorithmMethod method,
                javax.xml.crypto.XMLCryptoContext context) {
            for (Object conteudo : keyInfo.getContent()) {
                if (conteudo instanceof javax.xml.crypto.dsig.keyinfo.X509Data dados) {
                    for (Object item : dados.getContent()) {
                        if (item instanceof X509Certificate cert) {
                            return () -> cert.getPublicKey();
                        }
                    }
                }
            }
            throw new IllegalStateException("Assinatura sem X509Certificate no KeyInfo.");
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Mesma nota de R$ 28,00 dos testes do motor e do montador. */
    private XmlMontado montarNota() {
        RegraFiscal regra = new RegraFiscal("5102", null, "102", null, null, null,
                "99", null, "99", null, null, null, null, null, null);
        ItemOperacao operacao = new ItemOperacao(1, new BigDecimal("3"), new BigDecimal("10.00"),
                new BigDecimal("2.00"), null, regra, null, null, null);
        TributacaoResultado calculo = motor.calcular(
                new OperacaoFiscal(TipoOperacao.VENDA, "PR", TipoDestinatario.CONSUMIDOR_FINAL, List.of(operacao)),
                new ContextoFiscalEmpresa(1, "PR"));

        ItemNota item = new ItemNota(1, "P1", null, "PRODUTO DE TESTE", "61091000", null,
                "UN", new BigDecimal("3"), new BigDecimal("10.00"), null, null, null, 0);

        return montador.montar(new NotaParaMontar(
                AmbienteSefaz.HOMOLOGACAO, 1, 5, 13230051, EMISSAO, "VENDA AO CONSUMIDOR", 1,
                new Emitente(CNPJ, "MITRYUSCASH LTDA", null, "9122793165", 1,
                        "RUA MARIO CHALBAUD BISCAIA", "25", null, "NOVO MUNDO",
                        4106902, "CURITIBA", "PR", "81050240", null),
                null, List.of(item), calculo.itens(), calculo.totais(),
                List.of(new Pagamento("01", calculo.totais().valorNota(), null, null)), null, null,
                new ResponsavelTecnico(CNPJ, "SUPORTE VETOR", "suporte@vetorsistemas.com.br", "4133334444"),
                new UrlsConsultaUf("http://www.fazenda.pr.gov.br/nfce/qrcode",
                        "http://www.fazenda.pr.gov.br/nfce/consulta"),
                new CscEmpresa("000001", "csc-fake-de-teste"),
                "Niner 1.0"));
    }
}
