package com.vetor.niner.fiscal.sefaz;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.fiscal.sefaz.SefazDtos.FalhaDeComunicacaoException;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transporte com o webservice da SEFAZ — POST de XML sobre HTTPS com <b>mTLS</b> (bloco B6).
 *
 * <p><b>Sem Axis2, sem lib de NF-e</b>: o {@code HttpClient} do JDK faz mTLS nativamente, com
 * controle de timeout e pool. É o item 2 da DF7, validado no B0 contra a SEFAZ-PR real
 * ({@code cStat 107 Serviço em Operação}).
 *
 * <h2>O cuidado que não pode falhar: um SSLContext por EMPRESA</h2>
 *
 * <p>Cada requisição usa o certificado <b>do lojista</b>, não o da Vetor. Cachear um
 * {@code SSLContext} sem chave de tenant seria emitir nota com o certificado do lojista errado —
 * o pior erro possível deste módulo, e silencioso, porque a nota <b>seria autorizada</b>, só que
 * no CNPJ de outra empresa. É o {@code TenantContext} (P8) valendo para um recurso que não é
 * linha de banco.
 *
 * <p>A chave do cache inclui a <b>impressão digital do certificado</b>: trocar o certificado da
 * empresa invalida a entrada sozinho, sem precisar de expiração explícita nem de alguém lembrar
 * de limpar o cache.
 *
 * <h2>⚠️ Truststore ICP-Brasil</h2>
 *
 * <p>A raiz da ICP-Brasil <b>não está</b> no {@code cacerts} do JDK. Sem truststore própria, toda
 * chamada morre com {@code PKIX path building failed} — mensagem que não menciona ICP-Brasil
 * nenhuma e faz perder horas suspeitando do certificado do lojista (achado do B0). O caminho vem
 * de {@code niner.fiscal.truststore-path}; vazio = truststore padrão do JDK.
 */
@Component
public class SefazTransporte {

    private static final Duration TIMEOUT_CONEXAO = Duration.ofSeconds(10);
    /** DF18: 10 s antes de cair em contingência. Vale para a resposta, não só para o handshake. */
    private static final Duration TIMEOUT_RESPOSTA = Duration.ofSeconds(10);

    private static final Pattern TAG = Pattern.compile("<(?:\\w+:)?%s>(.*?)</(?:\\w+:)?%s>");

    private final NinerProperties props;
    private final Map<String, HttpClient> clientesPorCertificado = new ConcurrentHashMap<>();

    public SefazTransporte(NinerProperties props) {
        this.props = props;
    }

    /**
     * Envia o XML já assinado para o serviço da SEFAZ.
     *
     * @param url            endpoint vindo de {@code cfg_uf_autorizador} (F10), nunca hard-coded
     * @param servicoWsdl    nome do serviço no namespace do {@code nfeDadosMsg} (ex.:
     *                       {@code NFeAutorizacao4})
     * @param xmlPayload     conteúdo do {@code nfeDadosMsg} (ex.: o {@code enviNFe} completo)
     * @param pkcs12         certificado A1 <b>do lojista</b>, decifrado
     * @param senha          senha do certificado
     * @param impressaoDigital identifica o certificado no cache — trocar o certificado troca a chave
     */
    public RespostaSefaz enviar(String url, String servicoWsdl, String xmlPayload,
                                byte[] pkcs12, String senha, String impressaoDigital) {
        if (url == null || url.isBlank()) {
            throw new FalhaDeComunicacaoException(
                    "A UF não tem endpoint cadastrado para este serviço (cfg_uf_autorizador). "
                            + "Sem isso não há para onde transmitir.", null);
        }

        String soap = envelopar(servicoWsdl, xmlPayload);
        HttpClient http = clientePara(pkcs12, senha, impressaoDigital);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/soap+xml; charset=utf-8")
                .timeout(TIMEOUT_RESPOSTA)
                .POST(HttpRequest.BodyPublishers.ofString(soap, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FalhaDeComunicacaoException(
                    "Não foi possível falar com a SEFAZ (%s): %s".formatted(url, e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDeComunicacaoException("Envio para a SEFAZ interrompido.", e);
        }

        String corpo = resp.body();
        return new RespostaSefaz(
                resp.statusCode(),
                extrair(corpo, "cStat"),
                extrair(corpo, "xMotivo"),
                extrair(corpo, "nProt"),
                extrair(corpo, "chNFe"),
                corpo);
    }

    /** Envelope SOAP 1.2 — montado à mão, aceito pela SEFAZ-PR no B0 ({@code verAplic PR-v4_5_39}). */
    private static String envelopar(String servicoWsdl, String xmlPayload) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>\
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"><soap:Body>\
                <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/%s">%s</nfeDadosMsg>\
                </soap:Body></soap:Envelope>""".formatted(servicoWsdl, xmlPayload);
    }

    /**
     * Um {@link HttpClient} por certificado. A chave é a impressão digital — <b>nunca</b> um
     * cliente único compartilhado, que usaria o certificado de quem chamou primeiro para todas as
     * empresas (ver javadoc da classe).
     */
    private HttpClient clientePara(byte[] pkcs12, String senha, String impressaoDigital) {
        if (impressaoDigital == null || impressaoDigital.isBlank()) {
            throw new IllegalArgumentException(
                    "Impressão digital do certificado é obrigatória: é ela que separa o cache de TLS por empresa.");
        }
        return clientesPorCertificado.computeIfAbsent(impressaoDigital, ignorado ->
                HttpClient.newBuilder()
                        .sslContext(montarSslContext(pkcs12, senha))
                        .connectTimeout(TIMEOUT_CONEXAO)
                        .build());
    }

    private SSLContext montarSslContext(byte[] pkcs12, String senha) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pkcs12), senha.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, senha.toCharArray());

            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(kmf.getKeyManagers(), trustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new FalhaDeComunicacaoException(
                    "Não foi possível preparar a conexão segura com o certificado da empresa: " + e.getMessage(), e);
        }
    }

    /**
     * {@code null} = truststore padrão do JDK. Em produção contra a SEFAZ isso <b>não basta</b>:
     * a raiz ICP-Brasil não vem no {@code cacerts}, e a falha aparece como
     * {@code PKIX path building failed}.
     */
    private javax.net.ssl.TrustManager[] trustManagers() throws Exception {
        String caminho = props.fiscal() == null ? null : props.fiscal().truststorePath();
        if (caminho == null || caminho.isBlank()) {
            return null;
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(Path.of(caminho))) {
            trust.load(in, senhaTruststore());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        return tmf.getTrustManagers();
    }

    private char[] senhaTruststore() {
        String senha = props.fiscal() == null ? null : props.fiscal().truststoreSenha();
        return senha == null ? new char[0] : senha.toCharArray();
    }

    /**
     * Extrai o valor de uma tag da resposta, ignorando prefixo de namespace. A resposta da SEFAZ
     * vem embrulhada em SOAP e com prefixos que variam por UF — parsear por DOM aqui obrigaria a
     * lidar com essa variação sem ganho, já que só se quer quatro campos escalares. O XML cru é
     * guardado inteiro de qualquer forma (F9).
     */
    private static String extrair(String xml, String tag) {
        if (xml == null) {
            return null;
        }
        Matcher m = Pattern.compile(TAG.pattern().formatted(tag, tag), Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }
}
