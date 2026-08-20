package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rotina de Importação de Dados — tabela "estoque" (docs/telas/importacao-dados.md, EstoqueImportador).
 *
 * <p><b>Bug real corrigido em 2026-08-19</b> (achado ao vivo pelo usuário importando uma planilha
 * real): duas linhas do MESMO produto SEM grade real (id_grade PADRÃO), com {@code NOME_COR}/
 * {@code NOME_TAMANHO} textualmente diferentes entre si (comum em planilha migrada de sistema
 * legado — algumas linhas em branco, outras com um texto qualquer tipo "ÚNICO"), formavam DOIS
 * grupos diferentes em {@code EstoqueImportador} (a chave do grupo usava o texto cru, sem
 * considerar que o produto ia colapsar pra cor/tamanho PADRÃO). O 1º grupo criava a variação
 * (id_cor=1, id_tamanho=1 — sentinela); o 2º tentava criar a MESMA variação de novo e batia em
 * "duplicate key value violates unique constraint produto_barra_variacao_uk" — exatamente o erro
 * relatado ("PreparedStatementCallback; SQL [INSERT INTO produto_barra ...]"), que aparecia como
 * linha de erro no relatório mesmo com {@code confirmar=true} porque
 * {@code RelatorioImportacao.concluir} só confirma quando a lista de erros está vazia — qualquer
 * erro faz {@code processar()} lançar {@code SimulacaoConcluidaException} e nada é gravado.
 * Corrigido forçando cor/tamanho a {@code null} ANTES de montar a chave do grupo, quando o produto
 * não tem grade real — mesma regra que já valia dentro de
 * {@code ProdutoBarraService.criarParaImportacaoEmMassa}, agora também no agrupamento.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EstoqueImportadorCrudTest {

    private static final String[] COLUNAS_PRODUTO = colunasProduto();
    private static final String[] COLUNAS_ESTOQUE = {
            "CODIGO_PRODUTO", "EAN_CODIGO_BARRAS", "NOME_COR", "NOME_TAMANHO",
            "QUANTIDADE_ESTOQUE_1", "QUANTIDADE_ESTOQUE_2", "QUANTIDADE_ESTOQUE_3",
            "QUANTIDADE_ESTOQUE_4", "QUANTIDADE_ESTOQUE_5"
    };

    private static String[] colunasProduto() {
        List<String> colunas = new ArrayList<>(List.of(
                "CODIGO_PRODUTO", "MARCA", "REFERENCIA", "DESCRICAO", "PRECO_CUSTO", "PERCENTUAL_VENDA", "PRECO_VENDA",
                "DATA_INICIO_OFERTA", "DATA_FINAL_OFERTA", "PRECO_OFERTA", "CODIGO_NCM", "PESO_BRUTO", "PESO_LIQUIDO"));
        for (int i = 1; i <= 20; i++) {
            colunas.add("TAMANHO_" + i);
        }
        return colunas.toArray(new String[0]);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, long idTenant, long idEmpresa) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Estoque %s","email":"dono%s@lojaestoque.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");

        String empresasResp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(empresasResp, "$[0].idEmpresa")).longValue();
        return new TenantNovo(token, extrairIdTenant(token), idEmpresa);
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    private MockMultipartFile planilhaProduto(String[]... linhas) {
        byte[] bytes = ImportacaoPlanilha.gerarModelo(COLUNAS_PRODUTO, linhas);
        return new MockMultipartFile("arquivo", "produtos.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private MockMultipartFile planilhaEstoque(String[]... linhas) {
        byte[] bytes = ImportacaoPlanilha.gerarModelo(COLUNAS_ESTOQUE, linhas);
        return new MockMultipartFile("arquivo", "estoque.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    /** Importa 1 produto SEM grade (nenhum TAMANHO_N preenchido — tenant não usa cor/grade por
     *  padrão no signup) com o {@code CODIGO_PRODUTO} informado. */
    private void importarProdutoSemGrade(String token, String codigoProduto, String descricao) throws Exception {
        MockMultipartFile planilha = planilhaProduto(new String[] {
                codigoProduto, "MARCA X", "REF-" + codigoProduto, descricao, "10,00", "100", "20,00",
                "", "", "", "", "", ""
        });
        mvc.perform(multipart("/api/v1/importacao/produto/processar")
                        .file(planilha)
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros").isEmpty())
                .andExpect(jsonPath("$.confirmado").value(true));
    }

    @Test
    void produtoSemGradeComTextosDeCorETamanhoDiferentesEntreLinhasNaoDuplicaVariacao() throws Exception {
        TenantNovo tenant = assinarNovoTenant("semgrade-textos");
        importarProdutoSemGrade(tenant.token(), "PROD-1", "PRODUTO SEM GRADE");

        // Mesmo produto (sem grade real), duas linhas com NOME_COR/NOME_TAMANHO textualmente
        // diferentes — colapsam pra MESMA variação (id_cor=1, id_tamanho=1) porque o produto não
        // tem grade real. Antes da correção, isso batia em "duplicate key" na 2ª linha.
        MockMultipartFile planilhaEstoque = planilhaEstoque(
                new String[] {"PROD-1", "", "", "", "10", "", "", "", ""},
                new String[] {"PROD-1", "", "UNICO", "UN", "5", "", "", "", ""});

        String escolhas = "{\"mapeamentoEmpresas\":{\"QUANTIDADE_ESTOQUE_1\":" + tenant.idEmpresa() + "}}";

        String resp = mvc.perform(multipart("/api/v1/importacao/estoque/processar")
                        .file(planilhaEstoque)
                        .part(new MockPart("escolhas", escolhas.getBytes()))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros").isEmpty())
                .andExpect(jsonPath("$.confirmado").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(resp, "$.linhasImportadas")).intValue()).isEqualTo(2);

        try (Connection c = abrirConexao(tenant.idTenant())) {
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT id_variacao, id_cor, id_tamanho FROM produto_barra pb "
                                    + "JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant "
                                    + "WHERE p.id_tenant = " + tenant.idTenant() + " AND p.codigo_importacao = 'PROD-1'")) {
                int total = 0;
                while (rs.next()) {
                    total++;
                    assertThat(rs.getLong("id_cor")).isEqualTo(1L);
                    assertThat(rs.getLong("id_tamanho")).isEqualTo(1L);
                }
                assertThat(total).isEqualTo(1);
            }

            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT SUM(pmd.qtd_produto) AS total FROM produto_movimento_detalhe pmd
                    JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                    JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                    WHERE p.id_tenant = ? AND p.codigo_importacao = 'PROD-1'
                    """)) {
                ps.setLong(1, tenant.idTenant());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBigDecimal("total")).isEqualByComparingTo("15.000");
                }
            }
        }
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    /**
     * ⛔ Código de barras na faixa do gerador do Nainer **derruba a planilha inteira** (2026-08-20,
     * decisão do dono do produto).
     *
     * <p><b>Por que a planilha toda, e não a linha.</b> O lojista que migra de outro sistema traz
     * códigos <b>já impressos nas etiquetas</b>. Um deles na nossa faixa colidiria com um SKU que
     * {@code gerar_ean13_interno()} ainda vai emitir — o sequencial <b>cresce</b>, então o conflito
     * nasce meses depois, numa bipada que traz o produto errado no caixa. Importar metade e avisar
     * deixaria o lojista sem saber qual metade entrou: "pra não gerar mal-entendidos".
     */
    @Test
    void codigoDeBarrasNaFaixaDoNainerDerrubaAPlanilhaInteira() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ean-faixa-interna");
        importarProdutoSemGrade(tenant.token(), "PROD-A", "PRODUTO A");
        importarProdutoSemGrade(tenant.token(), "PROD-B", "PRODUTO B");

        // Linha 1 é perfeitamente válida; a 2ª traz um código começando por 9.
        MockMultipartFile planilha = planilhaEstoque(
                new String[] {"PROD-A", "7891234567895", "", "", "10", "", "", "", ""},
                new String[] {"PROD-B", "9001000041032", "", "", "7", "", "", "", ""});
        String escolhas = "{\"mapeamentoEmpresas\":{\"QUANTIDADE_ESTOQUE_1\":" + tenant.idEmpresa() + "}}";

        String erro = mvc.perform(multipart("/api/v1/importacao/estoque/processar")
                        .file(planilha)
                        .part(new MockPart("escolhas", escolhas.getBytes()))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // A mensagem tem de dizer QUAL linha, senão o lojista não sabe o que corrigir na planilha.
        assertThat(erro).contains("Nada foi importado").contains("9001000041032");

        // E nada entrou — nem a linha boa. É o ponto do "barrar".
        try (Connection c = abrirConexao(tenant.idTenant());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT count(*) AS n FROM produto_barra WHERE id_tenant = " + tenant.idTenant())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("n")).as("nenhuma variação criada").isZero();
        }
    }

    /** O caminho normal continua passando: EAN de fabricante (789…) não é da nossa faixa. */
    @Test
    void codigoDeBarrasDeFabricanteContinuaImportando() throws Exception {
        TenantNovo tenant = assinarNovoTenant("ean-fabricante-ok");
        importarProdutoSemGrade(tenant.token(), "PROD-C", "PRODUTO C");

        MockMultipartFile planilha = planilhaEstoque(
                new String[] {"PROD-C", "7891234567895", "", "", "3", "", "", "", ""});
        String escolhas = "{\"mapeamentoEmpresas\":{\"QUANTIDADE_ESTOQUE_1\":" + tenant.idEmpresa() + "}}";

        mvc.perform(multipart("/api/v1/importacao/estoque/processar")
                        .file(planilha)
                        .part(new MockPart("escolhas", escolhas.getBytes()))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros").isEmpty())
                .andExpect(jsonPath("$.confirmado").value(true));

        try (Connection c = abrirConexao(tenant.idTenant());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT ean FROM produto_barra WHERE id_tenant = " + tenant.idTenant())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("ean")).isEqualTo("7891234567895");
        }
    }
}
