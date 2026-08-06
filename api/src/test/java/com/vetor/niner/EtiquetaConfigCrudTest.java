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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD de Configuração de Etiqueta de Produtos (package-info.java do pacote
 * configuracao.etiqueta) — cabeçalho + coleções filhas (colunas/campos) salvas por
 * apaga-tudo-e-reinsere, validações de fronteira (nº de colunas, campo/coluna duplicados, campo
 * fora dos limites da etiqueta), e a busca de produto de exemplo pra pré-visualização.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EtiquetaConfigCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Etiqueta %s","email":"dono%s@lojaetiqueta.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    private static final String CORPO_3_COLUNAS = """
            {"nome":"%s","larguraRoloMm":110,"numeroColunas":3,"larguraEtiquetaMm":34,"alturaEtiquetaMm":30,
             "bordaSuperiorMm":2,"bordaInferiorMm":2,"bordaEsquerdaMm":2,"bordaDireitaMm":2,"ativo":true,
             "colunas":[{"numeroColuna":1,"posicaoInicialMm":3},{"numeroColuna":2,"posicaoInicialMm":39},
                        {"numeroColuna":3,"posicaoInicialMm":75}],
             "campos":[{"campo":"NOME_EMPRESA","posicaoXMm":0,"posicaoYMm":0,"larguraMm":34,"alturaMm":5,
                        "fonte":"ARIAL","tamanhoFontePt":8,"negrito":true,"fundoPreto":true,"alinhamento":"CENTRO"},
                       {"campo":"SKU_BARRAS","posicaoXMm":0,"posicaoYMm":18,"larguraMm":30,"alturaMm":8,
                        "fonte":"COURIER","negrito":false,"fundoPreto":false,"alinhamento":"CENTRO",
                        "exibirTextoLegivel":true}]}
            """;

    @Test
    void criaConfiguracaoComColunasECampos() throws Exception {
        String token = assinarNovoTenant("criacao");

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(CORPO_3_COLUNAS.formatted("ETIQUETA 3 COLUNAS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("ETIQUETA 3 COLUNAS"))
                .andExpect(jsonPath("$.numeroColunas").value(3))
                .andExpect(jsonPath("$.colunas.length()").value(3))
                .andExpect(jsonPath("$.colunas[1].numeroColuna").value(2))
                .andExpect(jsonPath("$.colunas[1].posicaoInicialMm").value(39))
                .andExpect(jsonPath("$.campos.length()").value(2))
                .andExpect(jsonPath("$.campos[0].campo").value("NOME_EMPRESA"))
                .andExpect(jsonPath("$.campos[0].fundoPreto").value(true))
                .andExpect(jsonPath("$.campos[1].campo").value("SKU_BARRAS"))
                .andExpect(jsonPath("$.campos[1].exibirTextoLegivel").value(true))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void numeroDeColunasForaDoIntervaloEhRejeitado() throws Exception {
        String token = assinarNovoTenant("colunas-fora");
        String corpo = CORPO_3_COLUNAS.formatted("ETIQUETA INVALIDA").replace("\"numeroColunas\":3", "\"numeroColunas\":7");

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quantidadeDeColunasDivergenteDoNumeroColunasEhRejeitada() throws Exception {
        String token = assinarNovoTenant("colunas-divergente");
        String corpo = """
                {"nome":"ETIQUETA DIVERGENTE","larguraRoloMm":50,"numeroColunas":2,"larguraEtiquetaMm":34,
                 "alturaEtiquetaMm":30,"colunas":[{"numeroColuna":1,"posicaoInicialMm":3}],"campos":[]}
                """;

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void numeroDeColunaRepetidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("coluna-repetida");
        String corpo = """
                {"nome":"ETIQUETA COLUNA REPETIDA","larguraRoloMm":50,"numeroColunas":2,"larguraEtiquetaMm":34,
                 "alturaEtiquetaMm":30,
                 "colunas":[{"numeroColuna":1,"posicaoInicialMm":3},{"numeroColuna":1,"posicaoInicialMm":39}],
                 "campos":[]}
                """;

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void campoRepetidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("campo-repetido");
        String corpo = """
                {"nome":"ETIQUETA CAMPO REPETIDO","larguraRoloMm":50,"numeroColunas":1,"larguraEtiquetaMm":34,
                 "alturaEtiquetaMm":30,"colunas":[{"numeroColuna":1,"posicaoInicialMm":3}],
                 "campos":[{"campo":"PRECO_VENDA","posicaoXMm":0,"posicaoYMm":0,"fonte":"ARIAL","negrito":false,"fundoPreto":false,"alinhamento":"ESQUERDA"},
                           {"campo":"PRECO_VENDA","posicaoXMm":0,"posicaoYMm":10,"fonte":"ARIAL","negrito":false,"fundoPreto":false,"alinhamento":"ESQUERDA"}]}
                """;

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void campoQueUltrapassaOsLimitesDaEtiquetaEhRejeitado() throws Exception {
        String token = assinarNovoTenant("campo-fora");
        String corpo = """
                {"nome":"ETIQUETA CAMPO FORA","larguraRoloMm":50,"numeroColunas":1,"larguraEtiquetaMm":34,
                 "alturaEtiquetaMm":30,"colunas":[{"numeroColuna":1,"posicaoInicialMm":3}],
                 "campos":[{"campo":"PRECO_VENDA","posicaoXMm":30,"posicaoYMm":0,"larguraMm":10,"alturaMm":5,
                            "fonte":"ARIAL","negrito":false,"fundoPreto":false,"alinhamento":"ESQUERDA"}]}
                """;

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nomeDuplicadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("nome-duplicado");
        criarConfig(token, "ETIQUETA UNICA");

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(CORPO_3_COLUNAS.formatted("etiqueta unica")))
                .andExpect(status().isConflict());
    }

    @Test
    void atualizarSubstituiColunasECampos() throws Exception {
        String token = assinarNovoTenant("atualiza");
        long id = criarConfig(token, "ETIQUETA ATUALIZA");

        String corpoAtualizado = """
                {"nome":"ETIQUETA ATUALIZA","larguraRoloMm":60,"numeroColunas":1,"larguraEtiquetaMm":40,
                 "alturaEtiquetaMm":25,"colunas":[{"numeroColuna":1,"posicaoInicialMm":5}],
                 "campos":[{"campo":"PRECO_VENDA","posicaoXMm":2,"posicaoYMm":2,"larguraMm":20,"alturaMm":8,
                            "fonte":"TIMES_NEW_ROMAN","tamanhoFontePt":12,"negrito":true,"fundoPreto":false,
                            "alinhamento":"DIREITA"}]}
                """;

        mvc.perform(put("/api/v1/etiquetas-config/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoAtualizado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numeroColunas").value(1))
                .andExpect(jsonPath("$.colunas.length()").value(1))
                .andExpect(jsonPath("$.campos.length()").value(1))
                .andExpect(jsonPath("$.campos[0].campo").value("PRECO_VENDA"));
    }

    @Test
    void excluirApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao");
        long id = criarConfig(token, "ETIQUETA EXCLUIR");

        mvc.perform(delete("/api/v1/etiquetas-config/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/etiquetas-config/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");
        criarConfig(token, "ORDETIQUETA BETA");
        criarConfig(token, "ORDETIQUETA ALFA");
        criarConfig(token, "ORDETIQUETA GAMA");

        mvc.perform(get("/api/v1/etiquetas-config").param("busca", "ORDETIQUETA")
                        .param("ordenarPor", "nome").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("ORDETIQUETA GAMA"));
    }

    @Test
    void buscaProdutosExemploDevolveDadosParaPreVisualizacao() throws Exception {
        String token = assinarNovoTenant("produto-exemplo");
        long idTenant = extrairIdTenant(token);

        long idProduto = criarProduto(token, "CAMISA BOT PADRAO ADIDAS");
        try (Connection c = abrirConexao(idTenant)) {
            criarVariacao(c, idTenant, idProduto);
        }

        mvc.perform(get("/api/v1/etiquetas-config/produtos-exemplo").param("busca", "CAMISA")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].descricao").value("CAMISA BOT PADRAO ADIDAS"))
                .andExpect(jsonPath("$[0].sku").exists())
                .andExpect(jsonPath("$[0].precoVenda").value(144.00));
    }

    private long criarConfig(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(CORPO_3_COLUNAS.formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idConfigEtiqueta")).longValue();
    }

    private long criarProduto(String token, String descricao) throws Exception {
        String corpo = """
                {"descricao":"%s","precoCusto":"70.00","percentualVenda":"0","precoVenda":"144.00"}
                """.formatted(descricao);
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private void criarVariacao(Connection c, long idTenant, long idProduto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (?, ?, gerar_ean13_interno())
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            ps.execute();
        }
    }
}
