package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cancelamento de NFC-e — evento 110111 (§10.1, bloco B8), disparado pela tela de Cancelamento de
 * Venda que já existia. O que importa provar aqui: estoque/caixa/fiscal <b>nunca divergem</b> — a
 * SEFAZ é consultada antes de reverter qualquer coisa, e uma recusa não deixa rastro nenhum de
 * reversão (nem estoque, nem caixa, nem {@code venda.cancelada}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CancelamentoNfceTest {

    private static final String SENHA_CERTIFICADO = "senha-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    SefazTransporte transporte;

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- fixture (mesmo recipe de VendaFiscalEmissaoTest)

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Cancelamento %s","email":"dono%s@lojacancelnfce.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static long idEmpresaDo(String token) {
        return ((Number) JsonPath.read(payload(token), "$.eid")).longValue();
    }

    private static long idTenantDo(String token) {
        return ((Number) JsonPath.read(payload(token), "$.tid")).longValue();
    }

    private void completarDadosDaEmpresa(long idTenant, long idEmpresa, String cnpj) {
        jdbc.sql("""
                        UPDATE empresa SET cnpj = ?, inscricao_estadual = '1234567890',
                            codigo_municipio_ibge = 4106902, cidade = 'CURITIBA', estado = 'PR',
                            endereco = 'RUA TESTE', numero = '100', bairro = 'CENTRO', cep = '80000000',
                            cnae = '4781400'
                        WHERE id_tenant = ? AND id_empresa = ?
                        """)
                .params(cnpj, idTenant, idEmpresa).update();
    }

    private void ligarFiscal(String token, long idEmpresa) throws Exception {
        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crt":1,"emiteNfce":true,"emiteNfe":false,
                                 "ambiente":"HOMOLOGACAO","serieNfce":1,"serieNfe":1,"serieContingencia":9}
                                """))
                .andExpect(status().isOk());
    }

    private byte[] gerarPfx(String cnpj) throws Exception {
        Path arquivo = tempDir.resolve("cert-" + cnpj + ".pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process processo = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "cancel-" + cnpj, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=EMPRESA CANCELAMENTO LTDA:" + cnpj + ", O=TESTE, C=BR",
                "-keystore", arquivo.toString(), "-storetype", "PKCS12",
                "-storepass", SENHA_CERTIFICADO, "-keypass", SENHA_CERTIFICADO)
                .redirectErrorStream(true).start();
        String saida = new String(processo.getInputStream().readAllBytes());
        if (processo.waitFor() != 0) {
            throw new IllegalStateException("keytool falhou: " + saida);
        }
        return Files.readAllBytes(arquivo);
    }

    private void enviarCertificado(String token, long idEmpresa, String cnpj) throws Exception {
        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", gerarPfx(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA_CERTIFICADO)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    private long criarPerfilFiscalCsosn102(long idTenant) {
        long idPerfil = jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome) VALUES (?, 'PERFIL CANCELAMENTO NFCE')
                        RETURNING id_perfil_fiscal
                        """)
                .param(idTenant).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal_regra
                            (id_tenant, id_perfil_fiscal, crt, uf_destino, tipo_destinatario, tipo_operacao,
                             cfop, csosn, cst_pis, cst_cofins)
                        VALUES (?, ?, 1, '*', 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5102', '102', '99', '99')
                        """)
                .params(idTenant, idPerfil).update();
        return idPerfil;
    }

    private long criarProdutoComPerfil(String token, long idTenant, String descricao, long idPerfil,
                                       String precoVenda) throws Exception {
        jdbc.sql("INSERT INTO cfg_produto_ncm (codigo_ncm, descricao_ncm) VALUES ('61091000', 'NCM TESTE') "
                        + "ON CONFLICT DO NOTHING")
                .update();
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100",
                                 "precoVenda":"%s","codigoNcm":"61091000"}
                                """.formatted(descricao, precoVenda)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idProduto = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        jdbc.sql("UPDATE produto SET id_perfil_fiscal = ? WHERE id_tenant = ? AND id_produto = ?")
                .params(idPerfil, idTenant, idProduto).update();

        return jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, sku)
                        VALUES (?, ?, gerar_ean13_interno())
                        RETURNING id_variacao
                        """)
                .params(idTenant, idProduto).query(Long.class).single();
    }

    private long criarClienteAnonimo(String token, String nome) throws Exception {
        long idCategoria = criarCategoriaCliente(token, "PADRAO " + nome);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"fisicaJuridica\":false,\"nome\":\"%s\",\"idCategoriaCliente\":%d}"
                                .formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private long criarCategoriaCliente(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoriaCliente")).longValue();
    }

    private long criarFuncionario(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long carteiraDinheiroComTpag(String token, long idTenant) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
        jdbc.sql("UPDATE tipo_carteira SET codigo_tpag = '01' WHERE id_tenant = ? AND id_carteira = ?")
                .params(idTenant, idCarteira).update();
        return idCarteira;
    }

    private void abrirCaixa(String token, long idCarteira) throws Exception {
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                               long idCarteira, String valor) throws Exception {
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,
                                 "idCliente":%d,"idFuncionario":%d,
                                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                                """.formatted(idVariacao, idCliente, idFuncionario, idCarteira, valor)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    private static RespostaSefaz autorizada(String chave) {
        return new RespostaSefaz(200, "100", "Autorizado o uso da NF-e", "141260001999999", chave,
                "<retEnviNFe><protNFe><infProt><cStat>100</cStat></infProt></protNFe></retEnviNFe>");
    }

    private static String extrairChaveDoEnvi(String enviNFe) {
        int inicio = enviNFe.indexOf("Id=\"NFe") + "Id=\"NFe".length();
        int fim = enviNFe.indexOf('"', inicio);
        return enviNFe.substring(inicio, fim);
    }

    /** Monta o cenário completo: fiscal ligado, venda efetivada, NFC-e autorizada. Devolve o
     *  {@code idVenda} pronto para o teste chamar o cancelamento. */
    private long vendaComNfceAutorizada(String token, long idTenant, long idEmpresa, String cnpj) throws Exception {
        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO CANCELAMENTO NFCE", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Cancelamento");
        long idFuncionario = criarFuncionario(token, "Vendedor Cancelamento");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));
        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        Mockito.reset(transporte);
        return idVenda;
    }

    // ---------------------------------------------------------------- casos

    @Test
    void cancelamentoAutorizadoPelaSefazReverteVendaEMarcaDocumentoCancelado() throws Exception {
        String token = assinarNovoTenant("autorizado");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceAutorizada(token, idTenant, idEmpresa, "11222333000181");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "135", "Evento registrado e vinculado a NF-e",
                        "141260009999999", null, "<retEnvEvento><retEvento><infEvento><cStat>135</cStat></infEvento></retEvento></retEnvEvento>"));

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"Cliente desistiu da compra, forma de pagamento errada\"}"))
                .andExpect(status().isOk());

        Boolean cancelada = jdbc.sql("SELECT cancelada FROM venda WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Boolean.class).single();
        assertThat(cancelada).as("a venda reverteu porque a SEFAZ autorizou o cancelamento").isTrue();

        Map<String, Object> doc = jdbc.sql("""
                        SELECT situacao FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?
                        """)
                .params(idTenant, idVenda)
                .query((rs, n) -> Map.of("situacao", (Object) rs.getString("situacao")))
                .single();
        assertThat(doc.get("situacao")).isEqualTo("CANCELADO");

        Integer eventos = jdbc.sql("""
                        SELECT count(*)::int FROM documento_fiscal_evento e
                          JOIN documento_fiscal d ON d.id_documento_fiscal = e.id_documento_fiscal AND d.id_tenant = e.id_tenant
                         WHERE e.id_tenant = ? AND d.id_venda = ? AND e.tipo_evento = '110111' AND e.autorizado = true
                        """)
                .params(idTenant, idVenda).query(Integer.class).single();
        assertThat(eventos).as("o evento fica registrado (P3), autorizado").isEqualTo(1);
    }

    /**
     * O caso mais importante: SEFAZ recusa o cancelamento. Nada pode ter revertido — nem
     * {@code venda.cancelada}, nem estoque, nem caixa. "Estoque/caixa e fiscal nunca divergem."
     */
    @Test
    void cancelamentoRecusadoPelaSefazNaoReverteNada() throws Exception {
        String token = assinarNovoTenant("recusado");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceAutorizada(token, idTenant, idEmpresa, "22333444000162");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "573", "Rejeicao: prazo de cancelamento excedido",
                        null, null, "<retEnvEvento><retEvento><infEvento><cStat>573</cStat></infEvento></retEvento></retEnvEvento>"));

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"Cliente desistiu da compra, forma de pagamento errada\"}"))
                .andExpect(status().isConflict());

        Boolean cancelada = jdbc.sql("SELECT cancelada FROM venda WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Boolean.class).single();
        assertThat(cancelada).as("SEFAZ recusou — a venda continua intacta").isFalse();

        String situacao = jdbc.sql("SELECT situacao FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(String.class).single();
        assertThat(situacao).as("o documento fiscal continua AUTORIZADO, não vira CANCELADO").isEqualTo("AUTORIZADO");

        Integer eventosRecusados = jdbc.sql("""
                        SELECT count(*)::int FROM documento_fiscal_evento e
                          JOIN documento_fiscal d ON d.id_documento_fiscal = e.id_documento_fiscal AND d.id_tenant = e.id_tenant
                         WHERE e.id_tenant = ? AND d.id_venda = ? AND e.autorizado = false
                        """)
                .params(idTenant, idVenda).query(Integer.class).single();
        assertThat(eventosRecusados).as("a tentativa recusada também fica registrada (P3)").isEqualTo(1);
    }

    /** Prazo de 30 min vencido: nem tenta a SEFAZ, bloqueia e ensina a saída (nota de devolução). */
    @Test
    void prazoVencidoBloqueiaSemChamarASefaz() throws Exception {
        String token = assinarNovoTenant("prazo-vencido");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceAutorizada(token, idTenant, idEmpresa, "33444555000143");

        jdbc.sql("""
                        UPDATE documento_fiscal SET data_autorizacao = now() - interval '2 hours'
                         WHERE id_tenant = ? AND id_venda = ?
                        """)
                .params(idTenant, idVenda).update();

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"Cliente desistiu da compra, forma de pagamento errada\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("nota de devolução")));

        Mockito.verifyNoInteractions(transporte);

        Boolean cancelada = jdbc.sql("SELECT cancelada FROM venda WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Boolean.class).single();
        assertThat(cancelada).isFalse();
    }

    /** Venda sem NFC-e (fiscal desligado): cancelamento segue o fluxo de sempre, sem chamar SEFAZ. */
    @Test
    void vendaSemNfceCancelaNormalmenteSemTocarNaSefaz() throws Exception {
        String token = assinarNovoTenant("sem-nfce");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        completarDadosDaEmpresa(idTenant, idEmpresa, "44555666000124");
        // NÃO liga o fiscal — venda sem NFC-e nenhuma.
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO SEM FISCAL CANCEL", idPerfil, "20.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Sem Fiscal Cancel");
        long idFuncionario = criarFuncionario(token, "Vendedor Sem Fiscal Cancel");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "20.00");

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"Cliente desistiu da compra, forma de pagamento errada\"}"))
                .andExpect(status().isOk());

        Mockito.verifyNoInteractions(transporte);

        Boolean cancelada = jdbc.sql("SELECT cancelada FROM venda WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Boolean.class).single();
        assertThat(cancelada).isTrue();
    }
}
