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
import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de Comissões (docs/telas/relatorio-comissoes.md) — venda menos devolução, vezes o
 * percentual de comissão do funcionário, por (empresa, funcionário) dentro do período.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioComissoesCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, String slug) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Comissao %s","email":"dono%s@lojacomissao.com",
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

    private long criarProdutoComPreco(String token, String descricao, String precoVenda) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"%s"}
                                """.formatted(descricao, precoVenda)))
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

    private long criarFuncionarioComComissao(String token, String nome, String percComissao) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"%s\",\"percComissao\":%s}".formatted(nome, percComissao)))
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

    /**
     * ⚠️ Obter-ou-criar, não criar (2026-08-31). Desde que o serviço passa a nascer com a própria
     * variação — sem ela ficava invisível na Ordem de Serviço e no PDV —, o INSERT cego bate em
     * produto_barra_variacao_uk. Este helper não estava errado: criava a variação à mão porque o
     * cadastro não criava, e o que os testes precisam é TER uma.
     */
    private long criarVariacao(Connection c, long idTenant, long idProduto) throws SQLException {
        try (PreparedStatement busca = c.prepareStatement(
                "SELECT id_variacao FROM produto_barra WHERE id_produto = ? LIMIT 1")) {
            busca.setLong(1, idProduto);
            try (ResultSet rs = busca.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

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

    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                                long idCarteira, String valorPago) throws Exception {
        String corpo = """
                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,"idFuncionario":%d,
                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                """.formatted(idVariacao, idCliente, idFuncionario, idCarteira, valorPago);
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    private String hojeISO() {
        return java.time.LocalDate.now().toString();
    }

    @Test
    void vendaSemDevolucaoCalculaComissaoSobreValorLiquido() throws Exception {
        TenantNovo tenant = assinarNovoTenant("simples");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Comissao Simples", "200.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO COMISSAO SIMPLES", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Comissao Simples");
        long idFuncionario = criarFuncionarioComComissao(tenant.token(), "Vendedor Comissao Simples", "10.00");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "200.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].nomeFuncionario").value("VENDEDOR COMISSAO SIMPLES"))
                .andExpect(jsonPath("$.linhas[0].valorVenda").value(200.00))
                .andExpect(jsonPath("$.linhas[0].valorDevolucao").value(0))
                .andExpect(jsonPath("$.linhas[0].valorLiquido").value(200.00))
                .andExpect(jsonPath("$.linhas[0].percComissao").value(10.00))
                .andExpect(jsonPath("$.linhas[0].valorComissao").value(20.00))
                .andExpect(jsonPath("$.totalGeral.valorComissao").value(20.00));
    }

    /**
     * ⭐ DS5, parte 1 — o percentual usado é o CONGELADO no dia da venda (V088).
     *
     * <p>Este é o par que a decisão cobra explicitamente: <b>venda antiga não muda de valor</b>
     * quando o percentual do funcionário é editado. Antes da V088 o relatório calculava
     * `líquido × funcionario.perc_comissao` na consulta, então promover o vendedor de 10% para
     * 25% <b>reescrevia a comissão de todos os meses passados</b> — inclusive os já pagos, que
     * deixavam de bater com a folha que os originou.
     */
    @Test
    void editarOPercentualDoFuncionarioNaoMudaAComissaoJaVendida() throws Exception {
        TenantNovo tenant = assinarNovoTenant("congelada");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Comissao Congelada", "200.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO CONGELADA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Congelada");
        long idFuncionario = criarFuncionarioComComissao(tenant.token(), "Vendedor Congelada", "10.00");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "200.00");
        }

        String hoje = hojeISO();
        // A venda saiu com 10% — R$ 20,00.
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(jsonPath("$.linhas[0].valorComissao").value(20.00));

        // O vendedor é promovido a 25% HOJE.
        mvc.perform(put("/api/v1/funcionarios/" + idFuncionario).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"VENDEDOR CONGELADA\",\"percComissao\":25.00}"))
                .andExpect(status().isOk());

        // …e a venda de antes continua valendo R$ 20,00, não R$ 50,00.
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas[0].valorComissao").value(20.00))
                .andExpect(jsonPath("$.linhas[0].percComissao").value(10.00));
    }

    /**
     * ⭐ DS5, parte 2 — a comissão do SERVIÇO vence a da pessoa.
     *
     * <p>O tosador ganha 20% do banho e 10% da tosa: é a prática de mercado, e é o que
     * {@code produto_servico.perc_comissao} passou a permitir. Aqui o funcionário tem 10% no
     * cadastro e o serviço tem 20% — a comissão sai R$ 40,00, não R$ 20,00.
     *
     * <p>⚠️ O caso NEGATIVO está no teste acima ({@code vendaSemDevolucaoCalculaComissaoSobreValor
     * Liquido}), que vende uma <b>mercadoria</b> e continua usando os 10% do funcionário — sem ele,
     * este teste passaria com uma implementação que aplicasse 20% a tudo.
     */
    @Test
    void aComissaoDoServicoVenceADoFuncionario() throws Exception {
        TenantNovo tenant = assinarNovoTenant("comissao-servico");
        long idTenant = extrairIdTenant(tenant.token());
        ligarModuloDeServicos(tenant.token());

        String corpoServico = """
                {"descricao":"BANHO E TOSA","precoCusto":"0","percentualVenda":"0","precoVenda":"200.00",
                 "tipoItem":"SERVICO","percComissaoServico":20.00}
                """;
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoServico))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long idProduto = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO SERVICO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente do Banho");
        long idFuncionario = criarFuncionarioComComissao(tenant.token(), "Tosador", "10.00");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            // ⚠️ Serviço NÃO precisa de estoque — é justamente o que a V086 garante. Se este teste
            // exigisse definirEstoque(), ele estaria provando o contrário do que o módulo decidiu.
            efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "200.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas[0].valorVenda").value(200.00))
                .andExpect(jsonPath("$.linhas[0].valorComissao").value(40.00))   // 20% do serviço…
                .andExpect(jsonPath("$.linhas[0].percComissao").value(20.00));   // …e não os 10% dele
    }

    private void ligarModuloDeServicos(String token) throws Exception {
        String atual = mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(atual.replaceFirst("\"cfgUsaServicos\":\s*false", "\"cfgUsaServicos\":true")))
                .andExpect(status().isOk());
    }

    @Test
    void devolucaoComVendedorIdentificadoReduzValorLiquidoEComissao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("com-devolucao");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Comissao Devolucao", "100.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO COMISSAO DEVOLUCAO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Comissao Devolucao");
        long idFuncionario = criarFuncionarioComComissao(tenant.token(), "Vendedor Comissao Devolucao", "10.00");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "100.00");
        }

        // Devolve o mesmo item, identificando o vendedor pelo número da venda.
        String corpoDevolucao = """
                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                """.formatted(idVenda, idVariacao);
        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpoDevolucao))
                .andExpect(status().isCreated());

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(1))
                .andExpect(jsonPath("$.linhas[0].valorVenda").value(100.00))
                .andExpect(jsonPath("$.linhas[0].valorDevolucao").value(100.00))
                .andExpect(jsonPath("$.linhas[0].valorLiquido").value(0))
                .andExpect(jsonPath("$.linhas[0].valorComissao").value(0));
    }

    /**
     * ⭐ <b>A DRE e a Lucratividade contam a MESMA história que a folha.</b>
     *
     * <p>⛔ Até 2026-08-29 não contavam: as duas <b>nunca estornavam a comissão da devolução</b> —
     * a consulta de devoluções nem selecionava a coluna. Com o vendedor a 10%, vender R$ 100 e
     * receber tudo de volta no mesmo mês dava <b>R$ 0,00</b> no Relatório de Comissões (o número
     * que a loja PAGA) e <b>R$ 10,00 de despesa de comissão</b> na DRE — um mês que não movimentou
     * nada fechava com prejuízo. O javadoc da Lucratividade ainda <i>afirmava</i> que não estornar
     * era a regra, "igual à DRE": estava alinhado ao relatório errado.
     *
     * <p>⚠️ Este teste é um <b>cruzamento</b>, não uma conta isolada: ele compara os três
     * relatórios sobre a mesma massa. É o formato que pegou o defeito, porque cada número sozinho
     * era plausível.
     */
    @Test
    void devolucaoEstornaAComissaoTambemNaDreENaLucratividade() throws Exception {
        TenantNovo tenant = assinarNovoTenant("comissao-dre");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Comissao DRE", "100.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO COMISSAO DRE", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Comissao DRE");
        long idFuncionario = criarFuncionarioComComissao(tenant.token(), "Vendedor Comissao DRE", "10.00");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        long idVariacao;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "100.00");
        }

        String hoje = hojeISO();

        // Antes da devolução os três concordam: R$ 10,00 de comissão.
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(jsonPath("$.linhas[0].valorComissao").value(10.00));
        org.assertj.core.api.Assertions.assertThat(comissaoNaDre(tenant.token(), hoje))
                .as("antes da devolução a DRE mostra a mesma comissão da folha")
                .isEqualTo(-10.00);

        mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                                """.formatted(idVenda, idVariacao)))
                .andExpect(status().isCreated());

        // Depois: a folha zera, e os outros dois TÊM de zerar junto.
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(jsonPath("$.linhas[0].valorComissao").value(0));
        org.assertj.core.api.Assertions.assertThat(comissaoNaDre(tenant.token(), hoje))
                .as("a devolução estorna a comissão na DRE, como já estornava na folha")
                .isEqualTo(0.0);
        org.assertj.core.api.Assertions.assertThat(comissaoNaLucratividade(tenant.token(), hoje))
                .as("e na Lucratividade também")
                .isEqualTo(0.0);
    }

    /** A linha "Comissões sobre Vendas" da DRE de competência (conta 3.02.001, valor negativo). */
    private double comissaoNaDre(String token, String dia) throws Exception {
        String json = mvc.perform(get("/api/v1/relatorios/dre").header("Authorization", "Bearer " + token)
                        .param("dataInicial", dia).param("dataFinal", dia).param("regime", "COMPETENCIA"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<Double> valores = JsonPath.read(json, "$.linhas[?(@.chave=='3.02.001')].valor");
        return valores.isEmpty() ? 0d : valores.get(0);
    }

    /** A despesa de comissão da Lucratividade — a linha derivada, não a de plano de contas. */
    private double comissaoNaLucratividade(String token, String dia) throws Exception {
        String json = mvc.perform(get("/api/v1/relatorios/lucratividade").header("Authorization", "Bearer " + token)
                        .param("dataInicial", dia).param("dataFinal", dia))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<Double> valores =
                JsonPath.read(json, "$.despesas[?(@.descricao =~ /.*omiss.*/i)].valor");
        return valores.isEmpty() ? 0d : valores.stream().mapToDouble(Double::doubleValue).sum();
    }

    @Test
    void vendaCanceladaNaoEntraNoRelatorio() throws Exception {
        TenantNovo tenant = assinarNovoTenant("cancelada");
        long idTenant = extrairIdTenant(tenant.token());
        long idProduto = criarProdutoComPreco(tenant.token(), "Produto Comissao Cancelada", "150.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO COMISSAO CANCELADA", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Comissao Cancelada");
        long idFuncionario = criarFuncionarioComComissao(tenant.token(), "Vendedor Comissao Cancelada", "5.00");
        abrirCaixaDinheiro(tenant.token());

        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            definirEstoque(c, idTenant, idEmpresa, idVariacao, new BigDecimal("10.000"));
            idVenda = efetivarVenda(tenant.token(), idVariacao, idCliente, idFuncionario, idCarteira, "150.00");
        }

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"teste\"}"))
                .andExpect(status().isOk());

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(0))
                .andExpect(jsonPath("$.totalGeral.valorComissao").value(0));
    }

    @Test
    void periodoObrigatorioRespondeErroDeValidacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("sem-periodo");
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantNovo tenantA = assinarNovoTenant("isolamento-comissao-a");
        TenantNovo tenantB = assinarNovoTenant("isolamento-comissao-b");
        long idTenantA = extrairIdTenant(tenantA.token());
        long idProduto = criarProdutoComPreco(tenantA.token(), "Produto Isolamento Comissao", "80.00");
        long idCarteira = criarTipoCarteira(tenantA.token(), "DINHEIRO ISOLAMENTO COMISSAO", "AVISTA");
        long idCliente = criarCliente(tenantA.token(), "Cliente Isolamento Comissao");
        long idFuncionario = criarFuncionarioComComissao(tenantA.token(), "Vendedor Isolamento Comissao", "8.00");
        abrirCaixaDinheiro(tenantA.token());

        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenantA, idProduto);
            definirEstoque(c, idTenantA, idEmpresa, idVariacao, new BigDecimal("10.000"));
            efetivarVenda(tenantA.token(), idVariacao, idCliente, idFuncionario, idCarteira, "80.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenantB.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(0));
    }

    @Test
    void ordenacaoPorValorVendaRespeitaAscEDesc() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ordenacao-comissao");
        long idTenant = extrairIdTenant(tenant.token());
        long idProdutoMenor = criarProdutoComPreco(tenant.token(), "Produto Comissao Menor", "40.00");
        long idProdutoMaior = criarProdutoComPreco(tenant.token(), "Produto Comissao Maior", "90.00");
        long idCarteira = criarTipoCarteira(tenant.token(), "DINHEIRO ORDENACAO COMISSAO", "AVISTA");
        long idCliente = criarCliente(tenant.token(), "Cliente Ordenacao Comissao");
        // Cria o vendedor da venda MENOR primeiro de propósito — se a ordenação não funcionasse,
        // cairia no default (nome do funcionário), que já colocaria "AAA" antes de "ZZZ" mesmo
        // sem ordenar por valor; nomeando ao contrário (MENOR = "ZZZ", MAIOR = "AAA") o teste só
        // passa se a ordenação por valorVenda realmente estiver em vigor.
        long idFuncionarioMenor = criarFuncionarioComComissao(tenant.token(), "ZZZ Vendedor Menor", "10.00");
        long idFuncionarioMaior = criarFuncionarioComComissao(tenant.token(), "AAA Vendedor Maior", "10.00");
        abrirCaixaDinheiro(tenant.token());

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacaoMenor = criarVariacao(c, idTenant, idProdutoMenor);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoMenor, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacaoMenor, idCliente, idFuncionarioMenor, idCarteira, "40.00");

            long idVariacaoMaior = criarVariacao(c, idTenant, idProdutoMaior);
            definirEstoque(c, idTenant, idEmpresa, idVariacaoMaior, new BigDecimal("10.000"));
            efetivarVenda(tenant.token(), idVariacaoMaior, idCliente, idFuncionarioMaior, idCarteira, "90.00");
        }

        String hoje = hojeISO();
        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje)
                        .param("ordenarPor", "valorVenda").param("direcao", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(2))
                .andExpect(jsonPath("$.linhas[0].valorVenda").value(40.00))
                .andExpect(jsonPath("$.linhas[1].valorVenda").value(90.00));

        mvc.perform(get("/api/v1/relatorios/comissoes").header("Authorization", "Bearer " + tenant.token())
                        .param("dataInicial", hoje).param("dataFinal", hoje)
                        .param("ordenarPor", "valorVenda").param("direcao", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(2))
                .andExpect(jsonPath("$.linhas[0].valorVenda").value(90.00))
                .andExpect(jsonPath("$.linhas[1].valorVenda").value(40.00));
    }
}
