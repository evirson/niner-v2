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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de Vendas (docs/telas/relatorio-vendas.md) — qualquer papel, somente leitura.
 * Helpers copiados/adaptados de {@code PesquisaVendaCrudTest} (mesma convenção do projeto: cada
 * teste de tela mantém sua própria cópia). Intervalo de datas amplo (180 dias antes/depois de
 * hoje) usado em toda parte pra não depender do dia exato em que o teste roda.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioVendasCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Relatorio %s","email":"dono%s@lojarelatorio.com",
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

    private long buscarPrimeiraEmpresa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$[0].idEmpresa")).longValue();
    }

    private String criarOperadorEFazerLogin(String tokenAdmin, String slug, String sufixo, long idEmpresa) throws Exception {
        String email = "operador%s@lojarelatorio.com".formatted(sufixo);
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

    private long criarProduto(String token, String descricao) throws Exception {
        return criarProduto(token, descricao, null);
    }

    private long criarProduto(String token, String descricao, String marca) throws Exception {
        String marcaJson = marca == null ? "" : ",\"marca\":\"%s\"".formatted(marca);
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"50.00"%s}
                                """.formatted(descricao, marcaJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarTipoCarteira(String token, String nome, String categoria) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":true}
                                """.formatted(nome, categoria)))
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

    private long criarFuncionario(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long buscarIdCarteiraDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
    }

    private void abrirCaixaDinheiro(String token) throws Exception {
        long idCarteira = buscarIdCarteiraDinheiro(token);
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
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

    private long criarSegundaEmpresa(Connection c, long idTenant) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta) VALUES ("
                             + idTenant + ", 2, 'FILIAL RELATORIO', '{sku}') RETURNING id_empresa")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long criarVariacao(Connection c, long idTenant, long idProduto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (?, ?, gerar_ean13_interno())
                RETURNING id_variacao
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void definirEstoque(Connection c, long idTenant, long idEmpresa, long idVariacao, BigDecimal qtd) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque) VALUES (?, ?, ?, ?)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVariacao);
            ps.setBigDecimal(4, qtd);
            ps.execute();
        }
    }

    /** Efetiva uma venda de 1 item via PDV de verdade — devolve o id_venda. */
    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                                long idCarteira, String descontoVenda, String valorPago) throws Exception {
        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":%s,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                """.formatted(idVariacao, descontoVenda, idCliente, idFuncionario, idCarteira, valorPago);
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    /** Efetiva uma venda com 2 itens (produtos diferentes) via PDV — devolve o id_venda. */
    private long efetivarVendaDoisItens(String token, long idVariacao1, long idVariacao2, long idCliente,
                                         long idFuncionario, long idCarteira, String valorPago) throws Exception {
        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":1},{"idVariacao":%d,"qtd":1}],"descontoVenda":0,
                 "idCliente":%d,"idFuncionario":%d,"pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                """.formatted(idVariacao1, idVariacao2, idCliente, idFuncionario, idCarteira, valorPago);
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    private void cancelar(String token, long idVenda) throws Exception {
        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"teste relatorio de vendas\"}"))
                .andExpect(status().isOk());
    }

    private long criarVendaBruta(Connection c, long idTenant, long idEmpresa) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO venda (id_tenant, id_empresa) VALUES (?, ?) RETURNING id_venda")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String[] intervaloAmplo() {
        return new String[] { LocalDate.now().minusDays(180).toString(), LocalDate.now().plusDays(180).toString() };
    }

    @Test
    void kpisEComposicaoBatemComSomaManualDeUmaUnicaVenda() throws Exception {
        TenantNovo tenant = assinarNovoTenant("kpis");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto KPI");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO KPI", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente KPI");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor KPI");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            // 2 unidades a 50.00 (bruto 100.00), sem desconto no PDV (cfg_geral.percentual_desconto_venda
            // é 0 por padrão no signup, o PDV rejeitaria qualquer desconto > 0) — o desconto de 10.00 é
            // aplicado direto no ledger depois, só pra exercitar o cálculo do relatório sobre o dado real.
            String corpo = """
                    {"itens":[{"idVariacao":%d,"qtd":2}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                     "pagamentos":[{"idCarteira":%d,"valorPago":100.00,"numeroParcelas":1}]}
                    """.formatted(idVariacao, idCliente, idFuncionario, idCarteira);
            String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + tenant.token())
                            .contentType(APPLICATION_JSON).content(corpo))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long idVenda = ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE produto_movimento_detalhe SET valor_desconto = 10.00
                    WHERE id_tenant = ? AND id_movimento IN (
                        SELECT id_movimento FROM produto_movimento_mestre
                        WHERE id_tenant = ? AND id_venda = ? AND tipo_movimento = 'VENDA')
                    """)) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idTenant);
                ps.setLong(3, idVenda);
                ps.executeUpdate();
            }
        }

        mvc.perform(get("/api/v1/relatorios/vendas").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1])
                        .param("totalizarPor", "NAO_TOTALIZAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.ticketMedioValor").value(90.00))
                .andExpect(jsonPath("$.kpis.ticketMedioNVendas").value(1))
                .andExpect(jsonPath("$.kpis.percentualMedioDesconto").value(10.00))
                .andExpect(jsonPath("$.kpis.valorDesconto").value(10.00))
                .andExpect(jsonPath("$.kpis.itensVendidos").value(2))
                .andExpect(jsonPath("$.kpis.mediaItensPorVenda").value(2.000))
                .andExpect(jsonPath("$.kpis.valorDevolucao").value(0))
                .andExpect(jsonPath("$.composicaoFaturamento.valorBruto").value(100.00))
                .andExpect(jsonPath("$.composicaoFaturamento.descontos").value(10.00))
                .andExpect(jsonPath("$.composicaoFaturamento.vendaLiquida").value(90.00))
                .andExpect(jsonPath("$.totalizador.tipo").value("ANALITICO"))
                .andExpect(jsonPath("$.totalizador.linhasAnaliticas.length()").value(1))
                .andExpect(jsonPath("$.totalizador.linhasAnaliticas[0].valorVenda").value(100.00))
                .andExpect(jsonPath("$.totalizador.linhasAnaliticas[0].descontos").value(10.00))
                .andExpect(jsonPath("$.totalizador.linhasAnaliticas[0].valorLiquido").value(90.00))
                .andExpect(jsonPath("$.totalizador.linhasAnaliticas[0].qtdProdutos").value(2));
    }

    @Test
    void vendaCanceladaNaoEntraEmKpiComposicaoNemTotalizador() throws Exception {
        TenantNovo tenant = assinarNovoTenant("cancelada");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Cancelada");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO CANCELADA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Cancelada");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Cancelada");
        abrirCaixaDinheiro(tenant.token());

        long idVendaCancelada;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "0", "50.00");
            idVendaCancelada = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "0", "50.00");
        }
        cancelar(tenant.token(), idVendaCancelada);

        mvc.perform(get("/api/v1/relatorios/vendas").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1])
                        .param("totalizarPor", "NAO_TOTALIZAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.ticketMedioNVendas").value(1))
                .andExpect(jsonPath("$.composicaoFaturamento.valorBruto").value(50.00))
                .andExpect(jsonPath("$.totalizador.linhasAnaliticas.length()").value(1))
                .andExpect(jsonPath("$.totalizador.linhasAnaliticas[?(@.idVenda == %d)]".formatted(idVendaCancelada)).isEmpty());
    }

    @Test
    void operadorSempreConsultaAPropriaEmpresaMesmoInformandoOutra() throws Exception {
        TenantNovo tenant = assinarNovoTenant("operador-relatorio");
        long idTenant = extrairIdTenant(tenant.token());
        long idEmpresaOrigem = buscarPrimeiraEmpresa(tenant.token());

        long idEmpresaDestino;
        long idVendaOutraEmpresa;
        try (Connection c = abrirConexao(idTenant)) {
            idEmpresaDestino = criarSegundaEmpresa(c, idTenant);
            idVendaOutraEmpresa = criarVendaBruta(c, idTenant, idEmpresaDestino);
        }

        String tokenOperador = criarOperadorEFazerLogin(tenant.token(), tenant.slug(), "operador-relatorio", idEmpresaOrigem);

        String resp = mvc.perform(get("/api/v1/relatorios/vendas").header("Authorization", "Bearer " + tokenOperador)
                        .param("idsEmpresa", String.valueOf(idEmpresaDestino))
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1])
                        .param("totalizarPor", "NAO_TOTALIZAR"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Number> idsRetornados = JsonPath.read(resp, "$.totalizador.linhasAnaliticas[*].idVenda");
        assertThat(idsRetornados).extracting(Number::longValue).doesNotContain(idVendaOutraEmpresa);
    }

    @Test
    void totalizarPorVendedorAgrupaEDrillDownBateComOGrupo() throws Exception {
        TenantNovo tenant = assinarNovoTenant("totalizador-vendedor");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Totalizador Vendedor");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO TOTALIZADOR VENDEDOR", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Totalizador Vendedor");
        long idVendedorA = criarFuncionario(tenant.token(), "Vendedor A");
        long idVendedorB = criarFuncionario(tenant.token(), "Vendedor B");
        abrirCaixaDinheiro(tenant.token());

        long idVenda1;
        long idVenda2;
        long idVenda3;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda1 = efetivarVenda(tenant.token(), idVariacao, idCliente, idVendedorA, idCarteira, "0", "50.00");
            idVenda2 = efetivarVenda(tenant.token(), idVariacao, idCliente, idVendedorA, idCarteira, "0", "50.00");
            idVenda3 = efetivarVenda(tenant.token(), idVariacao, idCliente, idVendedorB, idCarteira, "0", "50.00");
        }

        String resp = mvc.perform(get("/api/v1/relatorios/vendas").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1])
                        .param("totalizarPor", "VENDEDOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalizador.tipo").value("AGRUPADO"))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> grupos = JsonPath.read(resp, "$.totalizador.linhasAgrupadas");
        Map<String, Object> grupoA = grupos.stream().filter(g -> String.valueOf(idVendedorA).equals(g.get("chave")))
                .findFirst().orElseThrow();
        Map<String, Object> grupoB = grupos.stream().filter(g -> String.valueOf(idVendedorB).equals(g.get("chave")))
                .findFirst().orElseThrow();
        assertThat(((Number) grupoA.get("nVendas")).longValue()).isEqualTo(2);
        assertThat(new BigDecimal(grupoA.get("valorVenda").toString())).isEqualByComparingTo("100.00");
        assertThat(((Number) grupoB.get("nVendas")).longValue()).isEqualTo(1);

        String respDetalhe = mvc.perform(get("/api/v1/relatorios/vendas/detalhe").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1])
                        .param("totalizarPor", "VENDEDOR").param("chave", String.valueOf(idVendedorA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Number> idsDoGrupo = JsonPath.read(respDetalhe, "$.itens[*].idVenda");
        assertThat(idsDoGrupo).extracting(Number::longValue).containsExactlyInAnyOrder(idVenda1, idVenda2);
        assertThat(idsDoGrupo).extracting(Number::longValue).doesNotContain(idVenda3);
    }

    @Test
    void detalheSemTotalizadorAplicadoRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("detalhe-sem-totalizador");
        mvc.perform(get("/api/v1/relatorios/vendas/detalhe").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1])
                        .param("totalizarPor", "NAO_TOTALIZAR").param("chave", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void top10MarcasAgregaPorItemMesmoComVendaDeDuasMarcas() throws Exception {
        TenantNovo tenant = assinarNovoTenant("top-marcas");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoA = criarProduto(tenant.token(), "Produto Marca A", "MARCA A");
        long idProdutoB = criarProduto(tenant.token(), "Produto Marca B", "MARCA B");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO TOP MARCAS", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Top Marcas");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Top Marcas");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoA = criarVariacao(c, idTenant, idProdutoA);
            long idVariacaoB = criarVariacao(c, idTenant, idProdutoB);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoA, new BigDecimal("5.000"));
            definirEstoque(c, idTenant, idEmpresa, idVariacaoB, new BigDecimal("5.000"));
            efetivarVendaDoisItens(tenant.token(), idVariacaoA, idVariacaoB, idCliente, idFuncionario, idCarteira, "100.00");
        }

        String resp = mvc.perform(get("/api/v1/relatorios/vendas").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1])
                        .param("totalizarPor", "NAO_TOTALIZAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graficos.topMarcas.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> topMarcas = JsonPath.read(resp, "$.graficos.topMarcas");
        Map<String, BigDecimal> porMarca = topMarcas.stream().collect(java.util.stream.Collectors.toMap(
                m -> (String) m.get("rotulo"), m -> new BigDecimal(m.get("valor").toString())));
        assertThat(porMarca.get("MARCA A")).isEqualByComparingTo("50.00");
        assertThat(porMarca.get("MARCA B")).isEqualByComparingTo("50.00");
    }

    @Test
    void periodoMaiorQue400DiasRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("periodo-maximo-relatorio");
        mvc.perform(get("/api/v1/relatorios/vendas").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", "2020-01-01").param("dataFinal", "2022-06-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void vendaDeOutroTenantNaoApareceNoRelatorio() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-a-relatorio");
        TenantNovo tenantB = assinarNovoTenant("isolamento-b-relatorio");
        long idTenantA = extrairIdTenant(tenantA.token());
        long idProduto = criarProduto(tenantA.token(), "Produto Isolamento Relatorio");
        long idCarteira = criarTipoCarteira(tenantA.token(), "DINHEIRO ISOLAMENTO RELATORIO", "AVISTA");
        long idCliente = criarCliente(tenantA.token(), "Cliente Isolamento Relatorio");
        long idFuncionario = criarFuncionario(tenantA.token(), "Vendedor Isolamento Relatorio");
        abrirCaixaDinheiro(tenantA.token());

        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenantA, idProduto);
            definirEstoque(c, idTenantA, idEmpresa, idVariacao, new BigDecimal("5.000"));
            efetivarVenda(tenantA.token(), idVariacao, idCliente, idFuncionario, idCarteira, "0", "50.00");
        }

        mvc.perform(get("/api/v1/relatorios/vendas").header("Authorization", "Bearer " + tenantB.token())
                        .param("dataInicial", intervaloAmplo()[0]).param("dataFinal", intervaloAmplo()[1])
                        .param("totalizarPor", "NAO_TOTALIZAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.ticketMedioNVendas").value(0))
                .andExpect(jsonPath("$.totalizador.linhasAnaliticas.length()").value(0));
    }
}
