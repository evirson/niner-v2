package com.vetor.niner.fiscal.documento;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Assinatura digital do XML da NF-e/NFC-e — XMLDSig **enveloped**, RSA-SHA1 + C14N (bloco B6).
 *
 * <p><b>Só JDK, nenhuma lib de NF-e.</b> É o plano B da DF7, provado no B0: o
 * {@code java.xml.crypto} do JDK 25 assina no formato que a SEFAZ exige, sem o
 * {@code br.com.swconsultoria:java-nfe} (Java 8 + {@code javax} + Axis2, incompatível com Spring
 * Boot 4 jakarta).
 *
 * <p><b>Três detalhes sem os quais isto não funciona</b>, cada um custou tentativa no B0 ou
 * leitura do XSD no B5:
 * <ol>
 *   <li>{@code setIdAttribute("Id", true)} no {@code infNFe} — sem marcar o atributo como do tipo
 *       ID, a {@code Reference URI="#NFe<chave>"} não resolve e a assinatura sai apontando para
 *       nada.</li>
 *   <li>{@code secureValidation = FALSE} — o JDK bloqueia SHA-1 em XMLDSig por padrão, e a NF-e
 *       ainda exige RSA-SHA1 (MOC). Não é desleixo: é a norma que está atrasada, e o dia em que
 *       migrar para SHA-256 isto vira troca de constante.</li>
 *   <li>A assinatura é irmã do {@code infNFe} dentro do {@code NFe}, <b>depois</b> do
 *       {@code infNFeSupl} — ou seja, o QR Code não entra no cálculo (confirmado no XSD, B5).</li>
 * </ol>
 *
 * <p>Puro e sem I/O: recebe XML e {@link KeyStore}, devolve XML assinado. Quem carrega o
 * certificado do banco é o {@code fiscal.certificado}; quem fala com a SEFAZ é o
 * {@code fiscal.sefaz}.
 */
@Component
public class AssinadorXmlNfe {

    private static final String NS_NFE = MontadorXmlNfce.NS;

    /**
     * Assina o {@code infNFe} do XML.
     *
     * @param xml   XML montado pelo {@link MontadorXmlNfce} (ainda sem {@code Signature})
     * @param chave chave de acesso (44 posições) — é o alvo da {@code Reference}
     * @param pkcs12 keystore já aberto com o certificado A1 do <b>lojista</b> (nunca o da Vetor)
     * @param senha senha do keystore
     */
    public String assinar(String xml, String chave, KeyStore pkcs12, String senha) {
        return assinarElemento(xml, "infNFe", "NFe" + chave, pkcs12, senha);
    }

    /**
     * Assina o {@code infEvento} de um evento (cancelamento 110111, B8) — mesma mecânica
     * XMLDSig do {@link #assinar}, só muda o elemento alvo e a convenção do {@code Id}: aqui é
     * {@code "ID" + tpEvento(6) + chave(44) + nSeqEvento(2)}, não {@code "NFe" + chave}
     * (leiauteEventoCancNFe_v1.00.xsd — o {@code Id} tem 54 caracteres: {@code "ID"} + 52 dígitos).
     *
     * @param idEsperado o {@code Id} exato que {@code montarEventoCancelamento} gravou no XML —
     *                   confere igual {@link #assinar} confere {@code "NFe"+chave}, mesma razão:
     *                   assinar sem checar produziria um evento apontando pra outra nota/sequência.
     */
    public String assinarEvento(String xml, String idEsperado, KeyStore pkcs12, String senha) {
        return assinarElemento(xml, "infEvento", idEsperado, pkcs12, senha);
    }

    /**
     * Assina o {@code infInut} de um pedido de inutilização (§10.4, B8) — mesma mecânica, alvo
     * diferente. Ver {@link MontadorInutilizacaoNfe} para a convenção do {@code Id}.
     */
    public String assinarInutilizacao(String xml, String idEsperado, KeyStore pkcs12, String senha) {
        return assinarElemento(xml, "infInut", idEsperado, pkcs12, senha);
    }

