package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Orçamento de Venda (docs/telas/orcamento.md) — os critérios de aceitação da spec.
 *
 * <p>O que estes testes protegem, acima de tudo, são as regras que <b>não dá para inferir do
 * código</b> e que o dono do produto fechou uma a uma: o orçamento é imutável (R1), no PDV só dá
 * para <b>diminuir</b> quantidade (R2), o preço é congelado (R3), `VENDIDO_PARCIAL` é estado
 * <b>final</b> (R5) e produto inativado não passa (R7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrcamentoCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    // ------------------------------------------------------------------ fixture

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Orcamento %s","email":"dono%s@orcamento.com",
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

    private long criarCategoriaCliente(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(descricao)))
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

    private long criarProduto(String token, String descricao, String precoVenda) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"%s"}
                                """.formatted(descricao, precoVenda)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarVariacao(long idTenant, long idProduto) {
        Long id = jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, sku)
                        VALUES (?, ?, gerar_ean13_interno()) RETURNING id_variacao
                        """)
                .params(idTenant, idProduto).query(Long.class).single();
        // Estoque para a venda poder acontecer (a trigger da V054 barra débito que fica negativo).
        jdbc.sql("""
                        INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque)
                        SELECT ?, e.id_empresa, ?, 1000 FROM empresa e WHERE e.id_tenant = ?
                        """)
                .params(idTenant, id, idTenant).update();
        return id;
    }

    private record Cenario(String token, long idTenant, long idCliente, long idFuncionario, long idVariacao) {
    }

    private Cenario prepararCenario(String sufixo, String precoVenda) throws Exception {
        String token = assinarNovoTenant(sufixo);
        long idTenant = extrairIdTenant(token);
        long idCliente = criarCliente(token, "CLIENTE " + sufixo.toUpperCase());
        long idFuncionario = criarFuncionario(token, "VENDEDOR " + sufixo.toUpperCase());
        long idProduto = criarProduto(token, "PRODUTO " + sufixo.toUpperCase(), precoVenda);
        return new Cenario(token, idTenant, idCliente, idFuncionario, criarVariacao(idTenant, idProduto));
    }

    private long emitir(Cenario c, String qtd, String validade) throws Exception {
        String validadeJson = validade == null ? "" : ",\"dataValidade\":\"" + validade + "\"";
        String resp = mvc.perform(post("/api/v1/orcamentos").header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idCliente":%d,"idFuncionario":%d%s,
                                 "itens":[{"idVariacao":%d,"qtd":%s}]}
                                """.formatted(c.idCliente(), c.idFuncionario(), validadeJson, c.idVariacao(), qtd)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idOrcamento")).longValue();
    }

    private void abrirCaixa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = ((Number) carteiras.stream()
                .filter(x -> "DINHEIRO".equals(x.get("nomeCarteira")))
                .findFirst().orElseThrow().get("idCarteira")).longValue();
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    private long idCarteiraDinheiro(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        return ((Number) carteiras.stream()
                .filter(x -> "DINHEIRO".equals(x.get("nomeCarteira")))
                .findFirst().orElseThrow().get("idCarteira")).longValue();
    }

    /** Efetiva a venda a partir do orçamento, levando `qtd` do único item. */
    private org.springframework.test.web.servlet.ResultActions venderDoOrcamento(
            Cenario c, long idOrcamento, String qtd, String valorPago) throws Exception {
        return mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + c.token())
                .contentType(APPLICATION_JSON)
                .content("""
                        {"idOrcamento":%d,"idCliente":%d,"idFuncionario":%d,"descontoVenda":0,
                         "itens":[{"idVariacao":%d,"qtd":%s}],
                         "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                        """.formatted(idOrcamento, c.idCliente(), c.idFuncionario(), c.idVariacao(), qtd,
                        idCarteiraDinheiro(c.token()), valorPago)));
    }

    // ------------------------------------------------------------------ testes

    /** Critério 1: efetivar levando tudo → `VENDIDO`, com o número da venda gravado. */
    @Test
    void efetivarLevandoTudoMarcaVendido() throws Exception {
        Cenario c = prepararCenario("tudo", "10.00");
        long idOrcamento = emitir(c, "10", null);
        abrirCaixa(c.token());

        venderDoOrcamento(c, idOrcamento, "10", "100.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orcamentoParcial").value(false));

        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token()))
                .andExpect(jsonPath("$.situacao").value("VENDIDO"))
                .andExpect(jsonPath("$.idVenda").isNumber());
    }

    /**
     * Critério 2 + R5: levando menos → `VENDIDO_PARCIAL`, e o estado é <b>final</b>.
     *
     * <p>O cliente que volta para buscar o resto faz uma venda nova, sem vínculo — por isso o
     * teste confirma que uma segunda tentativa pelo mesmo orçamento é recusada.
     */
    @Test
    void efetivarLevandoMenosMarcaParcialEEhEstadoFinal() throws Exception {
        Cenario c = prepararCenario("parcial", "10.00");
        long idOrcamento = emitir(c, "10", null);
        abrirCaixa(c.token());

        venderDoOrcamento(c, idOrcamento, "8", "80.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orcamentoParcial").value(true));

        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token()))
                .andExpect(jsonPath("$.situacao").value("VENDIDO_PARCIAL"));

        // As 2 que sobraram NÃO podem ser levadas por este orçamento (critério 7).
        venderDoOrcamento(c, idOrcamento, "2", "20.00").andExpect(status().isConflict());
    }

    /** Critério 3 + R2: o PDV só deixa DIMINUIR — levar mais que o orçado é recusado. */
    @Test
    void levarMaisQueOOrcadoEhRecusado() throws Exception {
        Cenario c = prepararCenario("mais", "10.00");
        long idOrcamento = emitir(c, "5", null);
        abrirCaixa(c.token());

        venderDoOrcamento(c, idOrcamento, "6", "60.00")
                .andExpect(status().isConflict());
    }

    /**
     * ⚠️ Critério 4 — o coração da rotina: o preço é <b>congelado</b>.
     *
     * <p>Orçado a R$ 10,00; o cadastro sobe para R$ 25,00; a venda sai a R$ 10,00. Sem isto a data
     * de validade não significaria nada — o papel na mão do cliente viraria ficção.
     */
    @Test
    void precoDoOrcamentoPrevaleceQuandoOCadastroMuda() throws Exception {
        Cenario c = prepararCenario("congelado", "10.00");
        long idOrcamento = emitir(c, "3", null);

        jdbc.sql("""
                        UPDATE produto SET preco_venda = 25.00
                         WHERE id_tenant = ? AND id_produto = (
                             SELECT id_produto FROM produto_barra
                              WHERE id_tenant = ? AND id_variacao = ?)
                        """)
                .params(c.idTenant(), c.idTenant(), c.idVariacao()).update();

        // A consulta avisa a divergência, sem decidir por ninguém.
        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token()))
                .andExpect(jsonPath("$.itens[0].precoVenda").value(10.00))
                .andExpect(jsonPath("$.itens[0].precoAtual").value(25.00));

        abrirCaixa(c.token());
        venderDoOrcamento(c, idOrcamento, "3", "30.00")
                .andExpect(status().isCreated())
                // 3 × 10,00 congelados, não 3 × 25,00 do cadastro.
                .andExpect(jsonPath("$.valorTotalProdutos").value(30.00));
    }

    /**
     * Critério 5 + R6: orçamento vencido é marcado ao ser CONSULTADO (vencimento preguiçoso) e
     * não vira venda.
     */
    @Test
    void orcamentoVencidoEhMarcadoNaConsultaENaoViraVenda() throws Exception {
        Cenario c = prepararCenario("vencido", "10.00");
        long idOrcamento = emitir(c, "2", null);

        // Empurra a validade para ontem — o mesmo efeito de o tempo passar.
        jdbc.sql("UPDATE orcamento SET data_validade = ? WHERE id_tenant = ? AND id_orcamento = ?")
                .params(LocalDate.now().minusDays(1), c.idTenant(), idOrcamento).update();

        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token()))
                .andExpect(jsonPath("$.situacao").value("VENCIDO"));

        abrirCaixa(c.token());
        venderDoOrcamento(c, idOrcamento, "2", "20.00").andExpect(status().isConflict());
    }

    /**
     * ⚠️ Critério 6 + R7: produto inativado depois da emissão <b>não vende</b> — é a única regra do
     * PDV que o orçamento não afrouxa. E o aviso vem na CONSULTA, para o operador não descobrir
     * com o cliente na frente.
     */
    @Test
    void produtoInativadoDepoisNaoVendeEEhAvisadoNaConsulta() throws Exception {
        Cenario c = prepararCenario("inativo", "10.00");
        long idOrcamento = emitir(c, "2", null);

        jdbc.sql("""
                        UPDATE produto SET ativo = false
                         WHERE id_tenant = ? AND id_produto = (
                             SELECT id_produto FROM produto_barra
                              WHERE id_tenant = ? AND id_variacao = ?)
                        """)
                .params(c.idTenant(), c.idTenant(), c.idVariacao()).update();

        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token()))
                .andExpect(jsonPath("$.itens[0].produtoInativo").value(true));

        abrirCaixa(c.token());
        venderDoOrcamento(c, idOrcamento, "2", "20.00").andExpect(status().isConflict());
    }

    /** Critério 8: cancelado não vira venda, e a mensagem diz o motivo. */
    @Test
    void orcamentoCanceladoNaoViraVenda() throws Exception {
        Cenario c = prepararCenario("cancelado", "10.00");
        long idOrcamento = emitir(c, "2", null);

        mvc.perform(post("/api/v1/orcamentos/" + idOrcamento + "/cancelar")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"CLIENTE DESISTIU\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token()))
                .andExpect(jsonPath("$.situacao").value("CANCELADO"))
                .andExpect(jsonPath("$.motivoCancelamento").value("CLIENTE DESISTIU"));

        abrirCaixa(c.token());
        venderDoOrcamento(c, idOrcamento, "2", "20.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cancelado")));

        // Cancelar duas vezes também não.
        mvc.perform(post("/api/v1/orcamentos/" + idOrcamento + "/cancelar")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"DE NOVO\"}"))
                .andExpect(status().isConflict());
    }

    /** Critério 9 + R4: desconto acima do teto de Parâmetros do Sistema é recusado na emissão —
     *  um orçamento que a venda recusaria seria uma promessa que o PDV não consegue cumprir. */
    @Test
    void descontoAcimaDoTetoEhRecusadoNaEmissao() throws Exception {
        Cenario c = prepararCenario("desconto", "10.00");
        // Teto padrão do tenant novo é 0% — qualquer desconto já estoura.
        mvc.perform(post("/api/v1/orcamentos").header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idCliente":%d,"idFuncionario":%d,"valorDesconto":5.00,
                                 "itens":[{"idVariacao":%d,"qtd":10}]}
                                """.formatted(c.idCliente(), c.idFuncionario(), c.idVariacao())))
                .andExpect(status().isConflict());
    }

    /** Critério 10 (P8): tenant B não enxerga nem mexe no orçamento do tenant A. */
    @Test
    void isolamentoEntreTenants() throws Exception {
        Cenario a = prepararCenario("isoa", "10.00");
        long idOrcamento = emitir(a, "3", null);
        String tokenB = assinarNovoTenant("isob");

        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/orcamentos/" + idOrcamento + "/cancelar")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON).content("{\"motivo\":\"INVASAO\"}"))
                .andExpect(status().isNotFound());

        // E o dono continua enxergando o dele, intacto.
        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + a.token()))
                .andExpect(jsonPath("$.situacao").value("ABERTO"));
    }

    /**
     * Critério 12 + decisão explícita: cancelar a venda <b>não</b> reabre o orçamento.
     *
     * <p>Diferente do vale-mercadoria, que o Cancelamento de Venda reabre. Aqui o dono do produto
     * decidiu o contrário: "o orçamento não pode ser reaberto".
     */
    @Test
    void cancelarAVendaNaoReabreOOrcamento() throws Exception {
        Cenario c = prepararCenario("nao-reabre", "10.00");
        long idOrcamento = emitir(c, "4", null);
        abrirCaixa(c.token());

        String venda = venderDoOrcamento(c, idOrcamento, "4", "40.00")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idVenda = ((Number) JsonPath.read(venda, "$.idVenda")).longValue();

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda)
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"Cliente desistiu da compra\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token()))
                .andExpect(jsonPath("$.situacao").value("VENDIDO"));
    }


    /**
     * O cliente veio buscar o orçado e viu mais coisa na loja (decisão do dono do produto,
     * 2026-08-20): item fora do orçamento é permitido na mesma venda.
     *
     * <p>⚠️ A regra "só diminuir" (R2) vale para o que foi <b>orçado</b>, não para a venda inteira.
     * O item extra é venda comum: preço do <b>cadastro</b> (não congelado, porque nunca foi
     * prometido) e sem limite de quantidade. E ele <b>não</b> conta para decidir se o orçamento
     * foi parcial — levando tudo o que orçou + um extra, o orçamento fecha como VENDIDO.
     */
    @Test
    void produtoForaDoOrcamentoPodeEntrarNaMesmaVenda() throws Exception {
        Cenario c = prepararCenario("extra", "10.00");
        long idOrcamento = emitir(c, "2", null);
        long idOutroProduto = criarProduto(c.token(), "PRODUTO EXTRA", "20.00");
        long idOutraVariacao = criarVariacao(c.idTenant(), idOutroProduto);
        abrirCaixa(c.token());

        mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idOrcamento":%d,"idCliente":%d,"idFuncionario":%d,"descontoVenda":0,
                                 "itens":[{"idVariacao":%d,"qtd":2},{"idVariacao":%d,"qtd":3}],
                                 "pagamentos":[{"idCarteira":%d,"valorPago":80.00,"numeroParcelas":1}]}
                                """.formatted(idOrcamento, c.idCliente(), c.idFuncionario(),
                                c.idVariacao(), idOutraVariacao, idCarteiraDinheiro(c.token()))))
                .andExpect(status().isCreated())
                // 2 × 10,00 congelados do orçamento + 3 × 20,00 do cadastro = 80,00
                .andExpect(jsonPath("$.valorTotalProdutos").value(80.00))
                // Levou tudo o que estava orçado — o extra não torna a venda parcial.
                .andExpect(jsonPath("$.orcamentoParcial").value(false));

        mvc.perform(get("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token()))
                .andExpect(jsonPath("$.situacao").value("VENDIDO"));
    }
    /** R1: não existe endpoint de alteração — a ausência é a regra, e um PUT devolve 405. */
    @Test
    void naoExisteAlteracaoDeOrcamento() throws Exception {
        Cenario c = prepararCenario("imutavel", "10.00");
        long idOrcamento = emitir(c, "1", null);

        mvc.perform(put("/api/v1/orcamentos/" + idOrcamento).header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    /** A validade nasce de `cfg_geral.cfg_dias_validade_orcamento` (R11) quando não é informada. */
    @Test
    void validadePadraoVemDoParametroDoSistema() throws Exception {
        Cenario c = prepararCenario("validade", "10.00");
        long idOrcamento = emitir(c, "1", null);

        String resp = mvc.perform(get("/api/v1/orcamentos/" + idOrcamento)
                        .header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        LocalDate validade = LocalDate.parse(JsonPath.read(resp, "$.dataValidade"));

        // Default da coluna é 15 dias. Comparação por intervalo para não quebrar na virada do dia.
        assertThat(validade).isBetween(LocalDate.now().plusDays(14), LocalDate.now().plusDays(16));
    }

    /** Critério 11: o job vence o que ninguém consultou — e enxerga os tenants (regressão do bug
     *  de job sem TenantContext, que devolveria zero linhas em silêncio). */
    @Test
    void jobVenceOrcamentoQueNinguemConsultou() throws Exception {
        Cenario c = prepararCenario("job", "10.00");
        long idOrcamento = emitir(c, "1", null);
        jdbc.sql("UPDATE orcamento SET data_validade = ? WHERE id_tenant = ? AND id_orcamento = ?")
                .params(LocalDate.now().minusDays(3), c.idTenant(), idOrcamento).update();

        job.vencerOrcamentos();

        String situacao = jdbc.sql("""
                        SELECT situacao::text FROM orcamento
                         WHERE id_tenant = ? AND id_orcamento = ?
                        """)
                .params(c.idTenant(), idOrcamento).query(String.class).single();
        assertThat(situacao).isEqualTo("VENCIDO");
    }

    @Autowired
    com.vetor.niner.vendas.orcamento.OrcamentoVencimentoJob job;
}
