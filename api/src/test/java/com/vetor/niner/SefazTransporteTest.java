package com.vetor.niner;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.fiscal.sefaz.SefazDtos.FalhaDeComunicacaoException;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transporte com a SEFAZ (bloco B6) — testado contra um <b>servidor HTTPS real, com mTLS
 * exigido</b>, subido pelo próprio JDK ({@code com.sun.net.httpserver.HttpsServer} com
 * {@code setNeedClientAuth(true)}).
 *
 * <p><b>Por que não um mock HTTP:</b> o que pode dar errado aqui é justamente o handshake TLS
 * mútuo — certificado de cliente não apresentado, cadeia não confiada, contexto compartilhado
 * entre empresas. Um mock que devolve string por cima de HTTP não exercita nada disso. Com o
 * servidor de verdade, "o certificado chegou do outro lado" é uma afirmação verificada, não
 * suposta.
 *
 * <p>Todos os certificados são autoassinados, gerados no setup via {@code keytool} — nada real,
 * nada versionado.
 */
class SefazTransporteTest {

    private static final String SENHA = "senha-teste-123";

    private static HttpsServer servidor;
    private static String urlBase;
    private static Path dir;

    /** Certificado do "lojista" que o transporte apresenta à SEFAZ simulada. */
    private static byte[] pkcs12Cliente;
    private static String impressaoCliente = "fingerprint-cliente-1";

    /** Um segundo lojista — usado para provar que o cache de TLS não mistura empresas. */
    private static byte[] pkcs12OutroCliente;
    private static String impressaoOutroCliente = "fingerprint-cliente-2";

    /** Truststore que o transporte usa para confiar no servidor de teste (faz o papel da
     *  truststore ICP-Brasil em produção). */
    private static Path truststoreCliente;

    /** Guarda o DN do certificado que o servidor viu no handshake — é o que prova o mTLS. */
    private static final AtomicReference<String> ultimoClienteVisto = new AtomicReference<>();
    private static final AtomicReference<String> ultimoCorpoRecebido = new AtomicReference<>();
    private static final AtomicReference<String> respostaParaDevolver = new AtomicReference<>();

    private SefazTransporte transporte;

