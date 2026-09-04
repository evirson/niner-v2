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

    /**
     * ⚠️ Obter-ou-criar, não criar (2026-08-31). Desde que o serviço passa a nascer com a própria
     * variação — sem ela ficava invisível na Ordem de Serviço e no PDV —, o INSERT cego bate em
     * produto_barra_variacao_uk. Este helper não estava errado: criava a variação à mão porque o
     * cadastro não criava, e o que os testes precisam é TER uma.
     */
    private long criarVariacao(Connection c, long idProduto) throws SQLException {
        try (PreparedStatement busca = c.prepareStatement(
                "SELECT id_variacao FROM produto_barra WHERE id_produto = ? LIMIT 1")) {
            busca.setLong(1, idProduto);
            try (ResultSet rs = busca.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

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

    // ---------------------------------------------------------------- a OS virando venda

    private void abrirCaixa() throws Exception {
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteiraDinheiro())))
                .andExpect(status().isOk());
    }

    private long idCarteiraDinheiro() throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return ((Number) carteiras.stream()
                .filter(x -> "DINHEIRO".equals(x.get("nomeCarteira")))
                .findFirst().orElseThrow().get("idCarteira")).longValue();
    }

    /** Vende a OS inteira: 1 peça + 1 serviço, ambos marcados como dela. */
    private org.springframework.test.web.servlet.ResultActions venderDaOs(long idOs, String valorPago)
            throws Exception {
        return mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"idOrdemServico":%d,"idCliente":%d,"idFuncionario":%d,"descontoVenda":0,
                         "itens":[{"idVariacao":%d,"qtd":1,"daOrdemServico":true},
                                  {"idVariacao":%d,"qtd":1,"daOrdemServico":true}],
                         "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                        """.formatted(idOs, idCliente, idFuncionario, idVariacaoPeca, idVariacaoServico,
                        idCarteiraDinheiro(), valorPago)));
    }

    /**
     * ⭐ O caminho completo: OS concluída vira venda pelo PDV, com o preço <b>congelado</b> (DS16).
     *
     * <p>⛔ E o que mais importa aqui: <b>a reserva é liberada</b>. A venda debitou o estoque pelo
     * ledger; se a reserva ficasse, a mesma peça estaria contada duas vezes e o disponível ficaria
     * permanentemente menor que o físico, sem nada apontando a causa.
     */
    @Test
    void osConcluidaViraVendaNoPdvELiberaAReserva() throws Exception {
        prepararTenant("m");
        abrirCaixa();
        long id = criarOs("1");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("1.000");

        venderDaOs(id, "165.00")   // 45,00 da peça + 120,00 do serviço
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idOrdemServico").value(id))
                .andExpect(jsonPath("$.valorTotalProdutos").value(165.00));

        // a reserva sumiu...
        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("0");
        // ...e o estoque baixou de verdade (só a peça — serviço não tem saldo)
        try (Connection c = abrirConexao();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT qtd_estoque FROM produto_estoque WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacaoPeca);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("-1.000");
            }
        }
        assertThat(reservado(idVariacaoServico)).isEqualByComparingTo("0");

        mvc.perform(get("/api/v1/ordens-servico/" + id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.situacao").value("FATURADA"))
                .andExpect(jsonPath("$.idVenda").isNotEmpty());
    }

    /** ⛔ DS18 — OS que não está concluída não vira venda, e a mensagem diz o estado. */
    /**
     * ⭐ O executor do item da OS vira o funcionário da LINHA do movimento (DS5), e o vendedor da
     * venda continua sendo quem fechou o caixa.
     *
     * <p>Este é o par que pega a regressão de 2026-08-28: ao gravar o executor por linha, cinco
     * telas que derivavam "o vendedor da venda" do ledger (com {@code MAX(pmd.id_funcionario)} ou
     * {@code LIMIT 1}) passaram a mostrar o <b>mecânico</b> no lugar de quem vendeu — a Pesquisa
     * de Vendas exibiu isso primeiro. A V089 deu à venda o próprio vendedor.
     *
     * <p>⚠️ O caso NEGATIVO é a segunda metade: a linha da <b>peça</b>, que a OS não atribuiu a
     * ninguém, tem de ficar com o vendedor da venda. Sem ela, uma implementação que jogasse o
     * executor em todas as linhas passaria no positivo.
     */
    @Test
    void oExecutorVaiParaALinhaEOVendedorFicaNaVenda() throws Exception {
        prepararTenant("z4");
        long idMecanico = criarFuncionarioMecanico();
        long id = criarOsComExecutor(idMecanico);
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        abrirCaixa();

        String resp = venderDaOs(id, "165.00").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idVenda = ((Number) JsonPath.read(resp, "$.idVenda")).longValue();

        try (Connection c = abrirConexao()) {
            // A venda guarda quem VENDEU — o `idFuncionario` que o PDV recebeu, não o mecânico.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id_funcionario FROM venda WHERE id_venda = ?")) {
                ps.setLong(1, idVenda);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).isEqualTo(idFuncionario);
                }
            }
            // …e cada LINHA guarda quem executou: o serviço com o mecânico, a peça com o vendedor.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT pmd.id_variacao, pmd.id_funcionario, pmd.perc_comissao
                      FROM produto_movimento_mestre pmm
                      JOIN produto_movimento_detalhe pmd
                             ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                     WHERE pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                    """)) {
                ps.setLong(1, idVenda);
                int conferidas = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long idVariacao = rs.getLong("id_variacao");
                        if (idVariacao == idVariacaoServico) {
                            assertThat(rs.getLong("id_funcionario")).isEqualTo(idMecanico);
                            conferidas++;
                        } else if (idVariacao == idVariacaoPeca) {
                            assertThat(rs.getLong("id_funcionario")).isEqualTo(idFuncionario);
                            conferidas++;
                        }
                        // O percentual é congelado na gravação (V088), nunca nulo em linha nova.
                        assertThat(rs.getBigDecimal("perc_comissao")).isNotNull();
                    }
                }
                assertThat(conferidas).isEqualTo(2);
            }
        }
    }

    private long criarFuncionarioMecanico() throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"MECANICO DO SERVICO\",\"percComissao\":15.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    /** Igual a criarOs("1"), mas atribuindo o SERVIÇO a um executor próprio. */
    private long criarOsComExecutor(long idExecutor) throws Exception {
        String body = """
                {"idCliente":%d,"idFuncionario":%d,"objetoServico":"ABC-1234",
                 "itens":[{"idVariacao":%d,"qtdProduto":1},
                          {"idVariacao":%d,"qtdProduto":1,"idFuncionario":%d}]}
                """.formatted(idCliente, idFuncionario, idVariacaoPeca, idVariacaoServico, idExecutor);
        String resp = mvc.perform(post("/api/v1/ordens-servico").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idOrdemServico")).longValue();
    }

    /**
     * ⭐ O preço da OS é CONGELADO — a venda sai pelo preço que a OS fechou, não pelo de hoje.
     *
     * <p><b>O defeito que este teste prende</b> (achado de auditoria, 2026-08-29): o
     * {@code resolverItens} do PDV testava só {@code item.ehDoOrcamento()}, e a linha vinda da OS
     * chega com {@code daOrdemServico: true} e {@code doOrcamento} ausente — o preço congelado
     * <b>nunca era aplicado</b> e a venda saía pelo cadastro de hoje.
     *
     * <p>⚠️ <b>Por que o teste que existia não pegou:</b> {@code osConcluidaViraVendaNoPdv...} cria
     * a OS sem preço explícito e nunca mexe no cadastro — preço da OS = preço de cadastro, e as
     * duas fontes ficam indistinguíveis. Aqui o cadastro é <b>reajustado depois</b> de a OS existir,
     * que é o único jeito de separar uma da outra.
     *
     * <p>⚠️ O sintoma engana nas duas direções: preço para MAIS recusa a venda com <i>"os
     * pagamentos não fecham o saldo"</i> (mensagem sobre pagamento para um problema de preço); para
     * MENOS, a venda fecha e a loja cobra menos do que aprovou, sem erro nenhum.
     */
    @Test
    void aVendaUsaOPrecoCONGELADOdaOsMesmoDepoisDeReajuste() throws Exception {
        prepararTenant("z5");
        long id = criarOs("1");   // peça a 45,00 + serviço a 120,00 = 165,00
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        // O lojista reajusta a tabela DEPOIS de a OS estar fechada com o cliente.
        try (Connection c = abrirConexao();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE produto SET preco_venda = preco_venda * 2
                      WHERE id_produto IN (SELECT id_produto FROM produto_barra WHERE id_variacao IN (?, ?))
                     """)) {
            ps.setLong(1, idVariacaoPeca);
            ps.setLong(2, idVariacaoServico);
            ps.executeUpdate();
        }

        abrirCaixa();
        // A venda continua fechando em 165,00 — o preço que a OS prometeu, não os 330,00 de hoje.
        venderDaOs(id, "165.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valorTotalProdutos").value(165.00));
    }

    @Test
    void osNaoConcluidaNaoViraVenda() throws Exception {
        prepararTenant("n");
        abrirCaixa();
        long id = criarOs("1");
        venderDaOs(id, "165.00").andExpect(status().isConflict());
    }

    /** A mesma OS não vira duas vendas — o UPDATE condicional é quem garante. */
    @Test
    void aMesmaOsNaoViraDuasVendas() throws Exception {
        prepararTenant("o");
        abrirCaixa();
        long id = criarOs("1");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        venderDaOs(id, "165.00").andExpect(status().isCreated());
        venderDaOs(id, "165.00").andExpect(status().isConflict());
    }

    /**
     * ⚠️ A guarda de contrato, igual à do orçamento: mandar {@code idOrdemServico} sem marcar
     * nenhuma linha faria a venda sair a preço de cadastro e consumir a OS sem honrar o aprovado —
     * e ninguém veria erro nenhum.
     */
    @Test
    void vendaComOsMasSemLinhaMarcadaEhRecusada() throws Exception {
        prepararTenant("p");
        abrirCaixa();
        long id = criarOs("1");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idOrdemServico":%d,"idCliente":%d,"idFuncionario":%d,"descontoVenda":0,
                                 "itens":[{"idVariacao":%d,"qtd":1}],
                                 "pagamentos":[{"idCarteira":%d,"valorPago":45.00,"numeroParcelas":1}]}
                                """.formatted(id, idCliente, idFuncionario, idVariacaoPeca,
                                idCarteiraDinheiro())))
                .andExpect(status().isBadRequest());
    }

    /** Levar MAIS do que a OS aprovou é recusado — pode-se levar menos, nunca mais (DS16). */
    @Test
    void naoSeLevaMaisDoQueAOsAprovou() throws Exception {
        prepararTenant("q");
        abrirCaixa();
        long id = criarOs("1");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idOrdemServico":%d,"idCliente":%d,"idFuncionario":%d,"descontoVenda":0,
                                 "itens":[{"idVariacao":%d,"qtd":5,"daOrdemServico":true}],
                                 "pagamentos":[{"idCarteira":%d,"valorPago":225.00,"numeroParcelas":1}]}
                                """.formatted(id, idCliente, idFuncionario, idVariacaoPeca,
                                idCarteiraDinheiro())))
                .andExpect(status().isBadRequest());
    }

    /** ⛔ Orçamento e OS não se combinam: qual preço venceria se o item estivesse nos dois? */
    @Test
    void naoSeVendeDeOrcamentoEDeOsAoMesmoTempo() throws Exception {
        prepararTenant("r");
        abrirCaixa();
        long id = criarOs("1");
        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idOrdemServico":%d,"idOrcamento":1,"idCliente":%d,"idFuncionario":%d,
                                 "descontoVenda":0,
                                 "itens":[{"idVariacao":%d,"qtd":1,"daOrdemServico":true}],
                                 "pagamentos":[{"idCarteira":%d,"valorPago":45.00,"numeroParcelas":1}]}
                                """.formatted(id, idCliente, idFuncionario, idVariacaoPeca,
                                idCarteiraDinheiro())))
                .andExpect(status().isBadRequest());
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

    /**
     * ⭐ A resposta traz cor e tamanho — e o par NEGATIVO é o que importa aqui.
     *
     * <p>Achado abrindo a tela em 2026-08-28: a linha nasce com a variação (vem da pesquisa de
     * produto) e a <b>perdia ao recarregar</b>, porque a resposta não a trazia de volta. Numa OS
     * com duas peças do mesmo produto em cores diferentes, as duas linhas ficariam idênticas.
     *
     * <p>O caso negativo prende a sentinela: produto sem grade tem {@code id_cor = 1} (PADRÃO) e
     * precisa vir <b>nulo</b>, não a palavra "PADRÃO" impressa ao lado de todo item da oficina.
     */
    @Test
    void aRespostaTrazCorETamanhoESentinelaVemNula() throws Exception {
        prepararTenant("z2");
        long idVariacaoComGrade;
        // ⚠️ `cfg_cor`/`cfg_tamanho` têm PK de NEGÓCIO (id_tenant, id_cor) — sem sequência, o id
        // vem do chamador. O 1 é a sentinela PADRÃO; qualquer outro serve.
        long idCor = 100;
        long idTamanho = 100;
        try (Connection c = abrirConexao()) {
            inserir(c, "INSERT INTO cfg_cor (id_tenant, id_cor, descricao) VALUES (?, " + idCor + ", 'FERRUGEM')");
            inserir(c, "INSERT INTO cfg_tamanho (id_tenant, id_tamanho, descricao) VALUES (?, " + idTamanho + ", '35')");
            long idProduto = criarProduto("PALHETA", "MERCADORIA", "30.00");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO produto_barra (id_tenant, id_produto, id_cor, id_tamanho, sku)"
                            + " VALUES (?, ?, ?, ?, gerar_ean13_interno()) RETURNING id_variacao")) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idProduto);
                ps.setLong(3, idCor);
                ps.setLong(4, idTamanho);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    idVariacaoComGrade = rs.getLong(1);
                }
            }
        }

        String body = """
                {"idCliente":%d,"idFuncionario":%d,"objetoServico":"ABC-1234",
                 "itens":[{"idVariacao":%d,"qtdProduto":1},{"idVariacao":%d,"qtdProduto":1}]}
                """.formatted(idCliente, idFuncionario, idVariacaoComGrade, idVariacaoServico);
        String resp = mvc.perform(post("/api/v1/ordens-servico").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idOrdemServico")).longValue();

        mvc.perform(get("/api/v1/ordens-servico/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // a peça com grade traz os dois…
                .andExpect(jsonPath("$.itens[0].variacaoCor").value("FERRUGEM"))
                .andExpect(jsonPath("$.itens[0].variacaoTamanho").value("35"))
                // …e o serviço, que não tem grade, traz NULO — nunca a sentinela PADRÃO.
                .andExpect(jsonPath("$.itens[1].variacaoCor").doesNotExist())
                .andExpect(jsonPath("$.itens[1].variacaoTamanho").doesNotExist());
    }

    private void inserir(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, idTenant);
            ps.executeUpdate();
        }
    }

    /**
     * ⛔ Abrir OS exige o módulo ligado — e a trava é do SERVIDOR, não do menu (P4).
     *
     * <p>⭐ E o par completo: com o módulo desligado depois, <b>alterar e cancelar continuam
     * valendo</b>. Sem isso, quem desligasse o módulo com OS abertas trancaria as peças reservadas
     * para sempre, sem caminho para devolvê-las ao estoque — uma trava que impede de sair da
     * situação é pior que a situação.
     */
    @Test
    void semOModuloNaoSeAbreOsMasSeCancelaAQueJaExiste() throws Exception {
        prepararTenant("z3");
        long id = criarOs("2");
        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("2.000");

        desligarServicos();

        mvc.perform(post("/api/v1/ordens-servico").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoDaOs("1")))
                .andExpect(status().isBadRequest());

        // …mas a OS que já existe continua podendo ser desfeita, e a reserva volta.
        mvc.perform(post("/api/v1/ordens-servico/" + id + "/cancelar")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"CLIENTE DESISTIU\"}"))
                .andExpect(status().isOk());
        assertThat(reservado(idVariacaoPeca)).isEqualByComparingTo("0");
    }

    private void desligarServicos() throws Exception {
        String atual = mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(atual.replaceFirst("\"cfgUsaServicos\":\s*true", "\"cfgUsaServicos\":false")))
                .andExpect(status().isOk());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        prepararTenant("x");
        long idOutroTenant = criarOs("1");

        prepararTenant("y");   // troca o token para um tenant novo
        mvc.perform(get("/api/v1/ordens-servico/" + idOutroTenant).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
    /**
     * ⭐ Cancelar a venda <b>cancela a OS</b> — e a OS guarda qual venda caiu.
     *
     * <h2>⛔ O que acontecia antes (medido ao vivo em 2026-08-28, venda 621 ← OS 3)</h2>
     *
     * <p>A venda era cancelada, o estoque voltava, e a OS continuava {@code FATURADA} apontando
     * para uma venda que não existe mais — <b>sem caminho de volta</b>: {@code FATURADA} não se
     * cancela pela tela da OS e o F5 só oferece {@code CONCLUIDA}. O lojista teria de abrir outra
     * OS do zero, redigitando serviços, peças e executor de um trabalho já feito.
     *
     * <p>Decisão do dono do produto (2026-08-29): <i>"Se for cancelada uma venda, que tem uma OS ou
     * um ORÇAMENTO, cancela a venda e tb OS e ou ORÇAMENTO."</i>
     *
     * <h2>⚠️ As três coisas que este teste confere, e nenhuma é opcional</h2>
     *
     * <ol>
     *   <li>a OS ficou {@code CANCELADA};</li>
     *   <li>o {@code id_venda} <b>sobreviveu</b> — é ele que responde "qual venda caiu?" (a V092
     *       afrouxou o CHECK exatamente para isso). Um teste que olhasse só a situação passaria
     *       com o rastro perdido;</li>
     *   <li>a reserva <b>continua zerada</b>. O faturamento já a liberou; o cancelamento não pode
     *       liberar de novo, senão desconta de `produto_estoque` uma reserva que não existe mais —
     *       e é o tipo de erro que só aparece semanas depois, num disponível menor que o físico.</li>
     * </ol>
     */
    @Test
    void cancelarAVendaCancelaAOrdemDeServicoDeOrigem() throws Exception {
        prepararTenant("cancel-os");
        abrirCaixa();
        long id = criarOs("1");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        String venda = venderDaOs(id, "165.00")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idVenda = ((Number) JsonPath.read(venda, "$.idVenda")).longValue();

        mvc.perform(get("/api/v1/ordens-servico/" + id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.situacao").value("FATURADA"));

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"Cliente desistiu do servico\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/ordens-servico/" + id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.situacao").value("CANCELADA"))
                .andExpect(jsonPath("$.idVenda").value((int) idVenda))
                .andExpect(jsonPath("$.motivoCancelamento").value(
                        org.hamcrest.Matchers.containsString("VENDA Nº " + idVenda + " CANCELADA")));

        assertThat(reservado(idVariacaoPeca))
                .as("a reserva foi liberada no faturamento; o cancelamento não pode liberar de novo")
                .isEqualByComparingTo("0");
    }


    // ------------------------------------------- auditoria 2026-08-29, rodada 1

    /**
     * ⛔ <b>A venda não pode cobrar mais do que a OS aprovou.</b>
     *
     * <p>Até esta data o servidor lia do banco o <b>preço</b> congelado e aceitava o
     * <b>desconto</b> cru de {@code req.descontoVenda()} — calculado no {@code Pdv.tsx}. Uma OS de
     * R$ 165,00 com R$ 65,00 de desconto, impressa e assinada em R$ 100,00, fechava a venda em
     * R$ 165,00 se o cliente da API mandasse {@code descontoVenda: 0}: a OS ia para FATURADA
     * apontando para essa venda e <b>nada</b> registrava que a loja cobrou R$ 65,00 a mais.
     *
     * <p>⚠️ O par positivo está em {@link #descontoDaOsMenorPeloQueFoiLevadoEhAceito()} — sem ele,
     * uma versão que recusasse toda venda parcial passaria neste teste.
     */
    @Test
    void vendaDaOsNaoPodeIgnorarODescontoAprovado() throws Exception {
        prepararTenant("w1");
        abrirCaixa();
        permitirDesconto();
        long id = criarOsComDesconto("65.00");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        venderDaOs(id, "165.00")
                .andExpect(status().isConflict());

        // E com o desconto que a OS aprovou, a mesma venda passa.
        venderDaOsComDesconto(id, "65.00", "100.00").andExpect(status().isCreated());
    }

    /**
     * ⭐ O caso NEGATIVO do guarda acima: o cliente pode <b>levar menos</b> do que a OS aprovou, e
     * aí o desconto exigido é <b>proporcional</b> — cobrar o desconto cheio sobre metade dos itens
     * transformaria uma venda parcial legítima em 409.
     *
     * <p>A OS aprova peça (R$ 45,00) + serviço (R$ 120,00) = R$ 165,00 com R$ 66,00 de desconto
     * (40%). Levando só o serviço, o piso é 40% de R$ 120,00 = R$ 48,00.
     */
    @Test
    void descontoDaOsMenorPeloQueFoiLevadoEhAceito() throws Exception {
        prepararTenant("w2");
        abrirCaixa();
        permitirDesconto();
        long id = criarOsComDesconto("66.00");
        mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao?para=CONCLUIDA")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idOrdemServico":%d,"idCliente":%d,"idFuncionario":%d,"descontoVenda":48.00,
                                 "itens":[{"idVariacao":%d,"qtd":1,"daOrdemServico":true}],
                                 "pagamentos":[{"idCarteira":%d,"valorPago":72.00,"numeroParcelas":1}]}
                                """.formatted(id, idCliente, idFuncionario, idVariacaoServico,
                                idCarteiraDinheiro())))
                .andExpect(status().isCreated());
    }

    /**
     * ⛔ <b>A mesma variação não pode entrar duas vezes com preços diferentes.</b>
     *
     * <p>O PDV congela o preço da OS num {@code Map<idVariacao, preco>}, então de duas linhas só a
     * última sobrevivia — enquanto a validação de quantidade somava as duas. O serviço a
     * 1 × R$ 100,00 (negociado) + 1 × R$ 120,00 virava uma venda de <b>2 × R$ 120,00 = R$ 240,00</b>
     * contra os R$ 220,00 que a OS impressa promete.
     *
     * <p>⚠️ Recusa na ORIGEM: travar no fechamento da venda deixaria o operador com o cliente na
     * frente e uma OS impossível de faturar.
     */
    @Test
    void osNaoAceitaAMesmaVariacaoComDoisPrecos() throws Exception {
        prepararTenant("w3");
        String body = """
                {"idCliente":%d,"idFuncionario":%d,"objetoServico":"ABC-1234",
                 "itens":[{"idVariacao":%d,"qtdProduto":1,"precoVenda":120.00},
                          {"idVariacao":%d,"qtdProduto":1,"precoVenda":100.00}]}
                """.formatted(idCliente, idFuncionario, idVariacaoServico, idVariacaoServico);
        mvc.perform(post("/api/v1/ordens-servico").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        // O par: o MESMO preço nas duas linhas continua valendo — a trava é sobre divergência,
        // não sobre repetir o item.
        String iguais = """
                {"idCliente":%d,"idFuncionario":%d,"objetoServico":"ABC-1234",
                 "itens":[{"idVariacao":%d,"qtdProduto":1,"precoVenda":100.00},
                          {"idVariacao":%d,"qtdProduto":1,"precoVenda":100.00}]}
                """.formatted(idCliente, idFuncionario, idVariacaoServico, idVariacaoServico);
        mvc.perform(post("/api/v1/ordens-servico").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(iguais))
                .andExpect(status().isCreated());
    }

    /**
     * Libera desconto de venda até 50% neste tenant — o padrão é 0% ("nenhum desconto"), e sem
     * isto a própria OS com desconto é recusada por {@code exigirDescontoDentroDoTeto}.
     */
    private void permitirDesconto() throws Exception {
        String atual = mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(atual.replaceFirst("\"percentualDescontoVenda\":\s*[0-9.]+", "\"percentualDescontoVenda\":50")))
                .andExpect(status().isOk());
    }

    /** OS com desconto de documento — o total impresso é 165,00 menos o desconto. */
    private long criarOsComDesconto(String desconto) throws Exception {
        String body = """
                {"idCliente":%d,"idFuncionario":%d,"objetoServico":"ABC-1234","valorDesconto":%s,
                 "itens":[{"idVariacao":%d,"qtdProduto":1},
                          {"idVariacao":%d,"qtdProduto":1,"idFuncionario":%d}]}
                """.formatted(idCliente, idFuncionario, desconto, idVariacaoPeca,
                idVariacaoServico, idFuncionario);
        String resp = mvc.perform(post("/api/v1/ordens-servico").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idOrdemServico")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions venderDaOsComDesconto(
            long idOs, String desconto, String valorPago) throws Exception {
        return mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"idOrdemServico":%d,"idCliente":%d,"idFuncionario":%d,"descontoVenda":%s,
                         "itens":[{"idVariacao":%d,"qtd":1,"daOrdemServico":true},
                                  {"idVariacao":%d,"qtd":1,"daOrdemServico":true}],
                         "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                        """.formatted(idOs, idCliente, idFuncionario, desconto, idVariacaoPeca,
                        idVariacaoServico, idCarteiraDinheiro(), valorPago)));
    }

    /**
     * ⭐ <b>Corrida real: duas OS reservando a MESMA peça ao mesmo tempo.</b>
     *
     * <p>A invariante aqui é de <b>soma</b>, não de exclusão: as duas OS <i>devem</i> ser abertas, e
     * a reserva tem de ficar em <b>2</b>. O defeito que este teste exclui é o oposto do das outras
     * corridas — não é "as duas passaram quando só uma podia", é "as duas passaram e a reserva
     * ficou em 1".
     *
     * <p><b>Por que isso é possível.</b> A reserva é um ajuste por <b>delta</b>
     * ({@code UPDATE produto_estoque SET reservado = GREATEST(reservado + ?, 0)}), e a atomicidade
     * disso não é óbvia: escrito como "lê o reservado, soma em Java, grava o total", as duas
     * transações leriam 0, as duas gravariam 1, e uma peça sairia da oficina sem estar reservada
     * para ninguém — a segunda OS trabalharia com estoque que a primeira já contava como seu.
     * O {@code reservado + ?} dentro do próprio UPDATE é o que serializa, porque o Postgres trava a
     * linha para o read-modify-write.
     *
     * <p>⚠️ Não mede tempo nem dorme (ver {@code feedback_testes_frageis_por_relogio}): as threads
     * saem juntas de uma {@link java.util.concurrent.CountDownLatch}. Se não se cruzarem numa
     * execução, o teste continua verdadeiro — ele nunca acusa falso.
     *
     * <p>⭐ A prova vem do <b>banco</b> ({@code produto_estoque.reservado}), não do status HTTP:
     * duas respostas 201 são compatíveis tanto com a reserva certa quanto com a perdida.
     *
     * <p>⛔ <b>Este teste achou um defeito de verdade ao ser escrito (2026-09-04).</b> A reserva usava
     * "UPDATE primeiro, INSERT se casou zero linhas", que é correto sequencialmente e tem uma
     * <b>janela</b> entre os dois comandos: com a peça ainda sem linha em {@code produto_estoque}, as
     * duas OS faziam o UPDATE, as duas casavam zero, e as duas chegavam ao INSERT — a segunda violava
     * {@code produto_estoque_uk} e o lojista via <i>"Registro em uso por outro cadastro — não pode ser
     * excluído"</i> ao <b>abrir uma OS</b>. Hoje o INSERT tem {@code ON CONFLICT … DO UPDATE}, o que é
     * seguro aqui porque este caminho só roda com delta positivo (ver o comentário do
     * {@code aplicarReserva}).
     */
    @Test
    void duasOrdensSimultaneasReservandoAMesmaPecaSomamAsDuasReservas() throws Exception {
        prepararTenant("reserva-corrida");

        var largada = new java.util.concurrent.CountDownLatch(1);
        var prontas = new java.util.concurrent.CountDownLatch(2);
        var abertas = new java.util.concurrent.atomic.AtomicInteger();
        var falhas = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        var respostas = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        Runnable abrirOs = () -> {
            try {
                prontas.countDown();
                largada.await();
                var status = mvc.perform(post("/api/v1/ordens-servico")
                                .header("Authorization", "Bearer " + token)
                                .contentType(APPLICATION_JSON).content(corpoDaOs("1")))
                        .andReturn().getResponse();
                respostas.add(status.getStatus() + " " + status.getContentAsString());
                if (status.getStatus() == 200 || status.getStatus() == 201) {
                    abertas.incrementAndGet();
                }
            } catch (Exception e) {
                falhas.add(e.toString());
            }
        };

        Thread a = new Thread(abrirOs, "os-reserva-1");
        Thread b = new Thread(abrirOs, "os-reserva-2");
        a.start();
        b.start();
        prontas.await();
        largada.countDown();
        a.join();
        b.join();

        assertThat(falhas).as("nenhuma thread pode explodir por outro motivo").isEmpty();
        assertThat(abertas.get()).as("as duas OS podem existir: " + respostas).isEqualTo(2);

        // ⭐ A invariante: 1 + 1 = 2. Uma reserva perdida é peça que a segunda OS acha que tem.
        assertThat(reservado(idVariacaoPeca))
                .as("duas reservas de 1 peça têm de somar 2 — se der 1, uma sobrescreveu a outra")
                .isEqualByComparingTo(new BigDecimal("2.000"));
    }

}
