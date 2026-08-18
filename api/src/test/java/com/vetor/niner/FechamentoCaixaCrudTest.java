package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fechamento de Caixa (2026-07-30, revisado 2026-08-19 — deixou de ser "às cegas") — ADMIN
 * fecha o caixa de qualquer usuário, OPERADOR só o próprio; totais por tipo de carteira
 * (crédito/débito separados) recalculados a partir de {@code caixa_detalhe}. A tela busca o
 * caixa por {@code idCaixa} (escolhido na grade de "Caixas Abertos", {@code GET .../abertos}) e
 * já mostra o valor esperado antes do operador contar. Fecha quando bate, ou quando o operador
 * confirma fechar mesmo com divergência ({@code forcarComDivergencia}); sem a flag, uma
 * diferença devolve {@code fechado = false} sem gravar nada. Mesmo padrão de setup de {@link
 * RecebimentoCrediarioCrudTest} (única forma de popular {@code caixa_detalhe} de verdade).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FechamentoCaixaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Fechamento %s","email":"dono%s@lojafechamento.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TenantNovo(JsonPath.read(resp, "$.token"), JsonPath.read(resp, "$.slug"));
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    private long buscarIdUsuarioLogado(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.usuario.idUsuario")).longValue();
    }

    private long buscarPrimeiraEmpresa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$[0].idEmpresa")).longValue();
    }

    /** Cria um OPERADOR (não-admin) e devolve o token de login dele. */
    private String criarOperadorEFazerLogin(String tokenAdmin, String slug, String sufixo) throws Exception {
        long idEmpresa = buscarPrimeiraEmpresa(tokenAdmin);
        String email = "operador%s@lojafechamento.com".formatted(sufixo);
        mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nome":"Operador %s","email":"%s","senha":"senha1234",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(sufixo, email, idEmpresa)))
                .andExpect(status().isCreated());

        String login = """
                {"slug":"%s","email":"%s","senha":"senha1234"}
                """.formatted(slug, email);
        String resp = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long criarTipoCarteira(String token, String nome, String categoria, boolean permiteReceberCrediario) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":%s}
                                """.formatted(nome, categoria, permiteReceberCrediario)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
    }

    private long criarCategoriaCliente(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoriaCliente")).longValue();
    }

    private long criarCliente(String token, String nome) throws Exception {
        long idCategoria = criarCategoriaCliente(token, "PADRAO " + nome);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d}
                                """.formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private void definirConfigCrediario(String token) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                                 "cfgPermiteQtdDecimal":true,"cfgExigeNumeroVendaDevolucao":false,
                                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,"cfgConsisteValorContasPagar":false,
                                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":true}
                                """))
                .andExpect(status().isOk());
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private long buscarIdEmpresa(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long criarVenda(Connection c, long idTenant, long idEmpresa, long idCliente) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO venda (id_tenant, id_empresa, id_cliente) VALUES (?, ?, ?) RETURNING id_venda")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idCliente);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long criarParcela(Connection c, long idTenant, long idVenda, long idCarteira, String valorReceber) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO contas_receber
                    (id_tenant, id_venda, id_carteira, numero_parcela, data_vencimento, valor_receber, data_recebimento)
                VALUES (?, ?, ?, 1, now(), ?, NULL)
                RETURNING id_conta_receber
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idVenda);
            ps.setLong(3, idCarteira);
            ps.setBigDecimal(4, new BigDecimal(valorReceber));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Abre o caixa do dia (carteira "DINHEIRO" semeada no signup) para o dono do {@code token}. */
    private void abrirCaixaDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    private long buscarIdCarteiraDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
    }

    /** Monta o corpo de {@code POST /api/v1/caixa/fechamento} com um valor contado por carteira
     *  — {@code valoresPorCarteira} é {idCarteira: "valor"}. */
    private String corpoFechamento(long idCaixa, java.util.Map<Long, String> valoresPorCarteira) {
        return corpoFechamento(idCaixa, valoresPorCarteira, false);
    }

    private String corpoFechamento(long idCaixa, java.util.Map<Long, String> valoresPorCarteira, boolean forcarComDivergencia) {
        StringBuilder linhas = new StringBuilder();
        for (var entrada : valoresPorCarteira.entrySet()) {
            if (linhas.length() > 0) linhas.append(",");
            linhas.append("{\"idCarteira\":%d,\"valorContado\":%s}".formatted(entrada.getKey(), entrada.getValue()));
        }
        return "{\"idCaixa\":%d,\"valoresContados\":[%s],\"forcarComDivergencia\":%s}"
                .formatted(idCaixa, linhas, forcarComDivergencia);
    }

    /** Id do caixa aberto hoje para o dono de {@code token} — mesmo caminho que várias
     *  respostas de {@code /caixa/status} já usavam inline, extraído pra reduzir repetição
     *  depois que {@code GET /caixa/fechamento} passou a exigir {@code idCaixa} no path. */
    private long buscarIdCaixaAtual(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();
    }

    /** Efetiva um recebimento de crediário de {@code valor} na carteira DINHEIRO, gerando um
     *  lançamento de crédito em {@code caixa_detalhe} do caixa aberto de {@code token}. */
    private void efetivarRecebimento(String token, long idTenant, String valor) throws Exception {
        long idCliente = criarCliente(token, "Cliente Fechamento " + valor);
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO F " + valor, "CREDIARIO", false);
        long idCarteiraDinheiro;
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        idCarteiraDinheiro = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();

        long idContaReceber;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            idContaReceber = criarParcela(c, idTenant, idVenda, idCarteiraCrediario, valor);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":%s}]}
                """.formatted(idCliente, idContaReceber, idCarteiraDinheiro, valor);
        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk());
    }

    // --- Reabertura de caixa (2026-08-14) ----------------------------------------------------

    /** Fecha o caixa direto no banco e devolve o id — estes testes precisam do estado "fechado",
     *  não do fluxo de contagem às cegas (já coberto pelos testes de fechamento). */
    private long fecharCaixaNoBanco(long idTenant) throws SQLException {
        try (Connection c = abrirConexao(idTenant);
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE caixa_mestre SET caixa_fechado = true, data_fechamento = now()");
            try (ResultSet rs = st.executeQuery("SELECT id_caixa FROM caixa_mestre ORDER BY id_caixa DESC LIMIT 1")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Reabrir apaga a conferência gravada (foi calculada sobre um estado que vai mudar) e deixa o
     * motivo registrado em {@code observacoes} — é o que torna a reabertura auditável (P3).
     */
    @Test
    void adminReabreCaixaFechadoApagandoConferenciaERegistrandoMotivo() throws Exception {
        TenantNovo tenant = assinarNovoTenant("reabrir-ok");
        abrirCaixaDinheiro(tenant.token());
        long idTenant = extrairIdTenant(tenant.token());
        long idCaixa = fecharCaixaNoBanco(idTenant);

        // Uma linha de conferência que precisa sumir na reabertura.
        long idCarteira = buscarIdCarteiraDinheiro(tenant.token());
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO caixa_fechamento_conferencia
                         (id_tenant, id_caixa, id_carteira, valor_esperado, valor_contado)
                     VALUES (?, ?, ?, 0, 0)
                     """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idCaixa);
            ps.setLong(3, idCarteira);
            ps.executeUpdate();
        }

        mvc.perform(post("/api/v1/caixa/fechamento/" + idCaixa + "/reabrir")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"estornar recebimento lancado por engano\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reaberto").value(true));

        try (Connection c = abrirConexao(idTenant);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT caixa_fechado, data_fechamento, observacoes FROM caixa_mestre WHERE id_caixa = " + idCaixa)) {
                rs.next();
                assertThat(rs.getBoolean("caixa_fechado")).isFalse();
                assertThat(rs.getObject("data_fechamento")).isNull();
                assertThat(rs.getString("observacoes")).contains("REABERTO EM").contains("ESTORNAR RECEBIMENTO");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM caixa_fechamento_conferencia WHERE id_caixa = " + idCaixa)) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    /** Reabrir é decisão de ADMIN — invalida uma conferência já assinada pelo operador. */
    @Test
    void operadorNaoReabreCaixa() throws Exception {
        TenantNovo tenant = assinarNovoTenant("reabrir-operador");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "reabrir-operador");
        abrirCaixaDinheiro(tokenOperador);
        long idCaixa = fecharCaixaNoBanco(extrairIdTenant(tenant.token()));

        mvc.perform(post("/api/v1/caixa/fechamento/" + idCaixa + "/reabrir")
                        .header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"quero reabrir\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reabrirCaixaJaAbertoEhRecusado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("reabrir-ja-aberto");
        abrirCaixaDinheiro(tenant.token());
        long idTenant = extrairIdTenant(tenant.token());
        long idCaixa;
        try (Connection c = abrirConexao(idTenant);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id_caixa FROM caixa_mestre ORDER BY id_caixa DESC LIMIT 1")) {
            rs.next();
            idCaixa = rs.getLong(1);
        }

        mvc.perform(post("/api/v1/caixa/fechamento/" + idCaixa + "/reabrir")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"nada a fazer aqui\"}"))
                .andExpect(status().isConflict());
    }

    /** Motivo em branco é rejeitado — sem o porquê, a reabertura não deixa rastro auditável. */
    @Test
    void reabrirSemMotivoEhRejeitado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("reabrir-sem-motivo");
        abrirCaixaDinheiro(tenant.token());
        long idCaixa = fecharCaixaNoBanco(extrairIdTenant(tenant.token()));

        mvc.perform(post("/api/v1/caixa/fechamento/" + idCaixa + "/reabrir")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminConsultaFechamentoDoProprioCaixa() throws Exception {
        TenantNovo tenant = assinarNovoTenant("admin-proprio");
        abrirCaixaDinheiro(tenant.token());
        long idCaixa = buscarIdCaixaAtual(tenant.token());

        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(false))
                .andExpect(jsonPath("$.linhas[*].nomeCarteira").value(hasItem("DINHEIRO")));
    }

    @Test
    void operadorConsultaOProprio() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-proprio");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-proprio");
        abrirCaixaDinheiro(tokenOperador);
        long idCaixa = buscarIdCaixaAtual(tokenOperador);

        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(false));
    }

    @Test
    void operadorNaoConsegueConsultarCaixaDeOutroUsuario() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-bloqueado");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-bloqueado");
        abrirCaixaDinheiro(tenant.token());
        long idCaixaAdmin = buscarIdCaixaAtual(tenant.token());

        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixaAdmin).header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminConsultaCaixaDeOutroUsuario() throws Exception {
        TenantNovo tenant = assinarNovoTenant("admin-consulta-outro");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "admin-consulta-outro");
        long idOperador = buscarIdUsuarioLogado(tokenOperador);
        abrirCaixaDinheiro(tokenOperador);
        long idCaixa = buscarIdCaixaAtual(tokenOperador);

        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idUsuario").value(idOperador));
    }

    @Test
    void buscarFechamentoDeCaixaInexistenteRespondeNaoEncontrado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-caixa");

        mvc.perform(get("/api/v1/caixa/fechamento/999999").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void totaisSaoCalculadosCorretamenteAPartirDoCaixaDetalhe() throws Exception {
        TenantNovo tenant = assinarNovoTenant("totais");
        long idTenant = extrairIdTenant(tenant.token());
        definirConfigCrediario(tenant.token());
        abrirCaixaDinheiro(tenant.token());

        efetivarRecebimento(tenant.token(), idTenant, "150.00");
        efetivarRecebimento(tenant.token(), idTenant, "50.00");
        long idCaixa = buscarIdCaixaAtual(tenant.token());

        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas[0].nomeCarteira").value("DINHEIRO"))
                .andExpect(jsonPath("$.linhas[0].saldoInicial").value(100.00))
                .andExpect(jsonPath("$.linhas[0].totalCredito").value(200.00))
                .andExpect(jsonPath("$.linhas[0].totalDebito").value(0))
                .andExpect(jsonPath("$.linhas[0].valorEsperado").value(300.00));
    }

    // --- "Caixas Abertos" (2026-08-19) — substitui a busca por data/usuário -------------------

    @Test
    void operadorVeSoOsProprioCaixasAbertos() throws Exception {
        TenantNovo tenant = assinarNovoTenant("abertos-operador");
        String tokenOperadorA = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "abertos-operador-a");
        String tokenOperadorB = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "abertos-operador-b");
        long idOperadorA = buscarIdUsuarioLogado(tokenOperadorA);
        abrirCaixaDinheiro(tokenOperadorA);
        abrirCaixaDinheiro(tokenOperadorB);

        String resp = mvc.perform(get("/api/v1/caixa/abertos").header("Authorization", "Bearer " + tokenOperadorA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> abertos = JsonPath.read(resp, "$");
        assertThat(abertos).hasSize(1);
        assertThat(((Number) abertos.getFirst().get("idUsuario")).longValue()).isEqualTo(idOperadorA);
    }

    @Test
    void adminVeCaixasAbertosDeTodosOsUsuarios() throws Exception {
        TenantNovo tenant = assinarNovoTenant("abertos-admin");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "abertos-admin-op");
        abrirCaixaDinheiro(tenant.token());
        abrirCaixaDinheiro(tokenOperador);

        String resp = mvc.perform(get("/api/v1/caixa/abertos").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> abertos = JsonPath.read(resp, "$");
        assertThat(abertos).hasSize(2);
    }

    @Test
    void caixaFechadoNaoAparecemEmAbertos() throws Exception {
        TenantNovo tenant = assinarNovoTenant("abertos-fechado");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());
        long idCaixa = buscarIdCaixaAtual(tenant.token());

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content(corpoFechamento(idCaixa, java.util.Map.of(idCarteiraDinheiro, "100.00"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/caixa/abertos").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 2026-07-31 — a mesma bandeira pode ter um cadastro em débito e outro em crédito (mesmo
     *  {@code nome_carteira}, {@code categoria_carteira} diferente); o fechamento tem que
     *  mostrar as duas como linhas separadas, com os valores próprios de cada uma. */
    @Test
    void carteirasComMesmoNomeEmCategoriasDiferentesAparecemComoLinhasSeparadas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("mesmo-nome");
        long idTenant = extrairIdTenant(tenant.token());
        abrirCaixaDinheiro(tenant.token());
        long idHiperDebito = criarTipoCarteira(tenant.token(), "HIPER", "CARTAO_DEBITO", true);
        long idHiperCredito = criarTipoCarteira(tenant.token(), "HIPER", "CARTAO_CREDITO", true);

        String status = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(status, "$.idCaixa")).longValue();

        try (Connection c = abrirConexao(idTenant)) {
            for (var par : java.util.List.of(
                    java.util.Map.entry(idHiperDebito, "40.00"), java.util.Map.entry(idHiperCredito, "60.00"))) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO caixa_detalhe (id_tenant, id_caixa, id_carteira, valor, tipo_operacao, credito_debito)
                        VALUES (?, ?, ?, ?, 'RECEBIMENTO_VENDA', 'C')
                        """)) {
                    ps.setLong(1, idTenant);
                    ps.setLong(2, idCaixa);
                    ps.setLong(3, par.getKey());
                    ps.setBigDecimal(4, new BigDecimal(par.getValue()));
                    ps.execute();
                }
            }
        }

        String resp = mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<java.util.Map<String, Object>> linhas = JsonPath.read(resp, "$.linhas");
        java.util.List<java.util.Map<String, Object>> linhasHiper =
                linhas.stream().filter(l -> "HIPER".equals(l.get("nomeCarteira"))).toList();
        assertThat(linhasHiper).hasSize(2);

        var debito = linhasHiper.stream().filter(l -> ((Number) l.get("idCarteira")).longValue() == idHiperDebito).findFirst().orElseThrow();
        var credito = linhasHiper.stream().filter(l -> ((Number) l.get("idCarteira")).longValue() == idHiperCredito).findFirst().orElseThrow();
        assertThat(debito.get("categoriaCarteira")).isEqualTo("CARTAO_DEBITO");
        assertThat(((Number) debito.get("totalCredito")).doubleValue()).isEqualTo(40.00);
        assertThat(credito.get("categoriaCarteira")).isEqualTo("CARTAO_CREDITO");
        assertThat(((Number) credito.get("totalCredito")).doubleValue()).isEqualTo(60.00);
    }

    @Test
    void adminFechaOCaixaDeOutroUsuarioComSucesso() throws Exception {
        TenantNovo tenant = assinarNovoTenant("admin-fecha-outro");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "admin-fecha-outro");
        long idOperador = buscarIdUsuarioLogado(tokenOperador);
        abrirCaixaDinheiro(tokenOperador);
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tokenOperador);

        long idCaixa = buscarIdCaixaAtual(tokenOperador);

        // Só a carteira de abertura teve movimento (só o saldo inicial de 100.00) — contar 100.00 bate exato.
        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content(corpoFechamento(idCaixa, java.util.Map.of(idCarteiraDinheiro, "100.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(true))
                .andExpect(jsonPath("$.linhas[0].diferenca").value(0));

        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idUsuario").value(idOperador))
                .andExpect(jsonPath("$.fechado").value(true))
                .andExpect(jsonPath("$.conferencia[0].valorEsperado").value(100.00))
                .andExpect(jsonPath("$.conferencia[0].valorContado").value(100.00));
    }

    @Test
    void fechamentoComDivergenciaNaoFechaEDevolveDiferencaPorCarteira() throws Exception {
        TenantNovo tenant = assinarNovoTenant("divergencia");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();

        // Esperado é 100.00 (só o saldo inicial); contando 90.00 gera divergência de -10.00.
        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content(corpoFechamento(idCaixa, java.util.Map.of(idCarteiraDinheiro, "90.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(false))
                .andExpect(jsonPath("$.linhas[0].valorEsperado").value(100.00))
                .andExpect(jsonPath("$.linhas[0].valorContado").value(90.00))
                .andExpect(jsonPath("$.linhas[0].diferenca").value(-10.00));

        // O caixa continua aberto — nada foi gravado.
        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(false));
    }

    @Test
    void fechamentoComDivergenciaEForcarFechaEDeixaRastroNaObservacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("divergencia-forcada");
        long idTenant = extrairIdTenant(tenant.token());
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());
        long idCaixa = buscarIdCaixaAtual(tenant.token());

        // Esperado é 100.00 (só o saldo inicial); contando 90.00 gera divergência de -10.00.
        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content(corpoFechamento(idCaixa, java.util.Map.of(idCarteiraDinheiro, "90.00"), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(true));

        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixa).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(true));

        try (Connection c = abrirConexao(idTenant);
                PreparedStatement ps = c.prepareStatement(
                        "SELECT observacoes FROM caixa_mestre WHERE id_tenant = ? AND id_caixa = ?")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idCaixa);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("observacoes")).contains("FECHADO COM DIVERGENCIA");
            }
        }
    }

    @Test
    void fecharSemInformarTodasAsCarteirasRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("carteira-faltando");
        long idTenant = extrairIdTenant(tenant.token());
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());
        long idOutraCarteira = criarTipoCarteira(tenant.token(), "PIX FALTANDO", "AVISTA", false);

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();

        // Lança um movimento direto em caixa_detalhe pra uma segunda carteira ter movimento no dia
        // (sem endpoint de venda avulsa nos testes — mesmo padrão de outros arquivos deste projeto).
        try (Connection c = abrirConexao(idTenant); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO caixa_detalhe (id_tenant, id_caixa, id_carteira, valor, tipo_operacao, credito_debito)
                VALUES (?, ?, ?, 30.00, 'RECEBIMENTO_VENDA', 'C')
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idCaixa);
            ps.setLong(3, idOutraCarteira);
            ps.execute();
        }

        // Só informa a carteira de abertura — falta a segunda, que também teve movimento.
        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content(corpoFechamento(idCaixa, java.util.Map.of(idCarteiraDinheiro, "100.00"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void drillDownDeLancamentosDaCarteiraTrazAberturaEMovimentos() throws Exception {
        TenantNovo tenant = assinarNovoTenant("drill-down");
        long idTenant = extrairIdTenant(tenant.token());
        definirConfigCrediario(tenant.token());
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());
        efetivarRecebimento(tenant.token(), idTenant, "50.00");

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();

        mvc.perform(get("/api/v1/caixa/fechamento/%d/carteiras/%d/lancamentos".formatted(idCaixa, idCarteiraDinheiro))
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tipoOperacao").value("ABERTURA_CAIXA"))
                .andExpect(jsonPath("$[0].valor").value(100.00))
                .andExpect(jsonPath("$[1].tipoOperacao").value("RECEBIMENTO_PARCELA_CREDIARIO"))
                .andExpect(jsonPath("$[1].valor").value(50.00))
                .andExpect(jsonPath("$[1].origem").value(org.hamcrest.Matchers.startsWith("Recebimento nº")));
    }

    @Test
    void operadorNaoConsegueFecharOCaixaDeOutroUsuario() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-fecha-bloqueado");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-fecha-bloqueado");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON)
                        .content(corpoFechamento(idCaixa, java.util.Map.of(idCarteiraDinheiro, "100.00"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void fecharCaixaJaFechadoRespondeConflito() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ja-fechado");
        abrirCaixaDinheiro(tenant.token());
        long idCarteiraDinheiro = buscarIdCarteiraDinheiro(tenant.token());

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();
        String corpo = corpoFechamento(idCaixa, java.util.Map.of(idCarteiraDinheiro, "100.00"));

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(true));

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
    }

    @Test
    void fecharCaixaInexistenteRespondeNaoEncontrado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("caixa-inexistente");

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content(corpoFechamento(999999, java.util.Map.of(1L, "100.00"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void caixaDeUmTenantNaoInterfereNoFechamentoDeOutro() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-b");
        abrirCaixaDinheiro(tenantA.token());
        long idCaixaA = buscarIdCaixaAtual(tenantA.token());

        mvc.perform(get("/api/v1/caixa/fechamento/" + idCaixaA).header("Authorization", "Bearer " + tenantB.token()))
                .andExpect(status().isNotFound());
    }
}