    @BeforeAll
    static void subirServidorMtls() throws Exception {
        dir = Files.createTempDirectory("niner-sefaz");

        Path ksServidor = dir.resolve("servidor.p12");
        gerarCertificado(ksServidor, "servidor", "CN=localhost");
        Path ksCliente = dir.resolve("cliente.p12");
        gerarCertificado(ksCliente, "cliente", "CN=LOJISTA UM:11222333000181");
        Path ksOutro = dir.resolve("outro.p12");
        gerarCertificado(ksOutro, "outro", "CN=LOJISTA DOIS:99888777000160");

        pkcs12Cliente = Files.readAllBytes(ksCliente);
        pkcs12OutroCliente = Files.readAllBytes(ksOutro);

        // O servidor confia nos dois clientes; o cliente confia no servidor. Em produção, esse
        // "confiar no servidor" é a truststore ICP-Brasil.
        Path tsServidor = dir.resolve("ts-servidor.p12");
        criarTruststore(tsServidor, List.of(exportarCert(ksCliente, "cliente"), exportarCert(ksOutro, "outro")));
        truststoreCliente = dir.resolve("ts-cliente.p12");
        criarTruststore(truststoreCliente, List.of(exportarCert(ksServidor, "servidor")));

        SSLContext ctxServidor = contextoDe(ksServidor, tsServidor);
        servidor = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.setHttpsConfigurator(new HttpsConfigurator(ctxServidor) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters params) {
                javax.net.ssl.SSLParameters p = getSSLContext().getDefaultSSLParameters();
                p.setNeedClientAuth(true);   // mTLS de verdade: sem certificado de cliente, não passa
                params.setSSLParameters(p);
            }
        });
        servidor.createContext("/nfce/NFeAutorizacao4", troca -> {
            try {
                var ssl = ((com.sun.net.httpserver.HttpsExchange) troca).getSSLSession();
                ultimoClienteVisto.set(ssl.getPeerPrincipal().getName());
            } catch (Exception e) {
                ultimoClienteVisto.set(null);
            }
            ultimoCorpoRecebido.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] corpo = respostaParaDevolver.get().getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
            troca.sendResponseHeaders(200, corpo.length);
            try (OutputStream os = troca.getResponseBody()) {
                os.write(corpo);
            }
        });
        servidor.start();
        urlBase = "https://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @AfterAll
    static void pararServidor() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    private SefazTransporte transporteComTruststore() {
        NinerProperties props = new NinerProperties(null, null, null, null, null, null,
                new NinerProperties.Fiscal(truststoreCliente.toString(), SENHA, null), null);
        return new SefazTransporte(props);
    }

    // ------------------------------------------------------------------ o caminho feliz

    @Test
    void autorizacaoBemSucedidaExtraiCstatProtocoloEChave() {
        respostaParaDevolver.set(respostaSefaz("100", "Autorizado o uso da NF-e",
                "141260001531993", "41260837829453000135650010000000051323005118"));
        transporte = transporteComTruststore();

        RespostaSefaz r = transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, impressaoCliente);

        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.cStat()).isEqualTo("100");
        assertThat(r.xMotivo()).isEqualTo("Autorizado o uso da NF-e");
        assertThat(r.protocolo()).isEqualTo("141260001531993");
        assertThat(r.chaveAcesso()).isEqualTo("41260837829453000135650010000000051323005118");
        assertThat(r.autorizado()).isTrue();
        assertThat(r.corpoXml()).contains("<cStat>100</cStat>");
    }

    /**
     * <b>Achado real cancelando uma venda contra a SEFAZ-PR (2026-08-19), mesma classe do bug de
     * {@code autorizacaoBemSucedidaExtraiCstatProtocoloEChave}:</b> a resposta de
     * {@code RecepcaoEvento4} (cancelamento 110111) também tem DOIS {@code cStat} — um no LOTE de
     * evento ({@code retEnvEvento}, 128 "Lote de Evento Processado", não diz nada sobre o evento)
     * e outro dentro de {@code retEvento/infEvento}, o resultado REAL do cancelamento (135). Sem
     * escopo pra {@code infEvento}, o primeiro {@code cStat} do texto (128, do lote) vencia e todo
     * cancelamento saía "recusado" mesmo quando a SEFAZ autorizava de verdade.
     */
    @Test
    void cancelamentoComDoisCstatExtraiODoEventoNaoODoLote() {
        respostaParaDevolver.set("""
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"><soap:Body>\
                <nfeResultMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/RecepcaoEvento4">\
                <retEnvEvento versao="1.00"><idLote>1</idLote><tpAmb>2</tpAmb><cOrgao>41</cOrgao>\
                <cStat>128</cStat><xMotivo>Lote de Evento Processado</xMotivo>\
                <retEvento versao="1.00"><infEvento Id="ID1101114126083782945300013565001000000042">\
                <tpAmb>2</tpAmb><cOrgao>41</cOrgao><cStat>135</cStat>\
                <xMotivo>Evento registrado e vinculado a NF-e</xMotivo>\
                <chNFe>41260837829453000135650010000000421480365360</chNFe>\
                <tpEvento>110111</tpEvento><xEvento>Cancelamento</xEvento><nSeqEvento>1</nSeqEvento>\
                <nProt>141260001537524</nProt></infEvento></retEvento></retEnvEvento>\
                </nfeResultMsg></soap:Body></soap:Envelope>""");
        transporte = transporteComTruststore();

        RespostaSefaz r = transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "RecepcaoEvento4",
                "<envEvento versao=\"1.00\"/>", pkcs12Cliente, SENHA, impressaoCliente);

        assertThat(r.cStat()).as("cStat do EVENTO (infEvento), não do lote (retEnvEvento)").isEqualTo("135");
        assertThat(r.xMotivo()).isEqualTo("Evento registrado e vinculado a NF-e");
        assertThat(r.protocolo()).isEqualTo("141260001537524");
        assertThat(r.chaveAcesso()).isEqualTo("41260837829453000135650010000000421480365360");
        assertThat(r.autorizado()).isFalse(); // "autorizado" no sentido de RespostaSefaz é cStat 100 (nota); evento usa outra checagem no chamador
    }

    /**
     * <b>O teste que prova o mTLS.</b> O servidor exige certificado de cliente e registra o DN de
     * quem apresentou — se o transporte não estivesse mandando o certificado do lojista, o
     * handshake nem completaria.
     */
    @Test
    void oCertificadoDoLojistaChegaDoOutroLadoNoHandshake() {
        respostaParaDevolver.set(respostaSefaz("100", "Autorizado", "1", "2"));
        transporte = transporteComTruststore();

        transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, impressaoCliente);

        assertThat(ultimoClienteVisto.get()).contains("LOJISTA UM");
    }

    /**
     * <b>O risco mais grave deste módulo, testado.</b> Duas empresas em sequência têm que
     * apresentar cada uma o SEU certificado. Se o {@code SSLContext} fosse cacheado sem chave, a
     * segunda emitiria com o certificado da primeira — e a nota <b>seria autorizada</b>, no CNPJ
     * errado, sem erro nenhum. É o tipo de bug que só aparece na contabilidade de outra empresa.
     */
    @Test
    void duasEmpresasEmSequenciaUsamCadaUmaOSeuProprioCertificado() {
        respostaParaDevolver.set(respostaSefaz("100", "Autorizado", "1", "2"));
        transporte = transporteComTruststore();

        transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, impressaoCliente);
        assertThat(ultimoClienteVisto.get()).contains("LOJISTA UM");

        transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12OutroCliente, SENHA, impressaoOutroCliente);
        assertThat(ultimoClienteVisto.get())
                .as("a segunda empresa não pode apresentar o certificado da primeira")
                .contains("LOJISTA DOIS");
    }

    @Test
    void oPayloadVaiEnvelopadoEmSoapComONamespaceDoServico() {
        respostaParaDevolver.set(respostaSefaz("100", "Autorizado", "1", "2"));
        transporte = transporteComTruststore();

        transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"><idLote>1</idLote></enviNFe>", pkcs12Cliente, SENHA, impressaoCliente);

        assertThat(ultimoCorpoRecebido.get())
                .contains("soap:Envelope")
                .contains("http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4")
                .contains("<idLote>1</idLote>");
    }

    // ------------------------------------------------------------------ rejeição × falha

    /**
     * Rejeição da SEFAZ <b>não</b> é falha de comunicação: vem com {@code cStat} e é resposta
     * legítima. A distinção decide o fluxo — rejeição significa "corrija a nota", falha significa
     * "tente de novo ou entre em contingência".
     */
    @Test
    void rejeicaoDaSefazNaoEhTratadaComoFalhaDeComunicacao() {
        respostaParaDevolver.set(respostaSefaz("539", "Duplicidade de NF-e com diferença na chave de acesso", null, null));
        transporte = transporteComTruststore();

        RespostaSefaz r = transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, impressaoCliente);

        assertThat(r.autorizado()).isFalse();
        assertThat(r.cStat()).isEqualTo("539");
        assertThat(r.xMotivo()).contains("Duplicidade");
    }

    @Test
    void loteEmProcessamentoEhReconhecidoComoTalNaoComoErro() {
        respostaParaDevolver.set(respostaSefaz("103", "Lote recebido com sucesso", null, null));
        transporte = transporteComTruststore();

        RespostaSefaz r = transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, impressaoCliente);

        assertThat(r.autorizado()).isFalse();
        assertThat(r.emProcessamento()).isTrue();
    }

    @Test
    void servidorInalcancavelViraFalhaDeComunicacaoComAUrlNaMensagem() {
        transporte = transporteComTruststore();
        String urlMorta = "https://127.0.0.1:1/nfce/NFeAutorizacao4";

        assertThatThrownBy(() -> transporte.enviar(urlMorta, "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, impressaoCliente))
                .isInstanceOf(FalhaDeComunicacaoException.class)
                .hasMessageContaining("SEFAZ");
    }

    /** Sem truststore adequada, o handshake falha — é exatamente o {@code PKIX path building
     *  failed} que a raiz ICP-Brasil ausente provoca em produção. Vira falha de comunicação, não
     *  rejeição fiscal. */
    @Test
    void servidorNaoConfiavelViraFalhaDeComunicacaoNaoRejeicaoFiscal() {
        respostaParaDevolver.set(respostaSefaz("100", "Autorizado", "1", "2"));
        NinerProperties semTruststore = new NinerProperties(null, null, null, null, null, null,
                new NinerProperties.Fiscal(null, null, null), null);
        SefazTransporte semConfianca = new SefazTransporte(semTruststore);

        assertThatThrownBy(() -> semConfianca.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, "fingerprint-sem-truststore"))
                .isInstanceOf(FalhaDeComunicacaoException.class);
    }

    @Test
    void urlNaoCadastradaParaAUfEhRecusadaComMensagemQueApontaATabela() {
        transporte = transporteComTruststore();

        assertThatThrownBy(() -> transporte.enviar(null, "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, impressaoCliente))
                .isInstanceOf(FalhaDeComunicacaoException.class)
                .hasMessageContaining("cfg_uf_autorizador");
    }

    /** Sem impressão digital não há como separar o cache por empresa — e é justamente o que
     *  impede o vazamento de certificado entre lojistas. Falhar cedo é melhor que cachear errado. */
    @Test
    void impressaoDigitalAusenteEhRecusadaPorqueQuebrariaOIsolamentoDoCache() {
        transporte = transporteComTruststore();

        assertThatThrownBy(() -> transporte.enviar(urlBase + "/nfce/NFeAutorizacao4", "NFeAutorizacao4",
                "<enviNFe versao=\"4.00\"/>", pkcs12Cliente, SENHA, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("por empresa");
    }

    // ------------------------------------------------------------------ auxiliares

    private static String respostaSefaz(String cStat, String xMotivo, String nProt, String chNFe) {
        StringBuilder sb = new StringBuilder("""
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"><soap:Body>\
                <nfeResultMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">\
                <retEnviNFe versao="4.00"><tpAmb>2</tpAmb><verAplic>PR-v4_5_39</verAplic>""");
        sb.append("<cStat>").append(cStat).append("</cStat>");
        sb.append("<xMotivo>").append(xMotivo).append("</xMotivo>");
        if (nProt != null) {
            sb.append("<protNFe><infProt><chNFe>").append(chNFe).append("</chNFe>")
                    .append("<nProt>").append(nProt).append("</nProt></infProt></protNFe>");
        }
        sb.append("</retEnviNFe></nfeResultMsg></soap:Body></soap:Envelope>");
        return sb.toString();
    }

    private static void gerarCertificado(Path destino, String alias, String dn) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        List<String> cmd = new ArrayList<>(List.of(
                keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", dn,
                "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-keystore", destino.toString(), "-storetype", "PKCS12",
                "-storepass", SENHA, "-keypass", SENHA));
        executar(cmd);
    }

    private static Path exportarCert(Path keystore, String alias) throws Exception {
        Path cer = keystore.resolveSibling(alias + ".cer");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        executar(List.of(keytool, "-exportcert", "-alias", alias, "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", SENHA, "-file", cer.toString(), "-noprompt"));
        return cer;
    }

    private static void criarTruststore(Path destino, List<Path> certificados) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        int i = 0;
        for (Path cer : certificados) {
            executar(List.of(keytool, "-importcert", "-alias", "confiavel" + (i++),
                    "-file", cer.toString(), "-keystore", destino.toString(),
                    "-storetype", "PKCS12", "-storepass", SENHA, "-noprompt"));
        }
    }

    private static void executar(List<String> comando) throws Exception {
        Process p = new ProcessBuilder(comando).redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool falhou: " + saida);
        }
    }

    private static SSLContext contextoDe(Path keystore, Path truststore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, SENHA.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, SENHA.toCharArray());

        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(truststore)) {
            ts.load(in, SENHA.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }
}
