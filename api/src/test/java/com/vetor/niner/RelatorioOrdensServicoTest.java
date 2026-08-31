package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de Ordens de Serviço (docs/telas/relatorio-ordem-servico.md), pendência #56.
 *
 * <p>⚠️ <b>O que estes testes protegem.</b> As três decisões desta tela erram em silêncio se
 * ninguém as prender: o executor sai do <b>item</b> (o do cabeçalho é quem atendeu), o item
 * <b>sem executor</b> aparece em vez de sumir (senão o total não fecha e nada explica), e cada
 * contador do Movimento conta pela <b>sua própria data</b>. Nenhum desses erros derruba nada — os
 * números continuam plausíveis, só passam a responder outra pergunta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioOrdensServicoTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String token;
    private long idTenant;
    private long idCliente;
    private long idAtendente;
    private long idMecanico;
    private long idVariacaoPeca;
    private long idVariacaoServico;

    // ---------------------------------------------------------------- os casos

    /**
     * ⭐ O executor é do ITEM. A OS é atendida por JOAO (cabeçalho) e o serviço é executado por
     * MARIA (item). Creditar a produção a JOAO seria o mesmo defeito que a V088/V089 corrigiu no
     * ledger de venda — e ninguém veria, porque o número existiria e seria plausível.
     */
    @Test
    void produtividadeCreditaQuemEXECUTOU_naoQuemAtendeu() throws Exception {
        prepararTenant("exec");
        long id = criarOs("2");
        concluir(id);

        String resp = gerarRelatorio(hoje(), hoje());

        assertThat(nomesDasLinhas(resp))
                .as("a linha tem de ser do MECANICO (executor do item), não do ATENDENTE do cabeçalho")
                .contains("MECANICO")
                .doesNotContain("ATENDENTE");
        assertThat(valorServicosDe(resp, "MECANICO"))
                .as("120,00 do serviço executado")
                .isEqualByComparingTo("120.00");
    }

    /**
     * ⚠️ A peça entra sem executor ({@code id_funcionario} nulo é o caso normal dela). Filtrar
     * esses itens deixaria o total por executor menor que o total geral, <b>sem nada na tela
     * dizendo por quê</b> — o defeito de agrupar populações diferentes numa contagem só.
     */
    @Test
    void itemSemExecutorViraLinhaPropriaEOTotalContinuaFechando() throws Exception {
        prepararTenant("semexec");
        long id = criarOs("2");
        concluir(id);

        String resp = gerarRelatorio(hoje(), hoje());

        assertThat(nomesDasLinhas(resp))
                .as("a peça sem executor precisa aparecer, não sumir")
                .contains("(SEM EXECUTOR)");
        // 2 peças × 45,00 = 90,00 na linha sem executor; 1 serviço × 120,00 na do mecânico.
        assertThat(valorPecasDe(resp, "(SEM EXECUTOR)")).isEqualByComparingTo("90.00");

        java.math.BigDecimal somaDasLinhas = java.math.BigDecimal.ZERO;
        for (Object valor : JsonPath.<java.util.List<Object>>read(resp, "$.linhas[*].valorTotal")) {
            somaDasLinhas = somaDasLinhas.add(comoDecimal(valor));
        }
        assertThat(somaDasLinhas)
                .as("soma das linhas × total geral — é isto que a linha (SEM EXECUTOR) preserva")
                .isEqualByComparingTo(comoDecimal(JsonPath.<Object>read(resp, "$.totalGeral.valorTotal")));
    }

    /**
     * ⭐ Cada contador do Movimento conta pela SUA data. Uma OS aberta ontem e concluída hoje é
     * movimento de ontem na entrada e produção de hoje na execução — pedir só o dia de hoje tem de
     * mostrar 0 aberta e 1 concluída. Um eixo único responderia a pergunta errada com um número
     * que parece certo.
     */
    @Test
    void movimentoContaCadaEventoPelaPropriaData() throws Exception {
        prepararTenant("eixos");
        long id = criarOs("1");
        recuarAberturaUmDia(id);
        concluir(id);

        String hojeSo = gerarRelatorio(hoje(), hoje());
        assertThat(inteiro(hojeSo, "$.movimento.qtdAbertas"))
                .as("a OS foi aberta ONTEM — não pode contar como aberta hoje")
                .isZero();
        assertThat(inteiro(hojeSo, "$.movimento.qtdConcluidas"))
                .as("mas foi concluída hoje")
                .isEqualTo(1);

        String ontemSo = gerarRelatorio(hoje().minusDays(1), hoje().minusDays(1));
        assertThat(inteiro(ontemSo, "$.movimento.qtdAbertas")).isEqualTo(1);
        assertThat(inteiro(ontemSo, "$.movimento.qtdConcluidas")).isZero();
    }

    /**
     * OS concluída e depois cancelada: o trabalho foi feito e desfeito. Sai da produtividade (o
     * fato foi revertido) e aparece no contador de canceladas — se sumisse dos dois, o período
     * perderia uma OS sem deixar rastro.
     */
    @Test
    void osCanceladaSaiDaProdutividadeMasApareceNoMovimento() throws Exception {
        prepararTenant("cancel");
        long id = criarOs("1");
        concluir(id);
        mvc.perform(post("/api/v1/ordens-servico/" + id + "/cancelar")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"CLIENTE DESISTIU DO SERVICO\"}"))
                .andExpect(status().isOk());

        String resp = gerarRelatorio(hoje(), hoje());

        assertThat(JsonPath.<java.util.List<Object>>read(resp, "$.linhas"))
                .as("cancelada não conta produção")
                .isEmpty();
        assertThat(inteiro(resp, "$.movimento.qtdCanceladas"))
                .as("mas o cancelamento não pode sumir do relatório")
                .isEqualTo(1);
    }

    /**
     * ⭐ Percorre a allowlist INTEIRA, nas duas direções. Este teste nasceu de um defeito que os
     * outros cinco não pegavam: eles chamavam sempre <b>sem</b> {@code ordenarPor}, caindo no
     * default, e a tela abre pedindo {@code valorTotal} — que era {@code (valor_servicos +
     * valor_pecas)}, dois <b>aliases dentro de uma expressão</b>. O Postgres aceita alias do SELECT
     * no {@code ORDER BY} <b>sozinho</b>, não dentro de expressão: <i>column "valor_servicos" does
     * not exist</i>, e o relatório não gerava nenhuma vez. Só apareceu <b>abrindo a tela</b>.
     *
     * <p>Regra: allowlist de ordenação é uma lista de SQL que nunca foi executada até alguém
     * executá-la — o teste percorre todas as chaves, não uma amostra.
     */
    @Test
    void todasAsColunasOrdenaveisGeramSqlValido() throws Exception {
        prepararTenant("ordem");
        long id = criarOs("2");
        concluir(id);

        for (String coluna : java.util.List.of("nomeEmpresa", "nomeFuncionario", "qtdOrdens",
                "valorServicos", "valorPecas", "valorTotal", "tempoMedioHoras")) {
            for (String direcao : java.util.List.of("ASC", "DESC")) {
                String resp = mvc.perform(get("/api/v1/relatorios/ordens-servico")
                                .header("Authorization", "Bearer " + token)
                                .param("dataInicial", hoje().toString())
                                .param("dataFinal", hoje().toString())
                                .param("ordenarPor", coluna)
                                .param("direcao", direcao))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                assertThat(JsonPath.<java.util.List<Object>>read(resp, "$.linhas"))
                        .as("ordenando por %s %s", coluna, direcao)
                        .isNotEmpty();
            }
        }
    }

    /**
     * ⭐ "Não há o que medir" (nulo) × "medi, deu quase zero" — o par que a tela precisa distinguir,
     * porque só o **nulo** vira "—".
     *
     * <p>Achado **abrindo a tela**: o `COALESCE(AVG(...), 0)` mais o arredondamento para uma casa
     * faziam toda OS concluída em menos de 3 minutos virar `0,0`, e o front escrevia "—" para
     * qualquer valor `<= 0`. As OS reais do banco de dev levaram de 0,0047 h a 0,0594 h: a coluna
     * inteira saiu vazia, dando a impressão de relatório quebrado sobre um dado que existia.
     */
    @Test
    void tempoMedioEhNuloSoQuandoNaoHaOQueMedir() throws Exception {
        prepararTenant("tempo");

        String vazio = gerarRelatorio(hoje(), hoje());
        assertThat(JsonPath.<Object>read(vazio, "$.movimento.tempoMedioHoras"))
                .as("nenhuma OS concluída — aqui o nulo é a resposta certa, é o que vira '—' na tela")
                .isNull();

        long id = criarOs("1");
        // 1 minuto = 0,0167 h. Com o arredondamento antigo (UMA casa) isto virava 0,0 e a tela
        // escrevia "—" sobre um dado que existia — é a duração que separa a correção do defeito.
        recuarAbertura(id, "1 minute");
        concluir(id);

        String comOs = gerarRelatorio(hoje(), hoje());
        assertThat(comoDecimal(JsonPath.<Object>read(comOs, "$.movimento.tempoMedioHoras")))
                .as("OS concluída em segundos ainda é uma medida — não pode chegar nulo nem zerado")
                .isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat(comoDecimal(JsonPath.<Object>read(comOs, "$.linhas[0].tempoMedioHoras")))
                .as("e o mesmo vale por executor")
                .isGreaterThan(java.math.BigDecimal.ZERO);
    }

    /** P8 — o relatório de um tenant nunca enxerga a OS de outro. */
    @Test
    void isolamentoEntreTenants() throws Exception {
        prepararTenant("iso-a");
        long id = criarOs("1");
        concluir(id);
        String tokenA = token;

        prepararTenant("iso-b");

        String respB = gerarRelatorio(hoje(), hoje());
        assertThat(JsonPath.<java.util.List<Object>>read(respB, "$.linhas"))
                .as("tenant B não pode ver a produção do tenant A")
                .isEmpty();
        assertThat(inteiro(respB, "$.movimento.qtdConcluidas")).isZero();

        token = tokenA;
        assertThat(inteiro(gerarRelatorio(hoje(), hoje()), "$.movimento.qtdConcluidas"))
                .as("e o tenant A continua vendo a própria")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- apoio

    private String gerarRelatorio(LocalDate inicio, LocalDate fim) throws Exception {
        return mvc.perform(get("/api/v1/relatorios/ordens-servico")
                        .header("Authorization", "Bearer " + token)
                        .param("dataInicial", inicio.toString())
                        .param("dataFinal", fim.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Hoje no fuso da LOJA, não no do container — é o mesmo fuso em que o relatório compara. */
    private static LocalDate hoje() {
        return LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
    }

    private static int inteiro(String json, String caminho) {
        return ((Number) JsonPath.read(json, caminho)).intValue();
    }

    private java.util.List<String> nomesDasLinhas(String json) {
        return JsonPath.read(json, "$.linhas[*].nomeFuncionario");
    }

    private java.math.BigDecimal valorServicosDe(String json, String nome) {
        return campoDaLinha(json, nome, "valorServicos");
    }

    private java.math.BigDecimal valorPecasDe(String json, String nome) {
        return campoDaLinha(json, nome, "valorPecas");
    }

    /** ⚠️ Filtro do JsonPath devolve LISTA, mesmo quando casa uma linha só — o índice vem em Java,
     *  não dentro da expressão. E a lista vazia recebe mensagem própria: sem isso, uma linha que
     *  não existe apareceria como {@code IndexOutOfBounds}, que não diz nada sobre o relatório. */
    private java.math.BigDecimal campoDaLinha(String json, String nome, String campo) {
        java.util.List<Object> valores = JsonPath.read(json,
                "$.linhas[?(@.nomeFuncionario == '" + nome + "')]." + campo);
        assertThat(valores).as("nenhuma linha com nomeFuncionario = '%s'", nome).isNotEmpty();
        return comoDecimal(valores.get(0));
    }

    /** ⚠️ O parâmetro é {@code Object} de propósito: passar direto o retorno genérico do JsonPath
     *  para {@code String.valueOf} faz o compilador inferir {@code char[]} e estourar em
     *  {@code ClassCastException} no primeiro número — erro que fala de {@code [C} e não diz nada
     *  sobre o relatório. */
    private static java.math.BigDecimal comoDecimal(Object valor) {
        return new java.math.BigDecimal(String.valueOf(valor));
    }

    /** ABERTA → APROVADA → EM_EXECUCAO → CONCLUIDA: um passo por vez, como o serviço exige. */
    private void concluir(long id) throws Exception {
        for (String estado : java.util.List.of("APROVADA", "EM_EXECUCAO", "CONCLUIDA")) {
            mvc.perform(put("/api/v1/ordens-servico/" + id + "/situacao")
                            .header("Authorization", "Bearer " + token).param("para", estado))
                    .andExpect(status().isOk());
        }
    }

    private void recuarAberturaUmDia(long id) throws SQLException {
        recuarAbertura(id, "1 day");
    }

    /** Empurra a abertura para trás — é o que separa os dois eixos de data e o que dá à OS uma
     *  duração de verdade, sem depender do relógio. O {@code intervalo} é literal do teste, nunca
     *  entrada de usuário. */
    private void recuarAbertura(long id, String intervalo) throws SQLException {
        try (Connection c = abrirConexao();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ordem_servico SET data_abertura = data_abertura - interval '" + intervalo + "'"
                             + " WHERE id_tenant = ? AND id_ordem_servico = ?")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private void prepararTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja ROS %s","email":"dono%s@lojaros.com",
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
        idAtendente = criarFuncionario("ATENDENTE");
        idMecanico = criarFuncionario("MECANICO");
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

    private long criarFuncionario(String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"" + nome + "\",\"percComissao\":5.00}"))
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

    /** A peça entra SEM executor (é o caso normal dela); o serviço, com o MECANICO. O cabeçalho
     *  fica com o ATENDENTE — é essa separação que o primeiro teste mede. */
    private long criarOs(String qtdPeca) throws Exception {
        String body = """
                {"idCliente":%d,"idFuncionario":%d,"objetoServico":"ABC-1234",
                 "itens":[{"idVariacao":%d,"qtdProduto":%s},
                          {"idVariacao":%d,"qtdProduto":1,"idFuncionario":%d}]}
                """.formatted(idCliente, idAtendente, idVariacaoPeca, qtdPeca,
                idVariacaoServico, idMecanico);
        String resp = mvc.perform(post("/api/v1/ordens-servico").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idOrdemServico")).longValue();
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
}
