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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reprocessamento de documento preso (§12, {@code POST /documentos/{id}/reprocessar}, bloco B8).
 * O que importa provar: consulta <b>sempre</b> vem antes de qualquer retransmissão (F5), e um
 * documento que não está preso (já resolvido) nunca chega a chamar a SEFAZ.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentoFiscalReprocessamentoTest {

    private static final String SENHA_CERTIFICADO = "senha-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    SefazTransporte transporte;

    @TempDir
    Path tempDir;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Reprocessamento %s","email":"dono%s@lojareproc.com",
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

    private byte[] gerarPfx(String cnpj) throws Exception {
        Path arquivo = tempDir.resolve("cert-" + cnpj + ".pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process processo = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "reproc-" + cnpj, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=EMPRESA REPROCESSAMENTO LTDA:" + cnpj + ", O=TESTE, C=BR",
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
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", gerarPfx(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA_CERTIFICADO)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    /** Documento numa situação "presa" — pronto pra reprocessar. */
    private long criarDocumentoPreso(long idTenant, long idEmpresa, String chave, String situacao) {
        jdbc.sql("UPDATE empresa SET estado = 'PR' WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa).update();
        String xml = "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe Id=\"NFe%s\"/></NFe>".formatted(chave);
        return jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso, codigo_numerico,
                            digito_verificador, tipo_operacao, situacao, ambiente, tipo_emissao,
                            data_emissao, valor_produtos, valor_desconto, valor_outros, valor_total,
                            valor_troco, xml_assinado)
                        VALUES (?, ?, 65, 1, 1, ?, '12345678', 9, 'VENDA_CONSUMIDOR',
                                ?::situacao_documento_fiscal, 'HOMOLOGACAO', 1, now(),
                                30.00, 0, 0, 30.00, 0, ?)
                        RETURNING id_documento_fiscal
                        """)
                .params(idTenant, idEmpresa, chave, situacao, xml).query(Long.class).single();
    }

    @Test
    void consultaAcheAutorizadaEMarcaSemRetransmitir() throws Exception {
        String token = assinarNovoTenant("achou-autorizada");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        jdbc.sql("UPDATE empresa SET cnpj = '11222333000181' WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa).update();
        enviarCertificado(token, idEmpresa, "11222333000181");
        long idDoc = criarDocumentoPreso(idTenant, idEmpresa,
                "41260837829453000135650010000000011123456781", "TRANSMITINDO");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "100", "Autorizado o uso da NF-e", "141260001999999",
                        null, "<retConsSitNFe><cStat>100</cStat></retConsSitNFe>"));

        mvc.perform(post("/api/v1/fiscal/documentos/" + idDoc + "/reprocessar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("AUTORIZADO"))
                .andExpect(jsonPath("$.protocolo").value("141260001999999"));

        verify(transporte, times(1)).enviar(any(), any(), any(), any(), any(), any());
        String situacao = jdbc.sql("SELECT situacao FROM documento_fiscal WHERE id_tenant = ? AND id_documento_fiscal = ?")
                .params(idTenant, idDoc).query(String.class).single();
        assertThat(situacao).isEqualTo("AUTORIZADO");
    }

    /** O caso central: consulta diz "não consta" (217) — só aí vale retransmitir o XML assinado. */
    @Test
    void naoConstaRetransmiteOXmlJaAssinadoEAutorizaAgora() throws Exception {
        String token = assinarNovoTenant("nao-consta");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        jdbc.sql("UPDATE empresa SET cnpj = '22333444000162' WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa).update();
        enviarCertificado(token, idEmpresa, "22333444000162");
        long idDoc = criarDocumentoPreso(idTenant, idEmpresa,
                "41260837829453000135650010000000021123456782", "ASSINADO");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "217", "NF-e nao consta na base de dados da SEFAZ",
                        null, null, "<retConsSitNFe><cStat>217</cStat></retConsSitNFe>"))
                .thenReturn(new RespostaSefaz(200, "100", "Autorizado o uso da NF-e", "141260002999999",
                        null, "<retEnviNFe><protNFe><infProt><cStat>100</cStat></infProt></protNFe></retEnviNFe>"));

        mvc.perform(post("/api/v1/fiscal/documentos/" + idDoc + "/reprocessar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("AUTORIZADO"))
                .andExpect(jsonPath("$.protocolo").value("141260002999999"));

        verify(transporte, times(2)).enviar(any(), any(), any(), any(), any(), any());
        String situacao = jdbc.sql("SELECT situacao FROM documento_fiscal WHERE id_tenant = ? AND id_documento_fiscal = ?")
                .params(idTenant, idDoc).query(String.class).single();
        assertThat(situacao).isEqualTo("AUTORIZADO");
    }

    @Test
    void documentoJaAutorizadoBloqueiaSemChamarASefaz() throws Exception {
        String token = assinarNovoTenant("ja-autorizado");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idDoc = criarDocumentoPreso(idTenant, idEmpresa,
                "41260837829453000135650010000000031123456783", "AUTORIZADO");

        mvc.perform(post("/api/v1/fiscal/documentos/" + idDoc + "/reprocessar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        Mockito.verifyNoInteractions(transporte);
    }

    @Test
    void consultaDenegadaMarcaDenegadoSemRetransmitir() throws Exception {
        String token = assinarNovoTenant("denegada");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        jdbc.sql("UPDATE empresa SET cnpj = '33444555000143' WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa).update();
        enviarCertificado(token, idEmpresa, "33444555000143");
        long idDoc = criarDocumentoPreso(idTenant, idEmpresa,
                "41260837829453000135650010000000041123456784", "TRANSMITINDO");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "110", "Uso Denegado", null, null,
                        "<retConsSitNFe><cStat>110</cStat></retConsSitNFe>"));

        mvc.perform(post("/api/v1/fiscal/documentos/" + idDoc + "/reprocessar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("DENEGADO"));

        verify(transporte, times(1)).enviar(any(), any(), any(), any(), any(), any());
        String situacao = jdbc.sql("SELECT situacao FROM documento_fiscal WHERE id_tenant = ? AND id_documento_fiscal = ?")
                .params(idTenant, idDoc).query(String.class).single();
        assertThat(situacao).isEqualTo("DENEGADO");
    }
}
