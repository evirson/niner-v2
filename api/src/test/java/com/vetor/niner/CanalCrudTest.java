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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Canais de Venda (docs/MODULOMARKETPLACE.md) — CRUD + painel de saúde (R7). ADMIN-only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CanalCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Canais %s","email":"dono%s@lojacanais.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long idTenantDo(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    /** O canal só pode ser criado com o controle de estoque ligado — ver §8.1 do estudo. */
    private void ligarControleDeEstoque(String token) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                        {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                         "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                         "cfgPermiteQtdDecimal":false,"cfgPermiteEstoqueNegativo":false,
                         "cfgDiasValidadeOrcamento":15,"cfgExigeNumeroVendaDevolucao":false,
                         "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,
                         "cfgConsisteValorContasPagar":false,
                         "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":false}
                        """))
                .andExpect(status().isOk());
    }

    private long criarCanal(String token, String nome, String percPreco) throws Exception {
        String resp = mvc.perform(post("/api/v1/canais/MERCADO_LIVRE")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"%s\",\"percPreco\":%s}".formatted(nome, percPreco)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCanal")).longValue();
    }

    @Test
    void criaCanalDesconectadoComORotuloDoTipo() throws Exception {
        String token = assinarNovoTenant("cria");
        ligarControleDeEstoque(token);

        mvc.perform(post("/api/v1/canais/MERCADO_LIVRE").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"minha loja no ml\",\"percPreco\":18.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("MERCADO_LIVRE"))
                .andExpect(jsonPath("$.tipoRotulo").value("Mercado Livre"))
                // Texto livre sempre em maiúsculas — convenção do produto.
                .andExpect(jsonPath("$.nome").value("MINHA LOJA NO ML"))
                // Nasce desconectado: conectar é OAuth, outro passo.
                .andExpect(jsonPath("$.status").value("DESCONECTADO"))
                .andExpect(jsonPath("$.percPreco").value(18.00))
                .andExpect(jsonPath("$.anunciosVinculados").value(0))
                // ⛔ Credencial NUNCA volta pela API.
                .andExpect(jsonPath("$.contaExterna").doesNotExist());
    }

    /** ⛔ A decisão do dono do produto: marketplace exige controle de estoque. */
    @Test
    void naoCriaCanalComEstoqueNegativoPermitido() throws Exception {
        String token = assinarNovoTenant("sem-controle");
        // Não liga o controle: o signup deixa `permite estoque negativo` LIGADO (V055).

        mvc.perform(post("/api/v1/canais/MERCADO_LIVRE").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML\",\"percPreco\":0}"))
                .andExpect(status().isConflict());
    }

    /** ⭐ Percentual negativo é legítimo: "menor que a loja física". */
    @Test
    void aceitaPercentualNegativo() throws Exception {
        String token = assinarNovoTenant("negativo");
        ligarControleDeEstoque(token);

        mvc.perform(post("/api/v1/canais/MERCADO_LIVRE").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML PROMO\",\"percPreco\":-12.50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.percPreco").value(-12.50));
    }

    @Test
    void recusaPercentualAbsurdo() throws Exception {
        String token = assinarNovoTenant("absurdo");
        ligarControleDeEstoque(token);

        // 1500 é o dedo escorregado de quem queria 15,00 — o CHECK da V064 e o DTO barram os dois.
        mvc.perform(post("/api/v1/canais/MERCADO_LIVRE").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"ML\",\"percPreco\":1500}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void desconectarApagaACredencialDeVerdade() throws Exception {
        String token = assinarNovoTenant("desconecta");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML", "0");

        // Simula um canal conectado com credencial gravada.
        jdbc.sql("""
                        UPDATE canal SET status = 'CONECTADO',
                               credenciais = '{"contaExterna":"SELLER1","accessToken":"segredo"}'::jsonb
                         WHERE id_tenant = ? AND id_canal = ?
                        """).params(idTenant, idCanal).update();

        mvc.perform(post("/api/v1/canais/" + idCanal + "/desconectar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DESCONECTADO"));

        // ⚠️ O que importa: a credencial saiu do banco. Guardar o token de quem pediu para
        // desconectar é manter a chave da casa de alguém que pediu a chave de volta.
        String credenciais = jdbc.sql("SELECT credenciais::text FROM canal WHERE id_canal = ?")
                .param(idCanal).query(String.class).optional().orElse(null);
        org.assertj.core.api.Assertions.assertThat(credenciais).isNull();
    }

    @Test
    void naoExcluiCanalComAnuncioVinculado() throws Exception {
        String token = assinarNovoTenant("com-anuncio");
        long idTenant = idTenantDo(token);
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML", "0");

        // ⚠️ A massa é criada aqui, não procurada no banco. A primeira versão deste teste usava
        // `assumeTrue` sobre uma variação pré-existente e ficava PULADO num tenant novo — ou seja,
        // o guarda que ele deveria provar nunca era exercitado. Teste pulado não prova nada.
        Long idProduto = jdbc.sql("""
                        INSERT INTO produto (id_tenant, descricao) VALUES (?, 'PRODUTO DO CANAL')
                        RETURNING id_produto
                        """).param(idTenant).query(Long.class).single();
        // O SKU sai sempre de gerar_ean13_interno() — nunca texto livre (ver CLAUDE.md).
        Long idVariacao = jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, sku)
                        VALUES (?, ?, gerar_ean13_interno()) RETURNING id_variacao
                        """).params(idTenant, idProduto).query(Long.class).single();

        jdbc.sql("""
                        INSERT INTO anuncio (id_tenant, id_canal, id_variacao, id_externo)
                        VALUES (?, ?, ?, 'MLB1')
                        """).params(idTenant, idCanal, idVariacao).update();

        mvc.perform(delete("/api/v1/canais/" + idCanal).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void excluiCanalSemVinculo() throws Exception {
        String token = assinarNovoTenant("exclui");
        ligarControleDeEstoque(token);
        long idCanal = criarCanal(token, "ML", "0");

        mvc.perform(delete("/api/v1/canais/" + idCanal).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/canais/" + idCanal).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ painel de saúde (R7)

    /**
     * ⚠️ Os três números são separados de propósito: pendente é normal, erro está tentando
     * sozinho, dead-letter parou e espera gente. Somá-los produziria um alarme num dia saudável.
     */
    @Test
    void saudeSeparaPendenteErroEDeadLetter() throws Exception {
        String token = assinarNovoTenant("saude");
        long idTenant = idTenantDo(token);

        jdbc.sql("""
                        INSERT INTO outbox_evento (id_tenant, tipo, agregado_id, payload, status)
                        VALUES (?, 'X', '1', '{}'::jsonb, 'PENDENTE'),
                               (?, 'X', '2', '{}'::jsonb, 'ERRO'),
                               (?, 'X', '3', '{}'::jsonb, 'DEAD_LETTER'),
                               (?, 'X', '4', '{}'::jsonb, 'PROCESSADO')
                        """).params(idTenant, idTenant, idTenant, idTenant).update();

        mvc.perform(get("/api/v1/canais/saude").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendentes").value(1))
                .andExpect(jsonPath("$.comErro").value(1))
                .andExpect(jsonPath("$.deadLetter").value(1))
                // Só ERRO e DEAD_LETTER aparecem na lista — processado não é falha.
                .andExpect(jsonPath("$.falhas.length()").value(2))
                // Dead-letter primeiro: é o que precisa de gente.
                .andExpect(jsonPath("$.falhas[0].status").value("DEAD_LETTER"));
    }

    /**
     * ⚠️ Reprocessar <b>zera as tentativas</b>. Sem isso, um evento que chegou ao dead-letter com
     * 12 falhas voltaria e morreria na tentativa seguinte — o botão pareceria não funcionar.
     */
    @Test
    void reprocessarDevolveOEventoAFilaEZeraAsTentativas() throws Exception {
        String token = assinarNovoTenant("reprocessa");
        long idTenant = idTenantDo(token);

        Long idEvento = jdbc.sql("""
                        INSERT INTO outbox_evento (id_tenant, tipo, agregado_id, payload, status,
                                                   tentativas, erro)
                        VALUES (?, 'X', '1', '{}'::jsonb, 'DEAD_LETTER', 12, 'canal fora')
                        RETURNING id
                        """).param(idTenant).query(Long.class).single();

        mvc.perform(post("/api/v1/canais/eventos/" + idEvento + "/reprocessar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        var linha = jdbc.sql("""
                        SELECT status::text AS status, tentativas, erro FROM outbox_evento WHERE id = ?
                        """)
                .param(idEvento)
                .query((rs, n) -> List.of(rs.getString("status"), String.valueOf(rs.getInt("tentativas")),
                        String.valueOf(rs.getString("erro"))))
                .single();

        org.assertj.core.api.Assertions.assertThat(linha)
                .containsExactly("PENDENTE", "0", "null");
    }

    @Test
    void naoReprocessaEventoJaProcessado() throws Exception {
        String token = assinarNovoTenant("ja-ok");
        long idTenant = idTenantDo(token);
        Long idEvento = jdbc.sql("""
                        INSERT INTO outbox_evento (id_tenant, tipo, agregado_id, payload, status)
                        VALUES (?, 'X', '1', '{}'::jsonb, 'PROCESSADO') RETURNING id
                        """).param(idTenant).query(Long.class).single();

        mvc.perform(post("/api/v1/canais/eventos/" + idEvento + "/reprocessar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ papel e isolamento

    /** Mesmo helper de ConfiguracaoGeralTest — reemite o token do próprio admin como OPERADOR. */
    private String comoOperador(String tokenAdmin) {
        String payload = new String(Base64.getUrlDecoder().decode(tokenAdmin.split("\\.")[1]));
        long idUsuario = Long.parseLong(JsonPath.read(payload, "$.sub").toString());
        long idTenant = ((Number) JsonPath.read(payload, "$.tid")).longValue();
        long idEmpresa = ((Number) JsonPath.read(payload, "$.eid")).longValue();
        String email = JsonPath.read(payload, "$.email");
        return tokens.emitir(idUsuario, idTenant, idEmpresa, email, List.of("OPERADOR"));
    }

    @Test
    void operadorNaoAcessa() throws Exception {
        String operador = comoOperador(assinarNovoTenant("operador"));

        mvc.perform(get("/api/v1/canais").header("Authorization", "Bearer " + operador))
                .andExpect(status().isForbidden());
    }

    @Test
    void canalDeOutroTenantNaoAparece() throws Exception {
        String tokenA = assinarNovoTenant("iso-a");
        String tokenB = assinarNovoTenant("iso-b");
        ligarControleDeEstoque(tokenA);
        long idCanalA = criarCanal(tokenA, "ML DO A", "0");

        mvc.perform(get("/api/v1/canais").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/canais/" + idCanalA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
