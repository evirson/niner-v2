package com.vetor.niner.plataforma.configuracao;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/** DTOs da configuração da plataforma (backoffice). */
public final class ConfiguracaoPlataformaDtos {

    private ConfiguracaoPlataformaDtos() {
    }

    /**
     * O que a tela recebe. Segredo nunca aparece — só o {@code ...Definido}, que é o suficiente
     * para a tela mostrar "senha configurada" e oferecer "trocar".
     */
    public record ConfiguracaoResponse(
            boolean smtpHabilitado, String smtpHost, Integer smtpPorta, String smtpUsuario,
            boolean smtpSenhaDefinida, boolean smtpStarttls, String smtpRemetenteEmail, String smtpRemetenteNome,
            boolean backupHabilitado, LocalTime backupHora, int backupRetencaoDias,
            OffsetDateTime backupUltimoEm, String backupUltimoStatus, String backupUltimoDetalhe,
            boolean mpAccessTokenDefinido, boolean mpWebhookSecretDefinido, String mpNotificationUrl,
            OffsetDateTime atualizadoEm) {
    }

    /**
     * Gravação. Os três campos de segredo são opcionais: {@code null} ou vazio = <b>manter</b> o
     * que está gravado. Para limpar de fato, a tela envia a palavra {@code LIMPAR} — explícito,
     * porque apagar credencial por engano derruba cobrança ou e-mail sem aviso.
     */
    public record AtualizarConfiguracaoRequest(
            boolean smtpHabilitado,
            @Size(max = 200) String smtpHost,
            @Min(1) @Max(65535) Integer smtpPorta,
            @Size(max = 200) String smtpUsuario,
            @Size(max = 400) String smtpSenha,
            boolean smtpStarttls,
            @Email @Size(max = 200) String smtpRemetenteEmail,
            @Size(max = 120) String smtpRemetenteNome,
            boolean backupHabilitado,
            LocalTime backupHora,
            @Min(1) @Max(3650) Integer backupRetencaoDias,
            @Size(max = 400) String mpAccessToken,
            @Size(max = 400) String mpWebhookSecret,
            @Size(max = 300) String mpNotificationUrl) {
    }

    /** Configuração de envio já decifrada — uso interno (serviço de e-mail), nunca serializada. */
    public record Smtp(boolean habilitado, String host, Integer porta, String usuario, String senha,
                       boolean starttls, String remetenteEmail, String remetenteNome) {
    }

    /** Credenciais do gateway já decifradas — uso interno (adapter), nunca serializadas. */
    public record CredenciaisGateway(String accessToken, String webhookSecret, String notificationUrl) {
    }
}
