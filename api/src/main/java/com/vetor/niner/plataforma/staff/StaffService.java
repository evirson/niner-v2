package com.vetor.niner.plataforma.staff;

import com.vetor.niner.comum.seguranca.TokenService;
import com.vetor.niner.plataforma.staff.StaffDtos.LoginStaffRequest;
import com.vetor.niner.plataforma.staff.StaffDtos.SessaoStaffResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
 */
@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);
    private static final String CREDENCIAL_INVALIDA = "Credenciais inválidas.";

    private final JdbcClient jdbc;
    private final PasswordEncoder senhas;
    private final TokenService tokens;

    public StaffService(JdbcClient jdbc, PasswordEncoder senhas, TokenService tokens) {
        this.jdbc = jdbc;
        this.senhas = senhas;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public SessaoStaffResponse login(LoginStaffRequest req) {
        var staff = jdbc.sql("""
                        SELECT id_staff, nome, email, senha_hash, ativo, papel::text AS papel
                          FROM plataforma.staff
                         WHERE lower(email) = lower(?)
                        """)
                .param(req.email())
                .query((rs, n) -> new StaffAuth(rs.getLong("id_staff"), rs.getString("nome"),
                        rs.getString("email"), rs.getString("senha_hash"), rs.getBoolean("ativo"),
                        rs.getString("papel")))
                .optional()
                .orElse(null);

        // Compara mesmo sem achar o e-mail: sem isso, a diferença de tempo entre "não existe" e
        // "senha errada" já denuncia quais contas existem.
        String hash = staff != null ? staff.senhaHash() : "$2a$10$invalidoinvalidoinvalidoinvalidoinvalidoinvalidoinvalido";
        boolean senhaConfere = senhas.matches(req.senha(), hash);

        if (staff == null || !staff.ativo() || !senhaConfere) {
            log.info("Tentativa de login de staff recusada para {}", req.email());
            throw new ResponseStatusException(UNAUTHORIZED, CREDENCIAL_INVALIDA);
        }

        String token = tokens.emitirStaff(staff.idStaff(), staff.email(), staff.papel());
        return new SessaoStaffResponse(token, staff.nome(), staff.email(), staff.papel());
    }

    private record StaffAuth(long idStaff, String nome, String email, String senhaHash, boolean ativo, String papel) {
    }
}
