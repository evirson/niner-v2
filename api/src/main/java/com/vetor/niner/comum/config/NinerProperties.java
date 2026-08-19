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
        ArquivoCompartilhado arquivoCompartilhado, Seguranca seguranca, Fiscal fiscal,
        Cobranca cobranca) {

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
     * <p>Estes buckets são de <b>leitura pública</b> de propósito (marketplaces rebuscam a imagem
     * por URL). Tudo o que <b>não</b> pode ser público mora em {@link Privado}, outro provedor.
     */
    public record Storage(String bucket, String baseUrl, String host, Privado privado) {
    }

    /**
     * Object storage <b>privado</b> (ADR-014) — MinIO auto-hospedado, falado por API S3. Guarda o
     * que não pode ser público: <b>XML fiscal</b> (bucketFiscal, com WORM e retenção de 5 anos —
     * F6/DF21) e <b>dado pessoal</b> (bucketPrivado, apagável — LGPD). Endpoint e credencial são
     * a única coisa que muda para apontar num VPS dedicado, no Cloudflare R2 ou no S3 da AWS.
     *
     * <p>⚠️ <b>O certificado digital NÃO vai para cá</b> (DF21 revisada em 2026-08-17): o
     * {@code .pfx} fica cifrado no banco do cliente ({@code fiscal_certificado.arquivo_cifrado}),
     * o que o coloca no mesmo backup/restore do tenant e sob RLS, sem depender de política de
     * bucket.
     *
     * <p>{@code accessKey}/{@code secretKey} são do usuário de aplicação criado pelo
     * {@code infra/minio/bootstrap.sh} — <b>nunca</b> a conta root do MinIO: essa credencial não
     * tem permissão de apagar no bucket fiscal nem de burlar a retenção.
     */
    public record Privado(String endpoint, String accessKey, String secretKey, String regiao,
                          String bucketFiscal, String bucketPrivado) {
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
    public record Fiscal(String truststorePath, String truststoreSenha, RespTec respTec) {
    }

    /**
     * Grupo {@code infRespTec} (B7) — o responsável técnico pelo <b>software emissor</b>, exigido
     * em toda NFC-e/NF-e (MOC). É a Vetor/MITRYUSCASH, <b>nunca o tenant</b>: um valor só, igual
     * para todas as notas de todos os lojistas — por isso vive em configuração de aplicação, não
     * em {@code fiscal_config_empresa}.
     */
    public record RespTec(String cnpj, String contato, String email, String telefone) {
    }

    /** Cobrança da assinatura (ADR-016). {@code gateway} escolhe a implementação de
     *  {@code GatewayCobranca}; hoje só existe o Mercado Pago. */
    public record Cobranca(String gateway, MercadoPago mercadopago) {
    }

    /**
     * Credenciais e endereços do Mercado Pago. <b>Nada disso vai para o repositório</b> — em dev
     * usa-se o access token de teste (prefixo {@code TEST-}) das credenciais da aplicação; em
     * produção, secret manager.
     *
     * <p>{@code accessToken} vazio = cobrança <b>desligada</b>: a API sobe igual e só o endpoint
     * de pagamento responde 503. {@code webhookSecret} vazio deixa a notificação entrar sem
     * validar assinatura — aceitável só em dev, e mesmo assim inofensivo por construção: o
     * webhook não decide nada, quem aplica efeito é o worker <b>consultando o gateway</b>.
     *
     * <p>{@code notificationUrl} precisa ser um endereço público (em dev, um túnel) — sem ele o
     * Mercado Pago não tem para onde notificar e a confirmação depende só da consulta periódica.
     */
    public record MercadoPago(String baseUrl, String accessToken, String webhookSecret,
                              String notificationUrl, Duration validadePix) {
    }
}
