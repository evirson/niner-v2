package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.comum.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Login em duas etapas: código de 4 dígitos enviado por e-mail (V079).
 *
 * <p>Pedido do dono do produto (2026-08-27): opção no cadastro do usuário; ligada, depois da senha
 * o sistema manda um código que vale <b>10 minutos</b>, com botão de reenviar.
 *
 * <p><b>O que sustenta a segurança aqui não é o tamanho do código.</b> Quatro dígitos são 10.000
 * combinações — um script acerta em minutos se puder tentar à vontade. Quem segura são os três
 * limites juntos: <b>5 tentativas</b>, <b>10 minutos</b> e <b>3 reenvios</b>. Aumentar o código
 * para 6 dígitos sem esses limites seria pior do que 4 dígitos com eles.
 *
 * <p><b>⚠️ Nenhum método aqui é transacional, e isso é deliberado.</b> Se a conferência rodasse
 * dentro de uma transação, o {@code UPDATE} que soma a tentativa errada seria <b>desfeito pelo
 * rollback</b> da exceção que informa o erro ao usuário — o contador nunca sairia de zero e as
 * 10.000 combinações poderiam ser tentadas à vontade, com o teto de 5 tentativas parecendo estar
 * lá. A tabela não tem RLS (é do plano de controle), cada operação é um comando só, e o consumo do
 * código é um {@code UPDATE} condicional: nada aqui precisa de transação, e uma delas quebraria a
 * única defesa que existe.
 *
 * <p><b>O desafio é opaco.</b> A primeira etapa devolve um `id_desafio` (UUID) e a segunda manda
 * só ele mais o código — a senha não trafega de novo, e o identificador não revela nada sobre a
 * conta.
 */
@Service
public class CodigoLoginService {

    private static final Logger log = LoggerFactory.getLogger(CodigoLoginService.class);
    private static final Duration VALIDADE = Duration.ofMinutes(10);
    private static final int MAX_TENTATIVAS = 5;
    private static final int MAX_REENVIOS = 3;
    private static final int MAX_DESAFIOS_POR_HORA = 10;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final JdbcClient jdbc;
    private final EmailService email;

    public CodigoLoginService(JdbcClient jdbc, EmailService email) {
        this.jdbc = jdbc;
        this.email = email;
    }

    /**
     * Cria o desafio e manda o código. Devolve o {@code id_desafio} que a segunda etapa usa.
     *
     * <p>⚠️ Chamado <b>depois</b> de a senha ser conferida e de a conta/empresa estarem resolvidas
     * — é o último passo antes de emitir o token. Pedir o código antes da senha entregaria a
     * qualquer um a informação de que aquele e-mail existe, e ainda mandaria e-mail à toa.
     */
    public UUID criarDesafio(long idTenant, long idUsuario, long idEmpresa, String nome, String emailDestino,
            String ip) {
        // ⚠️ Teto de DESAFIOS, não só de tentativas dentro de um desafio. Sem ele, quem já tem a
        // senha (que é exatamente o caso que a segunda etapa existe para cobrir) refaz o login
        // quantas vezes quiser e ganha 5 palpites novos a cada vez — o teto por desafio não
        // significaria nada. Cada desafio manda um e-mail ao dono da conta, então o limite também
        // impede transformar o login numa metralhadora de e-mail contra o usuário.
        int recentes = jdbc.sql("""
                        SELECT count(*) FROM plataforma.codigo_login
                        WHERE id_tenant = ? AND id_usuario = ? AND criado_em > now() - interval '1 hour'
                        """)
                .params(idTenant, idUsuario)
                .query(Integer.class).single();
        if (recentes >= MAX_DESAFIOS_POR_HORA) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Muitos pedidos de código para esta conta. Tente novamente daqui a pouco.");
        }

        String codigo = gerarCodigo();
        UUID desafio = jdbc.sql("""
                        INSERT INTO plataforma.codigo_login
                            (id_tenant, id_usuario, id_empresa, codigo_hash, expira_em, ip_solicitante)
                        VALUES (?, ?, ?, ?, now() + make_interval(mins => ?), ?)
                        RETURNING id_desafio
                        """)
                .params(idTenant, idUsuario, idEmpresa, hash(codigo), (int) VALIDADE.toMinutes(), ip)
                .query(UUID.class).single();

