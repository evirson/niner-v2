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
import java.time.OffsetDateTime;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRM (2026-08-06, docs/telas/crm.md) — filtros de cliente/produtos comprados + colunas de
 * saída. Mesmo padrão de {@link ClienteHistoricoCrudTest}: venda/ledger de estoque gravados
 * direto via JDBC (não existe fluxo de PDV real disponível pros testes de integração).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CrmCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Crm %s","email":"dono%s@lojacrm.com",
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

    private long criarCategoriaCliente(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoriaCliente")).longValue();
    }

    private long criarCategoriaProduto(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-produto").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoria")).longValue();
    }

    private long criarClientePF(String token, long idCategoria, String nome, String dataNascimento, String genero,
                                 String email, String telefone) throws Exception {
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":true,"nome":"%s","idCategoriaCliente":%d,
                                 "dataNascimento":"%s","genero":"%s","email":"%s","telefone":"%s"}
                                """.formatted(nome, idCategoria, dataNascimento, genero, email, telefone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private long criarProduto(String token, String descricao, long... idsCategoria) throws Exception {
        StringBuilder categorias = new StringBuilder();
        for (int i = 0; i < idsCategoria.length; i++) {
            if (i > 0) categorias.append(",");
            categorias.append(idsCategoria[i]);
        }
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"10","precoVenda":"11.00",
                                 "categorias":[%s]}
                                """.formatted(descricao, categorias)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
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

    /** {@code idCor}/{@code idTamanho} nulos = sem variação de verdade — grava 1 (cor/tamanho
     *  PADRÃO, 2026-08-13) em vez de NULL (coluna é NOT NULL desde a V017). */
    private long criarVariacao(Connection c, long idTenant, long idProduto, Long idCor, Long idTamanho)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_barra (id_tenant, id_produto, id_cor, id_tamanho, sku) "
                        + "VALUES (?, ?, ?, ?, gerar_ean13_interno()) RETURNING id_variacao")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            ps.setLong(3, idCor != null ? idCor : 1L);
            ps.setLong(4, idTamanho != null ? idTamanho : 1L);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long idVariacao = rs.getLong(1);
                abastecerEstoqueDoFixture(c, idTenant, idVariacao);
                return idVariacao;
            }
        }
    }

    /**
     * Dá estoque à variação recém-criada, em todas as empresas do tenant.
     *
     * <p>Desde a V054 o débito não pode deixar o saldo negativo quando
     * {@code cfg_permite_estoque_negativo} está desligado — que é o padrão. Este teste não é sobre
     * estoque: ele precisa de uma venda como <b>fixture</b>, e uma venda de verdade tem estoque
     * antes. Abastecer aqui é mais honesto que ligar o parâmetro e fingir que a regra não existe.
     */
    private void abastecerEstoqueDoFixture(Connection c, long idTenant, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque)
                SELECT ?, e.id_empresa, ?, 1000 FROM empresa e WHERE e.id_tenant = ?
                ON CONFLICT (id_tenant, id_empresa, id_variacao)
                DO UPDATE SET qtd_estoque = produto_estoque.qtd_estoque + 1000
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idVariacao);
            ps.setLong(3, idTenant);
            ps.executeUpdate();
        }
    }


    private long criarCor(Connection c, long idTenant, String descricao) throws SQLException {
        // id_cor não é mais IDENTITY (V017, 2026-08-13) — calculado por tenant.
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cfg_cor (id_tenant, id_cor, descricao)
                VALUES (?, COALESCE((SELECT MAX(id_cor) FROM cfg_cor WHERE id_tenant = ?), 0) + 1, ?)
                RETURNING id_cor
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idTenant);
            ps.setString(3, descricao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long criarTamanho(Connection c, long idTenant, String descricao) throws SQLException {
        // id_tamanho não é mais IDENTITY (V017, 2026-08-13) — calculado por tenant.
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cfg_tamanho (id_tenant, id_tamanho, descricao)
                VALUES (?, COALESCE((SELECT MAX(id_tamanho) FROM cfg_tamanho WHERE id_tenant = ?), 0) + 1, ?)
                RETURNING id_tamanho
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idTenant);
            ps.setString(3, descricao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long criarVenda(Connection c, long idTenant, long idEmpresa, long idCliente, OffsetDateTime dataVenda)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO venda (id_tenant, id_empresa, id_cliente, data_venda) VALUES (?, ?, ?, ?) "
                        + "RETURNING id_venda")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idCliente);
            ps.setObject(4, dataVenda);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void cancelarVenda(Connection c, long idVenda) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE venda SET cancelada = true WHERE id_venda = ?")) {
            ps.setLong(1, idVenda);
            ps.executeUpdate();
        }
    }

    private void criarMovimentoVenda(Connection c, long idTenant, long idEmpresa, long idVenda, long idVariacao)
            throws SQLException {
        long idMovimento;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_venda) "
                        + "VALUES (?, ?, 'VENDA', ?) RETURNING id_movimento")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVenda);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idMovimento = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_movimento_detalhe
                    (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto, preco_venda)
                VALUES (?, ?, ?, ?, 'D', 1, 10.00)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idMovimento);
            ps.setLong(3, idEmpresa);
            ps.setLong(4, idVariacao);
            ps.executeUpdate();
        }
    }

    @Test
    void opcoesRetornaCategoriasCoresETamanhosDoTenant() throws Exception {
        String token = assinarNovoTenant("opcoes");
        criarCategoriaCliente(token, "VIP");
        criarCategoriaProduto(token, "Calçados");
        long idTenant = extrairIdTenant(token);
        try (Connection c = abrirConexao(idTenant)) {
            criarCor(c, idTenant, "AZUL");
            criarTamanho(c, idTenant, "38");
        }

        mvc.perform(get("/api/v1/crm/opcoes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoriasCliente[0].rotulo").value("VIP"))
                .andExpect(jsonPath("$.categoriasProduto[0].rotulo").value("CALÇADOS"))
                .andExpect(jsonPath("$.cores[0].rotulo").value("AZUL"))
                .andExpect(jsonPath("$.tamanhos[0].rotulo").value("38"));
    }

    @Test
    void semFiltroRetornaTodosOsClientesAtivos() throws Exception {
        String token = assinarNovoTenant("sem-filtro");
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        criarClientePF(token, idCategoria, "Ana Silva", "1990-01-01", "FEMININO", "ana@ex.com", "11999990001");
        criarClientePF(token, idCategoria, "Bruno Souza", "1985-06-15", "MASCULINO", "bruno@ex.com", "11999990002");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void filtroPorGeneroRestringeAOsSelecionados() throws Exception {
        String token = assinarNovoTenant("genero");
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        criarClientePF(token, idCategoria, "Ana Silva", "1990-01-01", "FEMININO", "ana@ex.com", "11999990001");
        criarClientePF(token, idCategoria, "Bruno Souza", "1985-06-15", "MASCULINO", "bruno@ex.com", "11999990002");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token).param("generos", "FEMININO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("ANA SILVA"));
    }

    @Test
    void filtroPorPrimeiraLetraDoNome() throws Exception {
        String token = assinarNovoTenant("letra");
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        criarClientePF(token, idCategoria, "Ana Silva", "1990-01-01", "FEMININO", "ana@ex.com", "11999990001");
        criarClientePF(token, idCategoria, "Bruno Souza", "1985-06-15", "MASCULINO", "bruno@ex.com", "11999990002");
        criarClientePF(token, idCategoria, "Zeca Pagodinho", "1970-03-03", "MASCULINO", "zeca@ex.com", "11999990003");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token)
                        .param("clienteInicial", "A").param("clienteFinal", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nome").value("ANA SILVA"))
                .andExpect(jsonPath("$[1].nome").value("BRUNO SOUZA"));
    }

    @Test
    void filtroPorFaixaDeIdade() throws Exception {
        String token = assinarNovoTenant("idade");
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        String nascimentoJovem = java.time.LocalDate.now().minusYears(20).toString();
        String nascimentoIdoso = java.time.LocalDate.now().minusYears(60).toString();
        criarClientePF(token, idCategoria, "Cliente Jovem", nascimentoJovem, "OUTROS", "jovem@ex.com", "11999990001");
        criarClientePF(token, idCategoria, "Cliente Idoso", nascimentoIdoso, "OUTROS", "idoso@ex.com", "11999990002");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token)
                        .param("idadeDe", "18").param("idadeAte", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("CLIENTE JOVEM"));
    }

    @Test
    void filtroPorCategoriaDoCliente() throws Exception {
        String token = assinarNovoTenant("categoria-cliente");
        long idVip = criarCategoriaCliente(token, "VIP");
        long idPadrao = criarCategoriaCliente(token, "Padrão");
        criarClientePF(token, idVip, "Cliente Vip", "1990-01-01", "OUTROS", "vip@ex.com", "11999990001");
        criarClientePF(token, idPadrao, "Cliente Padrao", "1990-01-01", "OUTROS", "padrao@ex.com", "11999990002");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token)
                        .param("idsCategoriaCliente", String.valueOf(idVip)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("CLIENTE VIP"));
    }

    @Test
    void diasSemComprasMinimoIncluiQuemNuncaComprou() throws Exception {
        String token = assinarNovoTenant("dias-sem-compra");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idNuncaComprou = criarClientePF(token, idCategoria, "Nunca Comprou", "1990-01-01", "OUTROS", "a@ex.com", "11999990001");
        long idComprouOntem = criarClientePF(token, idCategoria, "Comprou Ontem", "1990-01-01", "OUTROS", "b@ex.com", "11999990002");
        long idProduto = criarProduto(token, "Produto Generico");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto, null, null);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idComprouOntem, OffsetDateTime.now().minusDays(1));
            criarMovimentoVenda(c, idTenant, idEmpresa, idVenda, idVariacao);
        }

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token)
                        .param("diasSemComprasMinimo", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].idCliente").value(idNuncaComprou));
    }

    @Test
    void saidaTrazPrimeiraUltimaCompraENumeroDeCompras() throws Exception {
        String token = assinarNovoTenant("agregados");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCliente = criarClientePF(token, idCategoria, "Cliente Compras", "1990-01-01", "OUTROS", "c@ex.com", "11999990001");
        long idProduto = criarProduto(token, "Produto Generico");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto, null, null);
            long idVenda1 = criarVenda(c, idTenant, idEmpresa, idCliente, OffsetDateTime.now().minusDays(30));
            criarMovimentoVenda(c, idTenant, idEmpresa, idVenda1, idVariacao);
            long idVenda2 = criarVenda(c, idTenant, idEmpresa, idCliente, OffsetDateTime.now().minusDays(2));
            criarMovimentoVenda(c, idTenant, idEmpresa, idVenda2, idVariacao);
        }

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numeroCompras").value(2))
                .andExpect(jsonPath("$[0].primeiraCompra").value(java.time.LocalDate.now().minusDays(30).toString()))
                .andExpect(jsonPath("$[0].ultimaCompra").value(java.time.LocalDate.now().minusDays(2).toString()))
                // 2 vendas de 1 unidade a R$10,00 cada (criarMovimentoVenda) = R$20,00 total.
                .andExpect(jsonPath("$[0].valorTotalCompras").value(20.00))
                .andExpect(jsonPath("$[0].ticketMedio").value(10.00))
                .andExpect(jsonPath("$[0].diasSemUltimaCompra").value(2));
    }

    @Test
    void clienteSemNenhumaCompraTemValorZeroETicketEDiasNulos() throws Exception {
        String token = assinarNovoTenant("sem-compra-agregados");
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        criarClientePF(token, idCategoria, "Nunca Comprou Nada", "1990-01-01", "OUTROS", "n@ex.com", "11999990001");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numeroCompras").value(0))
                .andExpect(jsonPath("$[0].valorTotalCompras").value(0))
                .andExpect(jsonPath("$[0].ticketMedio").doesNotExist())
                .andExpect(jsonPath("$[0].diasSemUltimaCompra").doesNotExist());
    }

    @Test
    void vendaCanceladaNaoContaComoCompra() throws Exception {
        String token = assinarNovoTenant("cancelada");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCliente = criarClientePF(token, idCategoria, "Cliente Cancelado", "1990-01-01", "OUTROS", "c@ex.com", "11999990001");
        long idProduto = criarProduto(token, "Produto Generico");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto, null, null);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente, OffsetDateTime.now().minusDays(1));
            criarMovimentoVenda(c, idTenant, idEmpresa, idVenda, idVariacao);
            cancelarVenda(c, idVenda);
        }

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numeroCompras").value(0))
                .andExpect(jsonPath("$[0].ultimaCompra").doesNotExist());
    }

    @Test
    void filtroDeProdutosCompradosExigeMesmaLinhaDeVenda() throws Exception {
        String token = assinarNovoTenant("produtos-mesma-linha");
        long idTenant = extrairIdTenant(token);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        long idCategoriaCalcados = criarCategoriaProduto(token, "Calçados");
        long idCategoriaRoupas = criarCategoriaProduto(token, "Roupas");
        long idClienteAlvo = criarClientePF(token, idCategoria, "Cliente Alvo", "1990-01-01", "OUTROS", "alvo@ex.com", "11999990001");
        long idClienteFora = criarClientePF(token, idCategoria, "Cliente Fora", "1990-01-01", "OUTROS", "fora@ex.com", "11999990002");
        long idBota = criarProduto(token, "Bota", idCategoriaCalcados);
        long idCamisa = criarProduto(token, "Camisa", idCategoriaRoupas);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idCorAzul = criarCor(c, idTenant, "AZUL");
            long idTamanho36 = criarTamanho(c, idTenant, "36");

            // Cliente alvo: UMA compra de bota AZUL/36 (categoria Calçados) — deve bater no filtro
            // categoria=Calçados + cor=AZUL juntos, porque é a MESMA linha de venda.
            long idVariacaoBota = criarVariacao(c, idTenant, idBota, idCorAzul, idTamanho36);
            long idVendaAlvo = criarVenda(c, idTenant, idEmpresa, idClienteAlvo, OffsetDateTime.now());
            criarMovimentoVenda(c, idTenant, idEmpresa, idVendaAlvo, idVariacaoBota);

            // Cliente fora: comprou categoria Calçados (bota, sem cor) E, em outra compra,
            // uma camisa AZUL — a combinação categoria=Calçados + cor=AZUL NÃO deve bater porque
            // não é a mesma linha de venda (itens diferentes).
            long idVariacaoBotaSemCor = criarVariacao(c, idTenant, idBota, null, null);
            long idVendaBotaFora = criarVenda(c, idTenant, idEmpresa, idClienteFora, OffsetDateTime.now());
            criarMovimentoVenda(c, idTenant, idEmpresa, idVendaBotaFora, idVariacaoBotaSemCor);
            long idVariacaoCamisaAzul = criarVariacao(c, idTenant, idCamisa, idCorAzul, null);
            long idVendaCamisaFora = criarVenda(c, idTenant, idEmpresa, idClienteFora, OffsetDateTime.now());
            criarMovimentoVenda(c, idTenant, idEmpresa, idVendaCamisaFora, idVariacaoCamisaAzul);

            mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token)
                            .param("idsCategoriaProduto", String.valueOf(idCategoriaCalcados))
                            .param("idsCor", String.valueOf(idCorAzul)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].idCliente").value(idClienteAlvo));
        }
    }

    @Test
    void aniversarioApenasUmLadoInformadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("aniversario-parcial");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token)
                        .param("aniversarioDe", "01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void formatoDeAniversarioInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("aniversario-invalido");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token)
                        .param("aniversarioDe", "31-13").param("aniversarioAte", "01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aniversarioComVirdaDeAnoFuncionaComoIntervaloCircular() throws Exception {
        String token = assinarNovoTenant("aniversario-virada");
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        criarClientePF(token, idCategoria, "Natal", "1990-12-25", "OUTROS", "natal@ex.com", "11999990001");
        criarClientePF(token, idCategoria, "Meio Do Ano", "1990-06-15", "OUTROS", "meio@ex.com", "11999990002");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + token)
                        .param("aniversarioDe", "20-12").param("aniversarioAte", "10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("NATAL"));
    }

    @Test
    void outroTenantNaoVaza() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a-crm");
        String tokenB = assinarNovoTenant("tenant-b-crm");
        long idCategoriaA = criarCategoriaCliente(tokenA, "Padrão");
        criarClientePF(tokenA, idCategoriaA, "Cliente Tenant A", "1990-01-01", "OUTROS", "a@ex.com", "11999990001");

        mvc.perform(get("/api/v1/crm/clientes").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
