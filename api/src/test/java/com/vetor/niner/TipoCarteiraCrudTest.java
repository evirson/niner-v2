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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD de tipo de carteira — mesmo padrão de {@link PlanoContasCrudTest}. Absorveu o cadastro
 * de {@code moeda} em 2026-07-28 (motivo completo no topo de {@code
 * V025__financeiro_caixa_crediario.sql}): {@code percDesconto}/{@code percAcrescimo} vieram de
 * lá, e a chave única agora é {@code (nome_carteira, categoria_carteira)} — a mesma bandeira
 * pode existir uma vez por categoria (ex.: "HIPER" em débito e "HIPER" em crédito, prazo/taxa
 * independentes), mas não duas vezes na mesma categoria. Sem coluna {@code ativo}: exclusão
 * sem fallback de inativar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TipoCarteiraCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Carteira %s","email":"dono%s@lojacarteira.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void tenantNovoJaNasceComSeteTiposDeCarteiraSemeados() throws Exception {
        String token = assinarNovoTenant("seed");

        mvc.perform(get("/api/v1/tipos-carteira").param("limite", "20").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(7));
    }

    @Test
    void criaTipoCarteiraComDescontoEAcrescimo() throws Exception {
        String token = assinarNovoTenant("com-desconto");

        String carteira = """
                {"nomeCarteira":"crediario 30/60/90","categoriaCarteira":"CREDIARIO","prazoPagamento":30,
                 "pcMinima":1,"pcMaxima":3,"taxaAdministradora":2.5,"percDesconto":0,"percAcrescimo":0,
                 "permiteReceberCrediario":false}
                """;

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(carteira))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nomeCarteira").value("CREDIARIO 30/60/90"))
                .andExpect(jsonPath("$.categoriaCarteira").value("CREDIARIO"))
                .andExpect(jsonPath("$.pcMinima").value(1))
                .andExpect(jsonPath("$.pcMaxima").value(3))
                .andExpect(jsonPath("$.permiteReceberCrediario").value(false))
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void carteiraPodeSerMarcadaPermiteReceberCrediario() throws Exception {
        String token = assinarNovoTenant("permite-receber");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"DINHEIRO CREDIARIO","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permiteReceberCrediario").value(true));
    }

    /**
     * O motivo inteiro da junção com moeda (2026-07-28): a mesma bandeira em categorias
     * diferentes tem prazo/parcelas/taxa diferentes — precisa poder existir como duas linhas.
     */
    @Test
    void mesmoNomeEmCategoriasDiferentesEhPermitido() throws Exception {
        String token = assinarNovoTenant("mesma-bandeira");
        criarCarteira(token, "HIPER", "CARTAO_DEBITO", 1, 1, 1, "1.5");
        criarCarteira(token, "HIPER", "CARTAO_CREDITO", 30, 1, 6, "2.8");

        mvc.perform(get("/api/v1/tipos-carteira").param("busca", "HIPER").param("limite", "20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(2));
    }

    @Test
    void mesmoNomeNaMesmaCategoriaEhRejeitado() throws Exception {
        String token = assinarNovoTenant("nome-duplicado");
        criarCarteira(token, "CARTEIRA UNICA", "CREDIARIO", 30, 1, 1, "0");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"carteira unica","categoriaCarteira":"CREDIARIO","prazoPagamento":15,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void parcelaMaximaMenorQueMinimaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("parcela-invalida");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA PARCELA INVALIDA","categoriaCarteira":"CREDIARIO","prazoPagamento":30,
                                 "pcMinima":5,"pcMaxima":2,"taxaAdministradora":0,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taxaAdministradoraNegativaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("taxa-negativa");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA TAXA INVALIDA","categoriaCarteira":"CREDIARIO","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":-1,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** Opcional (2026-07-23) — nem todo tipo de carteira cobra taxa administradora. */
    @Test
    void taxaAdministradoraPodeFicarEmBrancoEPrazoPodeSerZero() throws Exception {
        String token = assinarNovoTenant("taxa-em-branco");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA SEM TAXA","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxaAdministradora").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.prazoPagamento").value(0));
    }

    /** Categoria (2026-07-23) é obrigatória — histórico do cliente depende dela pra isolar crediário. */
    @Test
    void categoriaCarteiraAusenteEhRejeitada() throws Exception {
        String token = assinarNovoTenant("categoria-ausente");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA SEM CATEGORIA","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void percentualDescontoNegativoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("percentual-negativo");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA PERCENTUAL INVALIDO","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":-1,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** Sem limite superior (2026-07-23, herdado de moeda) — só não pode ser negativo. */
    @Test
    void percentualAcimaDeCemEhAceito() throws Exception {
        String token = assinarNovoTenant("percentual-alto");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA ACRESCIMO ALTO","categoriaCarteira":"CARTAO_CREDITO","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":6,"percAcrescimo":150,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.percAcrescimo").value(150));
    }

    /**
     * A checagem é por valor positivo, não por presença (2026-07-23, herdado de moeda) — 0/0 é
     * o estado neutro normal (toda carteira semeada no signup nasce assim) e não pode rejeitar.
     */
    @Test
    void descontoEAcrescimoZeradosJuntosSaoAceitos() throws Exception {
        String token = assinarNovoTenant("zerados-juntos");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA NEUTRA","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":0,"percAcrescimo":0,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void descontoEAcrescimoPositivosJuntosSaoRejeitados() throws Exception {
        String token = assinarNovoTenant("ambos-positivos");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA INVALIDA","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":5,"percAcrescimo":3,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarMudaPercentuais() throws Exception {
        String token = assinarNovoTenant("atualiza-percentual");
        long id = criarCarteira(token, "CARTEIRA ATUALIZA PCT", "AVISTA", 0, 1, 1, "0");

        mvc.perform(put("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA ATUALIZA PCT","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":5,"permiteReceberCrediario":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percDesconto").value(5));
    }

    @Test
    void excluirCarteiraSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        long id = criarCarteira(token, "CARTEIRA SEM VINCULO", "CREDIARIO", 30, 1, 1, "0");

        mvc.perform(delete("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirCarteiraComContaAReceberVinculadaRespondeConflito() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo-cr");
        long idTenant = extrairIdTenant(token);
        long id = criarCarteira(token, "CARTEIRA COM VINCULO CR", "CREDIARIO", 30, 1, 1, "0");

        criarContaReceberComCarteira(idTenant, id);

        mvc.perform(delete("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeCarteira").value("CARTEIRA COM VINCULO CR"));
    }

    /** Vínculo novo desde que caixa_detalhe passou a referenciar tipo_carteira (2026-07-28). */
    @Test
    void excluirCarteiraComLancamentoDeCaixaVinculadoRespondeConflito() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo-caixa");
        long idTenant = extrairIdTenant(token);
        long id = criarCarteira(token, "CARTEIRA COM VINCULO CAIXA", "AVISTA", 0, 1, 1, "0");

        criarLancamentoDeCaixaComCarteira(idTenant, id);

        mvc.perform(delete("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");
        criarCarteira(token, "ORDCARTEIRA BETA", "CREDIARIO", 30, 1, 1, "0");
        criarCarteira(token, "ORDCARTEIRA ALFA", "CREDIARIO", 30, 1, 1, "0");
        criarCarteira(token, "ORDCARTEIRA GAMA", "CREDIARIO", 30, 1, 1, "0");

        mvc.perform(get("/api/v1/tipos-carteira").param("busca", "ORDCARTEIRA")
                        .param("ordenarPor", "nomeCarteira").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nomeCarteira").value("ORDCARTEIRA GAMA"));
    }

    private long criarCarteira(String token, String nome, String categoria, int prazoPagamento, int pcMinima,
                                int pcMaxima, String taxaAdministradora) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":%d,"pcMinima":%d,"pcMaxima":%d,
                                 "taxaAdministradora":%s,"permiteReceberCrediario":false}
                                """.formatted(nome, categoria, prazoPagamento, pcMinima, pcMaxima, taxaAdministradora)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    /**
     * Insere uma venda + parcela mínimas referenciando a carteira — vínculo que bloqueia a
     * exclusão. Não existe API de escrita pra venda/contas_receber ainda (mesmo caso de
     * {@code ClienteHistoricoCrudTest}), então vai direto via JDBC.
     */
    private void criarContaReceberComCarteira(long idTenant, long idCarteira) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            long idVenda;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO venda (id_tenant, id_empresa) VALUES (" + idTenant + ", " + idEmpresa
                            + ") RETURNING id_venda")) {
                rs.next();
                idVenda = rs.getLong(1);
            }
            st.executeUpdate("""
                    INSERT INTO contas_receber
                        (id_tenant, id_venda, id_carteira, numero_parcela, data_vencimento, valor_receber)
                    VALUES (%d, %d, %d, 1, now(), 100.00)
                    """.formatted(idTenant, idVenda, idCarteira));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Insere um caixa (mestre + detalhe) referenciando a carteira — mesmo padrão de {@link PlanoContasCrudTest}. */
    private void criarLancamentoDeCaixaComCarteira(long idTenant, long idCarteira) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            long idUsuario;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT id_usuario FROM usuario LIMIT 1")) {
                rs.next();
                idUsuario = rs.getLong(1);
            }
            long idCaixa;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO caixa_mestre (id_tenant, id_empresa, id_usuario, id_carteira) VALUES ("
                            + idTenant + ", " + idEmpresa + ", " + idUsuario + ", " + idCarteira + ") RETURNING id_caixa")) {
                rs.next();
                idCaixa = rs.getLong(1);
            }
            st.executeUpdate("""
                    INSERT INTO caixa_detalhe (id_tenant, id_caixa, id_carteira, valor, tipo_operacao, credito_debito)
                    VALUES (%d, %d, %d, 10.00, 'DEBITO_CAIXA', 'D')
                    """.formatted(idTenant, idCaixa, idCarteira));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
