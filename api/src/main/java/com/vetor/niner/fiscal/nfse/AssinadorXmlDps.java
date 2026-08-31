package com.vetor.niner.fiscal.nfse;

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
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Assinatura XMLDSig da DPS e do pedido de registro de evento — <b>RSA-SHA256 + C14N exclusiva</b>.
 *
 * <p>Só JDK ({@code java.xml.crypto}, JSR 105), sem Apache Santuario — o mesmo caminho que o
 * {@code AssinadorXmlNfe} já usa para a NF-e e que o {@code finance-v} usa em produção.
 *
 * <h2>⚠️ Não é o mesmo perfil de assinatura da NF-e, e confundir os dois é fácil</h2>
 *
 * <table border="1">
 *   <caption>Diferenças que fazem a assinatura ser recusada se trocadas</caption>
 *   <tr><th></th><th>NF-e / NFC-e ({@code AssinadorXmlNfe})</th><th>NFS-e (esta classe)</th></tr>
 *   <tr><td>Algoritmo</td><td>RSA-<b>SHA1</b></td><td>RSA-<b>SHA256</b></td></tr>
 *   <tr><td>Canonicalização</td><td><b>inclusiva</b> (c14n-20010315)</td><td><b>exclusiva</b> (exc-c14n)</td></tr>
 *   <tr><td>{@code secureValidation}</td><td>precisa ser desligada (o JDK bloqueia SHA-1)</td><td>não precisa</td></tr>
 *   <tr><td>Onde a {@code Signature} entra</td><td>irmã de {@code infNFe}, depois de {@code infNFeSupl}</td><td>filha do elemento raiz</td></tr>
 * </table>
 *
 * <h2>Três detalhes sem os quais isto não funciona</h2>
 *
 * <ol>
 *   <li>{@code setIdAttribute("Id", true)} no elemento assinado — sem marcar o atributo como do
 *       tipo ID, a {@code Reference URI="#DPS…"} não resolve e a assinatura sai apontando para
 *       nada. O SEFIN devolve {@code E0714}.</li>
 *   <li><b>Assinar ANTES de empacotar.</b> O gzip altera os bytes; inverter a ordem quebra o
 *       digest. Ver {@link EmpacotadorDps}.</li>
 *   <li>A serialização precisa manter a <b>declaração XML com o encoding</b>. Omiti-la devolve
 *       {@code E1229} — "Xml não está utilizando codificação UTF-8" —, mensagem que fala em
 *       <i>codificação</i> quando o que falta é a <i>declaração</i>, e manda o diagnóstico para o
 *       lado errado (medido em 2026-08-29).</li>
 * </ol>
 *
 * <p>⭐ <b>Este perfil está provado contra o servidor real, não contra si mesmo.</b> Em
 * 2026-08-29 a mesma montagem foi enviada duas vezes ao Sefin Nacional: com a assinatura íntegra
 * ela passou da checagem e parou numa regra de negócio; com <b>um caractere trocado</b> no
 * {@code SignatureValue} voltou {@code E0714 — "Arquivo enviado com erro na assinatura"}. Uma
 * verificação local da própria assinatura não provaria nada disso.
 */
@Component
public class AssinadorXmlDps {

    /**
     * Assina o XML e devolve o resultado serializado, pronto para o {@link EmpacotadorDps}.
     *
     * @param xml          XML montado, ainda sem {@code Signature}
     * @param tagAssinada  {@code infDPS} na emissão, {@code infPedReg} no evento
     * @param id           valor do atributo {@code Id} do elemento acima (o alvo da Reference)
     * @param chavePrivada chave do certificado A1 <b>do lojista</b>
     * @param certificado  certificado que vai no {@code KeyInfo}
     */
    public String assinar(String xml, String tagAssinada, String id,
                          PrivateKey chavePrivada, X509Certificate certificado) {
        try {
            DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
            fabrica.setNamespaceAware(true);
            Document doc = fabrica.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Element alvo = (Element) doc
                    .getElementsByTagNameNS(MontadorXmlDps.NS, tagAssinada).item(0);
            if (alvo == null) {
                throw new IllegalArgumentException(
                        "XML não tem o elemento <" + tagAssinada + "> para assinar");
            }
            alvo.setIdAttribute("Id", true);

            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
            Reference referencia = fac.newReference(
                    "#" + id,
                    fac.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                            fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                                    (C14NMethodParameterSpec) null)),
                    null, null);

            SignedInfo signedInfo = fac.newSignedInfo(
                    fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                            (C14NMethodParameterSpec) null),
                    fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                    List.of(referencia));

            KeyInfoFactory kif = fac.getKeyInfoFactory();
            KeyInfo keyInfo = kif.newKeyInfo(List.of(kif.newX509Data(List.of(certificado))));

            // A Signature é filha da RAIZ (<DPS> ou <pedRegEvento>), não do elemento assinado.
            fac.newXMLSignature(signedInfo, keyInfo)
                    .sign(new DOMSignContext(chavePrivada, doc.getDocumentElement()));

            return serializar(doc);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar o XML da NFS-e: " + e.getMessage(), e);
        }
    }

    private String serializar(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        // ⚠️ "no" + ENCODING: sem a declaração o SEFIN devolve E1229. Ver o javadoc da classe.
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter saida = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(saida));
        return saida.toString();
    }
}
