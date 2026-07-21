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
 * CRUD de funcionário (docs/telas/funcionario.md) — mesmo padrão de {@link ClienteCrudTest}:
 * cada teste assina um tenant novo (self-service, R12) e usa o token real emitido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FuncionarioCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Funcionario %s","email":"dono%s@lojafuncionario.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void criaFuncionarioComDadosCompletos() throws Exception {
        String token = assinarNovoTenant("completo");

        String funcionario = """
                {"nome":"Joao da Silva","cpf":"111.444.777-35","telefone":"11988887777",
                 "cargo":"Vendedor","percComissao":5.5}
                """;

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(funcionario))
                .andExpect(status().isCreated())
                // Nome/cargo normalizados para MAIÚSCULAS no servidor, mesma convenção de cliente.
                .andExpect(jsonPath("$.nome").value("JOAO DA SILVA"))
                .andExpect(jsonPath("$.cargo").value("VENDEDOR"))
                .andExpect(jsonPath("$.cpf").value("11144477735"))
                .andExpect(jsonPath("$.percComissao").value(5.5))
                .andExpect(jsonPath("$.ativo").value(true));

        mvc.perform(get("/api/v1/funcionarios").param("nome", "Joao").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("JOAO DA SILVA"));
    }

    @Test
    void funcionarioSemNenhumCampoOpcionalEhAceito() throws Exception {
        // Só nome é obrigatório estruturalmente — cpf/telefone/cargo/percComissao são
        // opcionais quando a tela não os marca como obrigatórios (default).
        String token = assinarNovoTenant("minimo");

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"Funcionario Minimo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.percComissao").value(0));
    }

    @Test
    void cpfComDigitoVerificadorInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cpf-invalido");

        String funcionario = """
                {"nome":"CPF Invalido","cpf":"111.444.777-99"}
                """;

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(funcionario))
                .andExpect(status().isBadRequest());
    }

    @Test
    void celularForaDoPadraoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("celular-invalido");

        String funcionario = """
                {"nome":"Celular Invalido","telefone":"1133334444"}
                """;

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(funcionario))
                .andExpect(status().isBadRequest());
    }

    @Test
    void percComissaoAcimaDeCemEhRejeitado() throws Exception {
        String token = assinarNovoTenant("comissao-invalida");

        String funcionario = """
                {"nome":"Comissao Invalida","percComissao":150}
                """;

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(funcionario))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cpfDuplicadoEntreDoisFuncionariosEhAceito() throws Exception {
        // Decisão de produto registrada em V016/§3.3.9: ao contrário do cliente, o CPF do
        // funcionário NÃO é único por tenant.
        String token = assinarNovoTenant("cpf-duplicado");
        String cpf = "111.444.777-35";

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"Funcionario Um\",\"cpf\":\"" + cpf + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nome\":\"Funcionario Dois\",\"cpf\":\"" + cpf + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void campoMarcadoObrigatorioNaConfiguracaoDeTelaEhExigidoNoBackend() throws Exception {
        String token = assinarNovoTenant("campo-obrigatorio");

        mvc.perform(put("/api/v1/config-tela/cadastros.funcionario.form").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("[{\"campo\":\"cargo\",\"visivel\":true,\"obrigatorio\":true}]"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"Sem Cargo\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"Com Cargo\",\"cargo\":\"Caixa\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");

        criarFuncionarioSimples(token, "ORDENACAOTESTE BETA");
        criarFuncionarioSimples(token, "ORDENACAOTESTE ALFA");
        criarFuncionarioSimples(token, "ORDENACAOTESTE GAMA");

        mvc.perform(get("/api/v1/funcionarios").param("nome", "ORDENACAOTESTE")
                        .param("ordenarPor", "nome").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("ORDENACAOTESTE GAMA"));
    }

    private void criarFuncionarioSimples(String token, String nome) throws Exception {
        mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"" + nome + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void excluirFuncionarioSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"Funcionario Sem Movimento\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idFuncionario = ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();

        mvc.perform(delete("/api/v1/funcionarios/" + idFuncionario).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/funcionarios/" + idFuncionario).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirFuncionarioComMovimentoInativaEmVezDeExcluir() throws Exception {
        String token = assinarNovoTenant("exclusao-com-movimento");
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"Funcionario Com Movimento\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idFuncionario = ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
        long idTenant = extrairIdTenant(token);

        criarMovimentoParaFuncionario(idTenant, idFuncionario);

        mvc.perform(delete("/api/v1/funcionarios/" + idFuncionario).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/funcionarios/" + idFuncionario).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    /**
     * Cria o mínimo necessário (produto + variação + movimento de estoque) para vincular um
     * funcionário a `produto_movimento_detalhe.id_funcionario` — mesmo padrão de
     * `ClienteCrudTest.criarVendaParaCliente`.
     */
    private void criarMovimentoParaFuncionario(long idTenant, long idFuncionario) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            long idProduto;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO produto (id_tenant, descricao) VALUES (" + idTenant
                            + ", 'PRODUTO TESTE') RETURNING id_produto")) {
                rs.next();
                idProduto = rs.getLong(1);
            }
            long idVariacao;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (" + idTenant + ", " + idProduto
                            + ", 'SKU-TESTE-" + idFuncionario + "') RETURNING id_variacao")) {
                rs.next();
                idVariacao = rs.getLong(1);
            }
            long idMovimento;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento) VALUES ("
                            + idTenant + ", " + idEmpresa + ", 'VENDA') RETURNING id_movimento")) {
                rs.next();
                idMovimento = rs.getLong(1);
            }
            st.executeUpdate(
                    "INSERT INTO produto_movimento_detalhe (id_tenant, id_movimento, id_empresa, id_funcionario,"
                            + " id_variacao, credito_debito, qtd_produto) VALUES (" + idTenant + ", " + idMovimento
                            + ", " + idEmpresa + ", " + idFuncionario + ", " + idVariacao + ", 'D', 1)");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