    private String assinarElemento(String xml, String nomeElemento, String idEsperado,
                                   KeyStore pkcs12, String senha) {
        try {
            Document doc = parse(xml);

            Element alvo = (Element) doc.getElementsByTagNameNS(NS_NFE, nomeElemento).item(0);
            if (alvo == null) {
                throw new AssinaturaInvalidaException(
                        "XML sem elemento " + nomeElemento + " — nada a assinar.");
            }
            // Marca o atributo como ID; sem isto a Reference "#<id>" não resolve.
            alvo.setIdAttribute("Id", true);

            String id = alvo.getAttribute("Id");
            if (!idEsperado.equals(id)) {
                throw new AssinaturaInvalidaException(
                        ("O Id do %s (%s) não corresponde ao esperado (%s) — assinar assim produziria "
                                + "um documento apontando para outra chave/evento.")
                                        .formatted(nomeElemento, id, idEsperado));
            }

            ChavePrivadaECertificado credencial = extrair(pkcs12, senha);
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

            Reference ref = fac.newReference(
                    "#" + id,
                    fac.newDigestMethod(DigestMethod.SHA1, null),
                    List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                            fac.newTransform(CanonicalizationMethod.INCLUSIVE, (TransformParameterSpec) null)),
                    null, null);

            SignedInfo signedInfo = fac.newSignedInfo(
                    fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                    fac.newSignatureMethod(SignatureMethod.RSA_SHA1, null),
                    List.of(ref));

            KeyInfoFactory kif = fac.getKeyInfoFactory();
            X509Data x509 = kif.newX509Data(List.of(credencial.certificado()));
            KeyInfo keyInfo = kif.newKeyInfo(List.of(x509));

            // A Signature entra no elemento raiz (<NFe> ou <evento>), como irmã do elemento alvo.
            DOMSignContext contexto = new DOMSignContext(credencial.chavePrivada(), doc.getDocumentElement());
            contexto.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
            fac.newXMLSignature(signedInfo, keyInfo).sign(contexto);

            return serializar(doc);
        } catch (AssinaturaInvalidaException e) {
            throw e;
        } catch (Exception e) {
            throw new AssinaturaInvalidaException("Falha ao assinar o XML: " + e.getMessage(), e);
        }
    }

    /**
     * Assina o texto do QR Code da <b>contingência offline</b> (§9.7): RSA-SHA1 sobre os
     * parâmetros, resultado em Base64 — o que o pattern <i>QRCODE V3 OFFLINE</i> do XSD espera no
     * último campo.
     *
     * <p>É assinatura de <b>texto puro</b>, não XMLDSig: não há canonicalização, não há
     * {@code Reference}, não há envelope. Confundir as duas produz um QR que o aplicativo de
     * consulta rejeita — e em contingência não há SEFAZ do outro lado para dizer o que houve.
     *
     * <p>Existe porque em contingência o consumidor recebe o cupom <b>antes</b> de a SEFAZ
     * conhecer a nota: sem essa assinatura, nada distingue um cupom legítimo de um inventado.
     */
    public String assinarQrCodeOffline(String parametros, KeyStore certificado, String senha) {
        try {
            ChavePrivadaECertificado credencial = extrair(certificado, senha);
            Signature rsa = Signature.getInstance("SHA1withRSA");
            rsa.initSign(credencial.chavePrivada());
            rsa.update(parametros.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rsa.sign());
        } catch (AssinaturaInvalidaException e) {
            throw e;
        } catch (Exception e) {
            throw new AssinaturaInvalidaException(
                    "Falha ao assinar o QR Code da contingência: " + e.getMessage(), e);
        }
    }

    /**
     * Abre o keystore e devolve a primeira entrada <b>com chave privada</b>. Um {@code .pfx} de
     * e-CNPJ costuma trazer também a cadeia (AC intermediária, raiz) como entradas só de
     * certificado — assinar com uma delas seria impossível, e pegar "o primeiro alias" sem checar
     * pega a errada dependendo da ordem.
     */
    private static ChavePrivadaECertificado extrair(KeyStore ks, String senha) throws Exception {
        for (String alias : Collections.list(ks.aliases())) {
            if (ks.isKeyEntry(alias)) {
                PrivateKey chave = (PrivateKey) ks.getKey(alias, senha.toCharArray());
                X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
                if (chave != null && cert != null) {
                    return new ChavePrivadaECertificado(chave, cert);
                }
            }
        }
        throw new AssinaturaInvalidaException(
                "O certificado não tem chave privada utilizável — envie o .pfx completo, não só a parte pública.");
    }

    private record ChavePrivadaECertificado(PrivateKey chavePrivada, X509Certificate certificado) {
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);   // sem isto, getElementsByTagNameNS não acha nada
        return dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Serializa <b>sem</b> declaração XML e sem reindentar. Reindentar depois de assinar
     * invalidaria a assinatura: a canonicalização já fixou os bytes, e um espaço a mais muda o
     * digest.
     */
    private static String serializar(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    /** Falha de assinatura — explícita (F11), nunca uma nota assinada "quase certo". */
    public static class AssinaturaInvalidaException extends RuntimeException {
        public AssinaturaInvalidaException(String mensagem) {
            super(mensagem);
        }

        public AssinaturaInvalidaException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
