package com.vetor.niner.plataforma.staff;

import com.vetor.niner.comum.seguranca.TokenService;
import com.vetor.niner.plataforma.staff.StaffDtos.LoginStaffRequest;
import com.vetor.niner.plataforma.staff.StaffDtos.SessaoStaffResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Autenticação do staff da Vetor (R18/ADR-009).
 *
 * <p>Mesma disciplina do login do lojista: senha só em hash BCrypt, e a resposta a e-mail
 * inexistente é <b>idêntica</b> à de senha errada — mensagem diferente entrega quais e-mails de
 * staff existem, que é meio caminho para um ataque direcionado.
 *
 * <p>Sessão curta (2h, ver {@code TokenService}) porque este token enxerga dado de todos os
 * tenants pelo backoffice.
 *
 * <h2>⚠️ Por que nada aqui é transacional (pendência #40, 2026-09-01)</h2>
 * O método era {@code @Transactional(readOnly = true)}. Somar a tentativa errada dentro dessa
 * transação seria escrever num contador que a <b>própria exceção</b> que informa o erro desfaz no
 * rollback — o teto ficaria escrito na tela e ausente no banco. É literalmente o defeito que o
 * login em duas etapas teve em 2026-08-27, medido lá: cinco erros deixavam {@code tentativas = 0}.
 * As tabelas de {@code plataforma} não têm RLS (P9), cada operação aqui é um comando só, e nenhuma
 * precisa de transação.
 *
 * <h2>⚠️ O hash de mentira tinha 63 caracteres (medido em 2026-09-01)</h2>
 * A comparação contra um hash inventado existe para dar tempo constante quando o e-mail não
 * existe. Só que o literal anterior tinha <b>63</b> caracteres onde o BCrypt exige <b>60</b>
 * (7 do prefixo + 22 de salt + 31 de hash): o {@code matches} recusava o formato e <b>retornava
 * sem calcular nada</b>. Medido contra a API rodando, antes da correção: e-mail existente
 * <b>~50 ms</b>, inexistente <b>~6 ms</b> — 8x de diferença, enumeração de staff medível pela
 * internet com uma requisição e sem custo nenhum.
 *
 * <p>⭐ Por isso o hash de mentira agora é <b>produzido pelo próprio encoder</b>
 * ({@code senhas.encode(aleatório)}) em vez de digitado: um literal pode nascer malformado de novo
 * e ninguém percebe — nada falha, a defesa é que some. O que o encoder gera, o encoder aceita, por
 * construção. O segredo é aleatório a cada subida e nunca sai desta classe, então nenhuma senha
 * casa com ele.
 *
 * <h2>⚠️ O teto de tentativas e o oráculo que sobra (declarado, não escondido)</h2>
 * Cinco falhas consecutivas bloqueiam a conta por 15 minutos, com <b>429</b>. Isso é um oráculo de
 * existência: e-mail inexistente nunca bloqueia. A alternativa — uma tabela chaveada pelo e-mail
 * <i>digitado</i>, cobrindo também o inexistente — fecharia o oráculo e abriria crescimento sem
 * teto por e-mail inventado. A troca é deliberada: <b>o oráculo que sobra custa 5 tentativas sob o
 * limite de IP; o oráculo de tempo que ele substitui custava 1 requisição e era grátis.</b>
 *
 * <p>⚠️ E o teto tem um custo próprio, que precisa ser dito: quem souber o e-mail do staff mantém a
 * conta trancada repetindo 5 erros a cada 15 minutos. O remédio para isso não é código — é o
 * <b>allowlist de IP do backoffice</b>, que já está escrito e comentado no nginx e é decisão do
 * dono do produto (pendência #40).
 */
@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);
    private static final String CREDENCIAL_INVALIDA = "Credenciais inválidas.";
    private static final int MAX_TENTATIVAS = 5;
    private static final int MINUTOS_DE_BLOQUEIO = 15;

    private final JdbcClient jdbc;
    private final PasswordEncoder senhas;
    private final TokenService tokens;

    /**
     * Hash bem-formado que nenhuma senha casa, gerado pelo próprio encoder na subida.
     * Ver o javadoc da classe: o literal anterior tinha 63 caracteres e desligava a defesa.
     */
    private final String hashDeMentira;

    public StaffService(JdbcClient jdbc, PasswordEncoder senhas, TokenService tokens) {
        this.jdbc = jdbc;
        this.senhas = senhas;
        this.tokens = tokens;
        this.hashDeMentira = gerarHashDeMentira(senhas);
    }

    /**
     * Produz o hash de mentira pelo próprio encoder.
     *
     * <p>Método separado e público <b>de propósito</b>: é o que o teste consegue afirmar
     * sem depender de relógio. O defeito original não era "lento demais", era um hash com tamanho
     * inválido que fazia o BCrypt <b>nem rodar</b> — e isso se prende com uma asserção de formato
     * ({@code length() == 60}), não com uma medida de tempo, que seria frágil
     * (ver {@code feedback_testes_frageis_por_relogio}).
     */
    public static String gerarHashDeMentira(PasswordEncoder senhas) {
        byte[] segredo = new byte[32];
        new SecureRandom().nextBytes(segredo);
        return senhas.encode(HexFormat.of().formatHex(segredo));
    }

    public SessaoStaffResponse login(LoginStaffRequest req) {
        var staff = jdbc.sql("""
                        SELECT id_staff, nome, email, senha_hash, ativo, papel::text AS papel,
                               tentativas_login, bloqueado_ate
                          FROM plataforma.staff
                         WHERE lower(email) = lower(?)
                        """)
                .param(req.email())
                .query((rs, n) -> new StaffAuth(rs.getLong("id_staff"), rs.getString("nome"),
                        rs.getString("email"), rs.getString("senha_hash"), rs.getBoolean("ativo"),
                        rs.getString("papel"), rs.getInt("tentativas_login"),
                        rs.getObject("bloqueado_ate", OffsetDateTime.class)))
                .optional()
                .orElse(null);

        if (staff != null && staff.bloqueadoAte() != null
                && staff.bloqueadoAte().isAfter(OffsetDateTime.now())) {
            log.warn("Login de staff recusado por bloqueio ({} ate {})", req.email(), staff.bloqueadoAte());
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Muitas tentativas nesta conta. Tente novamente daqui a " + MINUTOS_DE_BLOQUEIO + " minutos.");
        }

        // Compara mesmo sem achar o e-mail: sem isso, a diferença de tempo entre "não existe" e
        // "senha errada" já denuncia quais contas existem. O hash de mentira precisa ser
        // BEM-FORMADO para que o BCrypt seja de fato executado — ver o javadoc da classe.
        String hash = staff != null ? staff.senhaHash() : hashDeMentira;
        boolean senhaConfere = senhas.matches(req.senha(), hash);

        if (staff == null || !staff.ativo() || !senhaConfere) {
            if (staff != null) {
                registrarFalha(staff);
            }
            log.info("Tentativa de login de staff recusada para {}", req.email());
            throw new ResponseStatusException(UNAUTHORIZED, CREDENCIAL_INVALIDA);
        }

        // Entrou: o contador zera. É o ÚNICO caminho que zera — qualquer outra porta que o
        // limpasse tornaria o teto decorativo (ver feedback_teto_com_porta_ao_lado).
        if (staff.tentativas() > 0 || staff.bloqueadoAte() != null) {
            jdbc.sql("UPDATE plataforma.staff SET tentativas_login = 0, bloqueado_ate = NULL WHERE id_staff = ?")
                    .param(staff.idStaff())
                    .update();
        }

        String token = tokens.emitirStaff(staff.idStaff(), staff.email(), staff.papel());
        return new SessaoStaffResponse(token, staff.nome(), staff.email(), staff.papel());
    }

    /**
     * Soma a tentativa e liga o bloqueio ao chegar no teto.
     *
     * <p>⚠️ O incremento e a decisão de bloquear acontecem <b>no mesmo UPDATE</b>, no banco. Ler o
     * contador em Java, somar 1 e gravar deixaria a janela clássica: cinco requisições simultâneas
     * leem 0 e gravam 1, e o teto nunca chega.
     */
    private void registrarFalha(StaffAuth staff) {
        jdbc.sql("""
                        UPDATE plataforma.staff
                           SET tentativas_login = tentativas_login + 1,
                               bloqueado_ate = CASE WHEN tentativas_login + 1 >= ?
                                                    THEN now() + make_interval(mins => ?)
                                                    ELSE bloqueado_ate END
                         WHERE id_staff = ?
                        """)
                .params(MAX_TENTATIVAS, MINUTOS_DE_BLOQUEIO, staff.idStaff())
                .update();
    }

    private record StaffAuth(long idStaff, String nome, String email, String senhaHash, boolean ativo,
            String papel, int tentativas, OffsetDateTime bloqueadoAte) {
    }
}
