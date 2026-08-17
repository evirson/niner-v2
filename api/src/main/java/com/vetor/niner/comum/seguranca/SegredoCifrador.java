package com.vetor.niner.comum.seguranca;

import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cifra/decifra segredos de terceiro em repouso (F7) com AES-256-GCM — hoje só a senha do
 * certificado fiscal ({@code fiscal_certificado.senha_cifrada}), mas o util é genérico. A
 * chave mestra vem de {@code niner.seguranca.chave-segredos} (env {@code NINER_CHAVE_SEGREDOS}),
 * <b>fora do banco</b> de propósito — quem rouba só o banco não decifra nada.
 *
 * <p>Formato de saída: base64 de {@code nonce (12 bytes) || texto cifrado || tag (16 bytes)} —
 * um valor único por chamada, nunca reaproveita nonce (GCM com nonce repetido quebra a
 * confidencialidade). {@code decifrar} é a operação inversa exata.
 */
@Component
public class SegredoCifrador {

    private static final String TRANSFORMACAO = "AES/GCM/NoPadding";
    private static final int TAMANHO_TAG_BITS = 128;
    private static final int TAMANHO_NONCE_BYTES = 12;

    private final SecretKeySpec chave;

    public SegredoCifrador(NinerProperties props) {
        byte[] bytesChave = Base64.getDecoder().decode(props.seguranca().chaveSegredos());
        if (bytesChave.length != 32) {
            throw new IllegalStateException(
                    "niner.seguranca.chave-segredos precisa decodificar para 32 bytes (AES-256); "
                            + "recebeu " + bytesChave.length + ".");
        }
        this.chave = new SecretKeySpec(bytesChave, "AES");
    }

    public String cifrar(String textoClaro) {
        return Base64.getEncoder().encodeToString(cifrarBytes(textoClaro.getBytes(StandardCharsets.UTF_8)));
    }

    public String decifrar(String valorCifrado) {
        return new String(decifrarBytes(Base64.getDecoder().decode(valorCifrado)), StandardCharsets.UTF_8);
    }

    /**
     * Cifra conteúdo binário — usado pelo <b>arquivo do certificado digital</b> (`.pfx`), que
     * desde 2026-08-17 fica no banco do cliente e não em bucket.
     *
     * <p>⚠️ <b>Por que cifrar um `.pfx`, que já é um container protegido por senha:</b> o PKCS12
     * é cifrado com uma senha escolhida pelo lojista ou pela AC — quase sempre curta e sujeita a
     * força bruta <b>offline</b>, sem nenhum limite de tentativa, por quem tiver o arquivo. Um
     * dump do banco entregaria exatamente isso. Cifrando com a chave mestra (que vive fora do
     * banco), o dump sozinho não abre nem o arquivo nem a senha.
     */
    public byte[] cifrarBytes(byte[] claro) {
        try {
            byte[] nonce = new byte[TAMANHO_NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMACAO);
            cipher.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(TAMANHO_TAG_BITS, nonce));
            byte[] cifrado = cipher.doFinal(claro);

            return ByteBuffer.allocate(nonce.length + cifrado.length).put(nonce).put(cifrado).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar segredo.", e);
        }
    }

    public byte[] decifrarBytes(byte[] cifradoComNonce) {
        try {
            byte[] nonce = Arrays.copyOfRange(cifradoComNonce, 0, TAMANHO_NONCE_BYTES);
            byte[] cifrado = Arrays.copyOfRange(cifradoComNonce, TAMANHO_NONCE_BYTES, cifradoComNonce.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMACAO);
            cipher.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(TAMANHO_TAG_BITS, nonce));
            return cipher.doFinal(cifrado);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao decifrar segredo — chave incorreta ou dado corrompido.", e);
        }
    }
}
