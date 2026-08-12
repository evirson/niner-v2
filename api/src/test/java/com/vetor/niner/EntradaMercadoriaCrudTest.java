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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Entrada de Produtos por Compra (docs/telas/entrada-mercadoria.md) — cobre o fluxo Manual (a
 * base comum aos 3 fluxos: XML e Planilha, ainda não implementados, convergem no mesmo
 * `POST /api/v1/estoque/entradas` testado aqui). ADMIN e OPERADOR têm acesso (sem restrição de
 * papel, mesmo nível de Transferência/Devolução).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EntradaMercadoriaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Entrada %s","email":"dono%s@lojaentrada.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    private void criarPlano(String token, String codigo) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"DESPESA FORNECEDORES","tipoMovimento":"DEBITO",
                                 "natureza":"ANALITICA","incluiDre":false,"incluiFluxoCaixa":false}
                                """.formatted(codigo)))
                .andExpect(status().isCreated());
    }

    private long criarFornecedor(String token, String razaoSocial) throws Exception {
        criarPlano(token, "2.00.000.000");
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"%s\",\"idPlanoContas\":\"2.00.000.000\"}".formatted(razaoSocial)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFornecedor")).longValue();
    }

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"20.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarVariacao(String token, long idProduto) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos/" + idProduto + "/variacoes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVariacao")).longValue();
    }

    private void definirCfgGeral(String token, boolean rateiaFrete, boolean reajustaPreco) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                                 "cfgPermiteQtdDecimal":true,"cfgExigeNumeroVendaDevolucao":false,
                                 "cfgRateiaFreteEntrada":%s,"cfgReajustaPrecoEntrada":%s,
                                 "idPlanoContasCompraMercadoria":"3.03.001.001"}
                                """.formatted(rateiaFrete, reajustaPreco)))
                .andExpect(status().isOk());
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private BigDecimal buscarQtdEstoque(Connection c, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT qtd_estoque FROM produto_estoque WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    @Test
    void efetivarManualGravaMovimentoEstoqueEUsuario() throws Exception {
        TenantENota tenant = prepararTenantComProduto("basica");

        String corpo = """
                {"idFornecedor":%d,"notaFiscal":123,
                 "itens":[{"idVariacao":%d,"qtd":5,"precoCusto":10.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());

        String resp = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idFornecedor").value(tenant.idFornecedor()))
                .andExpect(jsonPath("$.notaFiscal").value(123))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].qtd").value(5))
                .andExpect(jsonPath("$.valorTotal").value(50.00))
                .andReturn().getResponse().getContentAsString();
        long idMovimento = ((Number) JsonPath.read(resp, "$.idMovimento")).longValue();

        try (Connection c = abrirConexao(tenant.idTenant())) {
            assertThat(buscarQtdEstoque(c, tenant.idVariacao())).isEqualByComparingTo("5.000");

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tipo_movimento, id_usuario, id_fornecedor FROM produto_movimento_mestre WHERE id_movimento = ?")) {
                ps.setLong(1, idMovimento);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString("tipo_movimento")).isEqualTo("COMPRA");
                    assertThat(rs.getLong("id_usuario")).isPositive();
                    assertThat(rs.getLong("id_fornecedor")).isEqualTo(tenant.idFornecedor());
                }
            }
        }
    }

    @Test
    void contasPagarGeradoAPartirDoCorpoDaRequisicao() throws Exception {
        TenantENota tenant = prepararTenantComProduto("contas-pagar");

        String corpo = """
                {"idFornecedor":%d,"notaFiscal":456,
                 "itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}],
                 "contasPagar":[{"numeroDuplicata":"001","dataVencimento":"2026-09-10","valor":10.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated());

        try (Connection c = abrirConexao(tenant.idTenant());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id_plano_contas, valor_pagar, nota_fiscal FROM contas_pagar WHERE id_fornecedor = ?")) {
            ps.setLong(1, tenant.idFornecedor());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                // 2026-08-12: contas_pagar da Entrada usa o plano de contas de CUSTO configurado em
                // cfg_geral (padrão "3.03.001.001", seed do signup) — não mais o plano do fornecedor.
                assertThat(rs.getString("id_plano_contas")).isEqualTo("3.03.001.001");
                assertThat(rs.getBigDecimal("valor_pagar")).isEqualByComparingTo("10.00");
                assertThat(rs.getInt("nota_fiscal")).isEqualTo(456);
            }
        }
    }

    @Test
    void semFlagsLigadasNaoRateiaNemReajustaPreco() throws Exception {
        TenantENota tenant = prepararTenantComProduto("sem-flags");

        String corpo = """
                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":15.00}],"valorRateio":5.00}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itens[0].valorTotal").value(15.00));

        mvc.perform(get("/api/v1/produtos/" + tenant.idProduto()).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(jsonPath("$.precoCusto").value(10.00));
    }

    @Test
    void rateioDeFreteDistribuiProporcionalmenteQuandoFlagLigada() throws Exception {
        TenantENota tenant = prepararTenantComProduto("rateio");
        definirCfgGeral(tenant.token(), true, false);
        long idVariacao2 = criarVariacao(tenant.token(), criarProduto(tenant.token(), "Segundo Produto Rateio"));

        // item 1: 1 x 10 = 10 (1/3 do total); item 2: 1 x 20 = 20 (2/3) -> rateio de 30 vira 10/20
        String corpo = """
                {"idFornecedor":%d,"valorRateio":30.00,
                 "itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00},
                           {"idVariacao":%d,"qtd":1,"precoCusto":20.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao(), idVariacao2);

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itens[0].valorTotal").value(20.00))
                .andExpect(jsonPath("$.itens[1].valorTotal").value(40.00))
                .andExpect(jsonPath("$.valorTotal").value(60.00));
    }

    @Test
    void reajustaPrecoAtualizaProdutoQuandoFlagLigada() throws Exception {
        TenantENota tenant = prepararTenantComProduto("reajuste");
        definirCfgGeral(tenant.token(), false, true);

        String corpo = """
                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":50.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated());

        // percentualVenda do produto criado em prepararTenantComProduto = 100% -> preco_venda dobra o custo
        mvc.perform(get("/api/v1/produtos/" + tenant.idProduto()).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(jsonPath("$.precoCusto").value(50.00))
                .andExpect(jsonPath("$.precoVenda").value(100.00))
                .andExpect(jsonPath("$.reajustadoEm").exists());
    }

    @Test
    void chaveNfeDuplicadaRespondeConflitoENaoDuplicaEstoque() throws Exception {
        TenantENota tenant = prepararTenantComProduto("chave-duplicada");
        String corpo = """
                {"idFornecedor":%d,"chaveNfe":"35260812345678000199550010000001231234567890",
                 "itens":[{"idVariacao":%d,"qtd":2,"precoCusto":10.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());

        try (Connection c = abrirConexao(tenant.idTenant())) {
            assertThat(buscarQtdEstoque(c, tenant.idVariacao())).isEqualByComparingTo("2.000");
        }
    }

    @Test
    void fornecedorInexistenteRespondeNaoEncontrado() throws Exception {
        TenantENota tenant = prepararTenantComProduto("fornecedor-inexistente");
        String corpo = """
                {"idFornecedor":999999,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}]}
                """.formatted(tenant.idVariacao());

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isNotFound());
    }

    @Test
    void editarItemAjustaEstoqueCorretamente() throws Exception {
        TenantENota tenant = prepararTenantComProduto("edicao");
        String corpo = """
                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":2,"precoCusto":10.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());
        String resp = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idMovimento = ((Number) JsonPath.read(resp, "$.idMovimento")).longValue();

        String detalhe = mvc.perform(get("/api/v1/estoque/entradas/" + idMovimento)
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idMovimentoDetalhe = ((Number) JsonPath.read(detalhe, "$.itens[0].idMovimentoDetalhe")).longValue();

        try (Connection c = abrirConexao(tenant.idTenant())) {
            assertThat(buscarQtdEstoque(c, tenant.idVariacao())).isEqualByComparingTo("2.000");
        }

        mvc.perform(put("/api/v1/estoque/entradas/" + idMovimento + "/itens/" + idMovimentoDetalhe)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"qtd\":5,\"precoCusto\":10.00}"))
                .andExpect(status().isOk());

        try (Connection c = abrirConexao(tenant.idTenant())) {
            assertThat(buscarQtdEstoque(c, tenant.idVariacao())).isEqualByComparingTo("5.000");
        }
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantENota tenantA = prepararTenantComProduto("isolamento-a");
        String tokenB = assinarNovoTenant("isolamento-b");

        String corpo = """
                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}]}
                """.formatted(tenantA.idFornecedor(), tenantA.idVariacao());
        String resp = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenantA.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idMovimento = ((Number) JsonPath.read(resp, "$.idMovimento")).longValue();

        mvc.perform(get("/api/v1/estoque/entradas/" + idMovimento).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /** Filtros da grid principal (2026-08-19, popup obrigatório "Fornecedor, Empresa, Nota
     *  Fiscal, Data Início/Fim") — idFornecedor/notaFiscal já eram cobertos; idEmpresa e o
     *  período (dataInicial/dataFinal) são novos. */
    @Test
    void filtraPorEmpresaEPorPeriodo() throws Exception {
        TenantSlug tenant = assinarComSlug("filtros");
        long idFornecedor = criarFornecedor(tenant.token(), "Fornecedor Filtros");
        long idProduto = criarProduto(tenant.token(), "Produto Filtros");
        long idVariacao = criarVariacao(tenant.token(), idProduto);
        long idTenant = extrairIdTenant(tenant.token());
        long idPrimeiraEmpresa = buscarPrimeiraEmpresa(tenant.token());
        long idSegundaEmpresa = inserirSegundaEmpresa(idTenant, "SEGUNDA EMPRESA FILTROS");

        // Data explícita (não "agora") — evita flakiness perto da virada do dia: o filtro
        // bucketiza por dia CIVIL de Brasília (AT TIME ZONE 'America/Sao_Paulo'), não UTC, então
        // comparar contra `LocalDate.now()` do processo de teste (que pode estar em UTC) seria
        // instável entre ~21h-23h59 de Brasília.
        String corpoRecente = """
                {"idFornecedor":%d,"idEmpresa":%d,"dataMovimento":"2026-06-15",
                 "itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}]}
                """.formatted(idFornecedor, idPrimeiraEmpresa, idVariacao);
        String respRecente = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoRecente))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idMovimentoRecente = ((Number) JsonPath.read(respRecente, "$.idMovimento")).longValue();

        String corpoAntigo = """
                {"idFornecedor":%d,"idEmpresa":%d,"dataMovimento":"2020-01-01",
                 "itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}]}
                """.formatted(idFornecedor, idSegundaEmpresa, idVariacao);
        String respAntigo = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoAntigo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idMovimentoAntigo = ((Number) JsonPath.read(respAntigo, "$.idMovimento")).longValue();

        // idEmpresa: só a entrada da segunda empresa.
        String respFiltroEmpresa = mvc.perform(get("/api/v1/estoque/entradas?idEmpresa=" + idSegundaEmpresa)
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(respFiltroEmpresa, "$.itens[0].idMovimento")).longValue())
                .isEqualTo(idMovimentoAntigo);

        // Período: só a entrada recente (a antiga é de 2020, fora da janela).
        String respFiltroPeriodo = mvc.perform(get("/api/v1/estoque/entradas?dataInicial=2026-06-15&dataFinal=2026-06-15")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(respFiltroPeriodo, "$.itens[0].idMovimento")).longValue())
                .isEqualTo(idMovimentoRecente);

        // Fora do período (2019 inteiro): nenhuma.
        mvc.perform(get("/api/v1/estoque/entradas?dataInicial=2019-01-01&dataFinal=2019-12-31")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(0));
    }

    private record TenantENota(String token, long idTenant, long idFornecedor, long idProduto, long idVariacao) {
    }

    private TenantENota prepararTenantComProduto(String sufixo) throws Exception {
        String token = assinarNovoTenant(sufixo);
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token, "Fornecedor " + sufixo);
        long idProduto = criarProduto(token, "Produto " + sufixo);
        long idVariacao = criarVariacao(token, idProduto);
        return new TenantENota(token, idTenant, idFornecedor, idProduto, idVariacao);
    }

    // ------------------------------------------------------------------------------------
    // idEmpresa (2026-08-12) — ADMIN escolhe qualquer empresa do tenant, OPERADOR só as
    // liberadas pra ele; ausente cai no `eid` da sessão (já coberto pelos testes acima, que
    // nunca informam idEmpresa e continuam passando).
    // ------------------------------------------------------------------------------------

    private long buscarPrimeiraEmpresa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$[0].idEmpresa")).longValue();
    }

    /** Não existe endpoint de criação de empresa (schema é 1:N mas o v1 só usa 1:1, CLAUDE.md) —
     *  insere direto via SQL pra testar a seleção de empresa, mesmo idioma de {@link
     *  #abrirConexao} (role `niner_app`, RLS respeitada via `app.id_tenant`). */
    private long inserirSegundaEmpresa(long idTenant, String razaoSocial) throws Exception {
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta) "
                             + "VALUES (?, 2, ?, '{sku}') RETURNING id_empresa")) {
            ps.setLong(1, idTenant);
            ps.setString(2, razaoSocial);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private record TenantSlug(String token, String slug) {
    }

    private TenantSlug assinarComSlug(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Entrada %s","email":"dono%s@lojaentrada.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TenantSlug(JsonPath.read(resp, "$.token"), JsonPath.read(resp, "$.slug"));
    }

    private long criarOperador(String tokenAdmin, String email, long idEmpresa) throws Exception {
        String resp = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nome":"Operador Teste","email":"%s","senha":"segredo123",
                                 "administrador":false,"idsEmpresa":[%d]}
                                """.formatted(email, idEmpresa)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idUsuario")).longValue();
    }

    private String logarComo(String slug, String email) throws Exception {
        String resp = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON)
                        .content("{\"slug\":\"%s\",\"email\":\"%s\",\"senha\":\"segredo123\"}".formatted(slug, email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void adminEscolheOutraEmpresaDoTenant() throws Exception {
        TenantSlug tenant = assinarComSlug("empresa-admin");
        long idFornecedor = criarFornecedor(tenant.token(), "Fornecedor Empresa Admin");
        long idProduto = criarProduto(tenant.token(), "Produto Empresa Admin");
        long idVariacao = criarVariacao(tenant.token(), idProduto);
        long idTenant = extrairIdTenant(tenant.token());
        long idSegundaEmpresa = inserirSegundaEmpresa(idTenant, "SEGUNDA EMPRESA");

        String corpo = """
                {"idFornecedor":%d,"idEmpresa":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}]}
                """.formatted(idFornecedor, idSegundaEmpresa, idVariacao);
        String resp = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idMovimento = ((Number) JsonPath.read(resp, "$.idMovimento")).longValue();

        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement("SELECT id_empresa FROM produto_movimento_mestre WHERE id_movimento = ?")) {
            ps.setLong(1, idMovimento);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong("id_empresa")).isEqualTo(idSegundaEmpresa);
            }
        }
    }

    @Test
    void idEmpresaInexistenteRespondeNaoEncontrado() throws Exception {
        TenantENota tenant = prepararTenantComProduto("empresa-inexistente");
        String corpo = """
                {"idFornecedor":%d,"idEmpresa":999999,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isNotFound());
    }

    @Test
    void operadorNaoPodeDarEntradaEmEmpresaNaoLiberada() throws Exception {
        TenantSlug tenant = assinarComSlug("empresa-operador");
        long idFornecedor = criarFornecedor(tenant.token(), "Fornecedor Empresa Operador");
        long idProduto = criarProduto(tenant.token(), "Produto Empresa Operador");
        long idVariacao = criarVariacao(tenant.token(), idProduto);
        long idTenant = extrairIdTenant(tenant.token());
        long idPrimeiraEmpresa = buscarPrimeiraEmpresa(tenant.token());
        long idSegundaEmpresa = inserirSegundaEmpresa(idTenant, "EMPRESA NAO LIBERADA");

        criarOperador(tenant.token(), "operador@lojaentrada.com", idPrimeiraEmpresa);
        String tokenOperador = logarComo(tenant.slug(), "operador@lojaentrada.com");

        String corpo = """
                {"idFornecedor":%d,"idEmpresa":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}]}
                """.formatted(idFornecedor, idSegundaEmpresa, idVariacao);
        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isNotFound());

        // Na própria empresa liberada, funciona normalmente.
        String corpoOk = """
                {"idFornecedor":%d,"idEmpresa":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}]}
                """.formatted(idFornecedor, idPrimeiraEmpresa, idVariacao);
        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content(corpoOk))
                .andExpect(status().isCreated());
    }
}
