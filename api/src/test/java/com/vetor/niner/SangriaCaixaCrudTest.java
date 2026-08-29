package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sangria de Caixa (V094) — dinheiro que sai da gaveta e entra numa conta corrente.
 *
 * <h2>⭐ O que estes testes medem, e por que</h2>
 *
 * <p>Sangria é <b>transferência</b>, não saída (decisão do dono do produto, 2026-08-29:
 * <i>"esta sangria tem que ter um destino: sempre será depositada numa conta bancária"</i>). Um
 * teste que conferisse só o 201, ou só o débito no caixa, passaria com metade do mecanismo — e
 * metade é exatamente o defeito: dinheiro que sai sem destino desaparece do fluxo.
 *
 * <p>Por isso o teste principal confere <b>os três lados no banco</b>: o mestre, o débito em
 * {@code caixa_detalhe} e o crédito em {@code conta_corrente_movimento}, ligados pelo
 * {@code id_sangria}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SangriaCaixaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, long idEmpresa) {
    }

    private TenantNovo assinar(String sufixo) throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Sangria %s","email":"dono%s@lojasangria.com",
                         "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                        """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String empresas = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(empresas, "$[0].idEmpresa")).longValue();
        return new TenantNovo(token, idEmpresa);
    }

    private static long extrairIdTenant(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    /** ⚠️ `SET app.id_tenant` é obrigatório: com `FORCE ROW LEVEL SECURITY` nem o dono enxerga. */
    private Connection abrirConexao(long idTenant) throws Exception {
        Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.id_tenant', ?, false)")) {
            ps.setString(1, String.valueOf(idTenant));
            ps.execute();
        }
        return c;
    }

    private void abrirCaixaComFundo(String token, String fundo) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":%s}".formatted(idCarteira, fundo)))
                .andExpect(status().isOk());
    }

    private void criarConta(String token, long idEmpresa, String codigo) throws Exception {
        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"%s","idEmpresa":%d,"idBanco":"341","idAgencia":"1234",
                                 "descricao":"Conta do deposito","ativo":true}
                                """.formatted(codigo, idEmpresa)))
                .andExpect(status().isCreated());
    }

    /** Um plano de contas qualquer do tenant — a coluna é NOT NULL nos dois lados. */
    private String primeiroPlanoDeContas(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/planos-contas?pagina=1&limite=1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.itens[0].idPlanoContas");
    }

    @Test
    void sangriaEscreveOsTresLadosNaMesmaTransacao() throws Exception {
        TenantNovo t = assinar("tres-lados");
        long idTenant = extrairIdTenant(t.token());
        abrirCaixaComFundo(t.token(), "500.00");
        criarConta(t.token(), t.idEmpresa(), "BB001");
        String plano = primeiroPlanoDeContas(t.token());

        String resp = mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"BB001","valor":300.00,"idPlanoContas":"%s",
                                 "observacao":"Deposito do meio-dia"}
                                """.formatted(plano)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valor").value(300.00))
                .andExpect(jsonPath("$.idContaCorrente").value("BB001"))
                .andReturn().getResponse().getContentAsString();
        long idSangria = ((Number) JsonPath.read(resp, "$.idSangria")).longValue();

        try (Connection c = abrirConexao(idTenant)) {
            // (1) o débito no caixa — o dinheiro saiu da gaveta
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT valor, credito_debito::text, tipo_operacao::text
                      FROM caixa_detalhe WHERE id_sangria = ?
                    """)) {
                ps.setLong(1, idSangria);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("o débito em caixa_detalhe tem de existir").isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("300.00");
                    assertThat(rs.getString(2)).isEqualTo("D");
                    assertThat(rs.getString(3)).isEqualTo("DEBITO_CAIXA");
                }
            }
            // (2) o crédito no banco — e é este lado que a rotina existe para criar. Sem ele o
            // dinheiro sairia do fluxo sem chegar a lugar nenhum.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT valor, credito_debito::text, id_conta_corrente
                      FROM conta_corrente_movimento WHERE id_sangria = ?
                    """)) {
                ps.setLong(1, idSangria);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("o crédito na conta corrente tem de existir").isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("300.00");
                    assertThat(rs.getString(2)).isEqualTo("C");
                    assertThat(rs.getString(3)).isEqualTo("BB001");
                }
            }
        }

        // (3) e o disponível caiu exatamente o que saiu: 500 − 300.
        mvc.perform(get("/api/v1/caixa/sangrias/contexto").header("Authorization", "Bearer " + t.token()))
                .andExpect(jsonPath("$.disponivel").value(200.00));
    }

    /**
     * Não se sangra mais do que há na gaveta.
     *
     * <p>⚠️ E o teste confere o <b>banco</b> depois do 409, não só o status: uma recusa que
     * acontecesse <i>depois</i> de gravar deixaria a sangria lá e o teste passaria mesmo assim —
     * foi exatamente esse o erro que a devolução de venda cancelada ensinou em 2026-08-27.
     */
    @Test
    void naoSangraMaisDoQueTemNaGaveta() throws Exception {
        TenantNovo t = assinar("acima-do-saldo");
        long idTenant = extrairIdTenant(t.token());
        abrirCaixaComFundo(t.token(), "100.00");
        criarConta(t.token(), t.idEmpresa(), "BB002");
        String plano = primeiroPlanoDeContas(t.token());

        mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"BB002","valor":250.00,"idPlanoContas":"%s"}
                                """.formatted(plano)))
                .andExpect(status().isConflict());

        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM caixa_sangria")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("nenhuma sangria pode ter nascido").isZero();
            }
        }
    }

    @Test
    void semCaixaAbertoNaoHaSangria() throws Exception {
        TenantNovo t = assinar("sem-caixa");
        criarConta(t.token(), t.idEmpresa(), "BB003");
        String plano = primeiroPlanoDeContas(t.token());

        mvc.perform(get("/api/v1/caixa/sangrias/contexto").header("Authorization", "Bearer " + t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caixaAberto").value(false));

        mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"BB003","valor":10.00,"idPlanoContas":"%s"}
                                """.formatted(plano)))
                .andExpect(status().isConflict());
    }

    /**
     * O depósito da sangria <b>não</b> se edita nem se apaga pela tela de extrato.
     *
     * <p>É a lição de 2026-08-15, aplicada no mesmo dia em que a rotina nasceu: tabela de CRUD
     * manual que passa a receber lançamento automático precisa recusar a edição já — senão o
     * extrato descasa do caixa <b>em silêncio</b>, e o banco não impede porque é uma linha comum.
     */
    @Test
    void extratoRecusaEditarOuExcluirODepositoDaSangria() throws Exception {
        TenantNovo t = assinar("extrato-recusa");
        long idTenant = extrairIdTenant(t.token());
        abrirCaixaComFundo(t.token(), "400.00");
        criarConta(t.token(), t.idEmpresa(), "BB004");
        String plano = primeiroPlanoDeContas(t.token());

        mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"BB004","valor":150.00,"idPlanoContas":"%s"}
                                """.formatted(plano)))
                .andExpect(status().isCreated());

        long localizador;
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT localizador FROM conta_corrente_movimento WHERE id_sangria IS NOT NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                localizador = rs.getLong(1);
            }
        }

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/contas-corrente-movimento/" + localizador)
                        .header("Authorization", "Bearer " + t.token()))
                .andExpect(status().isConflict());

        // E continua lá — o 409 não pode ter apagado nada pelo caminho.
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT valor FROM conta_corrente_movimento WHERE localizador = ?")) {
            ps.setLong(1, localizador);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("150.00"));
            }
        }
    }
}
