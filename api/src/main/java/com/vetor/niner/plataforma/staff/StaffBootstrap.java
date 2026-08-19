package com.vetor.niner.plataforma.staff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Cria o <b>primeiro</b> {@code SUPER_ADMIN} do backoffice a partir de variáveis de ambiente.
 *
 * <p>Existe porque o backoffice agora exige autenticação e não há como entrar nele numa instalação
 * nova: seria preciso um {@code INSERT} manual com hash BCrypt gerado à mão, no meio do deploy —
 * o tipo de passo que termina em senha fraca ou em hash colado errado.
 *
 * <p>Três guardas, porque bootstrap de credencial é onde se criam backdoors sem querer:
 * <ol>
 *   <li>só roda quando a tabela está <b>vazia</b> — nunca recria, nunca redefine senha de quem já
 *       existe (perder a senha se resolve pelo banco, não por variável de ambiente);</li>
 *   <li>exige as duas variáveis preenchidas e a senha com pelo menos 12 caracteres;</li>
 *   <li>avisa em WARN quando não há staff nenhum e nada foi configurado — instalação sem staff é
 *       backoffice inacessível, e é melhor descobrir no log da subida do que na hora do suporte.</li>
 * </ol>
 */
@Component
public class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);
    private static final int TAMANHO_MINIMO_SENHA = 12;

    private final JdbcClient jdbc;
    private final PasswordEncoder senhas;
    private final String email;
    private final String senha;
    private final String nome;

    public StaffBootstrap(JdbcClient jdbc, PasswordEncoder senhas,
            @Value("${niner.staff-inicial.email:}") String email,
            @Value("${niner.staff-inicial.senha:}") String senha,
            @Value("${niner.staff-inicial.nome:Administrador Vetor}") String nome) {
        this.jdbc = jdbc;
        this.senhas = senhas;
        this.email = email;
        this.senha = senha;
        this.nome = nome;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean vazia = Boolean.TRUE.equals(
                jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM plataforma.staff)").query(Boolean.class).single());
        if (!vazia) {
            return;
        }
        if (email.isBlank() || senha.isBlank()) {
            log.warn("Nenhum staff cadastrado e NINER_STAFF_INICIAL_EMAIL/SENHA não definidos — "
                    + "o backoffice (/api/admin) fica inacessível até criar o primeiro SUPER_ADMIN.");
            return;
        }
        if (senha.length() < TAMANHO_MINIMO_SENHA) {
            log.error("NINER_STAFF_INICIAL_SENHA tem menos de {} caracteres — staff inicial NÃO criado.",
                    TAMANHO_MINIMO_SENHA);
            return;
        }
        jdbc.sql("""
                        INSERT INTO plataforma.staff (nome, email, senha_hash, papel)
                        VALUES (?, ?, ?, 'SUPER_ADMIN')
                        """)
                .params(nome, email, senhas.encode(senha))
                .update();
        log.info("Staff inicial criado: {} (SUPER_ADMIN). Troque a senha no primeiro acesso.", email);
    }
}
