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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fechamento de Caixa (2026-07-30) — ADMIN fecha o caixa de qualquer usuário, OPERADOR só o
 * próprio; totais por tipo de carteira (crédito/débito separados) recalculados a partir de
 * {@code caixa_detalhe}; conferência de dinheiro contado gravada em {@code
 * caixa_mestre.valor_contado_dinheiro}. Mesmo padrão de setup de {@link RecebimentoCrediarioCrudTest}
 * (única forma de popular {@code caixa_detalhe} de verdade).
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
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaVarianteLinha":true,"cfgUsaVarianteColuna":true,
                                 "cfgPermiteQtdDecimal":true}
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

    @Test
    void adminConsultaFechamentoDoProprioCaixa() throws Exception {
        TenantNovo tenant = assinarNovoTenant("admin-proprio");
        abrirCaixaDinheiro(tenant.token());
        String hoje = LocalDate.now().toString();

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .param("data", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(false))
                .andExpect(jsonPath("$.linhas[*].nomeCarteira").value(hasItem("DINHEIRO")));
    }

    @Test
    void operadorConsultaOProprioSemInformarIdUsuario() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-proprio");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-proprio");
        abrirCaixaDinheiro(tokenOperador);
        String hoje = LocalDate.now().toString();

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tokenOperador)
                        .param("data", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(false));
    }

    @Test
    void operadorNaoConsegueConsultarCaixaDeOutroUsuario() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-bloqueado");
        long idAdmin = buscarIdUsuarioLogado(tenant.token());
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-bloqueado");
        abrirCaixaDinheiro(tenant.token());
        String hoje = LocalDate.now().toString();

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tokenOperador)
                        .param("idUsuario", String.valueOf(idAdmin))
                        .param("data", hoje))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminConsultaCaixaDeOutroUsuario() throws Exception {
        TenantNovo tenant = assinarNovoTenant("admin-consulta-outro");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "admin-consulta-outro");
        long idOperador = buscarIdUsuarioLogado(tokenOperador);
        abrirCaixaDinheiro(tokenOperador);
        String hoje = LocalDate.now().toString();

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .param("idUsuario", String.valueOf(idOperador))
                        .param("data", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idUsuario").value(idOperador));
    }

    @Test
    void buscarFechamentoSemCaixaNaDataRespondeNaoEncontrado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-caixa");

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .param("data", LocalDate.now().toString()))
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

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .param("data", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas[0].nomeCarteira").value("DINHEIRO"))
                .andExpect(jsonPath("$.linhas[0].saldoInicial").value(100.00))
                .andExpect(jsonPath("$.linhas[0].totalCredito").value(200.00))
                .andExpect(jsonPath("$.linhas[0].totalDebito").value(0))
                .andExpect(jsonPath("$.linhas[0].valorEsperado").value(300.00));
    }

    @Test
    void adminFechaOCaixaDeOutroUsuarioComSucesso() throws Exception {
        TenantNovo tenant = assinarNovoTenant("admin-fecha-outro");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "admin-fecha-outro");
        long idOperador = buscarIdUsuarioLogado(tokenOperador);
        abrirCaixaDinheiro(tokenOperador);
        String hoje = LocalDate.now().toString();

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tokenOperador))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCaixa\":%d,\"valorContadoDinheiro\":95.00}".formatted(idCaixa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(true))
                .andExpect(jsonPath("$.valorContadoDinheiro").value(95.00));

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .param("idUsuario", String.valueOf(idOperador))
                        .param("data", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(true));
    }

    @Test
    void operadorNaoConsegueFecharOCaixaDeOutroUsuario() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-fecha-bloqueado");
        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-fecha-bloqueado");
        abrirCaixaDinheiro(tenant.token());

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCaixa\":%d,\"valorContadoDinheiro\":100.00}".formatted(idCaixa)))
                .andExpect(status().isForbidden());
    }

    @Test
    void fecharCaixaJaFechadoRespondeConflito() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ja-fechado");
        abrirCaixaDinheiro(tenant.token());

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tenant.token()))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();
        String corpo = "{\"idCaixa\":%d,\"valorContadoDinheiro\":100.00}".formatted(idCaixa);

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
    }

    @Test
    void fecharCaixaInexistenteRespondeNaoEncontrado() throws Exception {
        TenantNovo tenant = assinarNovoTenant("caixa-inexistente");

        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCaixa\":999999,\"valorContadoDinheiro\":100.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void caixaDeUmTenantNaoInterfereNoFechamentoDeOutro() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-b");
        abrirCaixaDinheiro(tenantA.token());

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenantB.token())
                        .param("data", LocalDate.now().toString()))
                .andExpect(status().isNotFound());
    }
}
