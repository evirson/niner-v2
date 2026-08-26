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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relatório de Contas a Pagar / Pagas (docs/telas/relatorio-contas-pagar.md).
 *
 * <p>⭐ Dois testes valem mais que os outros:
 * <ul>
 *   <li>{@link #compraDeMercadoriaAPARECE()} — este relatório <b>não</b> respeita
 *       {@code inclui_dre}, ao contrário da DRE e da Lucratividade, e pelo motivo oposto: aqui a
 *       pergunta é "quanto sai do caixa".</li>
 *   <li>{@link #documentoPagoSemDataDePagamentoContaComoEmAbertoEEhDivergente()} — o critério de
 *       "paga" é o mesmo do Fluxo de Caixa, e a divergência é <b>mostrada</b>.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RelatorioContasPagarCrudTest {

    /** Fuso da loja — o mesmo que o relatório usa para decidir o que está vencido. */
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    // ------------------------------------------------------------------------------ ferramentas

    private String assinarNovoTenant(String sufixo) throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja RCP %s","email":"dono%s@lojarcp.com",
                         "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                        """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long idTenantDo(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'))));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    private long buscarIdEmpresa(long idTenant) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Aceita 409: o signup já copia o plano de contas padrão (76 contas), então o código pode já
     * existir — e nesse caso vale a descrição semeada, não a daqui.
     *
     * <p>⚠️ {@code incluiDre = false} é justamente o que a DRE <b>exclui</b> — e é isso que torna
     * {@link #compraDeMercadoriaAPARECE()} um teste de verdade: a conta é invisível para a DRE e
     * tem de aparecer aqui.
     */
    private void criarPlano(String token, String codigo, String descricao) throws Exception {
        int status = mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"%s","tipoMovimento":"DEBITO","natureza":"ANALITICA",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """.formatted(codigo, descricao)))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(201, 409);
    }

    private long criarFornecedor(String token, String razaoSocial) throws Exception {
        criarPlano(token, "9.00.000", "fornecedor teste");
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"%s\",\"idPlanoContas\":\"9.00.000\"}".formatted(razaoSocial)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFornecedor")).longValue();
    }

    private record Base(String token, long idFornecedor, long idEmpresa) {
    }

    private Base prepararBase(String sufixo) throws Exception {
        String token = assinarNovoTenant(sufixo);
        long idFornecedor = criarFornecedor(token, "FORNECEDOR " + sufixo.toUpperCase());
        criarPlano(token, "9.00.000", "despesa teste");
        return new Base(token, idFornecedor, buscarIdEmpresa(idTenantDo(token)));
    }

    /** @param dataPagamento {@code null} = conta em aberto */
    private long criarConta(Base base, String plano, String lancamento, String vencimento,
                            String valorPagar, String dataPagamento, String valorPago,
                            boolean documentoPago) throws Exception {
        // ⚠️ Registrar pagamento exige dizer DE ONDE saiu o dinheiro (2026-08-14, docs/telas/
        // fluxo-caixa.md): a baixa vira movimento no caixa aberto. Sem `origemPagamento` o CRUD
        // responde 400 — e é assim que ele impede uma conta "paga" sem dinheiro saindo de lugar
        // nenhum, que era o buraco entre Contas a Pagar e o Fluxo de Caixa.
        String pagamento = dataPagamento == null
                ? ""
                : ",\"dataPagamento\":\"%sT12:00:00Z\",\"valorPago\":%s,\"origemPagamento\":\"CAIXA\""
                        .formatted(dataPagamento, valorPago);

        String resp = mvc.perform(post("/api/v1/contas-pagar").header("Authorization", "Bearer " + base.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"idEmpresa":%d,"idPlanoContas":"%s","notaFiscal":100,
                                 "numeroDuplicata":"D-%s","dataLancamento":"%sT12:00:00Z",
                                 "dataVencimento":"%sT12:00:00Z","valorPagar":%s,
                                 "documentoPago":%s%s}
                                """.formatted(base.idFornecedor(), base.idEmpresa(), plano, vencimento,
                                lancamento, vencimento, valorPagar, documentoPago, pagamento)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idContaPagar")).longValue();
    }

    /** Abre o caixa: desde 2026-08-14 a baixa vira movimento no caixa aberto. */
    private void abrirCaixa(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":0}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    /** ⚠️ Datas relativas a HOJE no fuso da LOJA — nunca literais, senão o teste vence com o tempo. */
    private static String hojeMais(int dias) {
        return LocalDate.now(SP).plusDays(dias).toString();
    }

    private String gerar(String token, String query) throws Exception {
        return mvc.perform(get("/api/v1/relatorios/contas-pagar?" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Janela larga de vencimento, para o filtro nunca ser o motivo de uma linha sumir. */
    private static String janelaVencimento() {
        return "dataVencimentoInicial=" + hojeMais(-200) + "&dataVencimentoFinal=" + hojeMais(200);
    }

    // ------------------------------------------------------------------------------ validações

    @Test
    void semNenhumPeriodoResponde400() throws Exception {
        String token = assinarNovoTenant("sem-periodo");
        mvc.perform(get("/api/v1/relatorios/contas-pagar").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("ao menos um período")));
    }

    @Test
    void periodoIncompletoResponde400() throws Exception {
        String token = assinarNovoTenant("incompleto");
        mvc.perform(get("/api/v1/relatorios/contas-pagar?dataVencimentoInicial=2026-01-01")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("início e fim")));
    }

    @Test
    void periodoMaiorQue400DiasResponde400() throws Exception {
        String token = assinarNovoTenant("longo");
        mvc.perform(get("/api/v1/relatorios/contas-pagar"
                        + "?dataVencimentoInicial=2025-01-01&dataVencimentoFinal=2026-12-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("400 dias")));
    }

    @Test
    void dataInicialMaiorQueFinalResponde400() throws Exception {
        String token = assinarNovoTenant("invertido");
        mvc.perform(get("/api/v1/relatorios/contas-pagar"
                        + "?dataVencimentoInicial=2026-06-30&dataVencimentoFinal=2026-06-01")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------- o que o filtro faz

    /**
     * ⭐ Compra de mercadoria APARECE — este relatório é de <b>desembolso</b>, não de resultado.
     *
     * <p>A DRE e a Lucratividade excluem `3.03.x` de propósito (a mercadoria já está no CMV, e
     * contá-la duas vezes transformaria lucro em prejuízo). Aqui é o oposto: ela é a maior saída
     * de dinheiro da loja, e escondê-la esvaziaria o relatório.
     */
    @Test
    void compraDeMercadoriaAPARECE() throws Exception {
        Base base = prepararBase("mercadoria");
        criarPlano(base.token(), "3.03.001", "compra de mercadoria");
        criarConta(base, "3.03.001", hojeMais(-10), hojeMais(10), "5000.00", null, "0", false);

        String json = gerar(base.token(), janelaVencimento());

        assertThat((java.util.List<?>) JsonPath.read(json, "$.linhas")).hasSize(1);
        assertThat(JsonPath.read(json, "$.linhas[0].descricaoPlanoContas").toString())
                .containsIgnoringCase("mercadoria");
        assertThat(((Number) JsonPath.read(json, "$.totalGeral.valorPagar")).doubleValue())
                .isEqualTo(5000.00);
    }

    /**
     * ⭐ {@code documento_pago = true} sem {@code data_pagamento}: conta como <b>em aberto</b> —
     * mesmo critério do Fluxo de Caixa — e a linha é marcada como <b>divergente</b>.
     *
     * <p>⛔ Se este relatório usasse {@code documento_pago}, duas telas financeiras dariam
     * respostas opostas sobre o mesmo fato.
     */
    @Test
    void documentoPagoSemDataDePagamentoContaComoEmAbertoEEhDivergente() throws Exception {
        Base base = prepararBase("divergente");
        criarConta(base, "9.00.000", hojeMais(-10), hojeMais(10), "300.00", null, "0", true);

        String json = gerar(base.token(), janelaVencimento());

        assertThat(JsonPath.read(json, "$.linhas[0].situacao").toString()).isEqualTo("A_VENCER");
        assertThat((Boolean) JsonPath.read(json, "$.linhas[0].divergente")).isTrue();
        assertThat(((Number) JsonPath.read(json, "$.kpis.emAberto")).doubleValue()).isEqualTo(300.00);
        assertThat(((Number) JsonPath.read(json, "$.kpis.pagoNoPeriodo")).doubleValue()).isZero();
    }

    /** Pagamento parcial: o em aberto é a diferença, não zero nem o valor cheio. */
    @Test
    void pagamentoParcialDeixaOSaldoEmAberto() throws Exception {
        Base base = prepararBase("parcial");
        abrirCaixa(base.token());
        criarConta(base, "9.00.000", hojeMais(-10), hojeMais(10), "1000.00", hojeMais(-1), "400.00", true);

        String json = gerar(base.token(), janelaVencimento());

        assertThat(((Number) JsonPath.read(json, "$.linhas[0].valorEmAberto")).doubleValue())
                .isEqualTo(600.00);
        assertThat(((Number) JsonPath.read(json, "$.totalGeral.valorPago")).doubleValue())
                .isEqualTo(400.00);
    }

    /** Vencida soma no KPI Vencido; a vencer soma no outro — e os dois nunca no Total. */
    @Test
    void vencidaEAVencerCaemEmKpisDiferentes() throws Exception {
        Base base = prepararBase("vencida");
        criarConta(base, "9.00.000", hojeMais(-40), hojeMais(-5), "100.00", null, "0", false);
        criarConta(base, "9.00.000", hojeMais(-40), hojeMais(5), "250.00", null, "0", false);

        String json = gerar(base.token(), janelaVencimento());

        assertThat(((Number) JsonPath.read(json, "$.kpis.vencido")).doubleValue()).isEqualTo(100.00);
        assertThat(((Number) JsonPath.read(json, "$.kpis.aVencer")).doubleValue()).isEqualTo(250.00);
        // ⚠️ Vencido + A vencer = Em aberto. Nunca o Total.
        assertThat(((Number) JsonPath.read(json, "$.kpis.emAberto")).doubleValue()).isEqualTo(350.00);
    }

    @Test
    void filtroDeSituacaoIsolaAbertasEPagas() throws Exception {
        Base base = prepararBase("situacao");
        abrirCaixa(base.token());
        criarConta(base, "9.00.000", hojeMais(-20), hojeMais(-2), "100.00", hojeMais(-1), "100.00", true);
        criarConta(base, "9.00.000", hojeMais(-20), hojeMais(8), "200.00", null, "0", false);

        String abertas = gerar(base.token(), janelaVencimento() + "&situacao=ABERTA");
        assertThat((java.util.List<?>) JsonPath.read(abertas, "$.linhas")).hasSize(1);
        assertThat(((Number) JsonPath.read(abertas, "$.linhas[0].valorPagar")).doubleValue()).isEqualTo(200.00);

        String pagas = gerar(base.token(), janelaVencimento() + "&situacao=PAGA");
        assertThat((java.util.List<?>) JsonPath.read(pagas, "$.linhas")).hasSize(1);
        assertThat(((Number) JsonPath.read(pagas, "$.linhas[0].valorPago")).doubleValue()).isEqualTo(100.00);
    }

    /** O período de pagamento só traz o que foi pago dentro da janela. */
    @Test
    void filtroPorPeriodoDePagamentoIsolaAJanela() throws Exception {
        Base base = prepararBase("periodo-pag");
        abrirCaixa(base.token());
        criarConta(base, "9.00.000", hojeMais(-60), hojeMais(-50), "100.00", hojeMais(-45), "100.00", true);
        criarConta(base, "9.00.000", hojeMais(-20), hojeMais(-10), "700.00", hojeMais(-2), "700.00", true);

        String json = gerar(base.token(),
                "dataPagamentoInicial=" + hojeMais(-5) + "&dataPagamentoFinal=" + hojeMais(0));

        assertThat((java.util.List<?>) JsonPath.read(json, "$.linhas")).hasSize(1);
        assertThat(((Number) JsonPath.read(json, "$.kpis.pagoNoPeriodo")).doubleValue()).isEqualTo(700.00);
    }

    /**
     * ⭐ Filtrar por conta SINTÉTICA traz a subárvore.
     *
     * <p>⚠️ Sem o casamento por prefixo o resultado seria <b>vazio</b>: lançamento nunca cai em
     * conta sintética (V016 — só ANALITICA recebe lançamento). O lojista escolheria "Compra de
     * mercadoria" e veria o relatório em branco.
     */
    @Test
    void filtroPorContaSinteticaTrazASubarvore() throws Exception {
        Base base = prepararBase("sintetica");
        criarPlano(base.token(), "3.03.001", "mercadoria a");
        criarPlano(base.token(), "3.03.002", "mercadoria b");
        criarConta(base, "3.03.001", hojeMais(-10), hojeMais(10), "100.00", null, "0", false);
        criarConta(base, "3.03.002", hojeMais(-10), hojeMais(10), "200.00", null, "0", false);
        criarConta(base, "9.00.000", hojeMais(-10), hojeMais(10), "999.00", null, "0", false);

        String json = gerar(base.token(), janelaVencimento() + "&idPlanoContas=3.03.000");

        assertThat((java.util.List<?>) JsonPath.read(json, "$.linhas")).hasSize(2);
        assertThat(((Number) JsonPath.read(json, "$.totalGeral.valorPagar")).doubleValue())
                .isEqualTo(300.00);
    }

    @Test
    void filtroPorFornecedorIsola() throws Exception {
        Base base = prepararBase("fornecedor");
        long outro = criarFornecedor(base.token(), "OUTRO FORNECEDOR");
        criarConta(base, "9.00.000", hojeMais(-10), hojeMais(10), "100.00", null, "0", false);

        String json = gerar(base.token(), janelaVencimento() + "&idFornecedor=" + outro);
        assertThat((java.util.List<?>) JsonPath.read(json, "$.linhas")).isEmpty();

        String doMeu = gerar(base.token(), janelaVencimento() + "&idFornecedor=" + base.idFornecedor());
        assertThat((java.util.List<?>) JsonPath.read(doMeu, "$.linhas")).hasSize(1);
    }

    // -------------------------------------------------------------------------------- gráficos

    /**
     * O gráfico soma {@code valorPagar} e as fatias fecham o total.
     *
     * <p>⚠️ Um gráfico cujas fatias não somam o total faz o leitor desconfiar de tudo o que está
     * na tela — por isso o corte vira "Outros" em vez de ser descartado.
     */
    @Test
    void graficosSomamOTotalEAgrupamPorRotulo() throws Exception {
        Base base = prepararBase("grafico");
        criarPlano(base.token(), "3.03.001", "mercadoria");
        criarConta(base, "3.03.001", hojeMais(-10), hojeMais(10), "700.00", null, "0", false);
        criarConta(base, "9.00.000", hojeMais(-10), hojeMais(10), "300.00", null, "0", false);

        String json = gerar(base.token(), janelaVencimento());

        java.util.List<?> porPlano = JsonPath.read(json, "$.graficoPorPlanoContas");
        assertThat(porPlano).hasSize(2);
        double soma = ((Number) JsonPath.read(json, "$.graficoPorPlanoContas[0].valor")).doubleValue()
                + ((Number) JsonPath.read(json, "$.graficoPorPlanoContas[1].valor")).doubleValue();
        assertThat(soma).isEqualTo(1000.00);

        // Um fornecedor só: uma fatia com o total inteiro.
        assertThat((java.util.List<?>) JsonPath.read(json, "$.graficoPorFornecedor")).hasSize(1);
        assertThat(((Number) JsonPath.read(json, "$.graficoPorFornecedor[0].valor")).doubleValue())
                .isEqualTo(1000.00);
    }

    // ------------------------------------------------------------------ ordenação e isolamento

    @Test
    void ordenacaoPorValorRespeitaADirecao() throws Exception {
        Base base = prepararBase("ordem");
        criarConta(base, "9.00.000", hojeMais(-10), hojeMais(10), "100.00", null, "0", false);
        criarConta(base, "9.00.000", hojeMais(-10), hojeMais(11), "900.00", null, "0", false);

        String asc = gerar(base.token(), janelaVencimento() + "&ordenarPor=valorPagar&direcao=ASC");
        assertThat(((Number) JsonPath.read(asc, "$.linhas[0].valorPagar")).doubleValue()).isEqualTo(100.00);

        String desc = gerar(base.token(), janelaVencimento() + "&ordenarPor=valorPagar&direcao=DESC");
        assertThat(((Number) JsonPath.read(desc, "$.linhas[0].valorPagar")).doubleValue()).isEqualTo(900.00);
    }

    /** P8: conta de um tenant não aparece no relatório de outro. */
    @Test
    void contaDeOutroTenantNaoAparece() throws Exception {
        Base a = prepararBase("iso-a");
        Base b = prepararBase("iso-b");
        criarConta(a, "9.00.000", hojeMais(-10), hojeMais(10), "555.00", null, "0", false);

        String json = gerar(b.token(), janelaVencimento());
        assertThat((java.util.List<?>) JsonPath.read(json, "$.linhas")).isEmpty();
        assertThat(((Number) JsonPath.read(json, "$.totalGeral.valorPagar")).doubleValue()).isZero();
    }
}
