package com.vetor.niner.fiscal.nfse;

import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transporte com o Sefin Nacional e com o ADN — <b>HTTPS + mTLS, REST/JSON</b>.
 *
 * <h2>⚠️ Não é o {@code SefazTransporte}, e as diferenças são todas obrigatórias</h2>
 *
 * <table border="1">
 *   <caption>SEFAZ × Sefin Nacional</caption>
 *   <tr><th></th><th>{@code SefazTransporte} (NF-e)</th><th>esta classe (NFS-e)</th></tr>
 *   <tr><td>Protocolo</td><td>SOAP, XML no corpo</td><td>REST, <b>JSON</b> com o XML em gzip+base64</td></tr>
 *   <tr><td>Versão HTTP</td><td>padrão do JDK</td><td><b>HTTP/1.1 obrigatório</b> — HTTP/2 volta {@code RST_STREAM: Use HTTP/1.1}</td></tr>
 *   <tr><td>Truststore</td><td>ICP-Brasil própria ({@code niner.fiscal.truststore-path})</td><td><b>a padrão do JDK basta</b></td></tr>
 *   <tr><td>Autenticação</td><td>mTLS</td><td>mTLS, e <b>só</b> — sem header {@code Authorization}</td></tr>
 * </table>
 *
 * <p>⭐ <b>O que se copia do {@code SefazTransporte} é o cuidado que não pode falhar: um
 * {@code SSLContext} por CERTIFICADO</b>, com a impressão digital na chave do cache. Cachear sem
 * essa chave seria emitir nota com o certificado do lojista errado — o pior erro possível deste
 * módulo, e silencioso, porque a nota <b>seria autorizada</b>, só que no CNPJ de outra empresa.
 * Trocar o certificado da empresa invalida a entrada sozinho, sem expiração explícita.
 *
 * <p>⚠️ A raiz da ICP-Brasil <b>não</b> está no {@code cacerts} do JDK, e mesmo assim aqui não
 * precisamos de truststore própria: quem apresenta certificado é o servidor do governo (cadeias
 * SERPRO/GlobalSign em produção restrita, Sectigo em produção), e essas o JDK conhece. O
 * certificado ICP-Brasil é o <b>nosso</b>, do lado cliente — e ele vai no {@code KeyManager}, não
 * no {@code TrustManager}. Confundir os dois leva a caçar {@code PKIX path building failed} onde
 * não há problema nenhum.
 */
@Component
public class NfseTransporte {

    private static final Duration TIMEOUT_CONEXAO = Duration.ofSeconds(15);
    /** Emissão é síncrona: o SEFIN monta e assina a NFS-e antes de responder. */
    private static final Duration TIMEOUT_RESPOSTA = Duration.ofSeconds(60);

    private final Map<String, HttpClient> clientesPorCertificado = new ConcurrentHashMap<>();

    /** Resposta crua: quem interpreta é o {@link RespostaSefin}. */
    public record Retorno(int status, String corpo) {
    }

    /**
     * Falha de <b>comunicação</b> — a DPS não chegou a ser avaliada.
     *
     * <p>⚠️ É a distinção mais importante do módulo, e confundi-la custa caro nos dois sentidos:
     * tratar indisponibilidade como rejeição faz a nota sumir da fila com o prazo correndo;
     * tratar rejeição como indisponibilidade faz o sistema reenviar para sempre um erro permanente
     * (o {@code finance-v} chegou a 2.211 tentativas assim).
     */
    public static class FalhaDeComunicacaoException extends RuntimeException {
        public FalhaDeComunicacaoException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    public Retorno postJson(String url, String json, byte[] pkcs12, String senha,
                            String impressaoDigital) {
        return enviar(url, "POST", json, pkcs12, senha, impressaoDigital);
    }

    public Retorno get(String url, byte[] pkcs12, String senha, String impressaoDigital) {
        return enviar(url, "GET", null, pkcs12, senha, impressaoDigital);
    }

    private Retorno enviar(String url, String metodo, String json, byte[] pkcs12, String senha,
                           String impressaoDigital) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL do Sefin/ADN não informada");
        }
        HttpRequest.Builder requisicao = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(TIMEOUT_RESPOSTA);
        if (json == null) {
            requisicao.GET();
        } else {
            requisicao.header("Content-Type", "application/json; charset=utf-8")
                      .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        }

        try {
            HttpResponse<String> resposta = clientePara(pkcs12, senha, impressaoDigital)
                    .send(requisicao.build(),
                          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Retorno(resposta.statusCode(), resposta.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDeComunicacaoException("Envio à NFS-e interrompido", e);
        } catch (Exception e) {
            // Timeout e erro de rede caem aqui. ⚠️ Depois de um timeout a nota PODE ter sido
            // gerada do outro lado — quem resolve é a consulta por GET /dps/{id}, nunca reenviar
            // cego, que devolveria E0014 (DPS duplicada).
            throw new FalhaDeComunicacaoException(
                    "Não foi possível falar com a NFS-e Nacional: " + e.getMessage(), e);
        }
    }

    private HttpClient clientePara(byte[] pkcs12, String senha, String impressaoDigital) {
        if (impressaoDigital == null || impressaoDigital.isBlank()) {
            throw new IllegalArgumentException(
                    "Impressão digital do certificado é obrigatória: é ela que separa o cache de "
                    + "TLS por empresa.");
        }
        return clientesPorCertificado.computeIfAbsent(impressaoDigital, ignorado ->
                HttpClient.newBuilder()
                        // ⛔ HTTP/2 é recusado pelo SEFIN com RST_STREAM.
                        .version(HttpClient.Version.HTTP_1_1)
                        .sslContext(montarSslContext(pkcs12, senha))
                        .connectTimeout(TIMEOUT_CONEXAO)
                        .build());
    }

    private SSLContext montarSslContext(byte[] pkcs12, String senha) {
        try {
            char[] segredo = senha == null ? new char[0] : senha.toCharArray();
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(new ByteArrayInputStream(pkcs12), segredo);

            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keystore, segredo);

            SSLContext contexto = SSLContext.getInstance("TLS");
            // null no TrustManager = truststore padrão do JDK, que conhece as cadeias do SEFIN.
            contexto.init(kmf.getKeyManagers(), null, null);
            return contexto;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Falha ao preparar o certificado para o mTLS da NFS-e: " + e.getMessage(), e);
        }
    }

    /** Usado quando o certificado da empresa é trocado, para não servir o antigo do cache. */
    public void invalidar(String impressaoDigital) {
        if (impressaoDigital != null) {
            clientesPorCertificado.remove(impressaoDigital);
        }
    }
}
