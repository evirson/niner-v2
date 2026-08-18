package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importação de Produtos — coluna {@code TRIBUTACAO} (2026-08-19, docs/telas/importacao-dados.md).
 * Pedido do dono do produto: {@code NORMAL} = perfil "Revenda Tributada Normal",
 * {@code SUBSTITUICAO} = perfil "Revenda com Substituição Tributária (ST)", vazio = perfil "Não
 * Informado" (sentinela sem regra, semeado no signup junto dos outros dois — ver
 * {@code SignupService}/{@code PerfilFiscalCrudTest}) + aviso no relatório dizendo que o produto
 * não poderá emitir documento fiscal. <b>Achado ao vivo no mesmo dia:</b> a primeira versão só
 * aceitava código numérico (1/2) — a planilha real do usuário traz o texto (NORMAL/SUBSTITUICAO),
 * que virou o caminho principal; 1/2 continuam aceitos por compatibilidade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProdutoImportadorCrudTest {

    private static final String[] COLUNAS_PRODUTO = colunasProduto();

    private static String[] colunasProduto() {
        List<String> colunas = new ArrayList<>(List.of(
                "CODIGO_PRODUTO", "MARCA", "REFERENCIA", "DESCRICAO", "PRECO_CUSTO", "PERCENTUAL_VENDA", "PRECO_VENDA",
                "DATA_INICIO_OFERTA", "DATA_FINAL_OFERTA", "PRECO_OFERTA", "CODIGO_NCM", "TRIBUTACAO",
                "PESO_BRUTO", "PESO_LIQUIDO"));
        for (int i = 1; i <= 20; i++) {
            colunas.add("TAMANHO_" + i);
        }
        return colunas.toArray(new String[0]);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, long idTenant) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Tributacao %s","email":"dono%s@lojatributacao.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        return new TenantNovo(token, extrairIdTenant(token));
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    /** Uma linha de produto sem grade (tenant não usa cor/grade por padrão no signup), com
     *  {@code CODIGO_PRODUTO} e {@code TRIBUTACAO} informados — o resto fixo. */
    private MockMultipartFile planilhaComTributacao(String codigoProduto, String descricao, String tributacao) {
        byte[] bytes = ImportacaoPlanilha.gerarModelo(COLUNAS_PRODUTO, new String[] {
                codigoProduto, "MARCA X", "REF-" + codigoProduto, descricao, "10,00", "100", "20,00",
                "", "", "", "", tributacao == null ? "" : tributacao, "", ""
        });
        return new MockMultipartFile("arquivo", "produtos.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private String nomePerfilFiscalDoProduto(long idTenant, String codigoProduto) throws SQLException {
        try (Connection c = abrirConexao(idTenant);
                PreparedStatement ps = c.prepareStatement("""
                        SELECT pf.nome FROM produto p
                        LEFT JOIN cfg_perfil_fiscal pf
                          ON pf.id_tenant = p.id_tenant AND pf.id_perfil_fiscal = p.id_perfil_fiscal
                        WHERE p.id_tenant = ? AND p.codigo_importacao = ?
                        """)) {
            ps.setLong(1, idTenant);
            ps.setString(2, codigoProduto);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("produto %s importado", codigoProduto).isTrue();
                return rs.getString("nome");
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

    @Test
    void tributacaoNormalAtribuiPerfilRevendaTributadaNormal() throws Exception {
        TenantNovo tenant = assinarNovoTenant("trib-normal");

        mvc.perform(multipart("/api/v1/importacao/produto/processar")
                        .file(planilhaComTributacao("PROD-1", "PRODUTO NORMAL", "NORMAL"))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros").isEmpty())
                .andExpect(jsonPath("$.confirmado").value(true));

        assertThat(nomePerfilFiscalDoProduto(tenant.idTenant(), "PROD-1")).isEqualTo("REVENDA TRIBUTADA NORMAL");
    }

    @Test
    void tributacaoSubstituicaoAtribuiPerfilSubstituicaoTributaria() throws Exception {
        TenantNovo tenant = assinarNovoTenant("trib-st");

        mvc.perform(multipart("/api/v1/importacao/produto/processar")
                        .file(planilhaComTributacao("PROD-2", "PRODUTO ST", "SUBSTITUICAO"))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros").isEmpty())
                .andExpect(jsonPath("$.confirmado").value(true));

        assertThat(nomePerfilFiscalDoProduto(tenant.idTenant(), "PROD-2"))
                .isEqualTo("REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA (ST)");
    }

    /** Minúsculo, com acento, e o código numérico antigo (1/2) — todos precisam continuar
     *  funcionando, não só o texto maiúsculo sem acento do exemplo da planilha modelo. */
    @Test
    void tributacaoAceitaMinusculoAcentoECodigoNumerico() throws Exception {
        TenantNovo tenant = assinarNovoTenant("trib-variantes");

        mvc.perform(multipart("/api/v1/importacao/produto/processar")
                        .file(planilhaComTributacao("PROD-5", "PRODUTO MINUSCULO", "normal"))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmado").value(true));
        assertThat(nomePerfilFiscalDoProduto(tenant.idTenant(), "PROD-5")).isEqualTo("REVENDA TRIBUTADA NORMAL");

        mvc.perform(multipart("/api/v1/importacao/produto/processar")
                        .file(planilhaComTributacao("PROD-6", "PRODUTO ACENTO", "Substituição"))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmado").value(true));
        assertThat(nomePerfilFiscalDoProduto(tenant.idTenant(), "PROD-6"))
                .isEqualTo("REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA (ST)");

        mvc.perform(multipart("/api/v1/importacao/produto/processar")
                        .file(planilhaComTributacao("PROD-7", "PRODUTO CODIGO 1", "1"))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmado").value(true));
        assertThat(nomePerfilFiscalDoProduto(tenant.idTenant(), "PROD-7")).isEqualTo("REVENDA TRIBUTADA NORMAL");
    }

    /** Sem TRIBUTACAO: produto ainda é importado (não é erro bloqueante), mas recebe o perfil
     *  sentinela e o relatório avisa — não pode ficar em silêncio que a nota não vai sair. */
    @Test
    void tributacaoAusenteAtribuiPerfilNaoInformadoEAvisa() throws Exception {
        TenantNovo tenant = assinarNovoTenant("trib-vazia");

        String resp = mvc.perform(multipart("/api/v1/importacao/produto/processar")
                        .file(planilhaComTributacao("PROD-3", "PRODUTO SEM TRIBUTACAO", null))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros").isEmpty())
                .andExpect(jsonPath("$.confirmado").value(true))
                .andExpect(jsonPath("$.avisos[0]", containsString("NÃO INFORMADO")))
                .andExpect(jsonPath("$.avisos[0]", containsString("não poderão")))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(resp, "$.avisos[0]")).contains("1 produto(s)");
        assertThat(nomePerfilFiscalDoProduto(tenant.idTenant(), "PROD-3")).isEqualTo("NÃO INFORMADO");
    }

    @Test
    void tributacaoForaDoDominioEhRejeitada() throws Exception {
        TenantNovo tenant = assinarNovoTenant("trib-invalida");

        mvc.perform(multipart("/api/v1/importacao/produto/processar")
                        .file(planilhaComTributacao("PROD-4", "PRODUTO TRIBUTACAO INVALIDA", "3"))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros[0].motivo", containsString("TRIBUTACAO")))
                .andExpect(jsonPath("$.confirmado").value(false));
    }
}
