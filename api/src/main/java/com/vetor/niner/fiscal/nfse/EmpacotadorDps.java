package com.vetor.niner.fiscal.nfse;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * gzip + Base64 — o formato em que o XML viaja dentro do JSON do Sefin Nacional.
 *
 * <pre>
 *   emissão:      {"dpsXmlGZipB64": "&lt;base64(gzip(XML assinado))&gt;"}
 *   evento:       {"pedidoRegistroEventoXmlGZipB64": "…"}
 *   resposta:     {"nfseXmlGZipB64": "…"}  /  {"eventoXmlGZipB64": "…"}
 * </pre>
 *
 * <p>⚠️ <b>Ordem crítica: assinar primeiro, empacotar depois.</b> O gzip altera os bytes do XML;
 * empacotar antes de assinar quebra o digest da assinatura e o SEFIN devolve {@code E0714}.
 *
 * <p>⚠️ <b>O campo da resposta muda de nome entre emissão e evento</b> ({@code nfseXmlGZipB64} ×
 * {@code eventoXmlGZipB64}), então quem lê a resposta procura os dois — não é detalhe de estilo:
 * procurar só um faz o XML do cancelamento, que é a prova fiscal do evento, ser descartado em
 * silêncio.
 */
@Component
public class EmpacotadorDps {

    /** XML → gzip → Base64. */
    public String empacotar(String xml) {
        try {
            ByteArrayOutputStream comprimido = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(comprimido)) {
                gzip.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(comprimido.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Falha ao compactar o XML da NFS-e: " + e.getMessage(), e);
        }
    }

    /**
     * Base64 → gunzip → XML. É por aqui que o XML autorizado (a prova fiscal de 5 anos) sai da
     * resposta do SEFIN para o MinIO.
     */
    public String desempacotar(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // ⚠️ E1226 do SEFIN ("estrutura descompactada mal formada") é o espelho deste erro do
            // lado deles. Se aparecer aqui, é a resposta que veio corrompida, não o nosso envio.
            throw new IllegalStateException(
                    "Falha ao descompactar XML devolvido pelo SEFIN: " + e.getMessage(), e);
        }
    }
}
