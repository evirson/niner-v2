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
 * Cancelamento de Entrada (2026-08-12, `POST /api/v1/estoque/entradas/{id}/cancelar`) — mesmo
 * padrão de {@code CancelamentoVendaService}/{@code CancelamentoDevolucaoService}: ADMIN-only,
 * o {@code produto_movimento_mestre} original nunca é apagado (P3), o estorno de estoque é um
 * novo movimento (tipo CANCELAMENTO), e as duplicatas em {@code contas_pagar} são apagadas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CancelamentoEntradaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantSlug(String token, String slug) {
    }

    private TenantSlug assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Cancela %s","email":"dono%s@lojacancela.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TenantSlug(JsonPath.read(resp, "$.token"), JsonPath.read(resp, "$.slug"));
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
                .andExpect(result -> org.assertj.core.api.Assertions
                .assertThat(result.getResponse().getStatus()).isIn(201, 409));
    }

    private long criarFornecedor(String token, String razaoSocial) throws Exception {
        criarPlano(token, "9.00.000");
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"%s\",\"idPlanoContas\":\"9.00.000\"}".formatted(razaoSocial)))
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

    private long buscarPrimeiraEmpresa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$[0].idEmpresa")).longValue();
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

    private record TenantENota(String token, String slug, long idTenant, long idFornecedor, long idVariacao) {
    }

    private TenantENota prepararTenantComProduto(String sufixo) throws Exception {
        TenantSlug tenant = assinarNovoTenant(sufixo);
        long idTenant = extrairIdTenant(tenant.token());
        long idFornecedor = criarFornecedor(tenant.token(), "Fornecedor " + sufixo);
        long idVariacao = criarVariacao(tenant.token(), criarProduto(tenant.token(), "Produto " + sufixo));
        return new TenantENota(tenant.token(), tenant.slug(), idTenant, idFornecedor, idVariacao);
    }

    private long efetivarEntrada(String token, long idFornecedor, long idVariacao, String chaveNfe) throws Exception {
        String chaveJson = chaveNfe == null ? "" : ",\"chaveNfe\":\"" + chaveNfe + "\"";
        String corpo = """
                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":3,"precoCusto":10.00}]%s}
                """.formatted(idFornecedor, idVariacao, chaveJson);
        String resp = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idMovimento")).longValue();
    }

    @Test
    void cancelamentoRevertaEstoqueEMarcaCanceladoNoMestreOriginal() throws Exception {
        TenantENota tenant = prepararTenantComProduto("estorno");
        long idMovimento = efetivarEntrada(tenant.token(), tenant.idFornecedor(), tenant.idVariacao(), null);

        try (Connection c = abrirConexao(tenant.idTenant())) {
            assertThat(buscarQtdEstoque(c, tenant.idVariacao())).isEqualByComparingTo("3.000");
        }

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Lançada por engano\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idMovimento").value(idMovimento))
                .andExpect(jsonPath("$.dataCancelamento").exists());

        try (Connection c = abrirConexao(tenant.idTenant())) {
            assertThat(buscarQtdEstoque(c, tenant.idVariacao())).isEqualByComparingTo("0.000");

            // Mestre original: nunca apagado (P3), só marcado.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT cancelado, motivo_cancelamento, id_usuario_cancelamento, tipo_movimento
                    FROM produto_movimento_mestre WHERE id_movimento = ?
                    """)) {
                ps.setLong(1, idMovimento);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean("cancelado")).isTrue();
                    assertThat(rs.getString("motivo_cancelamento")).isEqualTo("Lançada por engano");
                    assertThat(rs.getLong("id_usuario_cancelamento")).isPositive();
                    assertThat(rs.getString("tipo_movimento")).isEqualTo("COMPRA");
                }
            }

            // Estorno: novo movimento CANCELAMENTO com detalhe 'D' (inverso do 'C' original).
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.credito_debito, d.qtd_produto FROM produto_movimento_detalhe d
                    JOIN produto_movimento_mestre m ON m.id_movimento = d.id_movimento
                    WHERE m.tipo_movimento = 'CANCELAMENTO' AND d.id_variacao = ?
                    """)) {
                ps.setLong(1, tenant.idVariacao());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("credito_debito")).isEqualTo("D");
                    assertThat(rs.getBigDecimal("qtd_produto")).isEqualByComparingTo("3.000");
                }
            }
        }
    }

    @Test
    void cancelamentoApagaContasPagarGeradasPelaEntrada() throws Exception {
        TenantENota tenant = prepararTenantComProduto("contaspagar");
        String corpo = """
                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}],
                 "contasPagar":[{"numeroDuplicata":"001","dataVencimento":"2026-09-10","valor":10.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());
        String resp = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idMovimento = ((Number) JsonPath.read(resp, "$.idMovimento")).longValue();

        try (Connection c = abrirConexao(tenant.idTenant());
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM contas_pagar WHERE id_movimento = ?")) {
            ps.setLong(1, idMovimento);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Duplicata errada\"}"))
                .andExpect(status().isOk());

        try (Connection c = abrirConexao(tenant.idTenant());
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM contas_pagar WHERE id_movimento = ?")) {
            ps.setLong(1, idMovimento);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    /**
     * Regressão de 2026-08-17. O guard de "conta já paga" checava {@code documento_pago = true},
     * mas a marca de baixa é {@code data_pagamento} — {@code documento_pago} é um checkbox
     * independente que nasce {@code false} e quase nunca é marcado.
     *
     * <p>Consequência: uma conta <b>de fato baixada</b> passava batido, o cancelamento apagava a
     * linha de {@code contas_pagar}, e o {@code caixa_detalhe}/{@code conta_corrente_movimento}
     * gerado pela baixa ficava <b>órfão para sempre</b> — o dinheiro seguia saindo do caixa sem
     * nenhuma conta que o justificasse. É o mesmo bug corrigido em
     * {@code ContaPagarService.excluir()} em 2026-08-14, reproduzido no outro deletador; o vínculo
     * {@code caixa_detalhe.id_conta_pagar} não tem FK (de propósito), então o banco não reclama.
     *
     * <p>O comentário no código afirmava que "não existe tela de baixa de contas_pagar, então
     * documento_pago nunca fica true" — obsoleto desde 2026-08-12.
     */
    @Test
    void naoCancelaEntradaComContaBaixadaAindaQueDocumentoPagoNaoEstejaMarcado() throws Exception {
        TenantENota tenant = prepararTenantComProduto("baixada");
        String corpo = """
                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00}],
                 "contasPagar":[{"numeroDuplicata":"001","dataVencimento":"2026-09-10","valor":10.00}]}
                """.formatted(tenant.idFornecedor(), tenant.idVariacao());
        String resp = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idMovimento = ((Number) JsonPath.read(resp, "$.idMovimento")).longValue();

        // Estado que a tela Contas a Pagar produz numa baixa normal: data preenchida,
        // `documento_pago` intocado (false) — exatamente a condição que o guard antigo não via.
        try (Connection c = abrirConexao(tenant.idTenant());
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE contas_pagar SET data_pagamento = now(), valor_pago = 10.00
                     WHERE id_movimento = ? AND documento_pago = false
                     """)) {
            ps.setLong(1, idMovimento);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Tentativa indevida\"}"))
                .andExpect(status().isConflict());

        // Conferir o BANCO, não só o status: a conta tem que continuar lá (sem ela, o movimento
        // de dinheiro da baixa ficaria órfão) e o movimento não pode ter sido marcado cancelado.
        try (Connection c = abrirConexao(tenant.idTenant())) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM contas_pagar WHERE id_movimento = ?")) {
                ps.setLong(1, idMovimento);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cancelado FROM produto_movimento_mestre WHERE id_movimento = ?")) {
                ps.setLong(1, idMovimento);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).isFalse();
                }
            }
        }
    }

    @Test
    void naoPodeCancelarUmaEntradaJaCancelada() throws Exception {
        TenantENota tenant = prepararTenantComProduto("duplo-cancel");
        long idMovimento = efetivarEntrada(tenant.token(), tenant.idFornecedor(), tenant.idVariacao(), null);

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Primeiro cancelamento\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Segunda tentativa\"}"))
                .andExpect(status().isConflict());
    }

    /**
     * ⚠️ O "e etc" do pedido de 2026-08-20: <b>cancelar uma entrada é um débito de estoque</b>, e
     * ninguém pensa nela quando ouve "rotinas que debitam estoque". Não há uma linha sequer de
     * checagem em {@code CancelamentoEntradaService} — quem barra é a trigger (V054), que fica no
     * único caminho por onde `produto_estoque` se mexe.
     *
     * <p>O caso é real e não é acadêmico: entrou 3, vendeu 2, e alguém tenta cancelar a entrada.
     * Se passasse, o estoque ficaria em −2 e a loja teria vendido mercadoria que o sistema passa a
     * dizer que nunca entrou.
     */

    /**
     * Desliga "Permite quantidade de estoque negativo" (Parâmetros do Sistema → Estoque).
     *
     * <p>O parâmetro nasce **ligado** — a loja típica do produto não faz gestão de estoque e a
     * venda não deve travar. Quem quer estoque confiável desliga, e é essa loja que este teste
     * representa.
     */
    private void desligarEstoqueNegativo(String token) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                                 "cfgPermiteQtdDecimal":true,"cfgPermiteEstoqueNegativo":false,"cfgDiasValidadeOrcamento":15,
                                 "cfgExigeNumeroVendaDevolucao":false,
                                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,
                                 "cfgConsisteValorContasPagar":false,
                                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":false}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void cancelarEntradaCujaMercadoriaJaSaiuEhBloqueado() throws Exception {
        TenantENota tenant = prepararTenantComProduto("ja-vendida");
        desligarEstoqueNegativo(tenant.token());
        long idMovimento = efetivarEntrada(tenant.token(), tenant.idFornecedor(), tenant.idVariacao(), null);
        long idEmpresa = buscarPrimeiraEmpresa(tenant.token());

        // Saída de 2 das 3 unidades, como uma venda faria. SQL direto porque montar uma venda de
        // verdade aqui (caixa, cliente, vendedor, pagamento) seria fixture sem relação com o que
        // este teste mede — o efeito no ledger é idêntico.
        try (Connection c = abrirConexao(tenant.idTenant())) {
            long idSaida;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_usuario)
                    SELECT ?, ?, 'VENDA', u.id_usuario FROM usuario u WHERE u.id_tenant = ? LIMIT 1
                    RETURNING id_movimento
                    """)) {
                ps.setLong(1, tenant.idTenant());
                ps.setLong(2, idEmpresa);
                ps.setLong(3, tenant.idTenant());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    idSaida = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO produto_movimento_detalhe (id_tenant, id_movimento, id_empresa, id_variacao,
                                                           credito_debito, qtd_produto, preco_custo, origem)
                    VALUES (?, ?, ?, ?, 'D', 2, 10.00, 'venda simulada')
                    """)) {
                ps.setLong(1, tenant.idTenant());
                ps.setLong(2, idSaida);
                ps.setLong(3, idEmpresa);
                ps.setLong(4, tenant.idVariacao());
                ps.executeUpdate();
            }
            assertThat(buscarQtdEstoque(c, tenant.idVariacao())).isEqualByComparingTo("1.000");
        }

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Nota lancada em duplicidade\"}"))
                .andExpect(status().isConflict());

        try (Connection c = abrirConexao(tenant.idTenant())) {
            // O cancelamento inteiro foi revertido — nem o estorno de estoque, nem a marca de
            // cancelado no mestre. Meio cancelamento seria pior que nenhum.
            assertThat(buscarQtdEstoque(c, tenant.idVariacao())).isEqualByComparingTo("1.000");
        }
    }

    @Test
    void entradaInexistenteRespondeNaoEncontrado() throws Exception {
        TenantSlug tenant = assinarNovoTenant("inexistente");
        mvc.perform(post("/api/v1/estoque/entradas/999999/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Qualquer\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void motivoEmBrancoRespondeErroDeValidacao() throws Exception {
        TenantENota tenant = prepararTenantComProduto("motivo-vazio");
        long idMovimento = efetivarEntrada(tenant.token(), tenant.idFornecedor(), tenant.idVariacao(), null);

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operadorNaoPodeCancelarEntrada() throws Exception {
        TenantENota tenant = prepararTenantComProduto("operador-bloqueado");
        long idMovimento = efetivarEntrada(tenant.token(), tenant.idFornecedor(), tenant.idVariacao(), null);
        long idPrimeiraEmpresa = buscarPrimeiraEmpresa(tenant.token());
        criarOperador(tenant.token(), "operador@lojacancela.com", idPrimeiraEmpresa);
        String tokenOperador = logarComo(tenant.slug(), "operador@lojacancela.com");

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tokenOperador)
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"Tentativa de operador\"}"))
                .andExpect(status().isForbidden());
    }

    /** Cancelar uma entrada importada por XML libera a `chave_nfe` pra reimportar a MESMA nota
     *  corrigida — sem isso, o índice único (`WHERE chave_nfe IS NOT NULL AND cancelado = false`,
     *  2026-08-12) bloquearia pra sempre, mesmo depois de cancelada. */
    @Test
    void cancelamentoDeEntradaXmlLiberaChaveNfeParaReimportar() throws Exception {
        TenantENota tenant = prepararTenantComProduto("libera-chave");
        String chave = "35260812345678000199550010000001231234567890";
        long idMovimento = efetivarEntrada(tenant.token(), tenant.idFornecedor(), tenant.idVariacao(), chave);

        mvc.perform(post("/api/v1/estoque/entradas/" + idMovimento + "/cancelar")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"NF-e errada\"}"))
                .andExpect(status().isOk());

        long novoIdMovimento = efetivarEntrada(tenant.token(), tenant.idFornecedor(), tenant.idVariacao(), chave);
        assertThat(novoIdMovimento).isNotEqualTo(idMovimento);
    }
}
