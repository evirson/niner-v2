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
 * Devolução de Produtos (docs/telas/devolucao-produtos.md) — sem o número da venda, devolve
 * estoque livremente (sem vínculo com nenhuma venda). Com o número da venda, além de resolver o
 * vendedor (gravado em cada linha do movimento para uma futura comissão), a partir de 2026-08-11
 * só é permitido devolver produtos que ela vendeu, até a quantidade ainda não devolvida dela
 * (validado no servidor, não só na tela). ADMIN e OPERADOR têm acesso (sem restrição de papel).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DevolucaoProdutoCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Devolucao %s","email":"dono%s@lojadevolucao.com",
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

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"50.00"}
                                """.formatted(descricao)))
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
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
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

    /** Efetiva uma venda de 1 unidade via PDV de verdade (não SQL bruto) — devolve o id_venda. */
    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                                long idCarteira, String valorPago, int numeroParcelas) throws Exception {
        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":%d}]}
                """.formatted(idVariacao, idCliente, idFuncionario, idCarteira, valorPago, numeroParcelas);
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    /** `assinarNovoTenant` já devolve token ADMIN — PUT exige o corpo inteiro (sem campo nullable). */
    private void definirExigeNumeroVendaDevolucao(String token, boolean exige) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                                 "cfgPermiteQtdDecimal":true,"cfgPermiteEstoqueNegativo":true,"cfgDiasValidadeOrcamento":15,"cfgExigeNumeroVendaDevolucao":%s,
                                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,"cfgConsisteValorContasPagar":false,
                                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":true}
                                """.formatted(exige)))
                .andExpect(status().isOk());
    }

    private int contarLinhas(Connection c, String sql, long param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void devolverSemNumeroDeVendaDevolveEstoqueSemVendedor() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-venda");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Devolucao Sem Venda");

        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("3.000"));
        }

        String corpo = """
                {"numeroVenda":null,"itens":[{"idVariacao":%d,"qtd":2}]}
                """.formatted(idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idFuncionario").doesNotExist())
                .andExpect(jsonPath("$.itens.length()").value(1));

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("5.000");
            assertThat(contarLinhas(c,
                    "SELECT count(*) FROM produto_movimento_detalhe pmd JOIN produto_movimento_mestre pmm "
                            + "ON pmm.id_movimento = pmd.id_movimento WHERE pmd.id_variacao = ? AND pmm.tipo_movimento = 'DEVOLUCAO'",
                    idVariacao)).isEqualTo(1);
        }
    }

    @Test
    void devolverSemNumeroDeVendaQuandoConfiguracaoExigeRespondeDadoInvalido() throws Exception {
        TenantNovo tenant = assinarNovoTenant("exige-numero-venda");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Exige Numero Venda");
        definirExigeNumeroVendaDevolucao(tenant.token(), true);

        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("3.000"));
        }

        String corpo = """
                {"numeroVenda":null,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Informe o número da venda")));

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("3.000");
        }
    }

    @Test
    void devolverComNumeroDeVendaResolveEGravaVendedorNaLinha() throws Exception {
        TenantNovo tenant = assinarNovoTenant("com-venda");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Devolucao Com Venda");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO DEVOLUCAO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Devolucao Com Venda");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Devolucao Com Venda");
        abrirCaixaDinheiro(tenant.token());

        long idVariacao;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        mvc.perform(get("/api/v1/vendas/devolucao/vendedor").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", String.valueOf(idVenda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idFuncionario").value(idFuncionario))
                .andExpect(jsonPath("$.nomeFuncionario").value("VENDEDOR DEVOLUCAO COM VENDA"));

        String corpo = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVenda, idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idFuncionario").value(idFuncionario));

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
            assertThat(contarLinhas(c,
                    "SELECT count(*) FROM produto_movimento_detalhe pmd JOIN produto_movimento_mestre pmm "
                            + "ON pmm.id_movimento = pmd.id_movimento WHERE pmd.id_variacao = ? AND pmm.tipo_movimento = 'DEVOLUCAO' "
                            + "AND pmd.id_funcionario = " + idFuncionario,
                    idVariacao)).isEqualTo(1);
            // O número da venda não fica gravado em lugar nenhum do movimento de devolução.
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) FROM produto_movimento_mestre WHERE tipo_movimento = 'DEVOLUCAO' AND id_venda IS NOT NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void buscarVendedorDeNumeroDeVendaInexistenteRespondeNaoEncontrada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("venda-inexistente");
        mvc.perform(get("/api/v1/vendas/devolucao/vendedor").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", "999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void devolverComProdutoInexistenteRespondeNaoEncontrada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("produto-inexistente");
        String corpo = """
                {"numeroVenda":null,"itens":[{"idVariacao":999999,"qtd":1}]}
                """;
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isNotFound());
    }

    @Test
    void vendedorDaVendaTrazItensVendidosComQuantidadeDisponivelParaDevolucao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("itens-venda-origem");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Itens Venda Origem");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO ITENS VENDA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Itens Venda Origem");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Itens Venda Origem");
        abrirCaixaDinheiro(tenant.token());

        long idVariacao;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        mvc.perform(get("/api/v1/vendas/devolucao/vendedor").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", String.valueOf(idVenda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].idVariacao").value(idVariacao))
                .andExpect(jsonPath("$.itens[0].qtdVendida").value(1))
                .andExpect(jsonPath("$.itens[0].qtdDisponivelDevolucao").value(1))
                .andExpect(jsonPath("$.itens[0].precoUnitario").value(50.00))
                .andExpect(jsonPath("$.itens[0].valorTotal").value(50.00));
    }

    /**
     * ⚠️ <b>Teste que discrimina de verdade</b> (2026-08-19): o preço do CADASTRO é alterado
     * depois da venda, então usar `produto.preco_venda` (comportamento até esta data) e usar o
     * preço da venda original dão resultados diferentes — sem essa divergência artificial, todos
     * os outros testes desta classe passam com as duas implementações (é exatamente o que
     * acontecia: a mudança de preço original entrou sem quebrar um único teste existente).
     *
     * <p>O vale-mercadoria tem que valer o que o cliente PAGOU, não o que o produto custa hoje —
     * e, a partir da NF-e de devolução (spec, revisão 2026-08-19), a nota de entrada precisa
     * espelhar os valores da nota de saída original, senão a SEFAZ recebe uma devolução com valor
     * diferente da venda que ela referencia.
     */
    @Test
    void devolucaoUsaOPrecoDaVendaOriginalMesmoDepoisDeOPrecoDoCadastroMudar() throws Exception {
        TenantNovo tenant = assinarNovoTenant("preco-da-venda");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Preco Mudou");   // cadastro nasce a 50,00
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO PRECO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Preco Mudou");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Preco Mudou");
        abrirCaixaDinheiro(tenant.token());

        long idVariacao;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);

            // Só agora o preço do cadastro muda — a venda já aconteceu a 50,00.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE produto SET preco_venda = 80.00, preco_custo = 40.00 WHERE id_produto = ?")) {
                ps.setLong(1, idProduto);
                ps.executeUpdate();
            }
        }

        // A grid de seleção da tela mostra o preço da VENDA (50,00), não o novo do cadastro (80,00).
        mvc.perform(get("/api/v1/vendas/devolucao/vendedor").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", String.valueOf(idVenda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].precoUnitario").value(50.00));

        String corpo = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVenda, idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valorVale").value(50.00))
                .andExpect(jsonPath("$.itens[0].precoVenda").value(50.00));

        // E o que ficou GRAVADO no movimento também é o preço da venda — é dele que saem o valor
        // do vale (sempre derivado) e o CMV do DRE.
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement("""
                     SELECT pmd.preco_venda, pmd.preco_custo
                       FROM produto_movimento_mestre pmm
                       JOIN produto_movimento_detalhe pmd ON pmd.id_movimento = pmm.id_movimento
                      WHERE pmm.tipo_movimento = 'DEVOLUCAO' AND pmd.id_variacao = ?
                     """)) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBigDecimal("preco_venda"))
                        .as("preço da venda original (50,00), nunca o do cadastro alterado depois (80,00)")
                        .isEqualByComparingTo("50.00");
                assertThat(rs.getBigDecimal("preco_custo"))
                        .as("custo da venda original (10,00), nunca o do cadastro alterado depois (40,00)")
                        .isEqualByComparingTo("10.00");
            }
        }
    }

    /** Sem venda de origem não há outro valor possível — cai no preço do cadastro, como sempre foi. */
    @Test
    void devolucaoSemNumeroDeVendaUsaOPrecoAtualDoCadastro() throws Exception {
        TenantNovo tenant = assinarNovoTenant("preco-cadastro");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Preco Cadastro");

        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE produto SET preco_venda = 80.00 WHERE id_produto = ?")) {
                ps.setLong(1, idProduto);
                ps.executeUpdate();
            }
        }

        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"itens\":[{\"idVariacao\":%d,\"qtd\":1}]}".formatted(idVariacao)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valorVale").value(80.00));
    }

    @Test
    void devolverProdutoQueNaoFezParteDaVendaInformadaRespondeDadoInvalido() throws Exception {
        TenantNovo tenant = assinarNovoTenant("produto-fora-da-venda");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoVendido = criarProduto(tenant.token(), "Produto Vendido Fora Venda");
        long idProdutoNaoVendido = criarProduto(tenant.token(), "Produto Nao Vendido Fora Venda");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO FORA VENDA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Fora Venda");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Fora Venda");
        abrirCaixaDinheiro(tenant.token());

        long idVariacaoVendida;
        long idVariacaoNaoVendida;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacaoVendida = criarVariacao(c, idTenant, idProdutoVendido);
            idVariacaoNaoVendida = criarVariacao(c, idTenant, idProdutoNaoVendido);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoVendida, new BigDecimal("5.000"));
            definirEstoque(c, idTenant, idEmpresa, idVariacaoNaoVendida, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacaoVendida, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        String corpo = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVenda, idVariacaoNaoVendida);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("não faz parte da venda")));

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(buscarQtdEstoque(c, idVariacaoNaoVendida)).isEqualByComparingTo("5.000");
        }
    }

    @Test
    void devolverQuantidadeMaiorQueAVendidaRespondeDadoInvalido() throws Exception {
        TenantNovo tenant = assinarNovoTenant("qtd-maior-que-vendida");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Qtd Maior Vendida");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO QTD MAIOR", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Qtd Maior");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Qtd Maior");
        abrirCaixaDinheiro(tenant.token());

        long idVariacao;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        String corpo = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":2}]}
                """.formatted(idVenda, idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("maior que a disponível")));
    }

    @Test
    void devolucaoAnteriorDaMesmaVendaReduzQuantidadeDisponivelParaAProxima() throws Exception {
        TenantNovo tenant = assinarNovoTenant("reduz-disponivel");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Reduz Disponivel");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO REDUZ DISPONIVEL", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Reduz Disponivel");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Reduz Disponivel");
        abrirCaixaDinheiro(tenant.token());

        long idVariacao;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        // Devolve 1 das 2 unidades vendidas — 1 unidade ainda deve estar disponível.
        String corpoPrimeiraDevolucao = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVenda, idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoPrimeiraDevolucao))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/vendas/devolucao/vendedor").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", String.valueOf(idVenda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].qtdDisponivelDevolucao").value(0));

        // A segunda tentativa de devolver mais 1 unidade (só sobrava 0) deve ser rejeitada.
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoPrimeiraDevolucao))
                .andExpect(status().isBadRequest());
    }

    @Test
    void numeroDeVendaDeOutroTenantNaoResolveVendedor() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-devolucao-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-devolucao-b");
        long idTenantA = extrairIdTenant(tenantA.token());
        long idProduto = criarProduto(tenantA.token(), "Produto Isolamento Devolucao");
        long idCarteira = criarTipoCarteira(tenantA.token(), "DINHEIRO ISOLAMENTO DEVOLUCAO", "AVISTA");
        long idCliente = criarCliente(tenantA.token(), "Cliente Isolamento Devolucao");
        long idFuncionario = criarFuncionario(tenantA.token(), "Vendedor Isolamento Devolucao");
        abrirCaixaDinheiro(tenantA.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenantA, idProduto);
            definirEstoque(c, idTenantA, idEmpresa, idVariacao, new BigDecimal("5.000"));
            idVenda = efetivarVenda(tenantA.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        mvc.perform(get("/api/v1/vendas/devolucao/vendedor").header("Authorization", "Bearer " + tenantB.token())
                        .param("numeroVenda", String.valueOf(idVenda)))
                .andExpect(status().isNotFound());
    }
/**
     * ⚠️ Regressão do item 2 da auditoria de 2026-08-21 (corrigido em 2026-08-22): a mesma
     * variação em DUAS linhas da venda, com preços diferentes, fazia o vale sair pela MÉDIA.
     *
     * <p>O cenário virou comum com o orçamento: o cliente leva a peça que orçou (preço congelado,
     * que a loja honra) e mais uma pelo preço do dia. Antes desta correção, devolver UMA peça de
     * uma venda 1×R$ 50 + 1×R$ 120 gerava vale de R$ 85,00 — valor que a venda nunca praticou —,
     * e o mesmo R$ 85 ia para a NF-e 55 de devolução.
     *
     * <p>A segunda linha é inserida direto no movimento porque é o estado que o PDV do orçamento
     * produz; o que está sob teste aqui é a DEVOLUÇÃO, não o caminho que gravou a venda.
     */
    @Test
    void devolucaoDeVariacaoComDoisPrecosNaMesmaVendaUsaOPrecoDaLinhaEscolhida() throws Exception {
        TenantNovo tenant = assinarNovoTenant("duas-linhas");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Dois Precos");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO DOIS PRECOS", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Dois Precos");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Dois Precos");
        abrirCaixaDinheiro(tenant.token());

        long idVariacao;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);

            // Segunda linha da MESMA variação, a outro preço — o que a venda com orçamento produz.
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO produto_movimento_detalhe
                        (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto,
                         preco_venda, preco_custo, id_funcionario, origem)
                    SELECT ?, pmm.id_movimento, ?, ?, 'D', 1, 120.00, 10.00, ?, 'venda'
                      FROM produto_movimento_mestre pmm
                     WHERE pmm.id_tenant = ? AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                    """)) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idEmpresa);
                ps.setLong(3, idVariacao);
                ps.setLong(4, idFuncionario);
                ps.setLong(5, idTenant);
                ps.setLong(6, idVenda);
                ps.executeUpdate();
            }
        }

        // A grid passa a listar UMA LINHA POR PREÇO — antes vinha uma só, com a média (85,00).
        mvc.perform(get("/api/v1/vendas/devolucao/vendedor").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", String.valueOf(idVenda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].precoUnitario").value(50.00))
                .andExpect(jsonPath("$.itens[1].precoUnitario").value(120.00));

        // Devolvendo a peça de 120,00, o vale é de 120,00 — NUNCA a média de 85,00.
        String corpo = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoUnitario":120.00}]}
                """.formatted(idVenda, idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valorVale").value(120.00))
                .andExpect(jsonPath("$.itens[0].precoVenda").value(120.00));

        // E o gravado no movimento é 120,00 — é dele que saem o vale e o item da NF-e 55.
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement("""
                     SELECT pmd.preco_venda
                       FROM produto_movimento_mestre pmm
                       JOIN produto_movimento_detalhe pmd ON pmd.id_movimento = pmm.id_movimento
                      WHERE pmm.tipo_movimento = 'DEVOLUCAO' AND pmd.id_variacao = ?
                     """)) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBigDecimal("preco_venda"))
                        .as("preço da LINHA devolvida (120,00), nunca a média das duas linhas (85,00)")
                        .isEqualByComparingTo("120.00");
            }
        }

        // O saldo consumido é o DAQUELA linha: a de 50,00 continua inteira, e a de 120,00 zerou.
        mvc.perform(get("/api/v1/vendas/devolucao/vendedor").header("Authorization", "Bearer " + tenant.token())
                        .param("numeroVenda", String.valueOf(idVenda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].precoUnitario").value(50.00))
                .andExpect(jsonPath("$.itens[0].qtdDisponivelDevolucao").value(1))
                .andExpect(jsonPath("$.itens[1].precoUnitario").value(120.00))
                .andExpect(jsonPath("$.itens[1].qtdDisponivelDevolucao").value(0));
    }
    /**
     * ⛔ <b>Venda cancelada não pode virar vale-mercadoria</b> (auditoria de segurança, 2026-08-27).
     *
     * <p>O teto de "não devolver mais do que foi vendido" existia e não pegava isto: ele mede
     * contra os <b>itens</b> da venda, e cancelar não apaga esses itens — o cancelamento grava um
     * movimento próprio. Então vender uma peça, cancelar (o estoque volta e o dinheiro sai do
     * caixa) e em seguida "devolver" a mesma venda gerava um vale <b>integral</b> por mercadoria já
     * reembolsada e já de volta na prateleira. A loja pagava duas vezes pela mesma peça.
     */
    @Test
    void naoDeixaDevolverVendaJaCancelada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("venda-cancelada");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProduto(tenant.token(), "Produto Venda Cancelada");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO CANCELADA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Venda Cancelada");
        long idFuncionario = criarFuncionario(tenant.token(), "Vendedor Venda Cancelada");
        abrirCaixaDinheiro(tenant.token());

        long idVariacao;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "50.00", 1);
        }

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"Cliente desistiu da compra no balcao\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                                """.formatted(idVenda, idVariacao)))
                .andExpect(status().isConflict());

        // ⚠️ Conferir o BANCO: nenhum vale pode ter nascido. Um teste que só valida 409 passaria
        // se a recusa viesse depois da gravação.
        try (Connection c = abrirConexao(idTenant)) {
            assertThat(contarLinhas(c, "SELECT count(*) FROM venda_devolucao WHERE id_venda_credito = ?", idVenda))
                    .as("venda cancelada não pode gerar vale-mercadoria")
                    .isZero();
            // E o estoque continua o que o cancelamento deixou — sem crédito em dobro.
            assertThat(buscarQtdEstoque(c, idVariacao)).isEqualByComparingTo("10.000");
        }
    }
}
