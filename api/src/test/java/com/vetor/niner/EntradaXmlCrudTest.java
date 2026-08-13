package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo XML da Entrada de Produtos por Compra (Fase 3, docs/telas/entrada-mercadoria.md,
 * 2026-08-18) — usa uma NF-e REAL de calçados (`src/test/resources/xml/nfe-dakota-calcados.xml`,
 * 36 itens, fornecedor Dakota Calçados) pra provar o parser/matching contra dado de verdade, não
 * um XML sintético simplificado. Mesma hierarquia de confiança do fluxo Planilha (EAN → aprendido
 * → pendência), mas SEM cadastro automático de cor/tamanho — ver {@link EntradaXmlService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EntradaXmlCrudTest {

    private static final String CNPJ_DAKOTA = "07414643000201";
    private static final String CHAVE_DAKOTA = "28260207414643000201550000003226411000014220";
    private static final String EAN_ITEM_1 = "7900282671000";
    private static final String CPROD_ITEM_1 = "J0781-0001-34";

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Xml %s","email":"dono%s@lojaxml.com",
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

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private void criarPlano(String token, String codigo) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"DESPESA FORNECEDORES","tipoMovimento":"DEBITO",
                                 "natureza":"ANALITICA","incluiDre":false,"incluiFluxoCaixa":false}
                                """.formatted(codigo)))
                .andExpect(status().isCreated());
    }

    private long criarFornecedor(String token, String razaoSocial, String cnpj) throws Exception {
        criarPlano(token, "2.00.000");
        String cnpjJson = cnpj == null ? "" : ",\"cnpj\":\"" + cnpj + "\"";
        String resp = mvc.perform(post("/api/v1/fornecedores").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"razaoSocial\":\"%s\",\"idPlanoContas\":\"2.00.000\"%s}".formatted(razaoSocial, cnpjJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFornecedor")).longValue();
    }

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"20.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarVariacao(String token, long idProduto, String ean) throws Exception {
        String eanJson = ean == null ? "null" : "\"" + ean + "\"";
        String resp = mvc.perform(post("/api/v1/produtos/" + idProduto + "/variacoes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"ean\":%s}".formatted(eanJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVariacao")).longValue();
    }

    private MockMultipartFile xmlDakota() throws Exception {
        byte[] bytes = new ClassPathResource("xml/nfe-dakota-calcados.xml").getInputStream().readAllBytes();
        return new MockMultipartFile("arquivo", "nfe-dakota-calcados.xml", "text/xml", bytes);
    }

    private MockMultipartFile xmlGrings() throws Exception {
        byte[] bytes = new ClassPathResource("xml/nfe-grings.xml").getInputStream().readAllBytes();
        return new MockMultipartFile("arquivo", "nfe-grings.xml", "text/xml", bytes);
    }

    /**
     * Insere um NCM diretamente (não há endpoint de escrita — {@code cfg_produto_ncm} é
     * mantida por script, global, sem tenant). Conecta como {@code niner_owner}: {@code
     * niner_app} só tem SELECT nessa tabela (V017) — mesmo padrão de {@code ProdutoCrudTest}.
     */
    private void criarNcm(String codigo, String descricao) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_owner", "dev_owner");
             Statement st = c.createStatement()) {
            // ON CONFLICT DO NOTHING: cfg_produto_ncm é GLOBAL (sem tenant), compartilhada por
            // TODOS os métodos de teste desta classe no mesmo container — sem isso, o 2º teste
            // que precisar do mesmo código bate em "duplicate key".
            st.executeUpdate("INSERT INTO cfg_produto_ncm (codigo_ncm, descricao_ncm) VALUES ('"
                    + codigo + "', '" + descricao + "') ON CONFLICT (codigo_ncm) DO NOTHING");
        }
    }

    private long criarProdutoComNcm(String token, String descricao, String ncm) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100","precoVenda":"20.00",
                                 "codigoNcm":"%s"}
                                """.formatted(descricao, ncm)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private String buscarCodigoNcm(long idTenant, long idProduto) throws SQLException {
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT codigo_ncm FROM produto WHERE id_tenant = ? AND id_produto = ?")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    /** O item 1 da NF-e real da Dakota (EAN_ITEM_1/CPROD_ITEM_1) traz {@code <NCM>64029190</NCM>}
     *  — motivou o pedido do dono do produto: "o NCM do XML sempre vale, mesmo que o produto já
     *  tenha outro cadastrado". */
    @Test
    void confirmarEntradaSubstituiNcmDoProdutoPeloDoXmlQuandoDiferente() throws Exception {
        String token = assinarNovoTenant("ncmsubstitui");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token, "FORNECEDOR NCM SUBSTITUI", null);
        criarNcm("64029190", "CALCADOS DE COURO");
        criarNcm("99998888", "NCM ANTIGO TESTE ENTRADA XML");
        long idProduto = criarProdutoComNcm(token, "BOTA COM NCM ANTIGO", "99998888");
        long idVariacao = criarVariacao(token, idProduto, EAN_ITEM_1);

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> item1 = JsonPath.read(resp, "$.itens[0]");
        assertThat((Boolean) item1.get("resolvido")).isTrue();
        assertThat((String) item1.get("ncm")).isEqualTo("64029190");

        assertThat(buscarCodigoNcm(idTenant, idProduto)).isEqualTo("99998888");

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":1,"precoCusto":10.00,
                                 "codigoFornecedor":"%s","ncm":"64029190"}]}
                                """.formatted(idFornecedor, idVariacao, CPROD_ITEM_1)))
                .andExpect(status().isCreated());

        assertThat(buscarCodigoNcm(idTenant, idProduto)).isEqualTo("64029190");
    }

    /** Pendência (produto não encontrado) também traz o NCM do XML, já validado contra
     *  {@code cfg_produto_ncm} — a tela usa isso pra pré-preencher o cadastro rápido. */
    @Test
    void pendenciaTrazNcmDoXmlQuandoEleExisteNoCadastroDeNcm() throws Exception {
        String token = assinarNovoTenant("ncmpendencia");
        criarNcm("64029190", "CALCADOS DE COURO");

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item1 = JsonPath.read(resp, "$.itens[0]");
        assertThat((Boolean) item1.get("resolvido")).isFalse();
        assertThat((String) item1.get("ncm")).isEqualTo("64029190");
    }

    /** Bug relatado (2026-08-20): NCM não vinha preenchido no cadastro rápido a partir de uma
     *  pendência — causa raiz era validar o NCM contra {@code cfg_produto_ncm} antes de mostrar
     *  QUALQUER coisa (a base local, ao contrário da oficial da Receita, normalmente não tem o
     *  código do XML cadastrado). Pendência não grava nada — só pré-preenche um campo — então o
     *  NCM cru do XML tem que aparecer independente de existir na base local ou não (diferente
     *  da linha resolvida, que vai virar um UPDATE de verdade e por isso continua validando
     *  antes). Este teste deliberadamente NÃO chama {@code criarNcm} — mesmo que outro método
     *  desta classe já tenha semeado esse código (tabela global, compartilhada no container),
     *  a asserção vale igual, já que pendência não depende mais disso. */
    @Test
    void pendenciaTrazNcmDoXmlMesmoQuandoEleNaoExisteNoCadastroDeNcm() throws Exception {
        String token = assinarNovoTenant("ncmpendenciacru");

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item1 = JsonPath.read(resp, "$.itens[0]");
        assertThat((Boolean) item1.get("resolvido")).isFalse();
        assertThat((String) item1.get("ncm")).isEqualTo("64029190");
    }

    @Test
    void parseiaCabecalhoDaNotaReal() throws Exception {
        String token = assinarNovoTenant("cabecalho");

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(resp, "$.chaveNfe")).isEqualTo(CHAVE_DAKOTA);
        assertThat(((Number) JsonPath.read(resp, "$.notaFiscal")).intValue()).isEqualTo(322641);
        assertThat(((Number) JsonPath.read(resp, "$.serieNota")).intValue()).isEqualTo(0);
        assertThat(((Number) JsonPath.read(resp, "$.valorTotalNota")).doubleValue()).isEqualTo(5108.64);
        assertThat((Boolean) JsonPath.read(resp, "$.chaveJaImportada")).isFalse();
        List<Map<String, Object>> itens = JsonPath.read(resp, "$.itens");
        assertThat(itens).hasSize(36);
        List<Map<String, Object>> duplicatas = JsonPath.read(resp, "$.duplicatas");
        assertThat(duplicatas).hasSize(3);
        assertThat(((Number) duplicatas.get(0).get("valor")).doubleValue()).isEqualTo(1704.64);
    }

    @Test
    void fornecedorNaoCadastradoVoltaSemIdMasComDadosDoXml() throws Exception {
        String token = assinarNovoTenant("fornsemcad");

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((Object) JsonPath.read(resp, "$.fornecedor.idFornecedor")).isNull();
        assertThat((String) JsonPath.read(resp, "$.fornecedor.cnpj")).isEqualTo(CNPJ_DAKOTA);
        assertThat((String) JsonPath.read(resp, "$.fornecedor.razaoSocial")).isEqualTo("DAKOTA CALCADOS S/A");
    }

    @Test
    void fornecedorCadastradoEhCasadoPeloCnpjDoEmitente() throws Exception {
        String token = assinarNovoTenant("fornocnpj");
        long idFornecedor = criarFornecedor(token, "DAKOTA JA CADASTRADA", CNPJ_DAKOTA);

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(resp, "$.fornecedor.idFornecedor")).longValue()).isEqualTo(idFornecedor);
        assertThat((String) JsonPath.read(resp, "$.fornecedor.razaoSocial")).isEqualTo("DAKOTA JA CADASTRADA");
    }

    @Test
    void itemSemProdutoConhecidoFicaPendenteSemInventarCorOuTamanho() throws Exception {
        String token = assinarNovoTenant("itempendente");

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item1 = JsonPath.read(resp, "$.itens[0]");
        assertThat((Boolean) item1.get("resolvido")).isFalse();
        assertThat((Object) item1.get("idVariacao")).isNull();
        assertThat((String) item1.get("motivoPendencia")).isNotBlank();
        assertThat((String) item1.get("codigoBarrasFabricante")).isEqualTo(EAN_ITEM_1);
        assertThat((String) item1.get("codigoFornecedor")).isEqualTo(CPROD_ITEM_1);
        assertThat(((Number) item1.get("custoUnitario")).doubleValue()).isEqualTo(88.0);
        assertThat(((Number) item1.get("qtd")).doubleValue()).isEqualTo(1.0);
        // Palpite de tamanho (último token numérico do xProd) é só um palpite em texto — nunca
        // um id de cor/tamanho já resolvido/criado.
        assertThat((String) item1.get("tamanho")).isEqualTo("34");
    }

    @Test
    void itemComEanJaCadastradoResolveDireto() throws Exception {
        String token = assinarNovoTenant("itemean");
        long idProduto = criarProduto(token, "BOTA MISSISSIPI BERTELI PRETO 220");
        long idVariacao = criarVariacao(token, idProduto, EAN_ITEM_1);

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item1 = JsonPath.read(resp, "$.itens[0]");
        assertThat((Boolean) item1.get("resolvido")).isTrue();
        assertThat(((Number) item1.get("idVariacao")).longValue()).isEqualTo(idVariacao);
        assertThat((String) item1.get("codigoFornecedor")).isEqualTo(CPROD_ITEM_1);
    }

    /** Vínculo `produto_fornecedor` aprendido numa entrada anterior deste MESMO fornecedor —
     *  casa pelo `cProd`, sem precisar de EAN nenhum (a variação criada aqui nem tem EAN, só
     *  pra provar que o EAN não é o que resolve este caso). */
    @Test
    void itemComCodigoDeFornecedorJaAprendidoResolveSemEan() throws Exception {
        String token = assinarNovoTenant("itemaprendido");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token, "DAKOTA APRENDIDA", CNPJ_DAKOTA);
        long idProduto = criarProduto(token, "BOTA MISSISSIPI BERTELI PRETO 220 34 (JA CADASTRADA)");
        long idVariacao = criarVariacao(token, idProduto, null);

        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO produto_fornecedor (id_tenant, id_fornecedor, codigo_fornecedor, id_variacao)
                     VALUES (?, ?, ?, ?)
                     """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idFornecedor);
            ps.setString(3, CPROD_ITEM_1);
            ps.setLong(4, idVariacao);
            ps.executeUpdate();
        }

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlDakota()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item1 = JsonPath.read(resp, "$.itens[0]");
        assertThat((Boolean) item1.get("resolvido")).isTrue();
        assertThat(((Number) item1.get("idVariacao")).longValue()).isEqualTo(idVariacao);
    }

    /** Confirmar uma entrada com `codigoFornecedor` num item grava/atualiza
     *  `produto_fornecedor` — a próxima nota do mesmo fornecedor com esse `cProd` resolve
     *  sozinha (fecha o ciclo de aprendizado ponta a ponta, não só via SQL direto). */
    @Test
    void confirmarEntradaComCodigoDeFornecedorAprendeParaProximaVez() throws Exception {
        String token = assinarNovoTenant("aprendeconfirma");
        long idTenant = extrairIdTenant(token);
        long idFornecedor = criarFornecedor(token, "FORNECEDOR APRENDIZADO", "11222333000181");
        long idProduto = criarProduto(token, "PRODUTO APRENDIZADO");
        long idVariacao = criarVariacao(token, idProduto, null);

        mvc.perform(post("/api/v1/estoque/entradas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idFornecedor":%d,"itens":[{"idVariacao":%d,"qtd":3,"precoCusto":10.00,
                                 "codigoFornecedor":"COD-FORN-XYZ"}]}
                                """.formatted(idFornecedor, idVariacao)))
                .andExpect(status().isCreated());

        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id_variacao FROM produto_fornecedor
                     WHERE id_tenant = ? AND id_fornecedor = ? AND codigo_fornecedor = 'COD-FORN-XYZ'
                     """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idFornecedor);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(idVariacao);
            }
        }
    }

    /** NF-e REAL da A. Grings S.A. (2026-08-19) — motivou o pedido do dono do produto: um item
     *  cujo tamanho o palpite acerta ("34", último token numérico) mas a cor não (tenant novo
     *  não tem "PTO" cadastrado em `cfg_cor`, então {@code adivinharCor} não acha nada). O
     *  tamanho identificado deve sair da descrição ({@code nomeProduto}) — repeti-lo lá é ruído
     *  e faz cada tamanho da mesma peça parecer um produto diferente pro operador. Os 6 tamanhos
     *  (34–39) do mesmo `xProd` (só o tamanho final muda) devem sobrar com o MESMO
     *  {@code nomeProduto} depois de tirar o tamanho — é essa igualdade que o front usa pra
     *  propagar o produto recém-cadastrado pras linhas irmãs (ver {@code EntradaMercadoriaForm}
     *  no front, `aoCriarProdutoComGrade`). */
    @Test
    void itemComTamanhoIdentificadoSaiDaDescricaoESobraIgualNasVariacoesDeTamanho() throws Exception {
        String token = assinarNovoTenant("gringssapato");

        String resp = mvc.perform(multipart("/api/v1/estoque/entradas/xml/preview")
                        .file(xmlGrings()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> itens = JsonPath.read(resp, "$.itens");
        List<Map<String, Object>> sapatos147308 = itens.stream()
                .filter(i -> String.valueOf(i.get("nomeProduto")).startsWith("SAPATO 147308"))
                .toList();
        assertThat(sapatos147308).hasSize(6);

        for (Map<String, Object> item : sapatos147308) {
            assertThat((Boolean) item.get("resolvido")).isFalse();
            assertThat((String) item.get("nomeProduto")).isEqualTo("SAPATO 147308 NAP LUX LNH PTO/NAP STR SOF PTO SOLA");
            assertThat((Object) item.get("cor")).isNull();
        }
        assertThat(sapatos147308.stream().map(i -> (String) i.get("tamanho")))
                .containsExactlyInAnyOrder("34", "35", "36", "37", "38", "39");
    }
}
