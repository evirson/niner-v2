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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importação de Contas a Receber (crediário).
 *
 * <p>⛔ <b>Este arquivo existe porque o recurso não tinha NENHUM teste</b>, e foi exatamente isso
 * que deixou passar, com 1049 testes verdes, um defeito que quebrava a importação inteira: ao ligar
 * as colunas {@code VALOR_JUROS}/{@code VALOR_DESCONTO} (auditoria 2026-08-29, rodada 2), os dois
 * campos entraram na lista de <b>parâmetros</b> e não na lista de <b>colunas</b> nem no
 * {@code VALUES} — 9 colunas, 8 {@code ?}, 10 parâmetros. Toda linha estouraria
 * <i>"The column index is out of range"</i>, o relatório sairia <b>0 importadas / N erros</b>, e
 * cada grupo (cliente, empresa) ainda deixaria uma <b>venda-casca órfã</b> no banco, porque ela é
 * criada antes do laço de parcelas e tem savepoint próprio.
 *
 * <p>⭐ Por isso os testes daqui conferem o <b>banco</b>, não o status HTTP: um teste que valida
 * "200 OK" passaria com o defeito presente, já que os erros vêm dentro do corpo do relatório.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ContasReceberImportadorCrudTest {

    private static final String[] COLUNAS = {
            "CPF_CNPJ", "EMPRESA", "NUMERO_PARCELA", "DATA_VENCIMENTO", "DATA_RECEBIMENTO",
            "VALOR_RECEBER", "VALOR_JUROS", "VALOR_DESCONTO", "VALOR_RECEBIDO",
    };

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, long idTenant) {
    }

    private TenantNovo assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja CR %s","email":"dono%s@lojacr.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        return new TenantNovo(token, ((Number) JsonPath.read(payload, "$.tid")).longValue());
    }

    /** CPF válido — o importador resolve o cliente por documento contra a base ao vivo. */
    private static final String CPF = "123.456.789-09";

    private long criarCliente(String token) throws Exception {
        String cat = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"GERAL\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long idCategoria = ((Number) JsonPath.read(cat, "$.idCategoriaCliente")).longValue();

        String body = """
                {"fisicaJuridica":true,"nome":"CLIENTE CREDIARIO","idCategoriaCliente":%d,
                 "cpfCnpj":"%s","genero":"MASCULINO"}
                """.formatted(idCategoria, CPF);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    /** Uma carteira de crediário para o arquivo inteiro — o importador exige a escolha. */
    private long criarCarteiraCrediario(String token) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CREDIARIO IMPORTADO","categoriaCarteira":"CREDIARIO",
                                 "prazoPagamento":30,"pcMinima":1,"pcMaxima":12,
                                 "taxaAdministradora":0,"permiteReceberCrediario":true}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
    }

    /** {@code escolhas} é um @RequestPart, não um parâmetro de query — a carteira de crediário
     *  vale para o arquivo inteiro. */
    private MockMultipartFile escolhas(long idCarteira) {
        return new MockMultipartFile("escolhas", "", "application/json",
                "{\"idCarteira\":%d}".formatted(idCarteira).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private MockMultipartFile planilha(String[]... linhas) {
        return new MockMultipartFile("arquivo", "contas_receber.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ImportacaoPlanilha.gerarModelo(COLUNAS, linhas));
    }

    /**
     * ⭐ O caso que o defeito quebrava: uma parcela com juros e desconto, importada e <b>conferida
     * no banco</b>. Com o INSERT desalinhado, o relatório traz erro em vez de importar.
     */
    @Test
    void importaJurosEDescontoEConfereNoBanco() throws Exception {
        TenantNovo tenant = assinarNovoTenant("juros");
        criarCliente(tenant.token());
        long idCarteira = criarCarteiraCrediario(tenant.token());

        mvc.perform(multipart("/api/v1/importacao/contas_receber/processar")
                        .file(planilha(new String[] {
                                CPF, "1", "1", "10/09/2026", "15/09/2026", "150,00", "7,50", "2,50", "155,00"}))
                        .file(escolhas(idCarteira))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros").isEmpty())
                .andExpect(jsonPath("$.linhasImportadas").value(1));

        try (Connection c = abrirConexao(tenant.idTenant());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("""
                        SELECT valor_receber, valor_juros, valor_desconto, valor_recebido, documento_recebido
                          FROM contas_receber ORDER BY id_conta_receber DESC LIMIT 1
                        """)) {
            assertThat(rs.next()).as("a parcela foi gravada").isTrue();
            assertThat(rs.getBigDecimal("valor_receber")).isEqualByComparingTo("150.00");
            assertThat(rs.getBigDecimal("valor_juros"))
                    .as("VALOR_JUROS é oferecido no modelo de planilha; ignorá-lo apaga o dado em silêncio")
                    .isEqualByComparingTo("7.50");
            assertThat(rs.getBigDecimal("valor_desconto")).isEqualByComparingTo("2.50");
            assertThat(rs.getBigDecimal("valor_recebido")).isEqualByComparingTo("155.00");
            assertThat(rs.getBoolean("documento_recebido")).isTrue();
        }
    }

    /**
     * ⚠️ Recebimento COM data e SEM valor é o caso normal de quem pagou o valor de face — gravava
     * a parcela como recebida por <b>zero</b>, e ela aparecia assim no Histórico do Cliente.
     */
    @Test
    void recebimentoSemValorUsaOValorDaParcela() throws Exception {
        TenantNovo tenant = assinarNovoTenant("face");
        criarCliente(tenant.token());
        long idCarteira = criarCarteiraCrediario(tenant.token());

        mvc.perform(multipart("/api/v1/importacao/contas_receber/processar")
                        .file(planilha(new String[] {CPF, "1", "1", "10/09/2026", "10/09/2026", "200,00", "", "", ""}))
                        .file(escolhas(idCarteira))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasImportadas").value(1));

        try (Connection c = abrirConexao(tenant.idTenant());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("""
                        SELECT valor_recebido, documento_recebido FROM contas_receber
                        ORDER BY id_conta_receber DESC LIMIT 1
                        """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal("valor_recebido"))
                    .as("sem valor digitado, o recebido é o que a parcela valia")
                    .isEqualByComparingTo("200.00");
            assertThat(rs.getBoolean("documento_recebido")).isTrue();
        }
    }

    /**
     * ⭐ O par NEGATIVO: parcela <b>em aberto</b> (sem data de recebimento) não pode nascer marcada
     * como recebida nem com valor recebido. Sem este caso, uma versão que marcasse tudo como pago
     * passaria nos dois testes acima.
     */
    @Test
    void parcelaEmAbertoNaoNasceRecebida() throws Exception {
        TenantNovo tenant = assinarNovoTenant("aberta");
        criarCliente(tenant.token());
        long idCarteira = criarCarteiraCrediario(tenant.token());

        mvc.perform(multipart("/api/v1/importacao/contas_receber/processar")
                        .file(planilha(new String[] {CPF, "1", "1", "10/12/2026", "", "300,00", "", "", ""}))
                        .file(escolhas(idCarteira))
                        .param("confirmar", "true")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhasImportadas").value(1));

        try (Connection c = abrirConexao(tenant.idTenant());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("""
                        SELECT valor_recebido, documento_recebido, data_recebimento FROM contas_receber
                        ORDER BY id_conta_receber DESC LIMIT 1
                        """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal("valor_recebido")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rs.getBoolean("documento_recebido")).isFalse();
            assertThat(rs.getObject("data_recebimento")).isNull();
        }
    }

    /** Conexão como {@code niner_owner} com o tenant no contexto — o {@code FORCE RLS} esconde
     *  as linhas de quem não o define, inclusive do dono da tabela. */
    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.id_tenant', ?, false)")) {
            ps.setString(1, String.valueOf(idTenant));
            ps.execute();
        }
        return c;
    }
}
