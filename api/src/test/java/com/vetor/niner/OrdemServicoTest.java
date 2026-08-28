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
 * Ordem de Serviço (bloco S4, V087) — {@code docs/MODULOSERVICOS.md} §4.2.
 *
 * <p>⚠️ <b>O que estes testes protegem.</b> A OS é a primeira coisa do sistema a escrever em
 * {@code produto_estoque.reservado} — coluna que existe desde julho e nunca teve produtor. Reserva
 * é o tipo de estado que erra em silêncio: sobra pendurada e o disponível fica menor que o físico
 * sem nada apontar a causa. Por isso todo teste de reserva confere o <b>banco</b>, e há sempre o
 * par (reservou × devolveu).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrdemServicoTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String token;
    private long idTenant;
    private long idCliente;
    private long idFuncionario;
    private long idVariacaoPeca;
    private long idVariacaoServico;

    /** Monta um tenant completo: cliente, funcionário, uma peça e um serviço com variação. */
    private void prepararTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja OS %s","email":"dono%s@lojaos.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        token = JsonPath.read(resp, "$.token");

        String eu = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        idTenant = ((Number) JsonPath.read(eu, "$.id_tenant")).longValue();

        ligarServicos();
        idCliente = criarCliente();
        idFuncionario = criarFuncionario();

        long idPeca = criarProduto("FILTRO DE OLEO", "MERCADORIA", "45.00");
        long idServico = criarProduto("TROCA DE OLEO", "SERVICO", "120.00");
        try (Connection c = abrirConexao()) {
            idVariacaoPeca = criarVariacao(c, idPeca);
            idVariacaoServico = criarVariacao(c, idServico);
        }
    }

    private void ligarServicos() throws Exception {
        String atual = mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(atual.replaceFirst("\"cfgUsaServicos\":\\s*false", "\"cfgUsaServicos\":true")))
                .andExpect(status().isOk());
    }

    private long criarCliente() throws Exception {
        // O signup não semeia categoria de cliente — o teste cria a própria massa, em vez de
        // depender do estado do banco (feedback_teste_pulado_nao_prova_nada).
        String cat = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"GERAL\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long idCategoria = ((Number) JsonPath.read(cat, "$.idCategoriaCliente")).longValue();
        String body = """
                {"nome":"CLIENTE DA OFICINA","fisicaJuridica":true,"cpfCnpj":"111.444.777-35",
                 "idCategoriaCliente":%d,"genero":"MASCULINO","dataNascimento":"1990-01-01"}
                """.formatted(idCategoria);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private long criarFuncionario() throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"MECANICO\",\"percComissao\":5.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long criarProduto(String descricao, String tipo, String preco) throws Exception {
        String body = """
                {"descricao":"%s","precoCusto":0,"percentualVenda":0,"precoVenda":%s,"tipoItem":"%s"}
                """.formatted(descricao, preco, tipo);
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private Connection abrirConexao() throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private long criarVariacao(Connection c, long idProduto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (?, ?, gerar_ean13_interno())"
                        + " RETURNING id_variacao")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** O reservado no banco — 0 quando não há linha ainda, que é o mesmo efeito prático. */
    private BigDecimal reservado(long idVariacao) throws SQLException {
        try (Connection c = abrirConexao();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(reservado), 0) FROM produto_estoque WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private String corpoDaOs(String qtdPeca) {
        return """
                {"idCliente":%d,"idFuncionario":%d,"objetoServico":"ABC-1234",
                 "itens":[{"idVariacao":%d,"qtdProduto":%s},
                          {"idVariacao":%d,"qtdProduto":1,"idFuncionario":%d}]}
                """.formatted(idCliente, idFuncionario, idVariacaoPeca, qtdPeca,
                idVariacaoServico, idFuncionario);
    }

    private long criarOs(String qtdPeca) throws Exception {
        String resp = mvc.perform(post("/api/v1/ordens-servico").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoDaOs(qtdPeca)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idOrdemServico")).longValue();
    }

    // ---------------------------------------------------------------- o caso central

    /** A OS nasce com serviço E peça juntos (DS14), e separa os dois totais. */
    @Test
    void aOsNasceComServicoEPecaESeparaOsTotais() throws Exception {
        prepararTenant("a");
        long id = criarOs("2");

        mvc.perform(get("/api/v1/ordens-servico/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("ABERTA"))
                .andExpect(jsonPath("$.objetoServico").value("ABC-1234"))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.totalPecas").value(90.00))      // 2 × 45,00
                .andExpect(jsonPath("$.totalServicos").value(120.00))
                .andExpect(jsonPath("$.total").value(210.00));
    }

    /** ⭐ DS19 — a peça reserva ao ser LANÇADA, antes mesmo da aprovação. */
    @Test
    void aPecaReservaEstoqueAoSerLancada() throws Exception {
        prepararTenant("b");
        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("0");
        criarOs("2");
        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("2.000");
    }

    /** O par: serviço não tem saldo, então nunca reserva (V086). */
    @Test
    void servicoNaoReservaNada() throws Exception {
        prepararTenant("c");
        criarOs("2");
        assertThat(reservado(idVariacaoServico)).isEqualByComparingTo("0");
    }

    /**
     * ⭐ DS17 — o caminho da OS parada é CANCELAR, e a reserva volta. Decisão dele em 2026-08-28,
     * no lugar de um worker de expiração.
     */
    @Test
    void cancelarDevolveAReservaAoEstoque() throws Exception {
        prepararTenant("d");
        long id = criarOs("3");
        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("3.000");

        mvc.perform(post("/api/v1/ordens-servico/" + id + "/cancelar")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"CLIENTE NAO VOLTOU\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("CANCELADA"));

        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("0");
    }

    /**
     * ⚠️ Cancelar duas vezes não pode liberar duas vezes — seria reserva negativa (o CHECK do banco
     * barraria) ou, pior, estoque disponível inflado.
     */
    @Test
    void cancelarDuasVezesNaoLiberaEmDobro() throws Exception {
        prepararTenant("e");
        long id = criarOs("3");
        String url = "/api/v1/ordens-servico/" + id + "/cancelar";
        String corpo = "{\"motivo\":\"ENGANO\"}";

        mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON).content(corpo)).andExpect(status().isOk());
        mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON).content(corpo)).andExpect(status().isConflict());

        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("0");
    }

    /**
     * ⭐ A OS é MUTÁVEL — é a diferença essencial para o orçamento, e a razão de existir: o mecânico
     * abre o motor e acha mais serviço. A reserva acompanha por delta.
     *
     * <p>⚠️ Este é o teste que pega o erro clássico de "apaga e regrava": liberar a reserva pela
     * quantidade NOVA em vez da que a linha reservou deixa resto pendurado para sempre.
     */
    @Test
    void alterarAOsAjustaAReservaSemDeixarResto() throws Exception {
        prepararTenant("f");
        long id = criarOs("5");
        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("5.000");

        mvc.perform(put("/api/v1/ordens-servico/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoDaOs("2")))
                .andExpect(status().isOk());

        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("2.000");
    }

    // ---------------------------------------------------------------- estados

    @Test
    void aSituacaoAvancaUmPassoDeCadaVez() throws Exception {
        prepararTenant("g");
        long id = criarOs("1");
        String url = "/api/v1/ordens-servico/" + id + "/situacao?para=";

        mvc.perform(put(url + "APROVADA").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.situacao").value("APROVADA"));
        mvc.perform(put(url + "EM_EXECUCAO").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(put(url + "CONCLUIDA").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataConclusao").isNotEmpty());
    }

    /** Voltar apagaria a informação de que o trabalho começou — o que a tela usa para saber o que
     *  está na bancada. Se foi engano, cancela e abre outra. */
    @Test
    void aSituacaoNaoVoltaAtras() throws Exception {
        prepararTenant("h");
        long id = criarOs("1");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=ABERTA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
    }

    /**
     * ⛔ FATURADA só existe com uma venda por trás — o CHECK do banco garante isso, e este endpoint
     * não pode ser a porta dos fundos que fura a garantia.
     */
    @Test
    void naoSeMarcaComoFaturadaPelaMaoNemSeCancelaPorAqui() throws Exception {
        prepararTenant("i");
        long id = criarOs("1");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=FATURADA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CANCELADA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }

    @Test
    void osCanceladaNaoPodeMaisSerAlterada() throws Exception {
        prepararTenant("j");
        long id = criarOs("1");
        mvc.perform(post("/api/v1/ordens-servico/" + id + "/cancelar")
                .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                .content("{\"motivo\":\"ENGANO\"}")).andExpect(status().isOk());

        mvc.perform(put("/api/v1/ordens-servico/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoDaOs("1")))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- o F5 do PDV

    /** ⭐ DS18 — só a CONCLUÍDA aparece para o PDV puxar. */
    @Test
    void soAOsConcluidaApareceParaOPdv() throws Exception {
        prepararTenant("k");
        long id = criarOs("1");
        String url = "/api/v1/ordens-servico/faturaveis?idCliente=" + idCliente;

        mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].objetoServico").value("ABC-1234"));
    }

    // ---------------------------------------------------------------- busca e isolamento

    /** O balcão procura pela placa/animal, não pelo número — é o campo que a §4.2 tornou obrigatório. */
    @Test
    void buscaPelaPlacaEPeloNomeDoCliente() throws Exception {
        prepararTenant("l");
        criarOs("1");

        mvc.perform(get("/api/v1/ordens-servico?busca=abc-12").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itens.length()").value(1));
        mvc.perform(get("/api/v1/ordens-servico?busca=oficina").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itens.length()").value(1));
        mvc.perform(get("/api/v1/ordens-servico?busca=XYZ-0000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itens.length()").value(0));
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        prepararTenant("x");
        long idOutroTenant = criarOs("1");

        prepararTenant("y");   // troca o token para um tenant novo
        mvc.perform(get("/api/v1/ordens-servico/" + idOutroTenant).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
