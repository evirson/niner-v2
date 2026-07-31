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
 * Fechamento de Caixa (2026-07-30, revisado no mesmo dia pro fechamento "às cegas") — ADMIN
 * fecha o caixa de qualquer usuário, OPERADOR só o próprio; totais por tipo de carteira
 * (crédito/débito separados) recalculados a partir de {@code caixa_detalhe}. O operador informa
 * o valor contado de CADA carteira com movimento; só fecha de fato quando todas batem — senão o
 * caixa continua aberto e a resposta traz a divergência de cada carteira, gravada em {@code
 * caixa_fechamento_conferencia} só no sucesso. Mesmo padrão de setup de {@link
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
        StringBuilder linhas = new StringBuilder();
        for (var entrada : valoresPorCarteira.entrySet()) {
            if (linhas.length() > 0) linhas.append(",");
            linhas.append("{\"idCarteira\":%d,\"valorContado\":%s}".formatted(entrada.getKey(), entrada.getValue()));
        }
        return "{\"idCaixa\":%d,\"valoresContados\":[%s]}".formatted(idCaixa, linhas);
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

        String resp = mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .param("data", LocalDate.now().toString()))
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
        String hoje = LocalDate.now().toString();

        String resp = mvc.perform(get("/api/v1/caixa/status").header("Authorization", "Bearer " + tokenOperador))
                .andReturn().getResponse().getContentAsString();
        long idCaixa = ((Number) JsonPath.read(resp, "$.idCaixa")).longValue();

        // Só a carteira de abertura teve movimento (só o saldo inicial de 100.00) — contar 100.00 bate exato.
        mvc.perform(post("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content(corpoFechamento(idCaixa, java.util.Map.of(idCarteiraDinheiro, "100.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(true))
                .andExpect(jsonPath("$.linhas[0].diferenca").value(0));

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .param("idUsuario", String.valueOf(idOperador))
                        .param("data", hoje))
                .andExpect(status().isOk())
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
        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenant.token())
                        .param("data", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechado").value(false));
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

        mvc.perform(get("/api/v1/caixa/fechamento").header("Authorization", "Bearer " + tenantB.token())
                        .param("data", LocalDate.now().toString()))
                .andExpect(status().isNotFound());
    }
}
