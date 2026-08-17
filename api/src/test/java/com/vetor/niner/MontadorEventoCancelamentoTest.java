package com.vetor.niner;

import com.vetor.niner.fiscal.documento.AssinadorXmlNfe;
import com.vetor.niner.fiscal.documento.MontadorEventoCancelamento;
import com.vetor.niner.fiscal.documento.MontadorXmlNfce;
import com.vetor.niner.fiscal.documento.MontagemEventoDtos.EventoCancelamento;
import com.vetor.niner.fiscal.documento.MontagemEventoDtos.XmlEventoMontado;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;
import com.vetor.niner.fiscal.documento.ValidadorXsd;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evento de cancelamento (110111, §10.1, bloco B8) — <b>sem Spring, sem rede</b>, mesmo estilo do
 * {@code AssinadorXmlNfeTest}: monta, assina de verdade (certificado autoassinado via
 * {@code keytool}), envelopa, valida contra o XSD oficial <b>e</b> valida a assinatura de volta
 * criptograficamente — não basta ter uma tag {@code Signature}, ela precisa servir.
 */
class MontadorEventoCancelamentoTest {

    private static final String SENHA = "senha-teste-123";
    private static final String CNPJ = "37829453000135";
    private static final String CHAVE = "41260837829453000135650010000000011123456789";

    private static final OffsetDateTime DATA_EVENTO =
            OffsetDateTime.of(2026, 8, 17, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

    private static KeyStore keystore;

    private final MontadorEventoCancelamento montador = new MontadorEventoCancelamento();
    private final AssinadorXmlNfe assinador = new AssinadorXmlNfe();
    private final ValidadorXsd validador = new ValidadorXsd();

    @BeforeAll
    static void gerarCertificado() throws Exception {
        Path dir = Files.createTempDirectory("niner-evento");
        Path arquivo = dir.resolve("teste.pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();

        List<String> comando = new ArrayList<>(List.of(
                keytool, "-genkeypair", "-alias", "evento", "-keyalg", "RSA", "-keysize", "2048",
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

    @Test
    void eventoAssinadoPassaNoXsdOficialENaValidacaoCriptograficaDaAssinatura() throws Exception {
        String envelope = montarEAssinar(eventoValido());

        assertThatCode(() -> validador.validarEventoCancelamento(envelope)).doesNotThrowAnyException();
        assertThat(validarAssinatura(envelope)).as("assinatura confere").isTrue();
    }

    @Test
    void idSeguesConvencaoDoMoc() {
        XmlEventoMontado montado = montador.montar(eventoValido());

        // "ID" + tpEvento(6) + chave(44) + nSeqEvento(2) = 54 caracteres.
        assertThat(montado.id()).hasSize(54).startsWith("ID110111" + CHAVE).endsWith("01");
    }

    @Test
    void alterarValorDepoisDeAssinarInvalidaAAssinatura() throws Exception {
        String envelope = montarEAssinar(eventoValido());
        String adulterado = envelope.replace("Cancelamento", "CancelamentoX");

        assertThat(validarAssinatura(adulterado))
                .as("mexer no conteúdo depois de assinar tem que quebrar a assinatura").isFalse();
    }

    // ------------------------------------------------------------------ validações (F11)

    @Test
    void justificativaCurtaEhRecusada() {
        EventoCancelamento evento = comJustificativa("motivo curto");

        assertThatThrownBy(() -> montador.montar(evento))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("15 caracteres");
    }

    @Test
    void justificativaLongaDemaisEhRecusada() {
        EventoCancelamento evento = comJustificativa("x".repeat(256));

        assertThatThrownBy(() -> montador.montar(evento))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("255 caracteres");
    }

    @Test
    void semProtocoloEhRecusado() {
        EventoCancelamento semProtocolo = new EventoCancelamento(AmbienteSefaz.HOMOLOGACAO, CHAVE,
                CNPJ, "PR", DATA_EVENTO, null, "Cliente desistiu da compra no balcão");

        assertThatThrownBy(() -> montador.montar(semProtocolo))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("protocolo");
    }

    /** Chave truncada nunca deveria chegar aqui, mas se chegar tem que falhar explícito, não
     *  produzir um {@code Id} de tamanho errado que a SEFAZ rejeitaria sem dizer por quê. */
    @Test
    void chaveComTamanhoErradoEhRecusada() {
        EventoCancelamento chaveCurta = new EventoCancelamento(AmbienteSefaz.HOMOLOGACAO, "41260837",
                CNPJ, "PR", DATA_EVENTO, "141260001999999", "Cliente desistiu da compra no balcão");

        assertThatThrownBy(() -> montador.montar(chaveCurta))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("44 posições");
    }

    // ------------------------------------------------------------------ fixtures

    private static EventoCancelamento eventoValido() {
        return new EventoCancelamento(AmbienteSefaz.HOMOLOGACAO, CHAVE, CNPJ, "PR", DATA_EVENTO,
                "141260001999999", "Cliente desistiu da compra no balcão, forma de pagamento errada");
    }

    private static EventoCancelamento comJustificativa(String justificativa) {
        return new EventoCancelamento(AmbienteSefaz.HOMOLOGACAO, CHAVE, CNPJ, "PR", DATA_EVENTO,
                "141260001999999", justificativa);
    }

    private String montarEAssinar(EventoCancelamento dados) {
        XmlEventoMontado montado = montador.montar(dados);
        String assinado = assinador.assinarEvento(montado.xml(), montado.id(), keystore, SENHA);
        return montador.montarEnvelope(1, assinado);
    }

    private static boolean validarAssinatura(String xml) throws Exception {
        Document doc = parse(xml);
        NodeList assinaturas = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (assinaturas.getLength() == 0) {
            return false;
        }
        Element infEvento = (Element) doc.getElementsByTagNameNS(MontadorXmlNfce.NS, "infEvento").item(0);
        if (infEvento != null) {
            infEvento.setIdAttribute("Id", true);
        }

        DOMValidateContext ctx = new DOMValidateContext(new SomenteChaveDoCertificado(), assinaturas.item(0));
        ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx);
    }

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
}
