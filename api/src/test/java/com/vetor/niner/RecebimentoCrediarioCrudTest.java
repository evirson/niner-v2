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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recebimento de Crediário — busca de cliente, listagem de parcelas em aberto com multa/juros
 * calculados na hora, e efetivação transacional (RN001–RN013 da spec fornecida pelo dono do
 * produto, 2026-07-29). Sem endpoint de venda/parcela avulsa — venda/contas_receber gravados
 * direto via JDBC como {@code niner_app} com {@code app.id_tenant} setado (mesmo padrão de
 * {@link PdvCrudTest}), já que não existe (nem deveria existir aqui) uma rota que cria parcela
 * solta sem passar pelo PDV.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RecebimentoCrediarioCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Recebimento %s","email":"dono%s@lojarecebimento.com",
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

    private void definirConfigCrediario(String token, int jurosCrediarioDias, String jurosCrediario,
                                         int multaCrediarioDias, String multaCrediario) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":%d,"jurosCrediario":%s,
                                 "multaCrediarioDias":%d,"multaCrediario":%s,"cfgUsaVarianteLinha":true,"cfgUsaVarianteColuna":true,
                                 "cfgPermiteQtdDecimal":true}
                                """.formatted(jurosCrediarioDias, jurosCrediario, multaCrediarioDias, multaCrediario)))
                .andExpect(status().isOk());
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    /** Abre o caixa do dia usando o "DINHEIRO" semeado no signup (2026-07-30) — o recebimento
     *  agora exige caixa aberto (financeiro.caixa.CaixaService) antes de efetivar; antes desta
     *  data o serviço abria sozinho, em silêncio. */
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

    /** {@code diasVencidaHa} positivo = venceu no passado (atrasada); negativo = ainda vai vencer. */
    private long criarParcela(Connection c, long idTenant, long idVenda, long idCarteira, int numeroParcela,
                               int diasVencidaHa, String valorReceber, boolean recebida) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO contas_receber
                    (id_tenant, id_venda, id_carteira, numero_parcela, data_vencimento, valor_receber, data_recebimento)
                VALUES (?, ?, ?, ?, now() - (? || ' days')::interval, ?, %s)
                RETURNING id_conta_receber
                """.formatted(recebida ? "now()" : "NULL"))) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idVenda);
            ps.setLong(3, idCarteira);
            ps.setInt(4, numeroParcela);
            ps.setInt(5, diasVencidaHa);
            ps.setBigDecimal(6, new BigDecimal(valorReceber));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private boolean parcelaFoiRecebida(Connection c, long idContaReceber) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT data_recebimento FROM contas_receber WHERE id_conta_receber = ?")) {
            ps.setLong(1, idContaReceber);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1) != null;
            }
        }
    }

    // --- Busca de cliente ----------------------------------------------------------------

    @Test
    void buscaSemNenhumFiltroEhRejeitada() throws Exception {
        String token = assinarNovoTenant("sem-filtro");

        mvc.perform(get("/api/v1/recebimento-crediario/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buscaPorNomeEncontraCliente() throws Exception {
        String token = assinarNovoTenant("busca-nome");
        criarCliente(token, "Maria Crediario Teste");

        mvc.perform(get("/api/v1/recebimento-crediario/clientes").param("nome", "CREDIARIO TESTE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("MARIA CREDIARIO TESTE"));
    }

    // --- Listagem de parcelas --------------------------------------------------------------

    @Test
    void listaSoParcelasDeCrediarioAindaEmAberto() throws Exception {
        String token = assinarNovoTenant("so-abertas");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Parcelas Abertas");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO TESTE", "CREDIARIO", false);
        long idCarteiraAvista = criarTipoCarteira(token, "DINHEIRO TESTE", "AVISTA", true);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            // 1) crediário em aberto — deve aparecer.
            criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 1, 5, "100.00", false);
            // 2) crediário já recebida — não deve aparecer.
            criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 2, 5, "100.00", true);
            // 3) categoria diferente (à vista) em aberto — não deve aparecer (RN002 é implícito ao escopo da tela).
            criarParcela(c, idTenant, idVenda, idCarteiraAvista, 1, 5, "100.00", false);
        }

        mvc.perform(get("/api/v1/recebimento-crediario/parcelas").param("idCliente", String.valueOf(idCliente))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].numeroParcela").value(1))
                .andExpect(jsonPath("$[0].valorOriginal").value(100.00));
    }

    @Test
    void calculaMultaEJurosComCarenciaConfigurada() throws Exception {
        String token = assinarNovoTenant("multa-juros");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Multa Juros");
        long idCarteira = criarTipoCarteira(token, "CREDIARIO MULTA", "CREDIARIO", false);
        // carência de 2 dias pra multa, 3 dias pra juros; multa 2% flat; juros 0,1%/dia.
        definirConfigCrediario(token, 3, "0.1", 2, "2");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            // vencida há 10 dias: multa = 100 * 2% = 2.00; juros = 100 * 0.1% * (10-3) = 0.70; total = 102.70.
            criarParcela(c, idTenant, idVenda, idCarteira, 1, 10, "100.00", false);
        }

        mvc.perform(get("/api/v1/recebimento-crediario/parcelas").param("idCliente", String.valueOf(idCliente))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].multa").value(2.00))
                .andExpect(jsonPath("$[0].juros").value(0.70))
                .andExpect(jsonPath("$[0].totalAPagar").value(102.70));
    }

    @Test
    void parcelaAindaDentroDaCarenciaNaoTemMultaNemJuros() throws Exception {
        String token = assinarNovoTenant("dentro-carencia");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Dentro Carencia");
        long idCarteira = criarTipoCarteira(token, "CREDIARIO CARENCIA", "CREDIARIO", false);
        definirConfigCrediario(token, 5, "1", 5, "10");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            // vencida há 2 dias, carência de 5 — nem multa nem juros ainda.
            criarParcela(c, idTenant, idVenda, idCarteira, 1, 2, "50.00", false);
        }

        mvc.perform(get("/api/v1/recebimento-crediario/parcelas").param("idCliente", String.valueOf(idCliente))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].multa").value(0))
                .andExpect(jsonPath("$[0].juros").value(0))
                .andExpect(jsonPath("$[0].totalAPagar").value(50.00));
    }

    // --- Carteiras disponíveis -------------------------------------------------------------

    @Test
    void listaCarteirasSoTrazPermiteReceberCrediarioNaCategoriaCerta() throws Exception {
        String token = assinarNovoTenant("carteiras-disponiveis");
        criarTipoCarteira(token, "PERMITIDA AVISTA", "AVISTA", true);
        criarTipoCarteira(token, "NAO PERMITIDA", "AVISTA", false);
        // permite_receber_crediario=true mas categoria CREDIARIO não é permitida mesmo assim.
        criarTipoCarteira(token, "CREDIARIO MARCADA", "CREDIARIO", true);

        // Não checa o tamanho exato — o tenant já nasce com DINHEIRO/PIX/CARTAO DEBITO/CARTAO
        // CREDITO semeados com permite_receber_crediario=true (SignupService, 2026-07-29).
        mvc.perform(get("/api/v1/recebimento-crediario/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nomeCarteira").value(org.hamcrest.Matchers.hasItem("PERMITIDA AVISTA")))
                .andExpect(jsonPath("$[*].nomeCarteira").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("NAO PERMITIDA"))))
                .andExpect(jsonPath("$[*].nomeCarteira")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("CREDIARIO MARCADA"))));
    }

    // --- Efetivação --------------------------------------------------------------------

    @Test
    void efetivarComCarteiraNaoPermitidaEhRejeitadoSemGravarNada() throws Exception {
        String token = assinarNovoTenant("carteira-nao-permitida");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Carteira Errada");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO REJEITA", "CREDIARIO", false);
        long idCarteiraNaoPermitida = criarTipoCarteira(token, "AVISTA NAO PERMITIDA", "AVISTA", false);

        long idContaReceber;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            idContaReceber = criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 1, 0, "100.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":100.00}]}
                """.formatted(idCliente, idContaReceber, idCarteiraNaoPermitida);

        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());

        try (Connection c = abrirConexao(idTenant)) {
            assertTrue(!parcelaFoiRecebida(c, idContaReceber));
        }
    }

    @Test
    void efetivarComSomaQueNaoFechaEhRejeitadoSemGravarNada() throws Exception {
        String token = assinarNovoTenant("soma-nao-fecha");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Soma Errada");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO SOMA", "CREDIARIO", false);
        long idCarteiraPaga = criarTipoCarteira(token, "AVISTA SOMA", "AVISTA", true);

        long idContaReceber;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            idContaReceber = criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 1, 0, "100.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":50.00}]}
                """.formatted(idCliente, idContaReceber, idCarteiraPaga);

        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());

        try (Connection c = abrirConexao(idTenant)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT data_recebimento FROM contas_receber WHERE id_conta_receber = ?")) {
                ps.setLong(1, idContaReceber);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(null, rs.getTimestamp(1));
                }
            }
        }
    }

    @Test
    void efetivarComSucessoBaixaParcelaEGravaLoteECaixaDetalhe() throws Exception {
        String token = assinarNovoTenant("fluxo-feliz");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Fluxo Feliz");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO FELIZ", "CREDIARIO", false);
        long idCarteiraPaga = criarTipoCarteira(token, "DINHEIRO FELIZ", "AVISTA", true);
        definirConfigCrediario(token, 0, "0", 0, "0");
        abrirCaixaDinheiro(token);

        long idContaReceber;
        long idVenda;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            idContaReceber = criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 1, 0, "150.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":150.00}]}
                """.formatted(idCliente, idContaReceber, idCarteiraPaga);

        String resp = mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qtdParcelas").value(1))
                .andExpect(jsonPath("$.valorTotal").value(150.00))
                .andReturn().getResponse().getContentAsString();
        long idLote = ((Number) JsonPath.read(resp, "$.idLoteRecebimento")).longValue();

        try (Connection c = abrirConexao(idTenant)) {
            // parcela baixada.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT valor_recebido, id_lote_recebimento, id_empresa_pagamento
                    FROM contas_receber WHERE id_conta_receber = ?
                    """)) {
                ps.setLong(1, idContaReceber);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, new BigDecimal("150.00").compareTo(rs.getBigDecimal(1)));
                    assertEquals(idLote, rs.getLong(2));
                    assertTrue(rs.getLong(3) > 0);
                }
            }

            // caixa foi aberto automaticamente e recebeu o lançamento certo.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT cd.valor, cd.tipo_operacao::text, cd.id_venda, cd.id_lote_recebimento, cd.id_carteira
                    FROM caixa_detalhe cd
                    JOIN caixa_mestre cm ON cm.id_caixa = cd.id_caixa
                    WHERE cd.id_lote_recebimento = ?
                    """)) {
                ps.setLong(1, idLote);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, new BigDecimal("150.00").compareTo(rs.getBigDecimal(1)));
                    assertEquals("RECEBIMENTO_PARCELA_CREDIARIO", rs.getString(2));
                    assertEquals(idVenda, rs.getLong(3));
                    assertEquals(idCarteiraPaga, rs.getLong(5));
                    assertTrue(!rs.next());
                }
            }

            // lote gravado com o total certo.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT valor_total, id_cliente FROM contas_receber_lote WHERE id_lote_recebimento = ?")) {
                ps.setLong(1, idLote);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, new BigDecimal("150.00").compareTo(rs.getBigDecimal(1)));
                    assertEquals(idCliente, rs.getLong(2));
                }
            }
        }
    }

    @Test
    void efetivarComSplitTenderAlocaCadaFormaNaParcelaCerta() throws Exception {
        String token = assinarNovoTenant("split-tender");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Split Tender");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO SPLIT", "CREDIARIO", false);
        long idCarteiraDinheiro = criarTipoCarteira(token, "DINHEIRO SPLIT", "AVISTA", true);
        long idCarteiraCartao = criarTipoCarteira(token, "DEBITO SPLIT", "CARTAO_DEBITO", true);
        definirConfigCrediario(token, 0, "0", 0, "0");
        abrirCaixaDinheiro(token);

        long idConta1;
        long idConta2;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda1 = criarVenda(c, idTenant, idEmpresa, idCliente);
            long idVenda2 = criarVenda(c, idTenant, idEmpresa, idCliente);
            idConta1 = criarParcela(c, idTenant, idVenda1, idCarteiraCrediario, 1, 0, "60.00", false);
            idConta2 = criarParcela(c, idTenant, idVenda2, idCarteiraCrediario, 1, 0, "40.00", false);
        }

        // 60 no dinheiro (cobre a parcela 1 inteira) + 40 no débito (cobre a parcela 2 inteira).
        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d,%d],
                 "pagamentos":[{"idCarteira":%d,"valorPago":60.00},{"idCarteira":%d,"valorPago":40.00}]}
                """.formatted(idCliente, idConta1, idConta2, idCarteiraDinheiro, idCarteiraCartao);

        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qtdParcelas").value(2))
                .andExpect(jsonPath("$.valorTotal").value(100.00));

        try (Connection c = abrirConexao(idTenant)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_carteira, valor FROM caixa_detalhe WHERE id_venda = (SELECT id_venda FROM contas_receber WHERE id_conta_receber = ?)")) {
                ps.setLong(1, idConta1);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(idCarteiraDinheiro, rs.getLong(1));
                    assertEquals(0, new BigDecimal("60.00").compareTo(rs.getBigDecimal(2)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_carteira, valor FROM caixa_detalhe WHERE id_venda = (SELECT id_venda FROM contas_receber WHERE id_conta_receber = ?)")) {
                ps.setLong(1, idConta2);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(idCarteiraCartao, rs.getLong(1));
                    assertEquals(0, new BigDecimal("40.00").compareTo(rs.getBigDecimal(2)));
                }
            }
        }
    }

    @Test
    void efetivarPagamentoNoCartaoGravaDetalheDeCartao() throws Exception {
        String token = assinarNovoTenant("detalhe-cartao");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Detalhe Cartao");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO CARTAO", "CREDIARIO", false);
        long idCarteiraCartao = criarTipoCarteira(token, "CREDITO CARTAO", "CARTAO_CREDITO", true);
        definirConfigCrediario(token, 0, "0", 0, "0");
        abrirCaixaDinheiro(token);

        long idContaReceber;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            idContaReceber = criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 1, 0, "200.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":200.00}]}
                """.formatted(idCliente, idContaReceber, idCarteiraCartao);

        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk());

        try (Connection c = abrirConexao(idTenant)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT valor_bruto FROM contas_receber_detalhe WHERE id_conta_receber = ?")) {
                ps.setLong(1, idContaReceber);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, new BigDecimal("200.00").compareTo(rs.getBigDecimal(1)));
                }
            }
        }
    }

    @Test
    void efetivarParcelaJaRecebidaRespondeConflitoSemGravarNada() throws Exception {
        String token = assinarNovoTenant("ja-recebida");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Ja Recebida");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO JA RECEBIDA", "CREDIARIO", false);
        long idCarteiraPaga = criarTipoCarteira(token, "AVISTA JA RECEBIDA", "AVISTA", true);

        long idContaReceber;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            idContaReceber = criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 1, 0, "80.00", true);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":80.00}]}
                """.formatted(idCliente, idContaReceber, idCarteiraPaga);

        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
    }

    @Test
    void parcelaDeOutroTenantNaoApareceNemPodeSerRecebida() throws Exception {
        String tokenA = assinarNovoTenant("isolamento-a");
        long idTenantA = extrairIdTenant(tokenA);
        long idClienteA = criarCliente(tokenA, "Cliente Isolamento A");
        long idCarteiraA = criarTipoCarteira(tokenA, "CREDIARIO ISOLAMENTO A", "CREDIARIO", false);

        long idContaReceberA;
        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenantA, idEmpresa, idClienteA);
            idContaReceberA = criarParcela(c, idTenantA, idVenda, idCarteiraA, 1, 0, "70.00", false);
        }

        String tokenB = assinarNovoTenant("isolamento-b");
        long idCarteiraPagaB = criarTipoCarteira(tokenB, "AVISTA ISOLAMENTO B", "AVISTA", true);

        // tenant B nunca vê a parcela do tenant A na listagem — busca pelo mesmo idCliente,
        // que só existe no tenant A, não devolve nada em B (RLS).
        mvc.perform(get("/api/v1/recebimento-crediario/parcelas").param("idCliente", String.valueOf(idClienteA))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // tentar receber a parcela do tenant A logado como tenant B não acha nada pra travar — 409.
        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":70.00}]}
                """.formatted(idClienteA, idContaReceberA, idCarteiraPagaB);

        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
    }

    // --- Caixa aberto é obrigatório (2026-07-30) -------------------------------------------

    @Test
    void efetivarSemCaixaAbertoRespondeErroDeValidacaoENaoGravaNada() throws Exception {
        String token = assinarNovoTenant("recebimento-sem-caixa");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Recebimento Sem Caixa");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO SEM CAIXA", "CREDIARIO", false);
        long idCarteiraPaga = criarTipoCarteira(token, "DINHEIRO SEM CAIXA", "AVISTA", true);
        // Sem abrirCaixaDinheiro(token) de propósito — nenhum caixa aberto hoje.

        long idContaReceber;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            idContaReceber = criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 1, 0, "100.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":100.00}]}
                """.formatted(idCliente, idContaReceber, idCarteiraPaga);

        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());

        try (Connection c = abrirConexao(idTenant)) {
            assertTrue(!parcelaFoiRecebida(c, idContaReceber));
        }
    }

    // --- Estorno de recebimento (2026-07-29) ------------------------------------------------

    @Test
    void listarLotesSemNomeClienteEhRejeitado() throws Exception {
        String token = assinarNovoTenant("estorno-sem-nome");

        mvc.perform(get("/api/v1/recebimento-crediario/estornos").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listarLotesFiltraPorNomeEDataEMostraQtdParcelasEFormasDePagamento() throws Exception {
        String token = assinarNovoTenant("estorno-listar");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Estorno Listar");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO ESTORNO LISTAR", "CREDIARIO", false);
        long idCarteiraDinheiro = criarTipoCarteira(token, "DINHEIRO ESTORNO LISTAR", "AVISTA", true);
        definirConfigCrediario(token, 0, "0", 0, "0");
        abrirCaixaDinheiro(token);

        long idConta1;
        long idConta2;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda1 = criarVenda(c, idTenant, idEmpresa, idCliente);
            long idVenda2 = criarVenda(c, idTenant, idEmpresa, idCliente);
            idConta1 = criarParcela(c, idTenant, idVenda1, idCarteiraCrediario, 1, 0, "60.00", false);
            idConta2 = criarParcela(c, idTenant, idVenda2, idCarteiraCrediario, 1, 0, "40.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d,%d],"pagamentos":[{"idCarteira":%d,"valorPago":100.00}]}
                """.formatted(idCliente, idConta1, idConta2, idCarteiraDinheiro);
        mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk());

        String hoje = java.time.LocalDate.now().toString();
        mvc.perform(get("/api/v1/recebimento-crediario/estornos")
                        .param("nomeCliente", "ESTORNO LISTAR")
                        .param("dataInicial", hoje).param("dataFinal", hoje)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nomeCliente").value("CLIENTE ESTORNO LISTAR"))
                .andExpect(jsonPath("$[0].qtdParcelas").value(2))
                .andExpect(jsonPath("$[0].valorTotal").value(100.00))
                .andExpect(jsonPath("$[0].formasPagamento").value("DINHEIRO ESTORNO LISTAR"));
    }

    @Test
    void listarParcelasDoLoteMostraCadaParcelaComSeuVenceESeuPlano() throws Exception {
        String token = assinarNovoTenant("visualizar-parcelas-lote");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Visualizar Parcelas Lote");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO VISUALIZAR", "CREDIARIO", false);
        long idCarteiraDinheiro = criarTipoCarteira(token, "DINHEIRO VISUALIZAR", "AVISTA", true);
        definirConfigCrediario(token, 0, "0", 0, "0");
        abrirCaixaDinheiro(token);

        long idConta1;
        long idConta2;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda1 = criarVenda(c, idTenant, idEmpresa, idCliente);
            long idVenda2 = criarVenda(c, idTenant, idEmpresa, idCliente);
            idConta1 = criarParcela(c, idTenant, idVenda1, idCarteiraCrediario, 1, 0, "25.00", false);
            idConta2 = criarParcela(c, idTenant, idVenda2, idCarteiraCrediario, 1, 0, "35.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d,%d],"pagamentos":[{"idCarteira":%d,"valorPago":60.00}]}
                """.formatted(idCliente, idConta1, idConta2, idCarteiraDinheiro);
        String resp = mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idLote = ((Number) JsonPath.read(resp, "$.idLoteRecebimento")).longValue();

        mvc.perform(get("/api/v1/recebimento-crediario/estornos/" + idLote + "/parcelas")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].valorRecebido")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(25.00, 35.00)))
                .andExpect(jsonPath("$[*].totalParcelas").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(1))));
    }

    @Test
    void estornarLoteReabreTodasAsParcelasEApagaCaixaEDetalheDeCartao() throws Exception {
        String token = assinarNovoTenant("estornar-lote");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Estornar Lote");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO ESTORNAR", "CREDIARIO", false);
        long idCarteiraCartao = criarTipoCarteira(token, "CREDITO ESTORNAR", "CARTAO_CREDITO", true);
        definirConfigCrediario(token, 0, "0", 0, "0");
        abrirCaixaDinheiro(token);

        long idConta1;
        long idConta2;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            // duas vendas (dois "contratos") recebidas juntas no mesmo lote.
            long idVenda1 = criarVenda(c, idTenant, idEmpresa, idCliente);
            long idVenda2 = criarVenda(c, idTenant, idEmpresa, idCliente);
            idConta1 = criarParcela(c, idTenant, idVenda1, idCarteiraCrediario, 1, 0, "120.00", false);
            idConta2 = criarParcela(c, idTenant, idVenda2, idCarteiraCrediario, 1, 0, "80.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d,%d],"pagamentos":[{"idCarteira":%d,"valorPago":200.00}]}
                """.formatted(idCliente, idConta1, idConta2, idCarteiraCartao);
        String resp = mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idLote = ((Number) JsonPath.read(resp, "$.idLoteRecebimento")).longValue();

        mvc.perform(post("/api/v1/recebimento-crediario/estornos/" + idLote)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idLoteRecebimento").value(idLote))
                .andExpect(jsonPath("$.qtdParcelas").value(2))
                .andExpect(jsonPath("$.valorTotal").value(200.00));

        try (Connection c = abrirConexao(idTenant)) {
            // as duas parcelas reabriram, não só a que "puxaria" o estorno.
            assertTrue(!parcelaFoiRecebida(c, idConta1));
            assertTrue(!parcelaFoiRecebida(c, idConta2));

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_lote_recebimento, valor_recebido, id_empresa_pagamento FROM contas_receber WHERE id_conta_receber IN (?, ?)")) {
                ps.setLong(1, idConta1);
                ps.setLong(2, idConta2);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assertEquals(null, rs.getObject(1));
                        assertEquals(0, BigDecimal.ZERO.compareTo(rs.getBigDecimal(2)));
                        assertEquals(null, rs.getObject(3));
                    }
                }
            }

            // detalhe de cartão apagado (as duas parcelas foram pagas com carteira de crédito).
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM contas_receber_detalhe WHERE id_conta_receber IN (?, ?)")) {
                ps.setLong(1, idConta1);
                ps.setLong(2, idConta2);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, rs.getInt(1));
                }
            }

            // lançamentos de caixa apagados.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM caixa_detalhe WHERE id_lote_recebimento = ?")) {
                ps.setLong(1, idLote);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, rs.getInt(1));
                }
            }

            // cabeçalho do lote apagado (decisão do dono do produto — mesmo padrão da Transferência).
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM contas_receber_lote WHERE id_lote_recebimento = ?")) {
                ps.setLong(1, idLote);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void estornarLoteInexistenteRespondeConflito() throws Exception {
        String token = assinarNovoTenant("estorno-inexistente");

        mvc.perform(post("/api/v1/recebimento-crediario/estornos/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void estornarODuasVezesNaSegundaRespondeConflito() throws Exception {
        String token = assinarNovoTenant("estorno-duas-vezes");
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "Cliente Estorno Duas Vezes");
        long idCarteiraCrediario = criarTipoCarteira(token, "CREDIARIO DUAS VEZES", "CREDIARIO", false);
        long idCarteiraDinheiro = criarTipoCarteira(token, "DINHEIRO DUAS VEZES", "AVISTA", true);
        definirConfigCrediario(token, 0, "0", 0, "0");
        abrirCaixaDinheiro(token);

        long idConta;
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenant, idEmpresa, idCliente);
            idConta = criarParcela(c, idTenant, idVenda, idCarteiraCrediario, 1, 0, "30.00", false);
        }

        String corpo = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":30.00}]}
                """.formatted(idCliente, idConta, idCarteiraDinheiro);
        String resp = mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idLote = ((Number) JsonPath.read(resp, "$.idLoteRecebimento")).longValue();

        mvc.perform(post("/api/v1/recebimento-crediario/estornos/" + idLote)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/recebimento-crediario/estornos/" + idLote)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void loteDeOutroTenantNaoApareceNaListagemNemPodeSerEstornado() throws Exception {
        String tokenA = assinarNovoTenant("estorno-isolamento-a");
        long idTenantA = extrairIdTenant(tokenA);
        long idClienteA = criarCliente(tokenA, "Cliente Estorno Isolamento A");
        long idCarteiraCrediarioA = criarTipoCarteira(tokenA, "CREDIARIO ESTORNO ISOLAMENTO A", "CREDIARIO", false);
        long idCarteiraPagaA = criarTipoCarteira(tokenA, "AVISTA ESTORNO ISOLAMENTO A", "AVISTA", true);
        definirConfigCrediario(tokenA, 0, "0", 0, "0");
        abrirCaixaDinheiro(tokenA);

        long idContaA;
        try (Connection c = abrirConexao(idTenantA)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVenda = criarVenda(c, idTenantA, idEmpresa, idClienteA);
            idContaA = criarParcela(c, idTenantA, idVenda, idCarteiraCrediarioA, 1, 0, "90.00", false);
        }

        String corpoRecebimento = """
                {"idCliente":%d,"idsContaReceber":[%d],"pagamentos":[{"idCarteira":%d,"valorPago":90.00}]}
                """.formatted(idClienteA, idContaA, idCarteiraPagaA);
        String resp = mvc.perform(post("/api/v1/recebimento-crediario").header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON).content(corpoRecebimento))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long idLoteA = ((Number) JsonPath.read(resp, "$.idLoteRecebimento")).longValue();

        String tokenB = assinarNovoTenant("estorno-isolamento-b");
        mvc.perform(get("/api/v1/recebimento-crediario/estornos").param("nomeCliente", "ESTORNO ISOLAMENTO A")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/v1/recebimento-crediario/estornos/" + idLoteA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isConflict());
    }
}
