package com.vetor.niner.comum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuração da aplicação (prefixo {@code niner}): assinatura de JWT, parâmetros do
 * trial, CORS e object storage. Valores em {@code application.yml}; segredo do JWT via
 * secret manager em prod.
 */
@ConfigurationProperties("niner")
public record NinerProperties(
        Jwt jwt, Trial trial, Cors cors, Storage storage,
        ArquivoCompartilhado arquivoCompartilhado, Seguranca seguranca, Fiscal fiscal) {

    public record Jwt(String secret, Duration expiracao, String emissor) {
    }

    public record Trial(int dias, String plano) {
    }

    /** Origens permitidas dos fronts (site/web/admin) para CORS. */
    public record Cors(List<String> origins) {
    }

    /** Bucket/base-url do object storage das fotos de produto (ADR-013). {@code host} vazio
     * = GCS real (ADC/chave); preenchido (ex.: {@code http://localhost:4443}) = emulador
     * fake-gcs-server, sem credencial — modo dev, ver docs/infra/armazenamento-imagens.md §3.
     *
     * <p>{@code bucketFiscal} (DF21) é um bucket <b>separado e privado</b>, para os <b>XML
     * autorizados</b> — nunca o mesmo {@code bucket} de fotos, que é de leitura pública de
     * propósito (marketplaces rebuscam a imagem por URL). Usa o mesmo {@code host}/credencial,
     * só o nome muda.
     *
     * <p>⚠️ <b>O certificado digital NÃO vai para cá</b> (DF21 revisada em 2026-08-17): o
     * {@code .pfx} fica cifrado no banco do cliente
     * ({@code fiscal_certificado.arquivo_cifrado}). Este bucket é só do XML, que tem obrigação
     * legal de guarda por 5 anos e precisa de versionamento/retenção — requisitos que o banco
     * não dá de graça e o bucket dá. */
    public record Storage(String bucket, String baseUrl, String host, String bucketFiscal) {
    }

    /** Cache temporário em memória de {@code comum.arquivocompartilhado} (ex.: comprovante
     * compartilhado por link do WhatsApp) — nunca vai pro banco nem pro object storage, de
     * propósito (custo zero, sem bucket novo); some sozinho após {@code expiracaoHoras}. */
    public record ArquivoCompartilhado(int expiracaoHoras) {
    }

    /** Chave mestra (AES-256, base64 de 32 bytes) para cifrar segredos de terceiro em repouso
     * — hoje só a senha do certificado fiscal ({@code fiscal_certificado.senha_cifrada}, F7),
     * mas o util é genérico ({@code SegredoCifrador}) para qualquer segredo futuro. A chave
     * fica <b>fora do banco</b> de propósito: quem rouba só o banco não decifra nada. */
    public record Seguranca(String chaveSegredos) {
    }

    /**
     * Módulo fiscal — transporte com a SEFAZ (B6).
     *
     * <p>⚠️ {@code truststorePath} é <b>requisito de ambiente, não opcional em produção</b>: a
     * raiz da ICP-Brasil não vem no {@code cacerts} do JDK, e sem ela toda chamada à SEFAZ falha
     * com {@code PKIX path building failed} — mensagem que não menciona ICP-Brasil e faz perder
     * horas suspeitando do certificado do lojista (achado do B0). Vazio = truststore padrão do
     * JDK, que serve para teste local mas não para a SEFAZ real.
     */
    public record Fiscal(String truststorePath, String truststoreSenha) {
    }
}
