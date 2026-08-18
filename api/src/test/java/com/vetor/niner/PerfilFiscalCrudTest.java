package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Perfis Fiscais (docs/telas/fiscal-perfil.md) — cabeçalho + coleção filha de regras, mesmo
 * mecanismo de "apaga e reinsere" de {@code ProdutoService.salvarCategorias}.
 *
 * <p>DF37: só CRT 1, 2 e 4 são aceitos. O CST de ICMS só é válido no CRT 2 — é o caso torto que
 * a massa de teste precisa cobrir, não o caso comum.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PerfilFiscalCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    // ---------------------------------------------------------------- helpers

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Perfil %s","email":"dono%s@lojaperfil.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private String comoOperador(String tokenAdmin) {
        String p = payload(tokenAdmin);
        return tokens.emitir(
                Long.parseLong(JsonPath.read(p, "$.sub").toString()),
                ((Number) JsonPath.read(p, "$.tid")).longValue(),
                ((Number) JsonPath.read(p, "$.eid")).longValue(),
                JsonPath.read(p, "$.email"),
                List.of("OPERADOR"));
    }

    private static String regra(int crt, String ufDestino, String tipoDestinatario, String tipoOperacao,
                                String cfop, String cstIcms, String csosn, String cstPis, String cstCofins) {
        return """
                {"crt":%d,"ufDestino":"%s","tipoDestinatario":"%s","tipoOperacao":"%s",
                 "cfop":"%s","cstIcms":%s,"csosn":%s,"aliquotaIcms":"0","percReducaoBc":"0",
                 "mvaSt":"0","aliquotaFcp":"0","cstPis":"%s","aliquotaPis":"0",
                 "cstCofins":"%s","aliquotaCofins":"0","cstIbscbs":null,"cclasstrib":null,
                 "codigoBeneficio":null}
                """.formatted(crt, ufDestino, tipoDestinatario, tipoOperacao, cfop,
                jsonStr(cstIcms), jsonStr(csosn), cstPis, cstCofins);
    }

    private static String jsonStr(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    /** Regra padrão do caso comum: CRT 1, CSOSN 102, CST 99 de PIS/COFINS. */
    private static String regraSimples(String uf, String tipoOperacao) {
        return regra(1, uf, "CONSUMIDOR_FINAL", tipoOperacao, "5102", null, "102", "99", "99");
    }

    private static String corpo(String nome, boolean ativo, String... regras) {
        return """
                {"nome":"%s","descricao":"TESTE","ativo":%b,"regras":[%s]}
                """.formatted(nome, ativo, String.join(",", regras));
    }

    private long criarProdutoSimples(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"1.00","percentualVenda":"0","precoVenda":"1.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private static long extrairIdTenant(String token) {
        String payload = payload(token);
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    // ---------------------------------------------------------------- criação e leitura

    @Test
    void criaComQuatroRegrasEListaOrdenadoPorEspecificidade() throws Exception {
        String token = assinarNovoTenant("cria4");

        String resp = mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("REVENDA GERAL", true,
                                regra(1, "*", "CONSUMIDOR_FINAL", "VENDA_CONSUMIDOR", "5102", null, "102", "99", "99"),
                                regra(1, "PR", "CONSUMIDOR_FINAL", "VENDA_CONSUMIDOR", "5102", null, "102", "99", "99"),
                                regra(1, "*", "CONTRIBUINTE", "VENDA_CONSUMIDOR", "5102", null, "102", "99", "99"),
                                regra(1, "*", "CONSUMIDOR_FINAL", "DEVOLUCAO_VENDA", "1102", null, "102", "99", "99"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idPerfilFiscal")).longValue();

        mvc.perform(get("/api/v1/fiscal/perfis/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regras.length()").value(4))
                .andExpect(jsonPath("$.regras[0].ufDestino").value("PR"));   // UF exata antes do '*'
    }

    @Test
    void nomeGravadoEmMaiusculas() throws Exception {
        String token = assinarNovoTenant("maiusc");

        String resp = mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("revenda minuscula", true, regraSimples("*", "VENDA_CONSUMIDOR"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(resp, "$.nome")).isEqualTo("REVENDA MINUSCULA");
    }

    // ---------------------------------------------------------------- atualização (apaga e reinsere)

    @Test
    void atualizarComDuasRegrasDeixaExatamenteDuas() throws Exception {
        String token = assinarNovoTenant("update2");

        String resp = mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL A", true,
                                regraSimples("*", "VENDA_CONSUMIDOR"),
                                regraSimples("*", "DEVOLUCAO_VENDA"),
                                regra(1, "PR", "CONSUMIDOR_FINAL", "VENDA_CONSUMIDOR", "5102", null, "102", "99", "99"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idPerfilFiscal")).longValue();

        mvc.perform(put("/api/v1/fiscal/perfis/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL A", true,
                                regraSimples("*", "VENDA_CONSUMIDOR"),
                                regraSimples("*", "DEVOLUCAO_VENDA"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regras.length()").value(2));

        mvc.perform(get("/api/v1/fiscal/perfis/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regras.length()").value(2));
    }

    // ---------------------------------------------------------------- DF37 — CST só no CRT 2

    @Test
    void crt1ComCstIcmsEhRejeitado() throws Exception {
        String token = assinarNovoTenant("crt1cst");

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL CRT1 CST", true,
                                regra(1, "*", "CONSUMIDOR_FINAL", "VENDA_CONSUMIDOR", "5102", "00", null, "99", "99"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("CRT 2")));
    }

    @Test
    void crt4ComCstIcmsEhRejeitado() throws Exception {
        String token = assinarNovoTenant("crt4cst");

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL CRT4 CST", true,
                                regra(4, "*", "CONSUMIDOR_FINAL", "VENDA_CONSUMIDOR", "5102", "00", null, "99", "99"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crt2ComCstIcmsEhAceito() throws Exception {
        String token = assinarNovoTenant("crt2cst");

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL CRT2 CST", true,
                                regra(2, "*", "CONSUMIDOR_FINAL", "VENDA_CONSUMIDOR", "5102", "00", null, "99", "99"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.regras[0].cstIcms").value("00"))
                .andExpect(jsonPath("$.regras[0].csosn").doesNotExist());
    }

    @Test
    void crt2ComCsosnTambemEhAceito() throws Exception {
        String token = assinarNovoTenant("crt2csosn");

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL CRT2 CSOSN", true, regraSimples("*", "VENDA_CONSUMIDOR")
                                .replace("\"crt\":1", "\"crt\":2"))))
                .andExpect(status().isCreated());
    }

    /** DF37: CRT 3 é Lucro Real/Presumido — fora do escopo do produto, recusa em toda camada. */
    @Test
    void crt3EhRejeitadoPorEstarForaDoEscopo() throws Exception {
        String token = assinarNovoTenant("crt3fora");

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL CRT3", true,
                                regra(3, "*", "CONSUMIDOR_FINAL", "VENDA_CONSUMIDOR", "5102", "00", null, "99", "99"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("fora do escopo")));
    }

    // ---------------------------------------------------------------- validações de regra

    @Test
    void regraSemCstESemCsosnEhRejeitada() throws Exception {
        String token = assinarNovoTenant("nemnem");

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL VAZIO", true,
                                regra(1, "*", "CONSUMIDOR_FINAL", "VENDA_CONSUMIDOR", "5102", null, null, "99", "99"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duasRegrasComMesmaCombinacaoSaoRejeitadasComMensagemLegivel() throws Exception {
        String token = assinarNovoTenant("duplic");

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL DUP", true,
                                regraSimples("*", "VENDA_CONSUMIDOR"),
                                regraSimples("*", "VENDA_CONSUMIDOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("duplicada")));
    }

    @Test
    void nomeDuplicadoRespondeConflito() throws Exception {
        String token = assinarNovoTenant("nomedup");

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL UNICO", true, regraSimples("*", "VENDA_CONSUMIDOR"))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL UNICO", true, regraSimples("*", "DEVOLUCAO_VENDA"))))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- exclusão

    @Test
    void excluiDeVerdadeQuandoNenhumProdutoAponta() throws Exception {
        String token = assinarNovoTenant("excluisemuso");

        String resp = mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL DESCARTAVEL", true, regraSimples("*", "VENDA_CONSUMIDOR"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idPerfilFiscal")).longValue();

        mvc.perform(delete("/api/v1/fiscal/perfis/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/fiscal/perfis/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void caiParaInativarQuandoProdutoAponta() throws Exception {
        String token = assinarNovoTenant("inativa");
        long idTenant = extrairIdTenant(token);

        String resp = mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL EM USO", true, regraSimples("*", "VENDA_CONSUMIDOR"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idPerfil = ((Number) JsonPath.read(resp, "$.idPerfilFiscal")).longValue();

        long idProduto = criarProdutoSimples(token, "Produto Com Perfil");
        jdbc.sql("UPDATE produto SET id_perfil_fiscal = ? WHERE id_tenant = ? AND id_produto = ?")
                .params(idPerfil, idTenant, idProduto).update();

        mvc.perform(delete("/api/v1/fiscal/perfis/" + idPerfil).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        // O produto continua apontando — desativar não pode quebrar quem já estava emitindo.
        Long idPerfilAindaVinculado = jdbc.sql(
                        "SELECT id_perfil_fiscal FROM produto WHERE id_tenant = ? AND id_produto = ?")
                .params(idTenant, idProduto).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(idPerfilAindaVinculado).isEqualTo(idPerfil);

        mvc.perform(get("/api/v1/fiscal/perfis/" + idPerfil).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    // ---------------------------------------------------------------- /opcoes

    /** O tenant já nasce com os 3 perfis padrão do signup (ver
     *  {@code novoTenantJaNasceComOsTresPerfisPadrao} abaixo) — /opcoes soma esses com o criado aqui. */
    @Test
    void opcoesNaoExigePapelESoTrazAtivos() throws Exception {
        String token = assinarNovoTenant("opcoes");
        String operador = comoOperador(token);

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL ATIVO", true, regraSimples("*", "VENDA_CONSUMIDOR"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL INATIVO", false, regraSimples("*", "DEVOLUCAO_VENDA"))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/fiscal/perfis/opcoes").header("Authorization", "Bearer " + operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].nome", org.hamcrest.Matchers.hasItem("PERFIL ATIVO")))
                .andExpect(jsonPath("$[*].nome", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("PERFIL INATIVO"))));
    }

    // ---------------------------------------------------------------- seed do signup (2026-08-19)

    /** REVENDA TRIBUTADA NORMAL (CSOSN 102), REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA (CSOSN 500) e
     *  NÃO INFORMADO (sem regra nenhuma) — os três perfis padrão que todo tenant novo já ganha
     *  (docs/telas/fiscal-perfil.md, seção "Perfis semeados no signup"), pra não começar o fiscal
     *  com uma tela vazia. Os dois primeiros cobrem os três CRT do produto (1, 2 e 4 — DF37),
     *  então atendem Simples Nacional e MEI ao mesmo tempo — daí o grid mostrar os dois badges. O
     *  terceiro é o sentinela que a Importação de Produtos usa quando TRIBUTACAO vem em branco:
     *  sem regra nenhuma, então nenhum badge (nem Simples nem MEI) e o motor recusa emitir. */
    @Test
    void novoTenantJaNasceComOsTresPerfisPadrao() throws Exception {
        String token = assinarNovoTenant("seed-signup");

        String resp = mvc.perform(get("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .queryParam("limite", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(3))
                .andReturn().getResponse().getContentAsString();

        List<java.util.Map<String, Object>> itens = JsonPath.read(resp, "$.itens");
        var normal = itens.stream().filter(i -> "REVENDA TRIBUTADA NORMAL".equals(i.get("nome"))).findFirst()
                .orElseThrow(() -> new AssertionError("Perfil padrão não encontrado: REVENDA TRIBUTADA NORMAL"));
        var st = itens.stream().filter(i -> "REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA (ST)".equals(i.get("nome"))).findFirst()
                .orElseThrow(() -> new AssertionError("Perfil padrão não encontrado: REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA (ST)"));
        var naoInformado = itens.stream().filter(i -> "NÃO INFORMADO".equals(i.get("nome"))).findFirst()
                .orElseThrow(() -> new AssertionError("Perfil padrão não encontrado: NÃO INFORMADO"));

        for (var perfil : List.of(normal, st)) {
            org.assertj.core.api.Assertions.assertThat(perfil.get("ativo")).isEqualTo(true);
            org.assertj.core.api.Assertions.assertThat(perfil.get("quantidadeRegras")).isEqualTo(3);
            org.assertj.core.api.Assertions.assertThat(perfil.get("atendeSimples")).isEqualTo(true);
            org.assertj.core.api.Assertions.assertThat(perfil.get("atendeMei")).isEqualTo(true);
        }

        org.assertj.core.api.Assertions.assertThat(naoInformado.get("ativo")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(naoInformado.get("quantidadeRegras")).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(naoInformado.get("atendeSimples")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(naoInformado.get("atendeMei")).isEqualTo(false);

        long idNormal = ((Number) normal.get("idPerfilFiscal")).longValue();
        String detalheNormal = mvc.perform(get("/api/v1/fiscal/perfis/" + idNormal).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> crtsNormal = JsonPath.read(detalheNormal, "$.regras[*].crt");
        org.assertj.core.api.Assertions.assertThat(crtsNormal).containsExactlyInAnyOrder(1, 2, 4);
        List<String> csosnNormal = JsonPath.read(detalheNormal, "$.regras[*].csosn");
        org.assertj.core.api.Assertions.assertThat(csosnNormal).containsOnly("102");

        long idSt = ((Number) st.get("idPerfilFiscal")).longValue();
        String detalheSt = mvc.perform(get("/api/v1/fiscal/perfis/" + idSt).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> csosnSt = JsonPath.read(detalheSt, "$.regras[*].csosn");
        org.assertj.core.api.Assertions.assertThat(csosnSt).containsOnly("500");
    }

    /** O perfil semeado precisa ser aceito de verdade pela regra que a emissão consulta
     *  (VendaFiscalAssembler.buscarRegra: CONSUMIDOR_FINAL/VENDA_CONSUMIDOR/UF '*' ou exata) —
     *  não só existir na tela. Confere criando um produto com o perfil padrão e o CRT da empresa. */
    @Test
    void perfilPadraoTemRegraQueAEmissaoConsegueAchar() throws Exception {
        String token = assinarNovoTenant("seed-emissao");
        long idTenant = extrairIdTenant(token);

        Long idPerfilNormal = jdbc.sql(
                        "SELECT id_perfil_fiscal FROM cfg_perfil_fiscal WHERE id_tenant = ? AND nome = 'REVENDA TRIBUTADA NORMAL'")
                .param(idTenant).query(Long.class).single();

        for (int crt : List.of(1, 2, 4)) {
            Long idRegra = jdbc.sql("""
                            SELECT id_regra FROM cfg_perfil_fiscal_regra
                            WHERE id_tenant = ? AND id_perfil_fiscal = ? AND crt = ?
                              AND tipo_destinatario = 'CONSUMIDOR_FINAL' AND tipo_operacao = 'VENDA_CONSUMIDOR'
                              AND uf_destino = '*'
                            """)
                    .params(idTenant, idPerfilNormal, crt).query(Long.class).optional().orElse(null);
            org.assertj.core.api.Assertions.assertThat(idRegra)
                    .as("regra do perfil padrão para CRT %d", crt).isNotNull();
        }
    }

    // ---------------------------------------------------------------- papéis e isolamento

    @Test
    void operadorNaoPodeListarNemGravar() throws Exception {
        String token = assinarNovoTenant("operador");
        String operador = comoOperador(token);

        mvc.perform(get("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + operador))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + operador)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("TENTATIVA", true, regraSimples("*", "VENDA_CONSUMIDOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("iso-perfil-a");
        String tokenB = assinarNovoTenant("iso-perfil-b");

        String resp = mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON)
                        .content(corpo("PERFIL DO TENANT A", true, regraSimples("*", "VENDA_CONSUMIDOR"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idPerfilFiscal")).longValue();

        mvc.perform(get("/api/v1/fiscal/perfis/" + id).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
