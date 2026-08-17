package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
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
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documentos Fiscais (§12, bloco B8) — lista com filtros, ver XML, consultar na SEFAZ.
 * Fixture crua via SQL direto (não passa pela emissão real) porque o que este teste cobre é a
 * <b>leitura</b>, já com a emissão coberta à exaustão por {@code VendaFiscalEmissaoTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentoFiscalListaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    @TempDir
    Path tempDir;

    private static final String SENHA_CERTIFICADO = "senha-teste-123";

    @MockitoBean
    SefazTransporte transporte;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Documentos Fiscais %s","email":"dono%s@lojadocfiscal.com",
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

    private String comoOperador(String tokenAdmin) {
        String p = payload(tokenAdmin);
        return tokens.emitir(
                Long.parseLong(JsonPath.read(p, "$.sub").toString()),
                ((Number) JsonPath.read(p, "$.tid")).longValue(),
                ((Number) JsonPath.read(p, "$.eid")).longValue(),
                JsonPath.read(p, "$.email"),
                List.of("OPERADOR"));
    }

    /** Grava um {@code documento_fiscal} AUTORIZADO direto no banco, sem passar pela emissão. */
    private byte[] gerarPfx(String cnpj) throws Exception {
        Path arquivo = tempDir.resolve("cert-" + cnpj + ".pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process processo = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "doc-" + cnpj, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=EMPRESA DOCUMENTOS FISCAIS LTDA:" + cnpj + ", O=TESTE, C=BR",
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

    private long criarDocumentoFiscal(long idTenant, long idEmpresa, String chave) {
        jdbc.sql("""
                        UPDATE empresa SET estado = 'PR' WHERE id_tenant = ? AND id_empresa = ?
                        """)
                .params(idTenant, idEmpresa).update();
        String xml = "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe Id=\"NFe%s\"/></NFe>".formatted(chave);
        return jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso, codigo_numerico,
                            digito_verificador, tipo_operacao, situacao, ambiente, tipo_emissao,
                            data_emissao, valor_produtos, valor_desconto, valor_outros, valor_total,
                            valor_troco, xml_assinado, protocolo, data_autorizacao)
                        VALUES (?, ?, 65, 1, 1, ?, '12345678', 9, 'VENDA_CONSUMIDOR', 'AUTORIZADO',
                                'HOMOLOGACAO', 1, now(), 30.00, 0, 0, 30.00, 0,
                                ?, '141260001999999', now())
                        RETURNING id_documento_fiscal
                        """)
                .params(idTenant, idEmpresa, chave, xml).query(Long.class).single();
    }

    @Test
    void listaFiltraPorPeriodoEModelo() throws Exception {
        String token = assinarNovoTenant("lista");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        criarDocumentoFiscal(idTenant, idEmpresa, "41260837829453000135650010000000011123456781");

        mvc.perform(get("/api/v1/fiscal/documentos")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", LocalDate.now().minusDays(1).toString())
                        .param("dataFinal", LocalDate.now().plusDays(1).toString())
                        .param("modelo", "65"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1))
                .andExpect(jsonPath("$.itens[0].situacao").value("AUTORIZADO"))
                .andExpect(jsonPath("$.itens[0].chaveAcesso").value("41260837829453000135650010000000011123456781"));

        // Modelo 55 não existe nesta massa — a lista fica vazia sem erro.
        mvc.perform(get("/api/v1/fiscal/documentos")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", LocalDate.now().minusDays(1).toString())
                        .param("dataFinal", LocalDate.now().plusDays(1).toString())
                        .param("modelo", "55"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));
    }

    @Test
    void listaExtraiLinkDeConsultaPublicaDoQrCodeQuandoExiste() throws Exception {
        String token = assinarNovoTenant("link-publico");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String chave = "41260837829453000135650010000000091123456789";
        jdbc.sql("UPDATE empresa SET estado = 'PR' WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa).update();
        String xml = ("<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe Id=\"NFe%s\"/>"
                + "<infNFeSupl><qrCode><![CDATA[http://www.fazenda.pr.gov.br/nfce/qrcode?p=%s]]></qrCode></infNFeSupl></NFe>")
                .formatted(chave, chave);
        jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso, codigo_numerico,
                            digito_verificador, tipo_operacao, situacao, ambiente, tipo_emissao,
                            data_emissao, valor_produtos, valor_desconto, valor_outros, valor_total,
                            valor_troco, xml_assinado, protocolo, data_autorizacao)
                        VALUES (?, ?, 65, 1, 9, ?, '12345678', 9, 'VENDA_CONSUMIDOR', 'AUTORIZADO',
                                'HOMOLOGACAO', 1, now(), 30.00, 0, 0, 30.00, 0,
                                ?, '141260001999999', now())
                        """)
                .params(idTenant, idEmpresa, chave, xml).update();

        mvc.perform(get("/api/v1/fiscal/documentos")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", LocalDate.now().minusDays(1).toString())
                        .param("dataFinal", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].urlConsultaPublica")
                        .value("http://www.fazenda.pr.gov.br/nfce/qrcode?p=" + chave));
    }

    @Test
    void verXmlDevolveOXmlAssinado() throws Exception {
        String token = assinarNovoTenant("ver-xml");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idDoc = criarDocumentoFiscal(idTenant, idEmpresa, "41260837829453000135650010000000021123456782");

        mvc.perform(get("/api/v1/fiscal/documentos/" + idDoc + "/xml").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.xml").value(org.hamcrest.Matchers.containsString("<infNFe")));
    }

    @Test
    void consultarNaSefazDevolveOCStatDeAgora() throws Exception {
        String token = assinarNovoTenant("consultar-sefaz");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idDoc = criarDocumentoFiscal(idTenant, idEmpresa, "41260837829453000135650010000000031123456783");
        jdbc.sql("UPDATE empresa SET cnpj = '11222333000181' WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa).update();
        enviarCertificado(token, idEmpresa, "11222333000181");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "100", "Autorizado o uso da NF-e", "141260001999999", null,
                        "<retConsSitNFe><cStat>100</cStat></retConsSitNFe>"));

        mvc.perform(post("/api/v1/fiscal/documentos/" + idDoc + "/consultar-sefaz")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cStat").value("100"));
    }

    @Test
    void operadorNaoAcessa() throws Exception {
        String token = assinarNovoTenant("operador");
        long idEmpresa = idEmpresaDo(token);
        String operador = comoOperador(token);

        mvc.perform(get("/api/v1/fiscal/documentos")
                        .header("Authorization", "Bearer " + operador)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", LocalDate.now().toString())
                        .param("dataFinal", LocalDate.now().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("iso-a");
        String tokenB = assinarNovoTenant("iso-b");
        long idTenantA = idTenantDo(tokenA);
        long idEmpresaA = idEmpresaDo(tokenA);
        long idDocA = criarDocumentoFiscal(idTenantA, idEmpresaA, "41260837829453000135650010000000041123456784");

        mvc.perform(get("/api/v1/fiscal/documentos/" + idDocA + "/xml").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
