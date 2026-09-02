package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Log de acesso ao ERP (docs/MODULOLOGACESSO.md) — os critérios de aceitação do §7.
 *
 * <p>⚠️ As asserções olham o <b>banco</b>, não o status HTTP: um login que devolve 200 não prova
 * que a linha foi gravada, e é a linha que a auditoria vai ler daqui a dois anos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AcessoLogTest {

    private static final String UA_CELULAR =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/143.0.0.0 Mobile Safari/537.36";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder senhas;

    private String criarConta(String sufixo) throws Exception {
        return mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeLoja":"Loja Acesso %s","email":"dono-%s@acesso.com",
                                 "senha":"segredo123","nomeAdmin":"Dono"}
                                """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> ultimoAcessoDe(String email) {
        return jdbc.sql("""
                        SELECT resultado, id_tenant, id_usuario, id_empresa, email_informado,
                               so, navegador, dispositivo, user_agent, ip_confiavel
                          FROM plataforma.acesso_login
                         WHERE lower(email_informado) = lower(?)
                         ORDER BY id_acesso DESC LIMIT 1
                        """)
                .param(email)
                .query((rs, n) -> {
                    Map<String, Object> linha = new java.util.HashMap<>();
                    linha.put("resultado", rs.getString("resultado"));
                    linha.put("temTenant", rs.getObject("id_tenant") != null);
                    linha.put("temUsuario", rs.getObject("id_usuario") != null);
                    linha.put("temEmpresa", rs.getObject("id_empresa") != null);
                    linha.put("email", rs.getString("email_informado"));
                    linha.put("so", rs.getString("so"));
                    linha.put("navegador", rs.getString("navegador"));
                    linha.put("dispositivo", rs.getString("dispositivo"));
                    linha.put("userAgent", rs.getString("user_agent"));
                    return linha;
                })
                .optional()
                .orElse(Map.of());
    }

    /** Critério 1: login válido grava SUCESSO com quem, onde e com o quê. */
    @Test
    void loginGravaSucessoComUsuarioEmpresaEAparelho() throws Exception {
        criarConta("ok");

        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .header("User-Agent", UA_CELULAR)
                        .content("{\"email\":\"dono-ok@acesso.com\",\"senha\":\"segredo123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        var linha = ultimoAcessoDe("dono-ok@acesso.com");
        assertThat(linha).as("o login precisa deixar linha no log").isNotEmpty();
        assertThat(linha.get("resultado")).isEqualTo("SUCESSO");
        assertThat(linha.get("temTenant")).as("os ids vêm do token recém-emitido").isEqualTo(true);
        assertThat(linha.get("temUsuario")).isEqualTo(true);
        assertThat(linha.get("temEmpresa")).isEqualTo(true);
        // ⚠️ O bruto é a PROVA; os derivados são interpretação — os dois têm de estar lá.
        assertThat(linha.get("userAgent")).isEqualTo(UA_CELULAR);
        assertThat(linha.get("so")).isEqualTo("Android");
        assertThat(linha.get("navegador")).isEqualTo("Chrome");
        assertThat(linha.get("dispositivo")).isEqualTo("CELULAR");
    }

    /**
     * Critérios 2 e 3: senha errada e e-mail inexistente gravam o <b>mesmo</b>
     * {@code CREDENCIAL_INVALIDA}.
     *
     * <p>⚠️ É o teste que prende a decisão de <b>não distinguir</b>: o login responde a mesma coisa
     * nos dois casos para não virar oráculo de e-mails, e o log não pode ser mais específico que a
     * autenticação — senão recria o oráculo para quem tem o backoffice.
     */
    @Test
    void falhasGravamOMesmoMotivoSemDistinguirSenhaDeContaInexistente() throws Exception {
        criarConta("falha");

        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"dono-falha@acesso.com\",\"senha\":\"errada\"}"))
                .andExpect(status().isUnauthorized());

        var senhaErrada = ultimoAcessoDe("dono-falha@acesso.com");
        assertThat(senhaErrada.get("resultado")).isEqualTo("CREDENCIAL_INVALIDA");
        assertThat(senhaErrada.get("temUsuario"))
                .as("na falha não há usuário resolvido — só o e-mail digitado")
                .isEqualTo(false);

        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"ninguem@acesso.com\",\"senha\":\"segredo123\"}"))
                .andExpect(status().isUnauthorized());

        var inexistente = ultimoAcessoDe("ninguem@acesso.com");
        assertThat(inexistente.get("resultado"))
                .as("e-mail inexistente e senha errada são indistinguíveis, aqui também")
                .isEqualTo("CREDENCIAL_INVALIDA");
        assertThat(inexistente.get("email"))
                .as("o e-mail DIGITADO é gravado mesmo sem existir conta — é o que a auditoria lê")
                .isEqualTo("ninguem@acesso.com");
    }

    /**
     * ⛔ Critério 5, e o mais importante: o log é da VETOR. Nenhuma rota do ERP o alcança.
     *
     * <p>Decisão do dono do produto: <i>"se o funcionário pedir os logs para o patrão, o patrão vai
     * ter que pedir pra Vetor"</i>. A tabela mora em {@code plataforma} <b>sem RLS</b>, então quem
     * separa os dois mundos é a superfície — e este teste é o que impede alguém de abrir a porta
     * do lado errado sem perceber.
     */
    @Test
    void tokenDeLojistaNaoAlcancaOLogDeAcesso() throws Exception {
        String resp = criarConta("isolado");
        String tokenLojista = JsonPath.read(resp, "$.token");

        mvc.perform(get("/api/admin/acessos").header("Authorization", "Bearer " + tokenLojista))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/admin/acessos"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ⛔ A regra de verdade: <b>nenhum controller sob {@code /api/v1} lê {@code acesso_login}</b>.
     *
     * <p><b>Por que este guarda estático existe, e não bastava o teste acima</b> (medido em
     * 2026-09-01): sabotei o controller movendo-o para {@code /api/v1/acessos} e o teste de
     * superfície <b>continuou verde</b> — ele passava pelo motivo errado, porque
     * {@code /api/admin/acessos} simplesmente deixava de existir e devolvia 401 por ausência, não
     * por proteção. Um teste que passa com o defeito presente é pior que teste nenhum: ele
     * <b>defende</b> o defeito na próxima revisão.
     *
     * <p>⚠️ Ele varre o código-fonte, e <b>falha se não encontrar os fontes</b> em vez de passar
     * vazio — guarda que não acha nada e diz "tudo certo" é o mesmo problema com outra roupa.
     */
    @Test
    void nenhumEndpointDoErpLeOLogDeAcesso() throws Exception {
        java.nio.file.Path raiz = java.nio.file.Path.of("src/main/java");
        assertThat(java.nio.file.Files.isDirectory(raiz))
                .as("o guarda precisa dos fontes: sem eles ele não prova nada")
                .isTrue();

        java.util.List<String> ofensores = new java.util.ArrayList<>();
        try (var arquivos = java.nio.file.Files.walk(raiz)) {
            for (java.nio.file.Path p : arquivos.filter(f -> f.toString().endsWith(".java")).toList()) {
                String fonte = java.nio.file.Files.readString(p);
                if (fonte.contains("acesso_login") && fonte.contains("\"/api/v1")) {
                    ofensores.add(p.getFileName().toString());
                }
            }
        }

        assertThat(ofensores)
                .as("o log de acesso é da VETOR: se o funcionário pedir ao patrão, o patrão pede "
                        + "à Vetor. Um endpoint do ERP lendo esta tabela quebra isso em silêncio.")
                .isEmpty();
    }

    /** Critério 6: qualquer papel de staff lê (decisão do dono do produto), com filtros. */
    @Test
    void staffDeQualquerPapelLeOsAcessosComFiltros() throws Exception {
        criarConta("staff-le");
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .header("User-Agent", UA_CELULAR)
                        .content("{\"email\":\"dono-staff-le@acesso.com\",\"senha\":\"errada\"}"))
                .andExpect(status().isUnauthorized());

        jdbc.sql("""
                        INSERT INTO plataforma.staff (nome, email, senha_hash, papel, ativo)
                        VALUES ('Suporte', 'suporte-acessos@vetor.com.br', ?, 'SUPORTE'::plataforma.papel_staff, true)
                        ON CONFLICT (lower(email)) DO NOTHING
                        """)
                .param(senhas.encode("senha-de-teste-123"))
                .update();

        String login = mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"suporte-acessos@vetor.com.br","senha":"senha-de-teste-123"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenStaff = JsonPath.read(login, "$.token");

        // ⭐ O filtro que a auditoria usa de verdade: "mostre o que não deu certo".
        String pagina = mvc.perform(get("/api/admin/acessos?somenteFalhas=true&email=dono-staff-le")
                        .header("Authorization", "Bearer " + tokenStaff))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Integer>read(pagina, "$.total")).isPositive();
        assertThat(JsonPath.<String>read(pagina, "$.itens[0].resultado")).isEqualTo("CREDENCIAL_INVALIDA");
        assertThat(JsonPath.<String>read(pagina, "$.itens[0].userAgent"))
                .as("o User-Agent bruto vai para a tela: os derivados são interpretação")
                .isEqualTo(UA_CELULAR);
    }

    /**
     * ⚠️ O par do teste acima, e o que <b>faltava</b>: uma linha <b>com tenant</b>.
     *
     * <p>Achado ao abrir a tela pela primeira vez em 2026-09-02 (pendência 85): o controller fazia
     * {@code (Long) rs.getObject("id_tenant")} sobre uma coluna {@code smallint}, que o driver
     * devolve como {@code Integer} — {@code ClassCastException} em <b>toda linha de login bem
     * sucedido</b>, ou seja, a tela inteira quebrada. Os 7 testes passavam porque o único registro
     * que eles liam vinha de uma falha de credencial <b>sem slug</b>, que grava {@code id_tenant}
     * nulo: {@code (Long) null} não estoura, e o cast nunca era exercitado.
     *
     * <p>⭐ Por isso a asserção aqui é o {@code idTenant} e o {@code nomeConta} da linha — o JOIN
     * com {@code plataforma.tenant} só tem o que mostrar quando existe tenant, e é exatamente o
     * caso que ninguém media.
     */
    @Test
    void acessoBemSucedidoAparecePeloEndpointComContaEtenant() throws Exception {
        criarConta("com-tenant");
        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .header("User-Agent", UA_CELULAR)
                        .content("""
                                {"email":"dono-com-tenant@acesso.com","senha":"segredo123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        jdbc.sql("""
                        INSERT INTO plataforma.staff (nome, email, senha_hash, papel, ativo)
                        VALUES ('Suporte', 'suporte-com-tenant@vetor.com.br', ?,
                                'SUPORTE'::plataforma.papel_staff, true)
                        ON CONFLICT (lower(email)) DO NOTHING
                        """)
                .param(senhas.encode("senha-de-teste-123"))
                .update();

        String login = mvc.perform(post("/api/admin/sessao").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"suporte-com-tenant@vetor.com.br","senha":"senha-de-teste-123"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenStaff = JsonPath.read(login, "$.token");

        String pagina = mvc.perform(get("/api/admin/acessos?email=dono-com-tenant")
                        .header("Authorization", "Bearer " + tokenStaff))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(pagina, "$.itens[0].resultado")).isEqualTo("SUCESSO");
        assertThat(JsonPath.<Integer>read(pagina, "$.itens[0].idTenant"))
                .as("a linha de um login bem-sucedido carrega o tenant — era aqui que o cast estourava")
                .isNotNull();
        assertThat(JsonPath.<String>read(pagina, "$.itens[0].nomeConta"))
                .as("o JOIN com plataforma.tenant traz o nome da loja para a tela")
                .isNotBlank();
    }

    /**
     * ⛔ Critério 4: se o log falhar, o usuário <b>entra assim mesmo</b>.
     *
     * <p>Trilha de auditoria não pode impedir o lojista de trabalhar — e a sabotagem aqui é real:
     * a tabela é renomeada, o login roda, e depois ela volta. Sem isso, o teste provaria apenas
     * que o caminho feliz funciona.
     */
    @Test
    void logIndisponivelNaoImpedeOLogin() throws Exception {
        criarConta("sem-log");
        jdbc.sql("ALTER TABLE plataforma.acesso_login RENAME TO acesso_login_fora").update();
        try {
            mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                            .content("{\"email\":\"dono-sem-log@acesso.com\",\"senha\":\"segredo123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isNotEmpty());
        } finally {
            jdbc.sql("ALTER TABLE plataforma.acesso_login_fora RENAME TO acesso_login").update();
        }
    }

    /** Critério 7: o expurgo apaga o que passou da retenção — e só isso. */
    @Test
    void expurgoApagaSoOQuePassouDaRetencao() {
        jdbc.sql("""
                        INSERT INTO plataforma.acesso_login (email_informado, resultado, ocorrido_em)
                        VALUES ('velho@expurgo.com', 'SUCESSO', now() - interval '30 months'),
                               ('novo@expurgo.com',  'SUCESSO', now() - interval '2 months')
                        """).update();

        Integer apagados = jdbc.sql("SELECT plataforma.expurgar_acesso_login(24)")
                .query(Integer.class).single();
        assertThat(apagados).isPositive();

        assertThat(ultimoAcessoDe("velho@expurgo.com")).as("passou de 24 meses: sai").isEmpty();
        assertThat(ultimoAcessoDe("novo@expurgo.com")).as("dentro da retenção: fica").isNotEmpty();
    }
}
