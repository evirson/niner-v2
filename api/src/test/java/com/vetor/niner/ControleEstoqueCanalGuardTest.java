package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.canais.ControleEstoqueCanalGuard;
import com.vetor.niner.comum.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ⛔ "Se vende em marketplace, não pode existir estoque negativo" — decisão do dono do produto em
 * 2026-08-25 (docs/MODULOMARKETPLACE.md §8.1).
 *
 * <p>São <b>dois</b> guardas, e este teste cobre os dois separadamente, porque só o primeiro seria
 * trava decorativa: a loja conectaria o canal com o controle ligado e religaria o parâmetro no dia
 * seguinte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ControleEstoqueCanalGuardTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ControleEstoqueCanalGuard guarda;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Canal %s","email":"dono%s@lojacanal.com",
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

    /** Corpo válido do PUT de Parâmetros do Sistema, com a única flag que interessa parametrizada. */
    private static String corpoConfig(boolean permiteEstoqueNegativo) {
        return """
                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                 "cfgPermiteQtdDecimal":false,"cfgPermiteEstoqueNegativo":%s,
                 "cfgDiasValidadeOrcamento":15,"cfgExigeNumeroVendaDevolucao":false,
                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,
                 "cfgConsisteValorContasPagar":false,
                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":false}
                """.formatted(permiteEstoqueNegativo);
    }

    private void criarCanalConectado(long idTenant) {
        jdbc.sql("""
                        INSERT INTO canal (id_tenant, tipo, nome, status)
                        VALUES (?, 'MERCADO_LIVRE', 'Mercado Livre', 'CONECTADO')
                        """)
                .params(idTenant).update();
    }

    // ------------------------------------------------------------------ guarda 1: conectar canal

    @Test
    void naoConectaCanalComEstoqueNegativoPermitido() throws Exception {
        String token = assinarNovoTenant("g1-bloqueia");
        long idTenant = idTenantDo(token);
        // O signup cria cfg_geral com `permite estoque negativo` LIGADO (V055) — é o padrão do
        // produto, e é exatamente o estado que tem de barrar a conexão.

        TenantContext.comTenant(idTenant, () ->
                assertThatThrownBy(() -> guarda.exigirControleDeEstoqueLigado())
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("controlar o estoque"));
    }

    @Test
    void conectaCanalQuandoOControleDeEstoqueEstaLigado() throws Exception {
        String token = assinarNovoTenant("g1-libera");
        long idTenant = idTenantDo(token);

        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoConfig(false)))
                .andExpect(status().isOk());

        TenantContext.comTenant(idTenant, () ->
                assertThatNoException().isThrownBy(() -> guarda.exigirControleDeEstoqueLigado()));
    }

    // ------------------------------------------------- guarda 2: religar o parâmetro (o esquecido)

    @Test
    void naoReligaEstoqueNegativoComCanalConectado() throws Exception {
        String token = assinarNovoTenant("g2-bloqueia");
        long idTenant = idTenantDo(token);

        // Loja em ordem: controle ligado, canal conectado.
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoConfig(false)))
                .andExpect(status().isOk());
        TenantContext.comTenant(idTenant, () -> criarCanalConectado(idTenant));

        // Agora tenta destravar o overselling pela porta dos fundos.
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoConfig(true)))
                .andExpect(status().isConflict());

        // ⚠️ O que realmente importa: o banco NÃO mudou. Conferir só o 409 passaria se o guarda
        // estivesse depois do UPDATE — ver feedback_teste_de_guard_passa_pelo_motivo_errado.
        //
        // ⚠️ E o filtro aqui é `id_tenant = ?`, NÃO `plataforma.tenant_atual()`: esta consulta roda
        // fora de uma transação do Spring, então o `SET LOCAL app.id_tenant` nunca aconteceu e
        // `tenant_atual()` é NULL — `WHERE id_tenant = NULL` casaria zero linha e o `single()`
        // estouraria (foi o que aconteceu na primeira versão deste teste).
        Boolean permite = jdbc.sql("""
                        SELECT cfg_permite_estoque_negativo FROM cfg_geral WHERE id_tenant = ?
                        """)
                .param(idTenant)
                .query(Boolean.class).single();
        assertThat(permite).as("o parâmetro não pode ter sido gravado").isFalse();
    }

    @Test
    void desligarEstoqueNegativoContinuaPermitidoComCanalConectado() throws Exception {
        String token = assinarNovoTenant("g2-aperta");
        long idTenant = idTenantDo(token);
        TenantContext.comTenant(idTenant, () -> criarCanalConectado(idTenant));

        // O guarda só barra a transição para `true`. Apertar o controle é sempre permitido —
        // barrar isto prenderia o lojista numa configuração pior sem motivo.
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoConfig(false)))
                .andExpect(status().isOk());
    }

    @Test
    void canalDesconectadoNaoPrendeOParametro() throws Exception {
        String token = assinarNovoTenant("g2-desconectado");
        long idTenant = idTenantDo(token);
        TenantContext.comTenant(idTenant, () -> jdbc.sql("""
                        INSERT INTO canal (id_tenant, tipo, nome, status)
                        VALUES (?, 'MERCADO_LIVRE', 'ML abandonado', 'DESCONECTADO')
                        """).params(idTenant).update());

        // Integração abandonada não publica saldo em lugar nenhum; prender o lojista por causa
        // dela seria travá-lo numa configuração por um canal que ele já largou.
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoConfig(true)))
                .andExpect(status().isOk());
    }

    @Test
    void canalDeOutroTenantNaoPrendeOParametro() throws Exception {
        String tokenA = assinarNovoTenant("g2-iso-a");
        String tokenB = assinarNovoTenant("g2-iso-b");
        long idTenantA = idTenantDo(tokenA);

        TenantContext.comTenant(idTenantA, () -> criarCanalConectado(idTenantA));

        // P8: o canal do tenant A não pode travar o parâmetro do tenant B.
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON).content(corpoConfig(true)))
                .andExpect(status().isOk());
    }
}
