package com.vetor.niner.plataforma.configuracao;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.seguranca.SegredoCifrador;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaDtos.AtualizarConfiguracaoRequest;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaDtos.ConfiguracaoResponse;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaDtos.CredenciaisGateway;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaDtos.Smtp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.OffsetDateTime;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Configuração da plataforma (SMTP, backup, gateway) — leitura/gravação pelo backoffice e leitura
 * interna pelos serviços que dependem dela.
 *
 * <p><b>Segredo entra e não sai.</b> {@link #consultar} devolve apenas se cada segredo está
 * definido; o valor em claro só existe dentro de {@link #smtp()} e {@link #credenciaisGateway()},
 * que são consumidos por serviço, nunca por controller.
 *
 * <p><b>Só SUPER_ADMIN grava.</b> SUPORTE e FINANCEIRO enxergam o funil e os leads, mas não podem
 * trocar para onde vão os e-mails da plataforma nem qual conta recebe o dinheiro.
 */
@Service
public class ConfiguracaoPlataformaService {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoPlataformaService.class);
    /** Sentinela explícita para apagar um segredo — em branco significa "manter". */
    private static final String LIMPAR = "LIMPAR";

    private final JdbcClient jdbc;
    private final SegredoCifrador cifrador;
    private final NinerProperties props;

    public ConfiguracaoPlataformaService(JdbcClient jdbc, SegredoCifrador cifrador, NinerProperties props) {
        this.jdbc = jdbc;
        this.cifrador = cifrador;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public ConfiguracaoResponse consultar(Jwt jwt) {
        exigirStaff(jwt);
        return jdbc.sql("""
                        SELECT smtp_habilitado, smtp_host, smtp_porta, smtp_usuario,
                               smtp_senha_cifrada IS NOT NULL AS smtp_senha_definida,
                               smtp_starttls, smtp_remetente_email, smtp_remetente_nome,
                               backup_habilitado, backup_hora, backup_retencao_dias,
                               backup_ultimo_em, backup_ultimo_status, backup_ultimo_detalhe,
                               mp_access_token_cifrado IS NOT NULL AS mp_token_definido,
                               mp_webhook_secret_cifrado IS NOT NULL AS mp_webhook_definido,
                               mp_notification_url, atualizado_em
                          FROM plataforma.configuracao_plataforma WHERE id = 1
                        """)
                .query((rs, n) -> new ConfiguracaoResponse(
                        rs.getBoolean("smtp_habilitado"), rs.getString("smtp_host"),
                        (Integer) rs.getObject("smtp_porta"), rs.getString("smtp_usuario"),
                        rs.getBoolean("smtp_senha_definida"), rs.getBoolean("smtp_starttls"),
                        rs.getString("smtp_remetente_email"), rs.getString("smtp_remetente_nome"),
                        rs.getBoolean("backup_habilitado"), rs.getObject("backup_hora", LocalTime.class),
                        rs.getInt("backup_retencao_dias"), rs.getObject("backup_ultimo_em", OffsetDateTime.class),
                        rs.getString("backup_ultimo_status"), rs.getString("backup_ultimo_detalhe"),
                        rs.getBoolean("mp_token_definido"), rs.getBoolean("mp_webhook_definido"),
                        rs.getString("mp_notification_url"), rs.getObject("atualizado_em", OffsetDateTime.class)))
                .single();
    }

    @Transactional
    public ConfiguracaoResponse atualizar(Jwt jwt, AtualizarConfiguracaoRequest req) {
        exigirSuperAdmin(jwt);

        jdbc.sql("""
                        UPDATE plataforma.configuracao_plataforma SET
                            smtp_habilitado = ?, smtp_host = ?, smtp_porta = ?, smtp_usuario = ?,
                            -- Parâmetro comparado com NULL/'' precisa de CAST explícito: sem ele o
                            -- Postgres não infere o tipo e recusa o comando inteiro.
                            smtp_senha_cifrada = CASE
                                WHEN CAST(? AS text) IS NULL THEN smtp_senha_cifrada  -- em branco: mantém
                                WHEN CAST(? AS text) = ''    THEN NULL                -- LIMPAR: apaga
                                ELSE CAST(? AS text) END,
                            smtp_starttls = ?, smtp_remetente_email = ?,
                            smtp_remetente_nome = COALESCE(CAST(? AS text), smtp_remetente_nome),
                            backup_habilitado = ?,
                            backup_hora = COALESCE(CAST(? AS time), backup_hora),
                            backup_retencao_dias = COALESCE(CAST(? AS smallint), backup_retencao_dias),
                            mp_access_token_cifrado = CASE
                                WHEN CAST(? AS text) IS NULL THEN mp_access_token_cifrado
                                WHEN CAST(? AS text) = ''    THEN NULL
                                ELSE CAST(? AS text) END,
                            mp_webhook_secret_cifrado = CASE
                                WHEN CAST(? AS text) IS NULL THEN mp_webhook_secret_cifrado
                                WHEN CAST(? AS text) = ''    THEN NULL
                                ELSE CAST(? AS text) END,
                            mp_notification_url = ?,
                            atualizado_em = now(),
                            atualizado_por = ?
                        WHERE id = 1
                        """)
                .params(req.smtpHabilitado(), vazioParaNulo(req.smtpHost()), req.smtpPorta(),
                        vazioParaNulo(req.smtpUsuario()),
                        cifrarOuMarcador(req.smtpSenha()), cifrarOuMarcador(req.smtpSenha()),
                        cifrarOuMarcador(req.smtpSenha()),
                        req.smtpStarttls(), vazioParaNulo(req.smtpRemetenteEmail()),
                        vazioParaNulo(req.smtpRemetenteNome()),
                        req.backupHabilitado(), req.backupHora(), req.backupRetencaoDias(),
                        cifrarOuMarcador(req.mpAccessToken()), cifrarOuMarcador(req.mpAccessToken()),
                        cifrarOuMarcador(req.mpAccessToken()),
                        cifrarOuMarcador(req.mpWebhookSecret()), cifrarOuMarcador(req.mpWebhookSecret()),
                        cifrarOuMarcador(req.mpWebhookSecret()),
                        vazioParaNulo(req.mpNotificationUrl()), Long.parseLong(jwt.getSubject()))
                .update();

        log.info("Configuração da plataforma atualizada pelo staff {}", jwt.getSubject());
        return consultar(jwt);
    }

    /** Configuração de envio já decifrada. Uso interno — nunca exposta por controller. */
    @Transactional(readOnly = true)
    public Smtp smtp() {
        return jdbc.sql("""
                        SELECT smtp_habilitado, smtp_host, smtp_porta, smtp_usuario, smtp_senha_cifrada,
                               smtp_starttls, smtp_remetente_email, smtp_remetente_nome
                          FROM plataforma.configuracao_plataforma WHERE id = 1
                        """)
                .query((rs, n) -> new Smtp(
                        rs.getBoolean("smtp_habilitado"), rs.getString("smtp_host"),
                        (Integer) rs.getObject("smtp_porta"), rs.getString("smtp_usuario"),
                        decifrar(rs.getString("smtp_senha_cifrada")), rs.getBoolean("smtp_starttls"),
                        rs.getString("smtp_remetente_email"), rs.getString("smtp_remetente_nome")))
                .single();
    }

    /**
     * Credenciais do gateway. <b>O banco vence o ambiente quando preenchido</b> — foi para isso
     * que a configuração saiu do arquivo. Vazio cai no {@code application.yml}/env, que é o
     * caminho de dev, CI e da primeira subida (antes de existir staff para configurar).
     */
    @Transactional(readOnly = true)
    public CredenciaisGateway credenciaisGateway() {
        var doBanco = jdbc.sql("""
                        SELECT mp_access_token_cifrado, mp_webhook_secret_cifrado, mp_notification_url
                          FROM plataforma.configuracao_plataforma WHERE id = 1
                        """)
                .query((rs, n) -> new CredenciaisGateway(
                        decifrar(rs.getString("mp_access_token_cifrado")),
                        decifrar(rs.getString("mp_webhook_secret_cifrado")),
                        rs.getString("mp_notification_url")))
                .single();

        var doAmbiente = props.cobranca().mercadopago();
        return new CredenciaisGateway(
                preferir(doBanco.accessToken(), doAmbiente.accessToken()),
                preferir(doBanco.webhookSecret(), doAmbiente.webhookSecret()),
                preferir(doBanco.notificationUrl(), doAmbiente.notificationUrl()));
    }

    private static String preferir(String banco, String ambiente) {
        return banco != null && !banco.isBlank() ? banco : ambiente;
    }

    /**
     * {@code null} quando o campo veio em branco (mantém o atual); string vazia quando veio o
     * marcador {@code LIMPAR} (apaga); o valor cifrado caso contrário.
     */
    private String cifrarOuMarcador(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return LIMPAR.equals(valor.trim()) ? "" : cifrador.cifrar(valor.trim());
    }

    private String decifrar(String cifrado) {
        if (cifrado == null || cifrado.isBlank()) {
            return null;
        }
        try {
            return cifrador.decifrar(cifrado);
        } catch (RuntimeException e) {
            // Chave mestra trocada sem re-cifrar o que estava gravado. Falhar em silêncio aqui
            // faria e-mail/cobrança pararem sem explicação — o log é o que salva o diagnóstico.
            log.error("Não foi possível decifrar um segredo da configuração da plataforma "
                    + "(a chave mestra mudou?): {}", e.getMessage());
            return null;
        }
    }

    private static String vazioParaNulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    private static void exigirStaff(Jwt jwt) {
        if (jwt.getClaimAsString("papel") == null) {
            throw new ResponseStatusException(FORBIDDEN, "Somente o staff da plataforma acessa esta configuração.");
        }
    }

    private static void exigirSuperAdmin(Jwt jwt) {
        if (!"SUPER_ADMIN".equals(jwt.getClaimAsString("papel"))) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas SUPER_ADMIN altera a configuração da plataforma.");
        }
    }
}
