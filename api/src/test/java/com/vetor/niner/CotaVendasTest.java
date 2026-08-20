package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
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
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cota de vendas do plano (ADR-015, docs/telas/painel-assinatura.md) — o gate que substituiu o
 * trial de 60 dias. Cobre os critérios de aceitação da spec da tela <i>Minha Conta</i>.
 *
 * <p>Os testes não emitem 100 vendas: ajustam a cota do tenant apontando a assinatura para um
 * plano próprio (linha em {@code plataforma.plano} criada por teste, nome carimbado com o
 * tenant) e escrevem o contador direto. É a mesma técnica de {@code PdvCrudTest} para variação/
 * estoque — o que está sob teste é a regra, não a aritmética de repetir venda.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CotaVendasTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    @Autowired
    PostgreSQLContainer postgres;

    // ---------------------------------------------------------------------------- infra do teste

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Cota %s","email":"dono%s@lojacota.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static long idTenantDe(String token) {
        return ((Number) JsonPath.read(payload(token), "$.tid")).longValue();
    }

    private static long idEmpresaDe(String token) {
        return ((Number) JsonPath.read(payload(token), "$.eid")).longValue();
    }

    private String comoOperador(String tokenAdmin) {
        String p = payload(tokenAdmin);
        return tokens.emitir(Long.parseLong(JsonPath.read(p, "$.sub").toString()),
                ((Number) JsonPath.read(p, "$.tid")).longValue(),
                ((Number) JsonPath.read(p, "$.eid")).longValue(),
                JsonPath.read(p, "$.email"), List.of("OPERADOR"));
    }

    private String naEmpresa(String tokenAdmin, long idEmpresa) {
        String p = payload(tokenAdmin);
        return tokens.emitir(Long.parseLong(JsonPath.read(p, "$.sub").toString()),
                ((Number) JsonPath.read(p, "$.tid")).longValue(), idEmpresa,
                JsonPath.read(p, "$.email"), List.of("ADMIN"));
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    /** Aponta a assinatura do tenant para um plano com o limite pedido (linha própria do teste). */
    private void definirCota(Connection c, long idTenant, int limite, int tolerancia) throws SQLException {
        long idPlano;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO plataforma.plano (nome, descricao, ciclo_padrao, preco_mensal, preco_anual,
                                              ativo, limite_vendas_mes, gratuito)
                VALUES ('TESTE cota tenant ' || ?, 'plano de teste', 'MENSAL', 0, 0, false, ?, false)
                RETURNING id_plano
                """)) {
            ps.setLong(1, idTenant);
            ps.setInt(2, limite);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idPlano = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE plataforma.assinatura SET id_plano = ?, tolerancia_vendas = ? WHERE id_tenant = ?")) {
            ps.setLong(1, idPlano);
            ps.setInt(2, tolerancia);
            ps.setLong(3, idTenant);
            ps.executeUpdate();
        }
    }

    private void definirUso(Connection c, long idTenant, int qtdVendas, String competenciaSql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE plataforma.uso_tenant SET qtd_vendas_mes = ?, competencia_vendas = " + competenciaSql
                        + " WHERE id_tenant = ?")) {
            ps.setInt(1, qtdVendas);
            ps.setLong(2, idTenant);
            ps.executeUpdate();
        }
    }

    private int usoAtual(Connection c, long idTenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT qtd_vendas_mes FROM plataforma.uso_tenant WHERE id_tenant = ?")) {
            ps.setLong(1, idTenant);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int totalDeVendas(Connection c, long idTenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM venda WHERE id_tenant = ?")) {
            ps.setLong(1, idTenant);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------- infra de venda (PDV)

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"50.00","ativo":true}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarCliente(String token, String nome) throws Exception {
        String cat = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"PADRAO %s\"}".formatted(nome)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long idCategoria = ((Number) JsonPath.read(cat, "$.idCategoriaCliente")).longValue();
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"fisicaJuridica\":false,\"nome\":\"%s\",\"idCategoriaCliente\":%d}"
                                .formatted(nome, idCategoria)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private long criarFuncionario(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long idCarteiraDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return carteiras.stream().filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue()).findFirst().orElseThrow();
    }

    private void abrirCaixa(String token) throws Exception {
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteiraDinheiro(token))))
                .andExpect(status().isOk());
    }

    private long criarVariacaoComEstoque(Connection c, long idTenant, long idEmpresa, long idProduto)
            throws SQLException {
        long idVariacao;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (?, ?, gerar_ean13_interno()) "
                        + "RETURNING id_variacao")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idVariacao = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVariacao);
            ps.setBigDecimal(4, new BigDecimal("500.000"));
            ps.executeUpdate();
        }
        return idVariacao;
    }

    /** Cenário mínimo para vender: produto com estoque, cliente, vendedor e caixa aberto. */
    private record Cenario(long idVariacao, long idCliente, long idFuncionario, long idCarteira) {
    }

    private Cenario prepararVenda(String token, Connection c, String sufixo) throws Exception {
        long idTenant = idTenantDe(token);
        long idEmpresa = idEmpresaDe(token);
        long idProduto = criarProduto(token, "Produto " + sufixo);
        long idVariacao = criarVariacaoComEstoque(c, idTenant, idEmpresa, idProduto);
        abrirCaixa(token);
        return new Cenario(idVariacao, criarCliente(token, "Cliente " + sufixo),
                criarFuncionario(token, "Vendedor " + sufixo), idCarteiraDinheiro(token));
    }

    private String corpoVenda(Cenario ce) {
        return """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":50.00,"numeroParcelas":1}]}
                """.formatted(ce.idVariacao(), ce.idCliente(), ce.idFuncionario(), ce.idCarteira());
    }

    // ------------------------------------------------------------------------------- os testes

    @Test
    void vendaDentroDaCotaEAceitaEIncrementaOContador() throws Exception {
        String token = assinarNovoTenant("dentro");
        long idTenant = idTenantDe(token);
        try (Connection c = abrirConexao(idTenant)) {
            Cenario ce = prepararVenda(token, c, "dentro");
            definirCota(c, idTenant, 5, 0);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpoVenda(ce)))
                    .andExpect(status().isCreated());

            assertThat(usoAtual(c, idTenant)).isEqualTo(1);
        }
    }

    @Test
    void vendaDentroDaToleranciaEAceita() throws Exception {
        String token = assinarNovoTenant("tolerancia");
        long idTenant = idTenantDe(token);
        try (Connection c = abrirConexao(idTenant)) {
            Cenario ce = prepararVenda(token, c, "tolerancia");
            definirCota(c, idTenant, 2, 1);
            definirUso(c, idTenant, 2, "date_trunc('month', now())::date");   // cota cheia

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpoVenda(ce)))
                    .andExpect(status().isCreated());

            assertThat(usoAtual(c, idTenant)).isEqualTo(3);
        }
    }

    @Test
    void vendaAlemDaToleranciaERecusadaComProblemDetailDeLimite() throws Exception {
        String token = assinarNovoTenant("bloqueio");
        long idTenant = idTenantDe(token);
        try (Connection c = abrirConexao(idTenant)) {
            Cenario ce = prepararVenda(token, c, "bloqueio");
            definirCota(c, idTenant, 2, 1);
            definirUso(c, idTenant, 3, "date_trunc('month', now())::date");   // cota + tolerância cheias
            int vendasAntes = totalDeVendas(c, idTenant);

            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpoVenda(ce)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:niner:erro:limite-de-vendas"))
                    .andExpect(jsonPath("$.limite").value(2))
                    .andExpect(jsonPath("$.tolerancia").value(1))
                    .andExpect(jsonPath("$.faixaRecomendada").exists());

            // rollback: nem a venda nem o incremento do contador ficam gravados
            assertThat(totalDeVendas(c, idTenant)).isEqualTo(vendasAntes);
            assertThat(usoAtual(c, idTenant)).isEqualTo(3);
        }
    }

    @Test
    void viradaDeMesZeraOContadorEArquivaACompetenciaFechada() throws Exception {
        String token = assinarNovoTenant("virada");
        long idTenant = idTenantDe(token);
        try (Connection c = abrirConexao(idTenant)) {
            Cenario ce = prepararVenda(token, c, "virada");
            definirCota(c, idTenant, 5, 0);
            definirUso(c, idTenant, 5, "(date_trunc('month', now()) - interval '1 month')::date");

            // cota do mês passado estava cheia, mas o mês virou: a venda passa
            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpoVenda(ce)))
                    .andExpect(status().isCreated());

            assertThat(usoAtual(c, idTenant)).isEqualTo(1);
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT qtd_vendas FROM plataforma.uso_venda_mes
                     WHERE id_tenant = ? AND competencia = (date_trunc('month', now()) - interval '1 month')::date
                    """)) {
                ps.setLong(1, idTenant);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(5);
                }
            }
        }
    }

    @Test
    void cancelarVendaNaoDevolveCota() throws Exception {
        String token = assinarNovoTenant("cancela");
        long idTenant = idTenantDe(token);
        try (Connection c = abrirConexao(idTenant)) {
            Cenario ce = prepararVenda(token, c, "cancela");
            definirCota(c, idTenant, 5, 0);

            String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpoVenda(ce)))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            long idVenda = ((Number) JsonPath.read(resp, "$.idVenda")).longValue();

            mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content("{\"motivo\":\"Cliente desistiu da compra\"}"))
                    .andExpect(status().isOk());

            assertThat(usoAtual(c, idTenant)).isEqualTo(1);   // ADR-015: incremento é puro
        }
    }

    @Test
    void vendaGravadaForaDoPdvNaoConsomeCota() throws Exception {
        // Venda histórica da Rotina de Importação de Dados entra sem passar pelo PDV (e sem
        // caixa). Se um dia alguém trocar o contador por trigger no INSERT de `venda`, este
        // teste quebra — que é exatamente o ponto.
        String token = assinarNovoTenant("importacao");
        long idTenant = idTenantDe(token);
        long idEmpresa = idEmpresaDe(token);
        try (Connection c = abrirConexao(idTenant)) {
            definirCota(c, idTenant, 5, 0);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO venda (id_tenant, id_empresa, data_venda) VALUES (?, ?, now())")) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idEmpresa);
                ps.executeUpdate();
            }
            assertThat(usoAtual(c, idTenant)).isZero();
        }
    }

    @Test
    void vendasDeEmpresasDiferentesSomamNaMesmaCotaDoTenant() throws Exception {
        String token = assinarNovoTenant("multicnpj");
        long idTenant = idTenantDe(token);

        String respEmpresa = mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Filial Cota Ltda\",\"cnpj\":\"11222333000181\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codigoEmpresa").value(2))
                .andReturn().getResponse().getContentAsString();
        long idFilial = ((Number) JsonPath.read(respEmpresa, "$.idEmpresa")).longValue();
        String tokenFilial = naEmpresa(token, idFilial);

        try (Connection c = abrirConexao(idTenant)) {
            definirCota(c, idTenant, 10, 0);

            Cenario matriz = prepararVenda(token, c, "matriz");
            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON).content(corpoVenda(matriz)))
                    .andExpect(status().isCreated());

            Cenario filial = prepararVenda(tokenFilial, c, "filial");
            mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tokenFilial)
                            .contentType(APPLICATION_JSON).content(corpoVenda(filial)))
                    .andExpect(status().isCreated());

            assertThat(usoAtual(c, idTenant)).isEqualTo(2);   // a cota é do tenant, não do CNPJ
        }
    }

    @Test
    void contaNasceGratuitaComCemVendasESemPrazo() throws Exception {
        String token = assinarNovoTenant("gratuito");

        mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano.nome").value("Gratuito"))
                .andExpect(jsonPath("$.plano.gratuito").value(true))
                .andExpect(jsonPath("$.plano.limiteVendasMes").value(100))
                .andExpect(jsonPath("$.uso.qtdVendas").value(0))
                .andExpect(jsonPath("$.uso.restantes").value(100))
                .andExpect(jsonPath("$.uso.situacao").value("NORMAL"))
                .andExpect(jsonPath("$.empresas.length()").value(1));
    }

    @Test
    void medidorMostraAtencaoTolerânciaEBloqueio() throws Exception {
        String token = assinarNovoTenant("medidor");
        long idTenant = idTenantDe(token);
        try (Connection c = abrirConexao(idTenant)) {
            definirCota(c, idTenant, 10, 2);

            definirUso(c, idTenant, 8, "date_trunc('month', now())::date");
            mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.uso.situacao").value("ATENCAO"))
                    .andExpect(jsonPath("$.uso.restantes").value(2));

            definirUso(c, idTenant, 11, "date_trunc('month', now())::date");
            mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.uso.situacao").value("TOLERANCIA"))
                    .andExpect(jsonPath("$.uso.toleranciaRestante").value(1));

            definirUso(c, idTenant, 12, "date_trunc('month', now())::date");
            mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.uso.situacao").value("BLOQUEADO"))
                    .andExpect(jsonPath("$.uso.toleranciaRestante").value(0));
        }
    }

    @Test
    void operadorNaoAcessaOPainelNemCriaEmpresa() throws Exception {
        String tokenOperador = comoOperador(assinarNovoTenant("operador"));

        mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content("{\"razaoSocial\":\"Nao Deve Criar\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cnpjDuplicadoNoMesmoTenantERecusado() throws Exception {
        String token = assinarNovoTenant("cnpjdup");
        String corpo = "{\"razaoSocial\":\"Filial Duplicada\",\"cnpj\":\"11222333000181\"}";

        mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Cnpj Invalido\",\"cnpj\":\"11222333000100\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("isolaA");
        String tokenB = assinarNovoTenant("isolaB");
        long idTenantA = idTenantDe(tokenA);

        mvc.perform(post("/api/v1/empresas").header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON).content("{\"razaoSocial\":\"So Do Tenant B\"}"))
                .andExpect(status().isCreated());

        try (Connection c = abrirConexao(idTenantA)) {
            definirUso(c, idTenantA, 7, "date_trunc('month', now())::date");
        }

        mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.uso.qtdVendas").value(7))
                .andExpect(jsonPath("$.empresas.length()").value(1));
        mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.uso.qtdVendas").value(0))
                .andExpect(jsonPath("$.empresas.length()").value(2))
                .andExpect(jsonPath("$.empresas[?(@.razaoSocial == 'SO DO TENANT B')]").exists());
    }
}
