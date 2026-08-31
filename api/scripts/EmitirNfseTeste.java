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
import java.util.regex.*;
import java.util.zip.GZIPOutputStream;

/**
 * S0' — emite UMA NFS-e de R$ 1,00 em PRODUCAO e a CANCELA na mesma execucao.
 *
 * ⛔⛔ ISTO EMITE UM DOCUMENTO FISCAL DE VERDADE no CNPJ da Vetor. Autorizado pelo dono do
 * produto em 2026-08-29. Exige a flag --confirmo-producao, que existe para que nenhuma execucao
 * acidental chegue ate aqui.
 *
 *   java api/scripts/EmitirNfseTeste.java --confirmo-producao \
 *        "<caminho .pfx>" "<senha>" <nDPS> <pTotTribSN>
 *
 * ⚠️ RODE CEDO NO DIA. O prazo de cancelamento em Curitiba e' de 24 h; se o cancelamento falhar,
 * sobra uma NFS-e de R$ 1,00 (ISS R$ 0,05) para o contador resolver.
 *
 * ⚠️ nDPS: a numeracao e' COMPARTILHADA com o finance-v, que emite pelo mesmo CNPJ na serie 1
 * (parseSerie("A") devolve 1). Medido em 2026-08-29: a ultima NFS-e existente e' a nDPS 2000878.
 * Depois de emitir, a sequencia do finance-v precisa ser empurrada para frente, senao ela um dia
 * devolve o numero usado aqui e aquela nota morre em E0014:
 *     SELECT setval('nfse_rps_vetor_a_seq', <nDPS usado>, true);
 *
 * ⚠️ pTotTribSN e' a aliquota efetiva do Simples da empresa — numero do contador. NAO tem
 * default de proposito: chutar aqui grava um numero inventado num documento fiscal real.
 *
 * Regras de Curitiba em PRODUCAO, medidas (docs/MODULONFSE.md §2.6):
 *   - NAO enviar <IM> no prestador  -> E0120 se enviar
 *   - NAO enviar xNome nem end do prestador -> E0121 / E0128
 *   - com opSimpNac=3 + regApTribSN=1 + tpRetISSQN=1, NAO enviar <pAliq>
 */
public class EmitirNfseTeste {

    static final String NS = "http://www.sped.fazenda.gov.br/nfse";
    static final String BASE = "https://sefin.nfse.gov.br/SefinNacional";   // PRODUCAO
    static final int TP_AMB = 1;

