package com.vetor.niner;

import com.vetor.niner.plataforma.staff.StaffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teto de tentativas no login do backoffice (pendência #40, V107).
 *
 * <p>O login do staff era o único ponto de autenticação do produto <b>sem teto de tentativas</b>, e
 * é a credencial mais valiosa que existe aqui: um token {@code aud=plataforma} alcança a lista de
 * contas, os leads (LGPD), o CSRT por UF e a configuração da plataforma.
 *
 * <p><b>⭐ O teste que mais importa é o NEGATIVO</b> ({@link #quatroErrosNaoBloqueiam}). Uma suíte
 * só de casos positivos aprovaria alegremente um teto que trancasse todo mundo — foi exatamente o
 * que quase aconteceu na revogação de sessão (2026-08-27), em que os três testes positivos
 * passaram de primeira e o defeito real (todo usuário recém-criado nascia com a sessão revogada)
 * só apareceu no caso negativo.
 *
 * <p><b>⚠️ Cada teste usa uma conta própria.</b> Trancar uma conta compartilhada faria este arquivo
 * derrubar os outros que fazem login de staff ({@code BackupTest}, {@code CsrtPorUfTest},
 * {@code TenantAdminTest}, {@code ConfiguracaoPlataformaTest}) — e a falha apareceria noutro
 * arquivo, mandando o diagnóstico para o lado errado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginStaffTetoDeTentativasTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder senhas;

    private static final String SENHA = "senha-de-teste-123";

    private void criarStaff(String email) {
        jdbc.sql("""
                        INSERT INTO plataforma.staff (nome, email, senha_hash, papel, ativo)
                        VALUES (?, ?, ?, 'SUPORTE'::plataforma.papel_staff, true)
                        ON CONFLICT (lower(email))
                        DO UPDATE SET senha_hash = excluded.senha_hash,
                                      tentativas_login = 0, bloqueado_ate = NULL
                        """)
                .params("Staff Teto", email, senhas.encode(SENHA))
                .update();
    }

    private void tentar(String email, String senha, int esperado) throws Exception {
        mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, senha)))
                .andExpect(status().is(esperado));
    }

    private int tentativasNoBanco(String email) {
        return jdbc.sql("SELECT tentativas_login FROM plataforma.staff WHERE lower(email) = lower(?)")
                .param(email).query(Integer.class).single();
    }

    /**
     * Cinco erros trancam, e o sexto é recusado <b>mesmo com a senha certa</b>.
     *
     * <p>⚠️ A asserção decisiva é a última: se o teto só recusasse senha errada, ele não seria um
     * teto — seria uma mensagem. E a contagem é conferida <b>no banco</b>, não pelo status HTTP:
     * um teste que valida só o 401 passa com o contador zerado pelo rollback, que é exatamente o
     * defeito que o 2FA teve em 2026-08-27.
     */
    @Test
    void cincoErrosBloqueiamAContaMesmoComASenhaCerta() throws Exception {
        String email = "teto-bloqueia@vetor.com.br";
        criarStaff(email);

        for (int i = 1; i <= 5; i++) {
            tentar(email, "errada" + i, 401);
            assertThat(tentativasNoBanco(email))
                    .as("a tentativa %d precisa SOBREVIVER à exceção que informa o erro", i)
                    .isEqualTo(i);
        }

        tentar(email, SENHA, 429);

        assertThat(jdbc.sql("SELECT bloqueado_ate FROM plataforma.staff WHERE lower(email) = lower(?)")
                .param(email).query(java.time.OffsetDateTime.class).single())
                .as("o bloqueio precisa estar gravado, não apenas respondido")
                .isAfter(java.time.OffsetDateTime.now());
    }

    /**
     * ⭐ O caso NEGATIVO: quatro erros não trancam, e o acerto zera o contador.
     *
     * <p>Sem ele, um teto quebrado que recusasse todo mundo passaria pelos testes positivos.
     */
    @Test
    void quatroErrosNaoBloqueiam() throws Exception {
        String email = "teto-nao-bloqueia@vetor.com.br";
        criarStaff(email);

        for (int i = 1; i <= 4; i++) {
            tentar(email, "errada" + i, 401);
        }
        assertThat(tentativasNoBanco(email)).isEqualTo(4);

        tentar(email, SENHA, 200);

        assertThat(tentativasNoBanco(email))
                .as("entrar é o ÚNICO caminho que zera o contador")
                .isZero();
    }

    /** Conta nova, sem nenhuma tentativa, entra na primeira — o teto não pode nascer ligado. */
    @Test
    void contaNovaEntraNaPrimeiraTentativa() throws Exception {
        String email = "teto-conta-nova@vetor.com.br";
        criarStaff(email);
        tentar(email, SENHA, 200);
    }

    /**
     * O hash de mentira precisa ser <b>bem-formado</b>, senão o BCrypt nem roda.
     *
     * <p>Era o defeito medido em 2026-09-01: o literal tinha <b>63</b> caracteres onde o BCrypt
     * exige <b>60</b> (7 do prefixo + 22 de salt + 31 de hash), o {@code matches} recusava o
     * formato e retornava sem calcular — e-mail existente respondia em ~50 ms e inexistente em
     * ~6 ms, uma enumeração de staff de uma requisição só.
     *
     * <p>⚠️ Este teste afirma <b>formato</b>, não tempo, de propósito: asserção de relógio é
     * frágil e falharia por carga da máquina, não por regressão.
     */
    @Test
    void hashDeMentiraEhAceitoPeloProprioEncoder() {
        String mentira = StaffService.gerarHashDeMentira(senhas);

        assertThat(mentira).as("BCrypt tem 60 caracteres — 63 desliga a comparação").hasSize(60);
        assertThat(mentira).startsWith("$2");
        assertThat(senhas.matches("qualquer-senha", mentira))
                .as("nenhuma senha pode casar com o hash de mentira")
                .isFalse();
    }
}
