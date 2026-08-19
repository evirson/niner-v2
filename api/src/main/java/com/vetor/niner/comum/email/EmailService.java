package com.vetor.niner.comum.email;

import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaDtos.Smtp;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaService;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Envio de e-mail da plataforma (recuperação de senha, avisos de cota e, mais adiante, NF-e ao
 * cliente do lojista).
 *
 * <p><b>O SMTP não vem do `application.yml`</b> — é editável pelo backoffice e vive cifrado em
 * {@code plataforma.configuracao_plataforma}. Por isso o {@code JavaMailSender} é montado a cada
 * envio, e não como bean: trocar o servidor de e-mail passa a ter efeito imediato, sem deploy.
 *
 * <p><b>Nunca lança para quem chamou.</b> Envio é efeito colateral: falha de SMTP não pode
 * derrubar cadastro, cobrança ou emissão de nota. O retorno diz se foi, o log diz por que não —
 * e quem chama decide se isso muda a resposta ao usuário (em geral, não muda).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ConfiguracaoPlataformaService configuracao;

    public EmailService(ConfiguracaoPlataformaService configuracao) {
        this.configuracao = configuracao;
    }

    /** {@code true} quando há SMTP configurado e ligado — a tela usa isso para avisar o staff. */
    public boolean disponivel() {
        Smtp smtp = configuracao.smtp();
        return smtp.habilitado() && smtp.host() != null && !smtp.host().isBlank()
                && smtp.remetenteEmail() != null && !smtp.remetenteEmail().isBlank();
    }

    /**
     * Envia um e-mail em HTML.
     *
     * @return {@code true} se saiu; {@code false} se o SMTP não está configurado ou o envio falhou
     */
    public boolean enviar(String destinatario, String assunto, String corpoHtml) {
        Smtp smtp = configuracao.smtp();
        if (!disponivel()) {
            log.warn("E-mail para {} não enviado: SMTP não configurado no backoffice. Assunto: {}",
                    destinatario, assunto);
            return false;
        }
        try {
            JavaMailSenderImpl sender = montar(smtp);
            MimeMessage mensagem = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensagem, false, StandardCharsets.UTF_8.name());
            helper.setFrom(smtp.remetenteEmail(), smtp.remetenteNome() == null ? "Nainer" : smtp.remetenteNome());
            helper.setTo(destinatario);
            helper.setSubject(assunto);
            helper.setText(corpoHtml, true);
            sender.send(mensagem);
            log.info("E-mail enviado para {} ({})", destinatario, assunto);
            return true;
        } catch (Exception e) {
            log.error("Falha ao enviar e-mail para {} ({}): {}", destinatario, assunto, e.getMessage());
            return false;
        }
    }

    private static JavaMailSenderImpl montar(Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.porta() == null ? 587 : smtp.porta());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (smtp.usuario() != null && !smtp.usuario().isBlank()) {
            sender.setUsername(smtp.usuario());
            sender.setPassword(smtp.senha());
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(smtp.usuario() != null && !smtp.usuario().isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(smtp.starttls()));
        // Porta 465 é SMTPS (TLS desde o handshake); 587 usa STARTTLS. Sem esta distinção, o
        // envio trava no timeout em vez de dar erro claro.
        if (smtp.porta() != null && smtp.porta() == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        return sender;
    }
}