    static final String CNPJ = "22120254000186";
    static final String CMUN = "4106902";
    static final int SERIE = 1;
    static final String CTRIB = "010501";              // 01.05.01 licenciamento de programas
    static final String CPF_TOMADOR = "19534563838";
    // ⚠️ xNome do tomador e' OBRIGATORIO — medido em 2026-08-29: <toma> so com <CPF> volta
    // E1235 "the element 'toma' has incomplete content. List of possible elements expected:
    // CAEPF, IM, xNome". O MAPA.md do finance-v marca xNome como [0..1]; nao e'.
    static final String VER_APLIC = "Nainer-S0";
    static final String VALOR = "1.00";

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || !"--confirmo-producao".equals(args[0])) {
            System.out.println("Uso: java EmitirNfseTeste.java --confirmo-producao \\");
            System.out.println("       <pfx> <senha> <nDPS> \"<nome do tomador>\" [pTotTribSN]");
            System.out.println();
            System.out.println("  <nome do tomador>  xNome; use \"-\" para emitir SEM tomador.");
            System.out.println("                     ⛔ CPF sozinho, sem nome, da E1235.");
            System.out.println("  [pTotTribSN]       aliquota efetiva do Simples, do contador.");
            System.out.println("                     Omitido, sai <indTotTrib>0</indTotTrib> (nao informado),");
            System.out.println("                     que passa no schema. ⛔ Nunca invente este numero.");
            System.out.println();
            System.out.println("⛔ Sem a flag nada e' enviado. Isto emite documento fiscal REAL.");
            return;
        }
        Path pfx = Path.of(args[1]);
        String senha = args[2];
        long nDps = Long.parseLong(args[3]);
        String nomeTomador = args[4];
        String pTotTribSN = args.length > 5 ? args[5] : null;

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(pfx)) { ks.load(in, senha.toCharArray()); }
        String alias = null;
        for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
            String a = e.nextElement();
            if (ks.isKeyEntry(a)) { alias = a; break; }
        }
        PrivateKey pk = (PrivateKey) ks.getKey(alias, senha.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, senha.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .sslContext(ctx).connectTimeout(Duration.ofSeconds(20)).build();

        System.out.println("cert ....: " + cert.getSubjectX500Principal().getName().split(",")[0]);
        System.out.println("ambiente : PRODUCAO (tpAmb=1)  " + BASE);
        System.out.println("serie/DPS: " + SERIE + " / " + nDps);

        // ---------- 1. EMITIR ----------
        String id = "DPS" + CMUN + "2" + CNPJ + String.format("%05d", SERIE) + String.format("%015d", nDps);
        String xml = montarDps(id, nDps, nomeTomador, pTotTribSN);
        String assinado = assinarESerializar(xml, "infDPS", id, pk, cert);
        Files.writeString(Path.of("nfse-dps-" + nDps + ".xml"), assinado);

        String corpo = post(http, BASE + "/nfse", "{\"dpsXmlGZipB64\":\"" + gzipB64(assinado) + "\"}");
        System.out.println("\n--- EMISSAO ---\n" + resumo(corpo));
        String chave = extrair(corpo, "chaveAcesso");
        if (chave == null) {
            System.out.println("\n⛔ Nenhuma chave devolvida — NADA foi emitido. Nada a cancelar.");
            return;
        }
        System.out.println("chave: " + chave);

        // ---------- 2. CANCELAR ----------
        String idEvt = "PRE" + chave + "101101";
        String evt = montarEvento(idEvt, chave);
        String evtAssinado = assinarESerializar(evt, "infPedReg", idEvt, pk, cert);
        Files.writeString(Path.of("nfse-cancelamento-" + nDps + ".xml"), evtAssinado);

        String corpoCanc = post(http, BASE + "/nfse/" + chave + "/eventos",
                "{\"pedidoRegistroEventoXmlGZipB64\":\"" + gzipB64(evtAssinado) + "\"}");
        System.out.println("\n--- CANCELAMENTO ---\n" + resumo(corpoCanc));
        System.out.println("\n⚠️ Empurre a sequencia do finance-v: "
                + "SELECT setval('nfse_rps_vetor_a_seq', " + nDps + ", true);");
    }

    static String montarDps(String id, long nDps, String nomeTomador, String pTotTribSN) {
        ZoneId sp = ZoneId.of("America/Sao_Paulo");
        String dhEmi = OffsetDateTime.now(sp).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        String dCompet = LocalDate.now(sp).withDayOfMonth(1).toString();
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        x.append("<DPS xmlns=\"").append(NS).append("\" versao=\"1.01\"><infDPS Id=\"").append(id).append("\">");
        x.append("<tpAmb>").append(TP_AMB).append("</tpAmb>");
        x.append("<dhEmi>").append(dhEmi).append("</dhEmi>");
        x.append("<verAplic>").append(VER_APLIC).append("</verAplic>");
        x.append("<serie>").append(SERIE).append("</serie>");
        x.append("<nDPS>").append(nDps).append("</nDPS>");
        x.append("<dCompet>").append(dCompet).append("</dCompet>");
        x.append("<tpEmit>1</tpEmit><cLocEmi>").append(CMUN).append("</cLocEmi>");
        x.append("<prest><CNPJ>").append(CNPJ).append("</CNPJ>");
        x.append("<regTrib><opSimpNac>3</opSimpNac><regApTribSN>1</regApTribSN>")
         .append("<regEspTrib>0</regEspTrib></regTrib></prest>");
        // "-" = sem tomador. O bloco <toma> e' opcional na DPS (medido: as sondagens sem ele
        // passaram no schema), e sem o xNome ele nem pode ser montado.
        if (nomeTomador != null && !nomeTomador.equals("-") && !nomeTomador.isBlank()) {
            x.append("<toma><CPF>").append(CPF_TOMADOR).append("</CPF>");
            x.append("<xNome>").append(nomeTomador).append("</xNome></toma>");
        }
        x.append("<serv><locPrest><cLocPrestacao>").append(CMUN).append("</cLocPrestacao></locPrest>");
        x.append("<cServ><cTribNac>").append(CTRIB).append("</cTribNac>");
        x.append("<xDescServ>Emissao de teste do sistema Nainer - nota cancelada em seguida</xDescServ>");
        x.append("</cServ></serv>");
        x.append("<valores><vServPrest><vServ>").append(VALOR).append("</vServ></vServPrest>");
        x.append("<trib><tribMun><tribISSQN>1</tribISSQN><tpRetISSQN>1</tpRetISSQN></tribMun>");
        x.append("<totTrib>");
        // Sem a aliquota do contador, "nao informado" e' a saida honesta — passa no schema
        // (medido em produção restrita, 2026-08-29). ⚠️ Se a regra de negocio do SEFIN exigir
        // pTotTribSN para optante do Simples, a recusa aparece aqui e nao queima o numero.
        x.append(pTotTribSN == null ? "<indTotTrib>0</indTotTrib>"
                                    : "<pTotTribSN>" + pTotTribSN + "</pTotTribSN>");
        x.append("</totTrib></trib></valores>");
        x.append("</infDPS></DPS>");
        return x.toString();
    }

    static String montarEvento(String idEvt, String chave) {
        String dh = OffsetDateTime.now(ZoneId.of("America/Sao_Paulo"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        x.append("<pedRegEvento xmlns=\"").append(NS).append("\" versao=\"1.01\">");
        x.append("<infPedReg Id=\"").append(idEvt).append("\">");
        x.append("<tpAmb>").append(TP_AMB).append("</tpAmb>");
        x.append("<verAplic>").append(VER_APLIC).append("</verAplic>");
        x.append("<dhEvento>").append(dh).append("</dhEvento>");
        x.append("<CNPJAutor>").append(CNPJ).append("</CNPJAutor>");
        x.append("<chNFSe>").append(chave).append("</chNFSe>");
        x.append("<e101101><xDesc>Cancelamento de NFS-e</xDesc><cMotivo>1</cMotivo>");
        x.append("<xMotivo>Emissao de teste do sistema Nainer, cancelada na mesma execucao</xMotivo>");
        x.append("</e101101></infPedReg></pedRegEvento>");
        return x.toString();
    }

    static String assinarESerializar(String xml, String tagInf, String id,
                                     PrivateKey pk, X509Certificate cert) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element inf = (Element) doc.getElementsByTagNameNS(NS, tagInf).item(0);
        inf.setIdAttribute("Id", true);

        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        Reference ref = fac.newReference("#" + id, fac.newDigestMethod(DigestMethod.SHA256, null),
                List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null)),
                null, null);
        SignedInfo si = fac.newSignedInfo(
                fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(ref));
        KeyInfoFactory kif = fac.getKeyInfoFactory();
        KeyInfo ki = kif.newKeyInfo(List.of(kif.newX509Data(List.of(cert))));
        fac.newXMLSignature(si, ki).sign(new DOMSignContext(pk, doc.getDocumentElement()));

        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");   // E1229 sem isto
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }

    static String post(HttpClient http, String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return "HTTP " + res.statusCode() + "\n" + res.body();
    }

    /** Corta o corpo gigante (o XML gzipado da nota) para o log ficar legivel. */
    static String resumo(String corpo) {
        String s = corpo.replaceAll("\"[a-zA-Z]*GZipB64\":\"[^\"]{40,}\"", "\"<xml gzipado omitido>\"");
        return s.length() > 1800 ? s.substring(0, 1800) + " …" : s;
    }

    static String extrair(String corpo, String campo) {
        Matcher m = Pattern.compile("\"" + campo + "\"\\s*:\\s*\"([^\"]+)\"").matcher(corpo);
        return m.find() ? m.group(1) : null;
    }

    static String gzipB64(String xml) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(xml.getBytes(StandardCharsets.UTF_8)); }
        return Base64.getEncoder().encodeToString(bo.toByteArray());
    }
}
