package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Devolução de Produtos Comprados (docs/telas/devolucao-compra.md, 2026-08-20).
 *
 * <p>O que estes testes protegem é <b>o teto duplo</b>, que é a regra que o dono do produto
 * enunciou e a única que não dá para inferir do resto do sistema: devolve-se o menor entre
 * <i>o que a nota trouxe menos o que já voltou</i> e <i>o que ainda existe em estoque</i>.
 *
 * <p>O segundo limite é <b>exceção deliberada</b> à política de estoque negativo — ver o javadoc
 * de {@code DevolucaoCompraService} para o porquê (a venda registra um fato que o operador está
 * vendo; a devolução declara à SEFAZ um fato que ninguém conferiu).
 *
 * <p>A emissão da NF-e não entra aqui de propósito: sem configuração fiscal na empresa o
 * assembler devolve vazio (F12) e a devolução acontece sem nota, que é o caminho testável sem
 * certificado. O lado fiscal tem casa própria em {@code DevolucaoFiscalEmissaoTest} e
 * {@code CfopDevolucaoCompraTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DevolucaoCompraCrudTest {

    /** Item 1 da NF-e real da Dakota — o mesmo XML que {@code EntradaXmlCrudTest} usa. */
    private static final String EAN_ITEM_1 = "7900282671000";

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    // ---------------------------------------------------------------- fixture

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja DevCompra %s","email":"dono%s@devcompra.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long extrairIdTenant(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private long criarFornecedor(String token) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"codigo":"9.00.000","descricao":"DESPESA FORNECEDORES","tipoMovimento":"DEBITO",
                         "natureza":"ANALITICA","incluiDre":false,"incluiFluxoCaixa":false}
                        """));
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"DAKOTA CALCADOS\",\"idPlanoContas\":\"9.00.000\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFornecedor")).longValue();
    }

    private long criarVariacaoComEan(String token, String descricao, String ean) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"20.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idProduto = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        String varResp = mvc.perform(post("/api/v1/produtos/" + idProduto + "/variacoes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"ean\":\"%s\"}".formatted(ean)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(varResp, "$.idVariacao")).longValue();
    }

    /**
     * Entrada de compra <b>com o XML</b> — é o XML que gera {@code entrada_nfe_item}, e sem essa
     * tabela a entrada nem aparece como devolvível (não haveria tributação para espelhar).
     */
    private long entradaComXml(String token, long idFornecedor, long idVariacao, String qtd) throws Exception {
        return entradaComXml(token, idFornecedor, idVariacao, qtd, 322641,
                "28260207414643000201550000003226411000014220");
    }

    private long entradaComXml(String token, long idFornecedor, long idVariacao, String qtd,
                               int notaFiscal, String chave) throws Exception {
        String xml = new String(new ClassPathResource("xml/nfe-dakota-calcados.xml")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String body = """
                {"idFornecedor":%d,"notaFiscal":%d,"serieNota":0,
                 "chaveNfe":"%s",
                 "xmlBruto":%s,
                 "itens":[{"idVariacao":%d,"qtd":%s,"precoCusto":10.00}]}
                """.formatted(idFornecedor, notaFiscal, chave, jsonString(xml), idVariacao, qtd);
        String resp = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idMovimento")).longValue();
    }

    private static String jsonString(String bruto) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : bruto.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Tira mercadoria do estoque por fora da devolução, como uma venda faria.
     *
     * <p>SQL direto em vez de uma venda no PDV: a venda exigiria caixa aberto, cliente, vendedor e
     * forma de pagamento — fixture que não tem nada a ver com o que este teste mede. O que importa
     * é o efeito, e ele é o mesmo: a trigger {@code fn_atualiza_estoque_movimento} baixa o estoque
     * a partir do detalhe {@code 'D'}, exatamente como no caixa.
     */
    private void simularVenda(long idTenant, long idEmpresa, long idVariacao, String qtd) throws SQLException {
        try (Connection c = abrirConexao(idTenant)) {
            long idMovimento;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_usuario)
                    SELECT ?, ?, 'VENDA', u.id_usuario FROM usuario u WHERE u.id_tenant = ? LIMIT 1
                    RETURNING id_movimento
                    """)) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idEmpresa);
                ps.setLong(3, idTenant);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    idMovimento = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO produto_movimento_detalhe (id_tenant, id_movimento, id_empresa, id_variacao,
                                                           credito_debito, qtd_produto, preco_custo, origem)
                    VALUES (?, ?, ?, ?, 'D', ?::numeric, 10.00, 'venda simulada')
                    """)) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idMovimento);
                ps.setLong(3, idEmpresa);
                ps.setLong(4, idVariacao);
                ps.setString(5, qtd);
                ps.executeUpdate();
            }
        }
    }

    private BigDecimal estoqueDe(long idTenant, long idVariacao) throws SQLException {
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(qtd_estoque), 0) FROM produto_estoque "
                             + "WHERE id_tenant = ? AND id_variacao = ?")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idVariacao);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getBigDecimal(1);
            }
        }
    }

    private String itensDevolviveis(String token, long idMovimento) throws Exception {
        return mvc.perform(get("/api/v1/estoque/devolucao-compra/entradas/" + idMovimento + "/itens")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------------------------------------------------------------- testes

    /** Entrada só aparece como devolvível quando veio com XML — entrada manual não tem o que
     *  espelhar, e oferecer a devolução dela seria oferecer um caminho que falha depois. */
    @Test
    void entradaComXmlApareceNaListaEEntradaManualNao() throws Exception {
        String token = assinarNovoTenant("lista");
        long idFornecedor = criarFornecedor(token);
        long idVariacao = criarVariacaoComEan(token, "BOTA DEVOLVIVEL", EAN_ITEM_1);
        long idComXml = entradaComXml(token, idFornecedor, idVariacao, "10");

        long idVariacaoManual = criarVariacaoComEan(token, "BOTA MANUAL", "7900282679999");
        String manual = mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"notaFiscal":99,
                                 "itens":[{"idVariacao":%d,"qtd":5,"precoCusto":10.00}]}
                                """.formatted(idFornecedor, idVariacaoManual)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idManual = ((Number) JsonPath.read(manual, "$.idMovimento")).longValue();

        String lista = mvc.perform(get("/api/v1/estoque/devolucao-compra/entradas")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<Integer> ids = JsonPath.read(lista, "$.itens[*].idMovimento");
        assertThat(ids).contains((int) idComXml).doesNotContain((int) idManual);
    }

    /**
     * O caso que o dono do produto enunciou: <b>entrou 10, o estoque tem 12 → devolve no máximo
     * 10</b>. O excedente veio de outro lugar (outra compra, ajuste) e não pertence a esta nota.
     */
    @Test
    void estoqueMaiorQueANotaNaoAumentaOMaximo() throws Exception {
        String token = assinarNovoTenant("maior");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token);
        long idVariacao = criarVariacaoComEan(token, "BOTA COM SOBRA", EAN_ITEM_1);
        long idEntrada = entradaComXml(token, idFornecedor, idVariacao, "10");

        // Mais 2 unidades por outra entrada — estoque vai a 12, o saldo da NOTA continua 10.
        // Chave e numero DIFERENTES: a mesma nota nao entra duas vezes no mesmo tenant (P2).
        long idOutra = entradaComXml(token, idFornecedor, idVariacao, "2", 322642,
                "28260207414643000201550000003226421000014221");
        assertThat(idOutra).isNotEqualTo(idEntrada);
        assertThat(estoqueDe(idTenant, idVariacao)).isEqualByComparingTo("12");

        String itens = itensDevolviveis(token, idEntrada);
        assertThat(new BigDecimal(JsonPath.read(itens, "$[0].qtdMaxima").toString())).isEqualByComparingTo("10");

        mvc.perform(post("/api/v1/estoque/devolucao-compra").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idMovimentoOrigem":%d,"itens":[{"idVariacao":%d,"qtd":11}]}
                                """.formatted(idEntrada, idVariacao)))
                .andExpect(status().isConflict());
    }

    /**
     * O outro caso: <b>entrou 10, o estoque tem 8 → devolve no máximo 8</b>. O que já saiu da loja
     * não pode ser mandado de volta ao fornecedor.
     */
    @Test
    void estoqueMenorQueANotaLimitaOMaximo() throws Exception {
        String token = assinarNovoTenant("menor");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token);
        long idVariacao = criarVariacaoComEan(token, "BOTA VENDIDA EM PARTE", EAN_ITEM_1);
        long idEntrada = entradaComXml(token, idFornecedor, idVariacao, "10");

        String entradaJson = mvc.perform(get("/api/v1/estoque/devolucao-compra/entradas")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(entradaJson, "$.itens[0].idEmpresa")).longValue();

        simularVenda(idTenant, idEmpresa, idVariacao, "2");
        assertThat(estoqueDe(idTenant, idVariacao)).isEqualByComparingTo("8");

        String itens = itensDevolviveis(token, idEntrada);
        assertThat(new BigDecimal(JsonPath.read(itens, "$[0].qtdSaldo").toString())).isEqualByComparingTo("10");
        assertThat(new BigDecimal(JsonPath.read(itens, "$[0].qtdMaxima").toString())).isEqualByComparingTo("8");

        mvc.perform(post("/api/v1/estoque/devolucao-compra").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idMovimentoOrigem":%d,"itens":[{"idVariacao":%d,"qtd":9}]}
                                """.formatted(idEntrada, idVariacao)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/estoque/devolucao-compra").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idMovimentoOrigem":%d,"itens":[{"idVariacao":%d,"qtd":8}]}
                                """.formatted(idEntrada, idVariacao)))
                .andExpect(status().isCreated());

        assertThat(estoqueDe(idTenant, idVariacao)).isEqualByComparingTo("0");
    }

    /**
     * ⚠️ Regressão da falha que a conferência linha a linha deixava passar: duas linhas de 5 do
     * MESMO produto somam 10 contra um estoque de 8. Validar por linha aprovaria as duas.
     */
    @Test
    void linhaRepetidaSomaAntesDeValidar() throws Exception {
        String token = assinarNovoTenant("repetida");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token);
        long idVariacao = criarVariacaoComEan(token, "BOTA LINHA REPETIDA", EAN_ITEM_1);
        long idEntrada = entradaComXml(token, idFornecedor, idVariacao, "10");

        String entradaJson = mvc.perform(get("/api/v1/estoque/devolucao-compra/entradas")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(entradaJson, "$.itens[0].idEmpresa")).longValue();
        simularVenda(idTenant, idEmpresa, idVariacao, "2");

        mvc.perform(post("/api/v1/estoque/devolucao-compra").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idMovimentoOrigem":%d,"itens":[{"idVariacao":%d,"qtd":5},
                                                                 {"idVariacao":%d,"qtd":5}]}
                                """.formatted(idEntrada, idVariacao, idVariacao)))
                .andExpect(status().isConflict());

        assertThat(estoqueDe(idTenant, idVariacao)).isEqualByComparingTo("8");
    }

    /** Devolução parcial baixa o estoque e deixa o resto disponível para uma segunda devolução. */
    @Test
    void devolucaoParcialDeixaSaldoParaAProxima() throws Exception {
        String token = assinarNovoTenant("parcial");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token);
        long idVariacao = criarVariacaoComEan(token, "BOTA PARCIAL", EAN_ITEM_1);
        long idEntrada = entradaComXml(token, idFornecedor, idVariacao, "10");

        mvc.perform(post("/api/v1/estoque/devolucao-compra").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idMovimentoOrigem":%d,"itens":[{"idVariacao":%d,"qtd":4}]}
                                """.formatted(idEntrada, idVariacao)))
                .andExpect(status().isCreated());

        assertThat(estoqueDe(idTenant, idVariacao)).isEqualByComparingTo("6");

        String itens = itensDevolviveis(token, idEntrada);
        assertThat(new BigDecimal(JsonPath.read(itens, "$[0].qtdDevolvida").toString())).isEqualByComparingTo("4");
        assertThat(new BigDecimal(JsonPath.read(itens, "$[0].qtdMaxima").toString())).isEqualByComparingTo("6");
    }

    /**
     * Cancelar devolve o estoque <b>e</b> o saldo devolvível — este último sem UPDATE em linha
     * nenhuma, porque {@code vw_entrada_saldo_devolucao} ignora devolução cancelada.
     */
    @Test
    void cancelamentoDevolveEstoqueESaldo() throws Exception {
        String token = assinarNovoTenant("cancela");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token);
        long idVariacao = criarVariacaoComEan(token, "BOTA CANCELADA", EAN_ITEM_1);
        long idEntrada = entradaComXml(token, idFornecedor, idVariacao, "10");

        String devResp = mvc.perform(post("/api/v1/estoque/devolucao-compra").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idMovimentoOrigem":%d,"itens":[{"idVariacao":%d,"qtd":10}]}
                                """.formatted(idEntrada, idVariacao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idDevolucao = ((Number) JsonPath.read(devResp, "$.idMovimento")).longValue();

        assertThat(estoqueDe(idTenant, idVariacao)).isEqualByComparingTo("0");
        assertThat(itensDevolviveis(token, idEntrada)).isEqualTo("[]");

        mvc.perform(post("/api/v1/estoque/devolucao-compra/" + idDevolucao + "/cancelar")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"FORNECEDOR NAO ACEITOU A DEVOLUCAO\"}"))
                .andExpect(status().isOk());

        assertThat(estoqueDe(idTenant, idVariacao)).isEqualByComparingTo("10");
        String itens = itensDevolviveis(token, idEntrada);
        assertThat(new BigDecimal(JsonPath.read(itens, "$[0].qtdMaxima").toString())).isEqualByComparingTo("10");

        // Cancelar de novo não pode passar: o segundo estorno devolveria estoque que já voltou.
        mvc.perform(post("/api/v1/estoque/devolucao-compra/" + idDevolucao + "/cancelar")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"TENTATIVA DUPLICADA\"}"))
                .andExpect(status().isConflict());
    }

    /** Um tenant não enxerga — nem devolve — entrada de outro (P8). */
    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("isoa");
        long idFornecedorA = criarFornecedor(tokenA);
        long idVariacaoA = criarVariacaoComEan(tokenA, "BOTA DO TENANT A", EAN_ITEM_1);
        long idEntradaA = entradaComXml(tokenA, idFornecedorA, idVariacaoA, "10");

        String tokenB = assinarNovoTenant("isob");

        // 409, nao 404, e de proposito: a resposta e a MESMA que um id inexistente ou uma entrada
        // manual recebem ("esta entrada nao pode gerar devolucao"). Distinguir "existe mas nao e sua"
        // de "nao existe" confirmaria a existencia da linha do outro tenant.
        mvc.perform(get("/api/v1/estoque/devolucao-compra/entradas/" + idEntradaA + "/itens")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/estoque/devolucao-compra").header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idMovimentoOrigem":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                                """.formatted(idEntradaA, idVariacaoA)))
                .andExpect(status().isConflict());
    }

    /**
     * ⭐ <b>Corrida real: duas devoluções ao fornecedor disputando o mesmo estoque.</b>
     *
     * <p>Com 10 em estoque e duas devoluções de 6 disparadas juntas, só uma pode passar — 12 não
     * existem. Este é o cenário que o javadoc de {@code DevolucaoCompraService.travarEstoque}
     * descreve em palavras (<i>"duas devoluções simultâneas leem as mesmas 8 unidades e devolvem
     * 16"</i>) e que nenhum teste exercitava.
     *
     * <p><b>Por que aqui a regra é mais estreita que no resto do sistema.</b> O Niner permite
     * estoque negativo de propósito ({@code cfg_permite_estoque_negativo}), então quase nenhuma
     * rotina depende de conferir saldo. A devolução ao fornecedor é a exceção: só devolve o que
     * <b>ainda está em estoque</b>, porque o que sai daqui vira uma <b>NF-e 55 declarando à SEFAZ
     * que a mercadoria saiu fisicamente</b>. Devolver 12 de 10 é nota fiscal afirmando um fato que
     * não aconteceu — por isso a leitura e a gravação têm de acontecer sob a mesma trava
     * ({@code FOR UPDATE}).
     *
     * <p>⚠️ Não mede tempo nem dorme (ver {@code feedback_testes_frageis_por_relogio}): as threads
     * saem juntas de uma {@link java.util.concurrent.CountDownLatch} e o que se afirma é a
     * <b>invariante</b> — o estoque nunca fica negativo e a soma devolvida nunca passa do que havia.
     *
     * <p>⭐ A prova vem do <b>banco</b>: contar 201 deixaria passar um servidor que responde 409 e
     * grava assim mesmo.
     */
    @Test
    void duasDevolucoesSimultaneasNaoDevolvemMaisDoQueTemEmEstoque() throws Exception {
        String token = assinarNovoTenant("dev-corrida");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token);
        long idVariacao = criarVariacaoComEan(token, "BOTA CORRIDA", EAN_ITEM_1);
        long idEntrada = entradaComXml(token, idFornecedor, idVariacao, "10");

        // 6 + 6 = 12 numa entrada de 10: a segunda tem de bater no saldo.
        String corpo = """
                {"idMovimentoOrigem":%d,"itens":[{"idVariacao":%d,"qtd":6}]}
                """.formatted(idEntrada, idVariacao);

        var largada = new java.util.concurrent.CountDownLatch(1);
        var prontas = new java.util.concurrent.CountDownLatch(2);
        var aceitas = new java.util.concurrent.atomic.AtomicInteger();
        var falhas = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        Runnable devolver = () -> {
            try {
                prontas.countDown();
                largada.await();
                int status = mvc.perform(post("/api/v1/estoque/devolucao-compra")
                                .header("Authorization", "Bearer " + token)
                                .contentType(APPLICATION_JSON).content(corpo))
                        .andReturn().getResponse().getStatus();
                if (status == 200 || status == 201) {
                    aceitas.incrementAndGet();
                }
            } catch (Exception e) {
                falhas.add(e.toString());
            }
        };

        Thread a = new Thread(devolver, "devolucao-1");
        Thread b = new Thread(devolver, "devolucao-2");
        a.start();
        b.start();
        prontas.await();
        largada.countDown();
        a.join();
        b.join();

        assertThat(falhas).as("nenhuma thread pode explodir por outro motivo").isEmpty();
        assertThat(aceitas.get()).as("6 + 6 numa entrada de 10: só uma devolução pode passar").isEqualTo(1);

        // ⭐ O estoque tem de ter caído exatamente uma vez — nunca ficar negativo.
        assertThat(estoqueDe(idTenant, idVariacao))
                .as("10 - 6 = 4; se der -2, as duas leram as mesmas unidades")
                .isEqualByComparingTo("4");

        // E a NF-e 55 correspondente: uma devolução gravada, não duas.
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM produto_movimento_mestre"
                             + " WHERE id_tenant = ? AND tipo_movimento = 'DEVOLUCAO_COMPRA'")) {
            ps.setLong(1, idTenant);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("uma devolução gravada, não duas").isEqualTo(1);
            }
        }
    }

}
