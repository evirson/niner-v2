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
             "margemEsquerdaMm":3,"espacamentoHorizontalMm":2,"ativo":true,
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
                .andExpect(jsonPath("$.margemEsquerdaMm").value(3))
                .andExpect(jsonPath("$.espacamentoHorizontalMm").value(2))
                .andExpect(jsonPath("$.campos.length()").value(2))
                .andExpect(jsonPath("$.campos[0].campo").value("NOME_EMPRESA"))
                .andExpect(jsonPath("$.campos[0].fundoPreto").value(true))
                .andExpect(jsonPath("$.campos[1].campo").value("SKU_BARRAS"))
                .andExpect(jsonPath("$.campos[1].exibirTextoLegivel").value(true))
                .andExpect(jsonPath("$.ativo").value(true))
                // Sem informar, o espaço entre fileiras nasce 0 = fileiras coladas, que é como o
                // sistema paginava antes da V056 — nenhuma configuração existente muda sozinha.
                .andExpect(jsonPath("$.espacamentoVerticalMm").value(0))
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    /**
     * ⚠️ Espaço entre fileiras (V056) — o valor cujo erro se ACUMULA.
     *
     * <p>Diagnosticado com etiqueta impressa na mão em 2026-08-20: a impressão empilhava as
     * fileiras usando só a altura do adesivo, e num rolo com 3 mm de gap o conteúdo subia 3 mm por
     * fileira — a primeira saía perfeita, a quarta inteiramente fora. O passo correto é
     * {@code altura + espaçamento}, e o campo precisa sobreviver à ida e volta da API para a tela
     * conseguir desenhar a prévia de duas fileiras.
     */
    @Test
    void espacamentoEntreFileirasEhGravadoELido() throws Exception {
        String token = assinarNovoTenant("espacamento");
        String corpo = CORPO_3_COLUNAS.formatted("ETIQUETA COM GAP")
                .replace("\"numeroColunas\":3", "\"numeroColunas\":3,\"espacamentoVerticalMm\":3.00");

        String resp = mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.espacamentoVerticalMm").value(3.00))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(resp, "$.idConfigEtiqueta")).longValue();

        mvc.perform(get("/api/v1/etiquetas-config/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.espacamentoVerticalMm").value(3.00));

        // Atualizar também precisa persistir — o UPDATE tem lista de colunas própria e é onde um
        // campo novo costuma ficar de fora sem ninguém notar.
        mvc.perform(put("/api/v1/etiquetas-config/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(corpo.replace("\"espacamentoVerticalMm\":3.00", "\"espacamentoVerticalMm\":5.50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.espacamentoVerticalMm").value(5.50));
    }

    @Test
    void numeroDeColunasForaDoIntervaloEhRejeitado() throws Exception {
        String token = assinarNovoTenant("colunas-fora");
        String corpo = CORPO_3_COLUNAS.formatted("ETIQUETA INVALIDA").replace("\"numeroColunas\":3", "\"numeroColunas\":7");

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    /**
     * ⚠️ Substitui os dois testes de coerência da lista de colunas (quantidade divergente e número
     * repetido), que deixaram de existir com a V057: sem lista, não há como estar incoerente. A
     * validação que sobra é a que **importa fisicamente** — as colunas cabem no rolo?
     *
     * <p>2 colunas de 34 mm com margem 3 e espaço 2 ocupam 73 mm. Num rolo de 50 mm, a segunda sai
     * cortada pela metade — e antes disso só a tela avisava, porque com posições digitadas o
     * servidor não tinha como saber onde cada coluna começava sem confiar no que recebeu.
     */
    @Test
    void colunasQueNaoCabemNoRoloSaoRejeitadas() throws Exception {
        String token = assinarNovoTenant("nao-cabe");
        String corpo = """
                {"nome":"ETIQUETA QUE NAO CABE","larguraRoloMm":50,"numeroColunas":2,"larguraEtiquetaMm":34,
                 "alturaEtiquetaMm":30,"margemEsquerdaMm":3,"espacamentoHorizontalMm":2,"campos":[]}
                """;

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
    }

    /** O mesmo rolo, com espaço entre colunas que cabe, passa — a validação não é um "não" fixo. */
    @Test
    void colunasQueCabemNoRoloSaoAceitas() throws Exception {
        String token = assinarNovoTenant("cabe");
        String corpo = """
                {"nome":"ETIQUETA QUE CABE","larguraRoloMm":110,"numeroColunas":3,"larguraEtiquetaMm":34,
                 "alturaEtiquetaMm":30,"margemEsquerdaMm":3,"espacamentoHorizontalMm":2,"campos":[]}
                """;

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.margemEsquerdaMm").value(3))
                .andExpect(jsonPath("$.espacamentoHorizontalMm").value(2));
    }

    /**
     * O MESMO campo pode ser posicionado mais de uma vez (2026-08-24, V060) — este teste prendia o
     * contrário até hoje.
     *
     * <p><b>O caso é a etiqueta destacável:</b> numa etiqueta alta o adesivo é picotado ao meio,
     * uma parte fica no produto e a outra é destacada no caixa, e as duas metades precisam do mesmo
     * conteúdo — principalmente o código de barras. Antes, a segunda metade saía em branco.
     *
     * <p>⚠️ Confere as DUAS instâncias de volta, com posição e propriedades <b>independentes</b>,
     * não só o 201: o que faz a repetição funcionar é `salvarCampos` reinserir a lista inteira em
     * ordem e `buscarCampos` ler com `ORDER BY id_config_etiqueta_campo`. Um teste que olhasse só o
     * status passaria mesmo se a leitura devolvesse uma linha só, ou as duas trocadas.
     */
    @Test
    void mesmoCampoPodeAparecerMaisDeUmaVez() throws Exception {
        String token = assinarNovoTenant("campo-repetido");
        String corpo = """
                {"nome":"ETIQUETA DESTACAVEL","larguraRoloMm":50,"numeroColunas":1,"larguraEtiquetaMm":34,
                 "alturaEtiquetaMm":60,"margemEsquerdaMm":3,
                 "campos":[{"campo":"SKU_BARRAS","posicaoXMm":2,"posicaoYMm":5,"larguraMm":30,"alturaMm":14,
                            "fonte":"ARIAL","negrito":false,"fundoPreto":false,"alinhamento":"CENTRO","exibirTextoLegivel":true},
                           {"campo":"SKU_BARRAS","posicaoXMm":2,"posicaoYMm":40,"larguraMm":30,"alturaMm":14,
                            "fonte":"ARIAL","negrito":false,"fundoPreto":false,"alinhamento":"CENTRO","exibirTextoLegivel":false},
                           {"campo":"PRECO_VENDA","posicaoXMm":2,"posicaoYMm":22,"larguraMm":30,"alturaMm":5,
                            "fonte":"ARIAL","negrito":true,"fundoPreto":false,"alinhamento":"DIREITA"}]}
                """;

        mvc.perform(post("/api/v1/etiquetas-config").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campos.length()").value(3))
                // Ordem preservada, e cada instância com a SUA posição.
                .andExpect(jsonPath("$.campos[0].campo").value("SKU_BARRAS"))
                .andExpect(jsonPath("$.campos[0].posicaoYMm").value(5))
                .andExpect(jsonPath("$.campos[1].campo").value("SKU_BARRAS"))
                .andExpect(jsonPath("$.campos[1].posicaoYMm").value(40))
                // Propriedades independentes: a 2ª metade pode não repetir os dígitos legíveis.
                .andExpect(jsonPath("$.campos[0].exibirTextoLegivel").value(true))
                .andExpect(jsonPath("$.campos[1].exibirTextoLegivel").value(false))
                .andExpect(jsonPath("$.campos[2].campo").value("PRECO_VENDA"));
    }

    @Test
    void campoQueUltrapassaOsLimitesDaEtiquetaEhRejeitado() throws Exception {
        String token = assinarNovoTenant("campo-fora");
        String corpo = """
                {"nome":"ETIQUETA CAMPO FORA","larguraRoloMm":50,"numeroColunas":1,"larguraEtiquetaMm":34,
                 "alturaEtiquetaMm":30,"margemEsquerdaMm":3,
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
                 "alturaEtiquetaMm":25,"margemEsquerdaMm":5,
                 "campos":[{"campo":"PRECO_VENDA","posicaoXMm":2,"posicaoYMm":2,"larguraMm":20,"alturaMm":8,
                            "fonte":"TIMES_NEW_ROMAN","tamanhoFontePt":12,"negrito":true,"fundoPreto":false,
                            "alinhamento":"DIREITA"}]}
                """;

        mvc.perform(put("/api/v1/etiquetas-config/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpoAtualizado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numeroColunas").value(1))
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
