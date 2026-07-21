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
 * CRUD de cliente + categoria de cliente (docs/telas/cliente.md). Cada teste assina um
 * tenant novo (self-service, R12) e usa o token real emitido — mesmo caminho de
 * {@link OnboardingTrialTest} — para exercitar o fluxo completo (JWT → TenantContext → RLS).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ClienteCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Cliente %s","email":"dono%s@lojacliente.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long criarCategoria(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoriaCliente")).longValue();
    }

    @Test
    void criaCategoriaEClientePessoaFisica() throws Exception {
        String token = assinarNovoTenant("pf");
        long idCategoria = criarCategoria(token, "Padrão");

        String cliente = """
                {"fisicaJuridica":true,"nome":"Maria Silva","idCategoriaCliente":%d,
                 "cpfCnpj":"111.444.777-35","dataNascimento":"1990-05-10","genero":"FEMININO",
                 "endereco":"Rua Um","numero":"100","complemento":"apto 12"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isCreated())
                // Todo campo de texto livre é normalizado para MAIÚSCULAS no servidor (item 2
                // do pedido de 2026-07-20) — defesa em profundidade além do frontend.
                .andExpect(jsonPath("$.nome").value("MARIA SILVA"))
                .andExpect(jsonPath("$.nomeCategoria").value("PADRÃO"))
                .andExpect(jsonPath("$.cpfCnpj").value("11144477735"))
                .andExpect(jsonPath("$.complemento").value("APTO 12"))
                .andExpect(jsonPath("$.ativo").value(true));

        // Filtra por nome: o datasource de teste (Testcontainers @ServiceConnection) conecta
        // como o superusuário do container, não como niner_app — RLS não filtra por tenant
        // nesse caminho (ver javadoc de RlsIsolamentoTest), então uma listagem sem filtro
        // veria clientes de outros testes também. O gate de isolamento real é RlsIsolamentoTest.
        mvc.perform(get("/api/v1/clientes").param("nome", "Maria").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("MARIA SILVA"));
    }

    @Test
    void clientePessoaFisicaSemGeneroEhRejeitado() throws Exception {
        String token = assinarNovoTenant("sem-genero");
        long idCategoria = criarCategoria(token, "Padrão");

        String cliente = """
                {"fisicaJuridica":true,"nome":"Sem Genero","idCategoriaCliente":%d,
                 "dataNascimento":"1990-05-10"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clientePessoaFisicaSemNascimentoComGeneroEhAceito() throws Exception {
        // Data de nascimento é sempre opcional (2026-07-21) — só o gênero é obrigatório para PF.
        String token = assinarNovoTenant("sem-nasc");
        long idCategoria = criarCategoria(token, "Padrão");

        String cliente = """
                {"fisicaJuridica":true,"nome":"Sem Nascimento","idCategoriaCliente":%d,"genero":"OUTROS"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataNascimento").doesNotExist());
    }

    @Test
    void clienteComDataNascimentoNoFuturoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("nasc-futuro");
        long idCategoria = criarCategoria(token, "Padrão");

        String cliente = """
                {"fisicaJuridica":true,"nome":"Nascimento Futuro","idCategoriaCliente":%d,
                 "genero":"OUTROS","dataNascimento":"2999-01-01"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clientePessoaJuridicaSemDadosPessoaisEhAceito() throws Exception {
        String token = assinarNovoTenant("pj");
        long idCategoria = criarCategoria(token, "Atacado");

        String cliente = """
                {"fisicaJuridica":false,"nome":"Comercio Ltda","idCategoriaCliente":%d,
                 "cpfCnpj":"11.222.333/0001-81"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cpfCnpj").value("11222333000181"));
    }

    @Test
    void cnpjAlfanumericoValidoEhAceitoEArmazenadoEmMaiusculas() throws Exception {
        // CNPJ alfanumérico (Receita Federal, IN RFB 2.229/2024, a partir de julho/2026): as
        // 12 primeiras posições podem ser letras — exemplo oficial "12.ABC.345/01DE-35"
        // (dígitos verificadores 35 conferidos manualmente com o algoritmo módulo 11).
        String token = assinarNovoTenant("cnpj-alfa");
        long idCategoria = criarCategoria(token, "Atacado");

        String cliente = """
                {"fisicaJuridica":false,"nome":"Empresa Alfanumerica","idCategoriaCliente":%d,
                 "cpfCnpj":"12.ABC.345/01DE-35"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cpfCnpj").value("12ABC34501DE35"));
    }

    @Test
    void cnpjAlfanumericoComDigitoVerificadorInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cnpj-alfa-invalido");
        long idCategoria = criarCategoria(token, "Atacado");

        String cliente = """
                {"fisicaJuridica":false,"nome":"Empresa Alfanumerica Invalida","idCategoriaCliente":%d,
                 "cpfCnpj":"12.ABC.345/01DE-99"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cpfComDigitoVerificadorInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cpf-invalido");
        long idCategoria = criarCategoria(token, "Padrão");

        String cliente = """
                {"fisicaJuridica":true,"nome":"CPF Invalido","idCategoriaCliente":%d,
                 "cpfCnpj":"111.444.777-99","dataNascimento":"1990-05-10","genero":"MASCULINO"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailComFormatoInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("email-invalido");
        long idCategoria = criarCategoria(token, "Padrão");

        String cliente = """
                {"fisicaJuridica":false,"nome":"Email Invalido","idCategoriaCliente":%d,
                 "email":"nao-e-um-email"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void celularForaDoPadraoEhRejeitado() throws Exception {
        // Validação de formato (docs/telas/cliente.md): 11 dígitos com o 3º = 9. Antes
        // (2026-07-21) só o frontend checava isso — a API aceitava qualquer texto.
        String token = assinarNovoTenant("celular-invalido");
        long idCategoria = criarCategoria(token, "Padrão");

        String cliente = """
                {"fisicaJuridica":false,"nome":"Celular Invalido","idCategoriaCliente":%d,
                 "telefone":"1133334444"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cepComMenosDeOitoDigitosEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cep-invalido");
        long idCategoria = criarCategoria(token, "Padrão");

        String cliente = """
                {"fisicaJuridica":false,"nome":"Cep Invalido","idCategoriaCliente":%d,"cep":"12345"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void campoMarcadoObrigatorioNaConfiguracaoDeTelaEhExigidoNoBackend() throws Exception {
        // A obrigatoriedade configurável (cfg_tela_campo, docs/telas/configuracao-tela.md) só
        // era aplicada no frontend antes de 2026-07-21 — a API aceitava o campo vazio mesmo
        // configurado como obrigatório. Este teste prova que agora a API também rejeita.
        String token = assinarNovoTenant("campo-obrigatorio");
        long idCategoria = criarCategoria(token, "Padrão");

        mvc.perform(put("/api/v1/config-tela/cadastros.cliente.form").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("[{\"campo\":\"email\",\"visivel\":true,\"obrigatorio\":true}]"))
                .andExpect(status().isOk());

        String clienteSemEmail = """
                {"fisicaJuridica":false,"nome":"Sem Email","idCategoriaCliente":%d}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(clienteSemEmail))
                .andExpect(status().isBadRequest());

        String clienteComEmail = """
                {"fisicaJuridica":false,"nome":"Com Email","idCategoriaCliente":%d,"email":"a@b.com"}
                """.formatted(idCategoria);

        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(clienteComEmail))
                .andExpect(status().isCreated());
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        // Prefixo único (não só "A"/"B"/"C") para o filtro por nome não pegar clientes de
        // outros testes — o datasource de teste não aplica RLS (ver comentário do primeiro
        // teste desta classe), então a tabela `cliente` é efetivamente compartilhada aqui.
        String token = assinarNovoTenant("ordenacao");
        long idCategoria = criarCategoria(token, "Padrão");

        criarClienteSimples(token, idCategoria, "ORDENACAOTESTE BETA");
        criarClienteSimples(token, idCategoria, "ORDENACAOTESTE ALFA");
        criarClienteSimples(token, idCategoria, "ORDENACAOTESTE GAMA");

        mvc.perform(get("/api/v1/clientes").param("nome", "ORDENACAOTESTE")
                        .param("ordenarPor", "nome").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("ORDENACAOTESTE GAMA"));
    }

    private void criarClienteSimples(String token, long idCategoria, String nome) throws Exception {
        String cliente = """
                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d}
                """.formatted(nome, idCategoria);
        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isCreated());
    }

    @Test
    void categoriaComNomeDuplicadoEhRejeitada() throws Exception {
        String token = assinarNovoTenant("cat-dup");
        criarCategoria(token, "VIP");

        mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"VIP\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void categoriaInexistenteAoCriarClienteEhRejeitada() throws Exception {
        String token = assinarNovoTenant("cat-inexistente");

        String cliente = """
                {"fisicaJuridica":false,"nome":"Sem Categoria","idCategoriaCliente":999999}
                """;
        mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isBadRequest());
    }

    @Test
    void excluirClienteSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        long idCategoria = criarCategoria(token, "Padrão");
        String cliente = """
                {"fisicaJuridica":false,"nome":"Cliente Sem Venda","idCategoriaCliente":%d}
                """.formatted(idCategoria);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCliente = ((Number) JsonPath.read(resp, "$.idCliente")).longValue();

        mvc.perform(delete("/api/v1/clientes/" + idCliente).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/clientes/" + idCliente).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirClienteComVendaInativaEmVezDeExcluir() throws Exception {
        String token = assinarNovoTenant("exclusao-com-venda");
        long idCategoria = criarCategoria(token, "Padrão");
        String cliente = """
                {"fisicaJuridica":false,"nome":"Cliente Com Venda","idCategoriaCliente":%d}
                """.formatted(idCategoria);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(cliente))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCliente = ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
        long idTenant = extrairIdTenant(token);

        criarVendaParaCliente(idTenant, idCliente);

        mvc.perform(delete("/api/v1/clientes/" + idCliente).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/clientes/" + idCliente).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    private static long extrairIdTenant(String token) {
        // O claim "tid" está no payload do JWT (segunda parte, base64url) — decodifica sem validar assinatura.
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    private void criarVendaParaCliente(long idTenant, long idCliente) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            st.executeUpdate(
                    "INSERT INTO venda (id_tenant, id_empresa, id_cliente) VALUES ("
                            + idTenant + ", " + idEmpresa + ", " + idCliente + ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
