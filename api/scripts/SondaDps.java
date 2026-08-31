import org.w3c.dom.*;
import javax.net.ssl.*;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.*;
import javax.xml.crypto.dsig.spec.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Sonda da NFS-e Nacional — bloco S0' de docs/MODULONFSE.md §2.6.
 *
 * Monta uma DPS minima, assina (JSR 105 · RSA-SHA256 · Exclusive C14N), comprime em gzip+base64 e
 * faz POST /nfse. Arquivo unico, roda sem build:
 *
 *   java api/scripts/SondaDps.java "<caminho do .pfx>" "<senha>" <numero inicial da DPS>
 *
 * ⛔ APONTA SO PARA PRODUCAO RESTRITA (tpAmb=2), de proposito: nota emitida ali NAO tem valor
 * fiscal. Para emitir em producao existe procedimento proprio (§2.2) — nota de valor minimo e
 * cancelamento no mesmo dia, dentro do prazo do municipio —, e trocar a constante BASE aqui sem
 * passar por ele emite um documento fiscal DE VERDADE no CNPJ do emitente.
 *
 * ⚠️ A senha entra por argumento, nunca no arquivo. O .pfx nao mora no repositorio
 * (.gitignore cobre *.pfx).
 *
 * O que esta sonda ja mediu, em 2026-08-29 (detalhe e o porque de cada um em MODULONFSE.md §2.6):
 *   E1229  declaracao XML ausente (nao e' o encoding, apesar da mensagem)
 *   E0116  CNC do municipio — sai com e sem IM, em qualquer formato
 *   E0714  assinatura invalida — e' o teste de sabotagem que prova que a nossa passa
 */
public class SondaDps {

    static final String NS = "http://www.sped.fazenda.gov.br/nfse";
    static final String BASE = "https://sefin.producaorestrita.nfse.gov.br/SefinNacional";
    static final String CNPJ = "22120254000186";
    static final String IM = "07156092";
    static final String CMUN = "4106902";
    static final String CTRIB = "010501";
    static final int SERIE = 900;

    public static void main(String[] args) throws Exception {
        Path pfx = Path.of(args[0]);
        String senha = args[1];
        long base = Long.parseLong(args[2]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(pfx)) { ks.load(in, senha.toCharArray()); }
        String alias = null;
        for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
            String a = e.nextElement();
            if (ks.isKeyEntry(a)) { alias = a; break; }
        }
        PrivateKey pk = (PrivateKey) ks.getKey(alias, senha.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        System.out.println("cert ..: " + cert.getSubjectX500Principal().getName().split(",")[0]);
        System.out.println("destino: " + BASE);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, senha.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(ctx)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        enviar(http, pk, cert, base, true, "COM Inscricao Municipal");
        enviar(http, pk, cert, base + 1, false, "SEM Inscricao Municipal");
    }

    static void enviar(HttpClient http, PrivateKey pk, X509Certificate cert,
                       long nDps, boolean comIm, String rotulo) throws Exception {
        System.out.println();
        System.out.println("========== " + rotulo + "  (nDPS " + nDps + ") ==========");
        String id = "DPS" + CMUN + "2" + CNPJ
                + String.format("%05d", SERIE) + String.format("%015d", nDps);
        String xml = montar(id, nDps, comIm);

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element inf = (Element) doc.getElementsByTagNameNS(NS, "infDPS").item(0);
        inf.setIdAttribute("Id", true);
        assinar(doc, id, pk, cert);

        String assinado = serializar(doc);
        String b64 = gzipB64(assinado);

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/nfse"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"dpsXmlGZipB64\":\"" + b64 + "\"}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.println("HTTP " + res.statusCode());
        String corpo = res.body();
        System.out.println(corpo.length() > 1500 ? corpo.substring(0, 1500) + " …" : corpo);
    }

    static void assinar(Document doc, String id, PrivateKey pk, X509Certificate cert) throws Exception {
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        Reference ref = fac.newReference("#" + id,
                fac.newDigestMethod(DigestMethod.SHA256, null),
                List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                                (C14NMethodParameterSpec) null)),
                null, null);
        SignedInfo si = fac.newSignedInfo(
                fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(ref));
        KeyInfoFactory kif = fac.getKeyInfoFactory();
        KeyInfo ki = kif.newKeyInfo(List.of(kif.newX509Data(List.of(cert))));
        fac.newXMLSignature(si, ki).sign(new DOMSignContext(pk, doc.getDocumentElement()));
    }

    static String serializar(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }

    static String gzipB64(String xml) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
            gz.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bo.toByteArray());
    }

    static String montar(String id, long nDps, boolean comIm) {
        String dhEmi = OffsetDateTime.now(ZoneId.of("America/Sao_Paulo"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        String dCompet = LocalDate.now(ZoneId.of("America/Sao_Paulo")).withDayOfMonth(1).toString();
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        x.append("<DPS xmlns=\"").append(NS).append("\" versao=\"1.01\">");
        x.append("<infDPS Id=\"").append(id).append("\">");
        x.append("<tpAmb>2</tpAmb>");
        x.append("<dhEmi>").append(dhEmi).append("</dhEmi>");
        x.append("<verAplic>Nainer-sonda</verAplic>");
        x.append("<serie>").append(SERIE).append("</serie>");
        x.append("<nDPS>").append(nDps).append("</nDPS>");
        x.append("<dCompet>").append(dCompet).append("</dCompet>");
        x.append("<tpEmit>1</tpEmit>");
        x.append("<cLocEmi>").append(CMUN).append("</cLocEmi>");
        x.append("<prest><CNPJ>").append(CNPJ).append("</CNPJ>");
        if (comIm) x.append("<IM>").append(IM).append("</IM>");
        x.append("<regTrib><opSimpNac>3</opSimpNac><regApTribSN>1</regApTribSN>")
         .append("<regEspTrib>0</regEspTrib></regTrib></prest>");
        x.append("<serv><locPrest><cLocPrestacao>").append(CMUN).append("</cLocPrestacao></locPrest>");
        x.append("<cServ><cTribNac>").append(CTRIB).append("</cTribNac>");
        x.append("<xDescServ>SONDA DE HOMOLOGACAO SEM VALOR FISCAL</xDescServ></cServ></serv>");
        x.append("<valores><vServPrest><vServ>1.00</vServ></vServPrest>");
        x.append("<trib><tribMun><tribISSQN>1</tribISSQN><tpRetISSQN>1</tpRetISSQN></tribMun>");
        x.append("<totTrib><pTotTribSN>6.00</pTotTribSN></totTrib></trib></valores>");
        x.append("</infDPS></DPS>");
        return x.toString();
    }
}
