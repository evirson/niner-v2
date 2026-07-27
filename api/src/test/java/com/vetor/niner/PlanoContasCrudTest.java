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
 * CRUD de plano de contas (docs/telas/plano-contas.md) — mesmo padrão de
 * {@link ClienteCrudTest}: cada teste assina um tenant novo e usa o token real emitido.
 * Particularidades cobertas: PK de negócio {@code text} (código contábil), código imutável
 * na atualização, e exclusão SEM fallback de inativar (409 quando há vínculo — a tabela não
 * tem coluna {@code ativo}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlanoContasCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Plano %s","email":"dono%s@lojaplano.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void criaPlanoDeContasComDadosCompletos() throws Exception {
        String token = assinarNovoTenant("completo");

        String plano = """
                {"codigo":"3.1.001","descricao":"receita de vendas","tipoMovimento":"CRÉDITO",
                 "incluiDre":true,"incluiFluxoCaixa":true}
                """;

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(plano))
                .andExpect(status().isCreated())
                // Descrição normalizada para MAIÚSCULAS no servidor, convenção do projeto.
                .andExpect(jsonPath("$.idPlanoContas").value("3.1.001"))
                .andExpect(jsonPath("$.descricao").value("RECEITA DE VENDAS"))
                .andExpect(jsonPath("$.tipoMovimento").value("CRÉDITO"))
                .andExpect(jsonPath("$.incluiDre").value(true))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists());

        mvc.perform(get("/api/v1/planos-contas").param("busca", "3.1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].idPlanoContas").value("3.1.001"));
    }

    @Test
    void codigoDuplicadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("duplicado");
        criarPlanoSimples(token, "1.1.001", "CAIXA GERAL");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"1.1.001","descricao":"Outra descricao","tipoMovimento":"DÉBITO",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void tipoMovimentoInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("tipo-invalido");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.9.999","descricao":"Tipo errado","tipoMovimento":"CREDITO",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarMudaDescricaoMasNaoOCodigo() throws Exception {
        String token = assinarNovoTenant("atualiza");
        criarPlanoSimples(token, "2.1.001", "DESPESA ORIGINAL");

        // O corpo tenta trocar o código — o do path prevalece e o registro continua o mesmo.
        mvc.perform(put("/api/v1/planos-contas/2.1.001").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.9.999","descricao":"despesa corrigida","tipoMovimento":"DÉBITO",
                                 "incluiDre":true,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPlanoContas").value("2.1.001"))
                .andExpect(jsonPath("$.descricao").value("DESPESA CORRIGIDA"))
                .andExpect(jsonPath("$.incluiDre").value(true));

        mvc.perform(get("/api/v1/planos-contas/9.9.999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirPlanoSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        criarPlanoSimples(token, "4.1.001", "SEM VINCULO");

        mvc.perform(delete("/api/v1/planos-contas/4.1.001").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/planos-contas/4.1.001").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirPlanoComFornecedorVinculadoRespondeConflito() throws Exception {
        // Sem coluna `ativo` não há fallback de inativar: com vínculo, 409 e nada muda.
        String token = assinarNovoTenant("exclusao-vinculo");
        criarPlanoSimples(token, "5.1.001", "COM FORNECEDOR");
        long idTenant = extrairIdTenant(token);

        criarFornecedorComPlano(idTenant, "5.1.001");

        mvc.perform(delete("/api/v1/planos-contas/5.1.001").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/planos-contas/5.1.001").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descricao").value("COM FORNECEDOR"));
    }

    @Test
    void excluirPlanoComCaixaVinculadoRespondeConflito() throws Exception {
        // Terceira FK de cfg_plano_contas (V025/V026): caixa_detalhe. A pré-checagem de
        // fornecedor/contas_pagar não cobre esta — precisa responder 409, não 500 genérico.
        String token = assinarNovoTenant("exclusao-caixa");
        criarPlanoSimples(token, "8.1.001", "COM CAIXA");
        long idTenant = extrairIdTenant(token);

        criarCaixaComPlano(idTenant, "8.1.001");

        mvc.perform(delete("/api/v1/planos-contas/8.1.001").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/planos-contas/8.1.001").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descricao").value("COM CAIXA"));
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");
        criarPlanoSimples(token, "6.1.001", "ORDPLANO BETA");
        criarPlanoSimples(token, "6.1.002", "ORDPLANO ALFA");
        criarPlanoSimples(token, "6.1.003", "ORDPLANO GAMA");

        mvc.perform(get("/api/v1/planos-contas").param("busca", "ORDPLANO")
                        .param("ordenarPor", "descricao").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].descricao").value("ORDPLANO GAMA"));
    }

    @Test
    void buscaEncontraPorDescricao() throws Exception {
        String token = assinarNovoTenant("busca");
        criarPlanoSimples(token, "7.1.001", "ALUGUEL DA LOJA");

        mvc.perform(get("/api/v1/planos-contas").param("busca", "ALUGUEL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].idPlanoContas").value("7.1.001"));
    }

    private void criarPlanoSimples(String token, String codigo, String descricao) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"%s","tipoMovimento":"NEUTRO",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """.formatted(codigo, descricao)))
                .andExpect(status().isCreated());
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    /** Insere um fornecedor mínimo referenciando o plano — vínculo que bloqueia a exclusão. */
    private void criarFornecedorComPlano(long idTenant, String idPlanoContas) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            st.executeUpdate(
                    "INSERT INTO fornecedor (id_tenant, id_plano_contas, razao_social) VALUES ("
                            + idTenant + ", '" + idPlanoContas + "', 'FORNECEDOR TESTE')");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Insere um caixa (mestre + detalhe) referenciando o plano — vínculo que bloqueia a
     * exclusão, mas que a pré-checagem de {@code excluir()} não enumerava até este teste
     * existir (achado do pedido "verifique todas as telas"). Reaproveita empresa/usuário
     * admin e a moeda semeados pelo signup ({@code SignupService}).
     */
    private void criarCaixaComPlano(long idTenant, String idPlanoContas) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            long idUsuario;
            long idMoeda;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT id_usuario FROM usuario LIMIT 1")) {
                rs.next();
                idUsuario = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT id_moeda FROM moeda LIMIT 1")) {
                rs.next();
                idMoeda = rs.getLong(1);
            }
            long idCaixa;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO caixa_mestre (id_tenant, id_empresa, id_usuario) VALUES ("
                            + idTenant + ", " + idEmpresa + ", " + idUsuario + ") RETURNING id_caixa")) {
                rs.next();
                idCaixa = rs.getLong(1);
            }
            st.executeUpdate("""
                    INSERT INTO caixa_detalhe
                        (id_tenant, id_caixa, id_moeda, id_plano_contas, valor, tipo_operacao, credito_debito)
                    VALUES (%d, %d, %d, '%s', 10.00, 'DEBITO_CAIXA', 'D')
                    """.formatted(idTenant, idCaixa, idMoeda, idPlanoContas));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
