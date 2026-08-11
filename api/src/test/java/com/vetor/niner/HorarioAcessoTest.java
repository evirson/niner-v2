package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Horário de acesso por dia da semana (docs/telas/usuario.md, 2026-08-14) — login (sem
 * tolerância) e filtro por requisição ({@code HorarioAcessoFilter}, 15 min de tolerância). Toda
 * janela é gravada com deltas relativos a {@code now()} do PRÓPRIO SERVIDOR Postgres (nunca o
 * relógio da JVM de teste) — a mesma exigência de "horário sempre do servidor" do recurso.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HorarioAcessoTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, long idTenant, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Horario %s","email":"dono%s@lojahorario.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TenantNovo(JsonPath.read(resp, "$.token"),
                ((Number) JsonPath.read(resp, "$.idTenant")).longValue(), JsonPath.read(resp, "$.slug"));
    }

    private long buscarPrimeiraEmpresa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$[0].idEmpresa")).longValue();
    }

    /** OPERADOR com o controle LIGADO e os 7 dias em branco (sem acesso nenhum ainda) — cada
     *  teste grava a janela de hoje por cima com {@link #definirJanelaHoje}. */
    private long criarOperadorComControle(String token, long idEmpresa, String email) throws Exception {
        StringBuilder horarios = new StringBuilder();
        for (int dia = 1; dia <= 7; dia++) {
            if (dia > 1) horarios.append(",");
            horarios.append("{\"diaSemana\":").append(dia).append(",\"horaInicio\":null,\"horaFim\":null}");
        }
        String body = """
                {"nome":"Operador Horario","email":"%s","senha":"senha1234","idsEmpresa":[%d],
                 "controlaHorarioAcesso":true,"horarios":[%s]}
                """.formatted(email, idEmpresa, horarios);
        String resp = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idUsuario")).longValue();
    }

    /** Sobrescreve hora_inicio/hora_fim do dia de HOJE (calculado pelo próprio banco via
     *  EXTRACT(ISODOW FROM now())) com deltas em minutos relativos a now() — dispensa esperar o
     *  relógio de verdade passar do horário pra testar a borda da janela. */
    private void definirJanelaHoje(long idTenant, long idUsuario, int deltaInicioMin, int deltaFimMin) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            st.executeUpdate("""
                    UPDATE usuario_horario_acesso
                    SET hora_inicio = (now() + interval '%d minutes')::time,
                        hora_fim    = (now() + interval '%d minutes')::time
                    WHERE id_tenant = %d AND id_usuario = %d AND dia_semana = EXTRACT(ISODOW FROM now())
                    """.formatted(deltaInicioMin, deltaFimMin, idTenant, idUsuario));
        }
    }

    private String logar(String slug, String email) throws Exception {
        String resp = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("{\"slug\":\"%s\",\"email\":\"%s\",\"senha\":\"senha1234\"}".formatted(slug, email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void operadorDentroDaJanelaConsegueLogarETrabalhar() throws Exception {
        TenantNovo tenant = assinarNovoTenant("dentro");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        long idOperador = criarOperadorComControle(tenant.token(), idEmpresa, "dentro@lojahorario.com");
        definirJanelaHoje(tenant.idTenant(), idOperador, -60, 60);

        String tokenOperador = logar(tenant.slug(), "dentro@lojahorario.com");
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk());
    }

    @Test
    void operadorForaDaJanelaNaoConsegueLogar() throws Exception {
        TenantNovo tenant = assinarNovoTenant("fora-login");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        long idOperador = criarOperadorComControle(tenant.token(), idEmpresa, "fora-login@lojahorario.com");
        definirJanelaHoje(tenant.idTenant(), idOperador, -120, -60);

        mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("{\"slug\":\"%s\",\"email\":\"fora-login@lojahorario.com\",\"senha\":\"senha1234\"}"
                                .formatted(tenant.slug())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Fora do horário de acesso permitido."));
    }

    @Test
    void chamadaAutenticadaDentroDaToleranciaDeQuinzeMinutosAindaFunciona() throws Exception {
        TenantNovo tenant = assinarNovoTenant("tolerancia-ok");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        long idOperador = criarOperadorComControle(tenant.token(), idEmpresa, "tolerancia-ok@lojahorario.com");

        // Loga com a janela ainda válida (termina em 5 min)...
        definirJanelaHoje(tenant.idTenant(), idOperador, -60, 5);
        String tokenOperador = logar(tenant.slug(), "tolerancia-ok@lojahorario.com");

        // ...simula o relógio ter passado: agora o horário terminou há 5 min — dentro dos 15 de tolerância.
        definirJanelaHoje(tenant.idTenant(), idOperador, -60, -5);
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk());
    }

    @Test
    void chamadaAutenticadaAposAToleranciaEhBloqueada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("tolerancia-estourada");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        long idOperador = criarOperadorComControle(tenant.token(), idEmpresa, "tolerancia-estourada@lojahorario.com");

        definirJanelaHoje(tenant.idTenant(), idOperador, -60, 5);
        String tokenOperador = logar(tenant.slug(), "tolerancia-estourada@lojahorario.com");

        // Horário terminou há 20 min — além dos 15 de tolerância do filtro.
        definirJanelaHoje(tenant.idTenant(), idOperador, -80, -20);
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Fora do horário de acesso permitido."));
    }

    @Test
    void euExpoeSegundosRestantesDeToleranciaSoNaJanelaDeGraca() throws Exception {
        TenantNovo tenant = assinarNovoTenant("segundos-restantes");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());
        long idOperador = criarOperadorComControle(tenant.token(), idEmpresa, "segundos-restantes@lojahorario.com");

        // Dentro do horário normal (termina em 30 min) — ainda não é "aviso", tolerância não começou.
        definirJanelaHoje(tenant.idTenant(), idOperador, -60, 30);
        String tokenOperador = logar(tenant.slug(), "segundos-restantes@lojahorario.com");
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segundosRestantesTolerancia").doesNotExist());

        // Horário acabou de terminar (fim = agora) — tolerância cheia de 15 min = 900s, com folga
        // pro tempo de execução do teste.
        definirJanelaHoje(tenant.idTenant(), idOperador, -60, 0);
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segundosRestantesTolerancia").value(org.hamcrest.Matchers.lessThanOrEqualTo(900)))
                .andExpect(jsonPath("$.segundosRestantesTolerancia").value(org.hamcrest.Matchers.greaterThan(850)));
    }

    @Test
    void administradorNuncaEhBloqueadoMesmoComControleLigadoForaDaJanela() throws Exception {
        TenantNovo tenant = assinarNovoTenant("admin-imune");
        // Só pra provar que a regra realmente checa `administrador`, e não confia em a tela
        // nunca deixar isso acontecer: liga o controle e grava uma janela já encerrada há muito
        // tempo direto no ADMIN (a tela de usuário nunca faz isso, mas o filtro/login não podem
        // depender só disso pra proteger o admin).
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + tenant.idTenant());
            st.executeUpdate("UPDATE usuario SET controla_horario_acesso = true "
                    + "WHERE id_tenant = " + tenant.idTenant() + " AND administrador = true");
            st.executeUpdate("""
                    INSERT INTO usuario_horario_acesso (id_tenant, id_usuario, dia_semana, hora_inicio, hora_fim)
                    SELECT id_tenant, id_usuario, EXTRACT(ISODOW FROM now()),
                           (now() - interval '3 hours')::time, (now() - interval '2 hours')::time
                    FROM usuario WHERE id_tenant = %d AND administrador = true
                    """.formatted(tenant.idTenant()));
        }

        // Login do admin já foi feito em assinarNovoTenant — o token dele continua válido e
        // funcionando, mesmo fora da janela que acabamos de forçar.
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());
    }

    @Test
    void usuarioComControleDesligadoNuncaEhBloqueado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("controle-desligado");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        String body = """
                {"nome":"Sem Controle","email":"semcontrole@lojahorario.com","senha":"senha1234",
                 "idsEmpresa":[%d],"controlaHorarioAcesso":false,"horarios":[]}
                """.formatted(idEmpresa);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        String tokenOperador = logar(tenant.slug(), "semcontrole@lojahorario.com");
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk());
    }

    @Test
    void controleLigadoSemTodosOsSeteDiasEhRejeitado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("dias-incompletos");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        String body = """
                {"nome":"Dias Incompletos","email":"diasincompletos@lojahorario.com","senha":"senha1234",
                 "idsEmpresa":[%d],"controlaHorarioAcesso":true,
                 "horarios":[{"diaSemana":1,"horaInicio":"09:00:00","horaFim":"19:00:00"}]}
                """.formatted(idEmpresa);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void horaFimAntesDaHoraInicioEhRejeitada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("hora-invertida");
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        StringBuilder horarios = new StringBuilder();
        for (int dia = 1; dia <= 7; dia++) {
            if (dia > 1) horarios.append(",");
            horarios.append(dia == 1
                    ? "{\"diaSemana\":1,\"horaInicio\":\"19:00:00\",\"horaFim\":\"09:00:00\"}"
                    : "{\"diaSemana\":" + dia + ",\"horaInicio\":null,\"horaFim\":null}");
        }
        String body = """
                {"nome":"Hora Invertida","email":"horainvertida@lojahorario.com","senha":"senha1234",
                 "idsEmpresa":[%d],"controlaHorarioAcesso":true,"horarios":[%s]}
                """.formatted(idEmpresa, horarios);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Regressão (2026-08-14, bug real achado na verificação manual): quando o fim do
     * expediente cai nos últimos {@code toleranciaMinutos} do dia (ex.: fecha 23:50, tolerância
     * de 15 min), somar a tolerância direto num valor {@code time} "embrulha" na virada da
     * meia-noite (23:50 + 15min = 00:05 — limite superior MENOR que o inferior, quebrando o
     * BETWEEN). A correção soma a tolerância no domínio de TIMESTAMP ({@code current_date +
     * hora}), que não embrulha. Este teste exercita literalmente a mesma expressão usada em
     * {@code HorarioAcessoService.podeAcessarAgora}, com timestamps fixos — não depende do
     * horário real em que a suíte roda.
     */
    @Test
    void toleranciaPertoDaMeiaNoiteNaoEmbrulhaParaODiaAnterior() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT (TIMESTAMP '2026-08-11 23:55:00') BETWEEN
                            (DATE '2026-08-11' + TIME '21:44:00') AND
                            (DATE '2026-08-11' + TIME '23:50:00' + (15 * INTERVAL '1 minute'))
                     """)) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean(1),
                    "23:55 deveria estar dentro de [21:44, 23:50 + 15min] mesmo a tolerância cruzando a meia-noite");
        }
    }
}
