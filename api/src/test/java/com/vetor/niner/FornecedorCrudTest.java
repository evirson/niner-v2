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
 * CRUD de fornecedor (docs/telas/fornecedor.md) — mesmo padrão de {@link ClienteCrudTest}.
 * Todo fornecedor exige um plano de contas já criado ({@code id_plano_contas} NOT NULL sem
 * seed), então cada teste cria um antes via API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FornecedorCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Fornecedor %s","email":"dono%s@lojafornecedor.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private void criarPlano(String token, String codigo) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"DESPESA FORNECEDORES","tipoMovimento":"DÉBITO",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """.formatted(codigo)))
                .andExpect(status().isCreated());
    }

    @Test
    void criaFornecedorComDadosCompletos() throws Exception {
        String token = assinarNovoTenant("completo");
        criarPlano(token, "2.1.001");

        String fornecedor = """
                {"razaoSocial":"distribuidora abc ltda","idPlanoContas":"2.1.001",
                 "nomeFantasia":"abc distribui","cnpj":"11.222.333/0001-81",
                 "inscricaoEstadual":"110.042.490.114","email":"contato@abc.com.br",
                 "telefone":"1130554400","cep":"01310-000","endereco":"avenida paulista",
                 "numero":"1578","bairro":"bela vista","cidade":"sao paulo","estado":"SP"}
                """;

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(fornecedor))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.razaoSocial").value("DISTRIBUIDORA ABC LTDA"))
                .andExpect(jsonPath("$.nomeFantasia").value("ABC DISTRIBUI"))
                .andExpect(jsonPath("$.cnpj").value("11222333000181"))
                .andExpect(jsonPath("$.descricaoPlanoContas").value("DESPESA FORNECEDORES"))
                // Telefone fixo (10 dígitos) é aceito — regra mais frouxa que a do cliente.
                .andExpect(jsonPath("$.telefone").value("1130554400"))
                .andExpect(jsonPath("$.ativo").value(true));

        mvc.perform(get("/api/v1/fornecedores").param("razaoSocial", "abc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].razaoSocial").value("DISTRIBUIDORA ABC LTDA"));
    }

    @Test
    void fornecedorSemPlanoDeContasEhRejeitado() throws Exception {
        String token = assinarNovoTenant("sem-plano");

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Sem Plano Ltda\",\"idPlanoContas\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fornecedorComPlanoInexistenteEhRejeitadoComErroAmigavel() throws Exception {
        String token = assinarNovoTenant("plano-inexistente");

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Plano Fantasma Ltda\",\"idPlanoContas\":\"9.9.999\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cnpjAlfanumericoValidoEhAceito() throws Exception {
        // Convenção do CNPJ alfanumérico (CLAUDE.md) — exemplo oficial da Receita Federal.
        String token = assinarNovoTenant("cnpj-alfa");
        criarPlano(token, "2.2.001");

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"razaoSocial":"Fornecedor Alfanumerico","idPlanoContas":"2.2.001",
                                 "cnpj":"12.ABC.345/01DE-35"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cnpj").value("12ABC34501DE35"));
    }

    @Test
    void cnpjComDigitoVerificadorInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cnpj-invalido");
        criarPlano(token, "2.3.001");

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"razaoSocial":"CNPJ Errado","idPlanoContas":"2.3.001",
                                 "cnpj":"11.222.333/0001-99"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cpfNoCampoCnpjEhRejeitado() throws Exception {
        // Fornecedor é pessoa jurídica: CPF válido (11 dígitos) não é aceito como CNPJ.
        String token = assinarNovoTenant("cpf-no-cnpj");
        criarPlano(token, "2.4.001");

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"razaoSocial":"CPF Indevido","idPlanoContas":"2.4.001",
                                 "cnpj":"111.444.777-35"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cnpjDuplicadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cnpj-duplicado");
        criarPlano(token, "2.5.001");
        String cnpj = "11.222.333/0001-81";

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Primeiro\",\"idPlanoContas\":\"2.5.001\",\"cnpj\":\"" + cnpj + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Segundo\",\"idPlanoContas\":\"2.5.001\",\"cnpj\":\"" + cnpj + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void telefoneComOitoDigitosEhRejeitado() throws Exception {
        String token = assinarNovoTenant("telefone-curto");
        criarPlano(token, "2.6.001");

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"razaoSocial":"Telefone Curto","idPlanoContas":"2.6.001",
                                 "telefone":"30554400"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void campoMarcadoObrigatorioNaConfiguracaoDeTelaEhExigidoNoBackend() throws Exception {
        String token = assinarNovoTenant("campo-obrigatorio");
        criarPlano(token, "2.7.001");

        mvc.perform(put("/api/v1/config-tela/cadastros.fornecedor.form").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("[{\"campo\":\"email\",\"visivel\":true,\"obrigatorio\":true}]"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Sem Email\",\"idPlanoContas\":\"2.7.001\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"Com Email\",\"idPlanoContas\":\"2.7.001\",\"email\":\"a@b.com\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");
        criarPlano(token, "2.8.001");

        criarFornecedorSimples(token, "2.8.001", "ORDFORN BETA");
        criarFornecedorSimples(token, "2.8.001", "ORDFORN ALFA");
        criarFornecedorSimples(token, "2.8.001", "ORDFORN GAMA");

        mvc.perform(get("/api/v1/fornecedores").param("razaoSocial", "ORDFORN")
                        .param("ordenarPor", "razaoSocial").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].razaoSocial").value("ORDFORN GAMA"));
    }

    @Test
    void excluirFornecedorSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        criarPlano(token, "2.9.001");
        long id = criarFornecedorSimples(token, "2.9.001", "Fornecedor Sem Vinculo");

        mvc.perform(delete("/api/v1/fornecedores/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/fornecedores/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirFornecedorComMovimentoInativaEmVezDeExcluir() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo");
        criarPlano(token, "2.10.001");
        long id = criarFornecedorSimples(token, "2.10.001", "Fornecedor Com Movimento");
        long idTenant = extrairIdTenant(token);

        criarMovimentoParaFornecedor(idTenant, id);

        mvc.perform(delete("/api/v1/fornecedores/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/fornecedores/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    private long criarFornecedorSimples(String token, String plano, String razaoSocial) throws Exception {
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"" + razaoSocial + "\",\"idPlanoContas\":\"" + plano + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFornecedor")).longValue();
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    /** Insere um movimento de estoque mínimo referenciando o fornecedor (compra, V019). */
    private void criarMovimentoParaFornecedor(long idTenant, long idFornecedor) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            st.executeUpdate(
                    "INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_fornecedor)"
                            + " VALUES (" + idTenant + ", " + idEmpresa + ", 'COMPRA', " + idFornecedor + ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
