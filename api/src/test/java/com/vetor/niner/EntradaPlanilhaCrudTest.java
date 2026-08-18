package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo Planilha da Entrada de Produtos por Compra (docs/telas/entrada-mercadoria.md,
 * 2026-08-12) — preview de matching (EAN, descrição+marca+referência, cor/tamanho por nome) e
 * download do modelo. Nenhum teste aqui confirma a entrada (isso já é coberto por
 * {@code EntradaMercadoriaCrudTest} — o preview só resolve/materializa variação, não grava
 * ledger nem contas a pagar).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EntradaPlanilhaCrudTest {

    private static final String[] COLUNAS = {
            "NOME DO PRODUTO", "MARCA", "REFERENCIA", "COR", "TAMANHO",
            "CODIGO BARRAS FABRICANTE", "QTD", "CUSTO UNITARIO"
    };

    @Autowired
    MockMvc mvc;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Planilha %s","email":"dono%s@lojaplanilha.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    /** Grade só é gravada no produto quando o tenant usa cor/grade (`cfg_geral`, default
     *  desligado no signup) — mesmo helper de {@code ProdutoCrudTest.ativarCorGrade}. */
    private void ativarCorGrade(String token) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":true,
                                 "cfgPermiteQtdDecimal":true,"cfgExigeNumeroVendaDevolucao":false,
                                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,"cfgConsisteValorContasPagar":false,
                                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":true}
                                """))
                .andExpect(status().isOk());
    }

    private long criarProduto(String token, String descricao, String marca, String referencia, Long idGrade) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","marca":"%s","referencia":"%s","precoCusto":"10.00",
                                 "percentualVenda":"100","precoVenda":"20.00","idGrade":%s}
                                """.formatted(descricao, marca, referencia, idGrade == null ? "null" : idGrade)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarVariacao(String token, long idProduto, Long idCor, Long idTamanho, String ean) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos/" + idProduto + "/variacoes")
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("""
                                {"idCor":%s,"idTamanho":%s,"ean":%s}
                                """.formatted(
                                idCor == null ? "null" : idCor,
                                idTamanho == null ? "null" : idTamanho,
                                ean == null ? "null" : "\"" + ean + "\"")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVariacao")).longValue();
    }

    private long criarCor(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/cores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"%s\"}".formatted(descricao)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCor")).longValue();
    }

    private long criarTamanho(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/tamanhos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"descricao\":\"%s\"}".formatted(descricao)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idTamanho")).longValue();
    }

    private long criarGrade(String token, String descricao, long idTamanho) throws Exception {
        String resp = mvc.perform(post("/api/v1/grades").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"%s\",\"idsTamanho\":[%d]}".formatted(descricao, idTamanho)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idGrade")).longValue();
    }

    private MockMultipartFile planilhaCom(String[]... linhas) {
        byte[] bytes = ImportacaoPlanilha.gerarModelo(COLUNAS, linhas);
        return new MockMultipartFile("arquivo", "entrada.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    @Test
    void casaPorCodigoDeBarrasDoFabricante() throws Exception {
        String token = assinarNovoTenant("ean");
        long idProduto = criarProduto(token, "TENIS EAN", "MARCA X", "REF-1", null);
        long idVariacao = criarVariacao(token, idProduto, null, null, "7891111111111");

        MockMultipartFile planilha = planilhaCom(
                new String[] {"NOME QUALQUER DIFERENTE", "OUTRA MARCA", "OUTRA REF", "", "", "7891111111111", "5", "12,50"});

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/planilha/preview")
                        .file(planilha).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> itens = JsonPath.read(resp, "$");
        assertThat(itens).hasSize(1);
        assertThat(itens.get(0).get("resolvido")).isEqualTo(true);
        assertThat(((Number) itens.get(0).get("idVariacao")).longValue()).isEqualTo(idVariacao);
        assertThat(((Number) itens.get(0).get("qtd")).doubleValue()).isEqualTo(5.0);
        assertThat(((Number) itens.get(0).get("custoUnitario")).doubleValue()).isEqualTo(12.5);
    }

    @Test
    void casaPorDescricaoMarcaReferenciaQuandoProdutoNaoTemGrade() throws Exception {
        String token = assinarNovoTenant("semgrade");
        criarProduto(token, "CAMISETA BASICA", "MARCA Y", "REF-2", null);

        MockMultipartFile planilha = planilhaCom(
                new String[] {"CAMISETA BASICA", "MARCA Y", "REF-2", "", "", "", "3", "20,00"});

        mvc.perform(multipart("/api/v1/estoque/entradas/planilha/preview")
                        .file(planilha).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].resolvido").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].idVariacao").exists());
    }

    @Test
    void casaCorETamanhoPorNomeDentroDaGradeDoProduto() throws Exception {
        String token = assinarNovoTenant("comgrade");
        ativarCorGrade(token);
        long idTamanho = criarTamanho(token, "M");
        long idGrade = criarGrade(token, "GRADE TESTE PLANILHA", idTamanho);
        criarProduto(token, "CALCA JEANS", "MARCA Z", "REF-3", idGrade);
        criarCor(token, "AZUL");

        MockMultipartFile planilha = planilhaCom(
                new String[] {"CALCA JEANS", "MARCA Z", "REF-3", "AZUL", "M", "", "2", "45,90"});

        mvc.perform(multipart("/api/v1/estoque/entradas/planilha/preview")
                        .file(planilha).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].resolvido").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].variacaoCor").value("AZUL"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].variacaoTamanho").value("M"));
    }

    @Test
    void produtoNaoEncontradoFicaPendente() throws Exception {
        String token = assinarNovoTenant("pendente");

        MockMultipartFile planilha = planilhaCom(
                new String[] {"PRODUTO INEXISTENTE XPTO", "MARCA W", "REF-9", "", "", "", "1", "10,00"});

        mvc.perform(multipart("/api/v1/estoque/entradas/planilha/preview")
                        .file(planilha).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].resolvido").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].idProdutoEncontrado").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].motivoPendencia").exists());
    }

    /** Cor que não bate com nada de {@code cfg_cor} nasce na hora (2026-08-13, pedido do dono do
     *  produto: "cor e tamanho que não existirem no cadastro você pode cadastrar
     *  automaticamente") — a linha resolve de primeira, sem virar pendência. */
    @Test
    void corNaoCadastradaEhCriadaAutomaticamenteEALinhaResolve() throws Exception {
        String token = assinarNovoTenant("autocor");
        ativarCorGrade(token);
        long idTamanho = criarTamanho(token, "G");
        long idGrade = criarGrade(token, "GRADE AUTO COR", idTamanho);
        criarProduto(token, "BERMUDA", "MARCA K", "REF-4", idGrade);

        MockMultipartFile planilha = planilhaCom(
                new String[] {"BERMUDA", "MARCA K", "REF-4", "ROXO", "G", "", "1", "30,00"});

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/planilha/preview")
                        .file(planilha).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((Boolean) JsonPath.read(resp, "$[0].resolvido")).isTrue();
        assertThat((String) JsonPath.read(resp, "$[0].variacaoCor")).isEqualTo("ROXO");
        assertThat((String) JsonPath.read(resp, "$[0].variacaoTamanho")).isEqualTo("G");

        mvc.perform(get("/api/v1/cores").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[?(@.descricao=='ROXO')]").exists());
    }

    /** Tamanho que não bate com nada de {@code cfg_tamanho} (nem está na grade do produto)
     *  nasce na hora E a grade é estendida com ele — mesma operação que "＋ Gerenciar Grades"
     *  faria manualmente. O tamanho que já existia na grade continua lá. */
    @Test
    void tamanhoNaoCadastradoEhCriadoEAdicionadoNaGradeDoProduto() throws Exception {
        String token = assinarNovoTenant("autotam");
        ativarCorGrade(token);
        long idTamanhoExistente = criarTamanho(token, "38");
        long idGrade = criarGrade(token, "GRADE AUTO TAMANHO", idTamanhoExistente);
        criarProduto(token, "TENIS CORRIDA", "MARCA T", "REF-5", idGrade);
        criarCor(token, "PRETO");

        MockMultipartFile planilha = planilhaCom(
                new String[] {"TENIS CORRIDA", "MARCA T", "REF-5", "PRETO", "43", "", "2", "89,90"});

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/planilha/preview")
                        .file(planilha).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((Boolean) JsonPath.read(resp, "$[0].resolvido")).isTrue();
        assertThat((String) JsonPath.read(resp, "$[0].variacaoTamanho")).isEqualTo("43");

        String grade = mvc.perform(get("/api/v1/grades/" + idGrade).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> tamanhos = JsonPath.read(grade, "$.tamanhos[*].descricao");
        assertThat(tamanhos).containsExactlyInAnyOrder("38", "43");
    }

    @Test
    void colunaCorVaziaFicaPendenteQuandoProdutoExigeGrade() throws Exception {
        String token = assinarNovoTenant("corvazia");
        ativarCorGrade(token);
        long idTamanho = criarTamanho(token, "P");
        long idGrade = criarGrade(token, "GRADE COR VAZIA", idTamanho);
        long idProduto = criarProduto(token, "REGATA", "MARCA R", "REF-6", idGrade);

        MockMultipartFile planilha = planilhaCom(
                new String[] {"REGATA", "MARCA R", "REF-6", "", "P", "", "1", "15,00"});

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/planilha/preview")
                        .file(planilha).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((Boolean) JsonPath.read(resp, "$[0].resolvido")).isFalse();
        assertThat(((Number) JsonPath.read(resp, "$[0].idProdutoEncontrado")).longValue()).isEqualTo(idProduto);
        assertThat(((Number) JsonPath.read(resp, "$[0].idGradeEncontrada")).longValue()).isEqualTo(idGrade);
        assertThat((String) JsonPath.read(resp, "$[0].motivoPendencia")).containsIgnoringCase("COR");
    }

    @Test
    void colunaTamanhoVaziaFicaPendenteQuandoProdutoExigeGrade() throws Exception {
        String token = assinarNovoTenant("tamvazio");
        ativarCorGrade(token);
        long idTamanho = criarTamanho(token, "U");
        long idGrade = criarGrade(token, "GRADE TAMANHO VAZIO", idTamanho);
        long idProduto = criarProduto(token, "BONE", "MARCA B", "REF-7", idGrade);

        MockMultipartFile planilha = planilhaCom(
                new String[] {"BONE", "MARCA B", "REF-7", "AZUL", "", "", "1", "25,00"});

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/planilha/preview")
                        .file(planilha).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((Boolean) JsonPath.read(resp, "$[0].resolvido")).isFalse();
        assertThat(((Number) JsonPath.read(resp, "$[0].idProdutoEncontrado")).longValue()).isEqualTo(idProduto);
        assertThat(((Number) JsonPath.read(resp, "$[0].idGradeEncontrada")).longValue()).isEqualTo(idGrade);
        assertThat((String) JsonPath.read(resp, "$[0].motivoPendencia")).containsIgnoringCase("TAMANHO");
    }

    @Test
    void baixaModeloComAsColunasEsperadas() throws Exception {
        String token = assinarNovoTenant("modelo");

        mvc.perform(get("/api/v1/estoque/entradas/planilha/modelo").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("entrada_produtos_modelo.xlsx")));
    }
}
