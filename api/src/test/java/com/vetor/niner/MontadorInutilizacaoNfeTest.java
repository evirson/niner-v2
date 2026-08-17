package com.vetor.niner;

import com.vetor.niner.fiscal.documento.AssinadorXmlNfe;
import com.vetor.niner.fiscal.documento.ChaveAcesso;
import com.vetor.niner.fiscal.documento.MontadorInutilizacaoNfe;
import com.vetor.niner.fiscal.documento.MontadorXmlNfce;
import com.vetor.niner.fiscal.documento.MontagemInutilizacaoDtos.PedidoInutilizacao;
import com.vetor.niner.fiscal.documento.MontagemInutilizacaoDtos.XmlInutilizacaoMontado;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pedido de inutilização de numeração (§10.4, bloco B8) — <b>sem Spring, sem rede</b>, mesmo
 * estilo do {@code MontadorEventoCancelamentoTest}: monta, assina de verdade (certificado
 * autoassinado via {@code keytool}), valida contra o XSD oficial <b>e</b> valida a assinatura de
 * volta criptograficamente.
 */
class MontadorInutilizacaoNfeTest {

    private static final String SENHA = "senha-teste-123";
    private static final String CNPJ = "37829453000135";

    private static KeyStore keystore;

    private final MontadorInutilizacaoNfe montador = new MontadorInutilizacaoNfe();
    private final AssinadorXmlNfe assinador = new AssinadorXmlNfe();
    private final ValidadorXsd validador = new ValidadorXsd();

    @BeforeAll
    static void gerarCertificado() throws Exception {
        Path dir = Files.createTempDirectory("niner-inutilizacao");
        Path arquivo = dir.resolve("teste.pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();

        List<String> comando = new ArrayList<>(List.of(
                keytool, "-genkeypair", "-alias", "inutilizacao", "-keyalg", "RSA", "-keysize", "2048",
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
    void inutilizacaoAssinadaPassaNoXsdOficialENaValidacaoCriptograficaDaAssinatura() throws Exception {
        String xmlAssinado = montarEAssinar(pedidoValido());

        assertThatCode(() -> validador.validarInutilizacao(xmlAssinado)).doesNotThrowAnyException();
        assertThat(validarAssinatura(xmlAssinado)).as("assinatura confere").isTrue();
    }

    @Test
    void idSeguesConvencaoDoLeiaute() {
        XmlInutilizacaoMontado montado = montador.montar(pedidoValido());

        // "ID" + cUF(2) + ano(2) + CNPJ(14) + mod(2) + serie(3) + nNFIni(9) + nNFFin(9) = 43.
        int codigoUf = ChaveAcesso.codigoUfDe("PR");
        assertThat(montado.id()).hasSize(43)
                .isEqualTo("ID%02d26%s65001%09d%09d".formatted(codigoUf, CNPJ, 100, 105));
    }

    @Test
    void alterarValorDepoisDeAssinarInvalidaAAssinatura() throws Exception {
        String xmlAssinado = montarEAssinar(pedidoValido());
        String adulterado = xmlAssinado.replace("<nNFFin>105</nNFFin>", "<nNFFin>999</nNFFin>");

        assertThat(validarAssinatura(adulterado))
                .as("mexer no conteúdo depois de assinar tem que quebrar a assinatura").isFalse();
    }

    // ------------------------------------------------------------------ validações (F11)

    @Test
    void justificativaCurtaEhRecusada() {
        PedidoInutilizacao pedido = comJustificativa("motivo curto");

        assertThatThrownBy(() -> montador.montar(pedido))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("15 caracteres");
    }

    @Test
    void justificativaLongaDemaisEhRecusada() {
        PedidoInutilizacao pedido = comJustificativa("x".repeat(256));

        assertThatThrownBy(() -> montador.montar(pedido))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("255 caracteres");
    }

    @Test
    void faixaComFinalMenorQueInicialEhRecusada() {
        PedidoInutilizacao invertido = new PedidoInutilizacao(AmbienteSefaz.HOMOLOGACAO, "PR", CNPJ,
                26, 65, 1, 105, 100, "Numeracao pulada por falha no caixa, nunca transmitida");

        assertThatThrownBy(() -> montador.montar(invertido))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("menor que o inicial");
    }

    // ------------------------------------------------------------------ fixtures

    private static PedidoInutilizacao pedidoValido() {
        return new PedidoInutilizacao(AmbienteSefaz.HOMOLOGACAO, "PR", CNPJ,
                26, 65, 1, 100, 105, "Numeracao pulada por falha no caixa, nunca transmitida");
    }

    private static PedidoInutilizacao comJustificativa(String justificativa) {
        return new PedidoInutilizacao(AmbienteSefaz.HOMOLOGACAO, "PR", CNPJ,
                26, 65, 1, 100, 105, justificativa);
    }

    private String montarEAssinar(PedidoInutilizacao dados) {
        XmlInutilizacaoMontado montado = montador.montar(dados);
        return assinador.assinarInutilizacao(montado.xml(), montado.id(), keystore, SENHA);
    }

    private static boolean validarAssinatura(String xml) throws Exception {
        Document doc = parse(xml);
        NodeList assinaturas = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (assinaturas.getLength() == 0) {
            return false;
        }
        Element infInut = (Element) doc.getElementsByTagNameNS(MontadorXmlNfce.NS, "infInut").item(0);
        if (infInut != null) {
            infInut.setIdAttribute("Id", true);
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