        enviar(nome, emailDestino, codigo);
        return desafio;
    }

    /**
     * Confere o código e devolve o desafio consumido, pronto para o token ser emitido.
     *
     * <p>⚠️ O consumo é <b>um comando só</b> (`UPDATE ... WHERE usado_em IS NULL ... RETURNING`):
     * ler e depois marcar deixaria uma janela em que o mesmo código serviria duas vezes.
     */
    public DesafioConsumido conferir(UUID idDesafio, String codigo) {
        Desafio d = buscar(idDesafio);
        if (d == null || d.usado()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Código inválido ou expirado. Peça um novo código.");
        }
        if (d.tentativas() >= MAX_TENTATIVAS) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Muitas tentativas erradas. Peça um novo código para continuar.");
        }

        var consumido = jdbc.sql("""
                        UPDATE plataforma.codigo_login
                           SET usado_em = now()
                         WHERE id_desafio = ? AND usado_em IS NULL AND expira_em > now()
                               AND codigo_hash = ? AND tentativas < ?
                        RETURNING id_tenant, id_usuario, id_empresa
                        """)
                .params(idDesafio, hash(codigo), MAX_TENTATIVAS)
                .query((rs, n) -> new DesafioConsumido(rs.getLong("id_tenant"), rs.getLong("id_usuario"),
                        rs.getLong("id_empresa")))
                .optional().orElse(null);

        if (consumido == null) {
            // Errou (ou expirou): a tentativa é contada mesmo quando o código está errado — é a
            // contagem que fecha a porta da força bruta.
            jdbc.sql("UPDATE plataforma.codigo_login SET tentativas = tentativas + 1 WHERE id_desafio = ?")
                    .param(idDesafio).update();
            throw new ResponseStatusException(UNAUTHORIZED, "Código inválido ou expirado. Confira o e-mail e tente de novo.");
        }
        return consumido;
    }

    /**
     * Reenvia — gerando um código NOVO e invalidando o anterior.
     *
     * <p>⚠️ Reenviar o mesmo código pareceria mais amigável e seria pior: o código antigo continua
     * na caixa de entrada, e dois e-mails com o mesmo número fazem a pessoa usar o que chegou
     * primeiro, que pode já estar expirado. Um código novo por reenvio deixa claro qual vale: o
     * último.
     */
    public void reenviar(UUID idDesafio, String nome, String emailDestino) {
        Desafio d = buscar(idDesafio);
        if (d == null || d.usado()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Não há código pendente. Faça o login novamente.");
        }
        if (d.reenvios() >= MAX_REENVIOS || d.tentativas() >= MAX_TENTATIVAS) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Você já pediu o código várias vezes. Faça o login novamente daqui a pouco.");
        }

        String codigo = gerarCodigo();
        // ⚠️ `tentativas` NÃO volta a zero. Zerando, o teto de 5 vira 20 por desafio (5 × 3
        // reenvios) — e o reenvio é pedido pelo próprio atacante, não custa nada a ele. Quem errou
        // cinco vezes de verdade recomeça o login; é o preço de um código de 4 dígitos.
        jdbc.sql("""
                        UPDATE plataforma.codigo_login
                           SET codigo_hash = ?, expira_em = now() + make_interval(mins => ?),
                               reenvios = reenvios + 1
                         WHERE id_desafio = ? AND usado_em IS NULL
                        """)
                .params(hash(codigo), (int) VALIDADE.toMinutes(), idDesafio)
                .update();
        enviar(nome, emailDestino, codigo);
    }

    /** Dados do desafio para a segunda etapa saber para quem reenviar. */
    public Desafio buscar(UUID idDesafio) {
        return jdbc.sql("""
                        SELECT id_desafio, id_tenant, id_usuario, id_empresa, tentativas, reenvios,
                               (usado_em IS NOT NULL) AS usado, (expira_em <= now()) AS expirado
                          FROM plataforma.codigo_login WHERE id_desafio = ?
                        """)
                .param(idDesafio)
                .query((rs, n) -> new Desafio(
                        rs.getObject("id_desafio", UUID.class), rs.getLong("id_tenant"), rs.getLong("id_usuario"),
                        rs.getLong("id_empresa"), rs.getInt("tentativas"), rs.getInt("reenvios"),
                        rs.getBoolean("usado"), rs.getBoolean("expirado")))
                .optional().orElse(null);
    }

    private void enviar(String nome, String emailDestino, String codigo) {
        boolean foi = email.enviar(emailDestino, "Seu código de acesso ao Nainer", """
                <p>Olá, %s.</p>
                <p>Seu código de acesso é:</p>
                <p style="font-size:32px; font-weight:700; letter-spacing:6px; margin:12px 0">%s</p>
                <p>Ele vale por <b>%d minutos</b> e só pode ser usado uma vez.</p>
                <p style="color:#5c6660;font-size:13px">Se não foi você que tentou entrar, ignore este e-mail e
                troque a sua senha — alguém acertou ela.</p>
                """.formatted(nome, codigo, VALIDADE.toMinutes()));
        if (!foi) {
            // ⚠️ Não é detalhe: sem e-mail o usuário fica TRANCADO FORA, porque a senha sozinha já
            // não basta. Quem chamou precisa saber para poder avisar em vez de deixar a tela
            // esperando um código que nunca chega.
            log.error("Código de login não pôde ser enviado para {} — o usuário não conseguirá entrar.", emailDestino);
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível enviar o código de acesso agora. Tente novamente em instantes.");
        }
    }

    /** 4 dígitos, incluindo os que começam com zero — "0042" é código válido. */
    private static String gerarCodigo() {
        return String.format("%04d", ALEATORIO.nextInt(10_000));
    }

    private static String hash(String codigo) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(codigo.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    public record Desafio(UUID id, long idTenant, long idUsuario, long idEmpresa, int tentativas, int reenvios,
            boolean usado, boolean expirado) {
    }

    public record DesafioConsumido(long idTenant, long idUsuario, long idEmpresa) {
    }
}
