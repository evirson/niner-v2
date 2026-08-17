package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Certificado Digital (docs/telas/fiscal-certificado.md) — write-only de verdade, upload
 * multipart. Os `.pfx` de teste são <b>autoassinados gerados no setup</b> via {@code keytool}
 * (bundlado no JDK, mesmo achado do B0 — nenhuma lib de NF-e é necessária pra isto), nunca um
 * certificado real versionado no repositório.
 *
 * <p>Convenção do CN de e-CNPJ ICP-Brasil: {@code RAZAO SOCIAL:14DIGITOS} — é daí que o
 * serviço extrai CNPJ e razão social, nunca digitados pelo lojista.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FiscalCertificadoCrudTest {

    private static final String SENHA = "senha-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    /** Injetado para exercitar {@code carregarAtivoParaAssinatura}, que é o caminho do B6 e não
     *  tem (nem terá) endpoint — o certificado nunca sai pela API. */
    @Autowired
    FiscalCertificadoService certificados;

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- helpers de tenant/empresa

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Certificado %s","email":"dono%s@lojacert.com",
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

    private void definirCnpjDaEmpresa(long idTenant, long idEmpresa, String cnpj) {
        jdbc.sql("UPDATE empresa SET cnpj = ? WHERE id_tenant = ? AND id_empresa = ?")
                .params(cnpj, idTenant, idEmpresa).update();
    }

    // ---------------------------------------------------------------- geração de .pfx via keytool

    /** {@code null} em {@code inicioRelativo} = certificado começa a valer agora. */
    private byte[] gerarPfx(String alias, String cnpj, String inicioRelativo, int validadeDias) throws Exception {
        Path arquivo = tempDir.resolve(alias + ".pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();

        List<String> comando = new ArrayList<>(List.of(
                keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", String.valueOf(validadeDias),
                "-dname", "CN=EMPRESA TESTE LTDA:" + cnpj + ", O=EMPRESA TESTE, C=BR",
                "-keystore", arquivo.toString(), "-storetype", "PKCS12",
                "-storepass", SENHA, "-keypass", SENHA));
        if (inicioRelativo != null) {
            comando.add("-startdate");
            comando.add(inicioRelativo);
        }

        Process processo = new ProcessBuilder(comando).redirectErrorStream(true).start();
        String saida = new String(processo.getInputStream().readAllBytes());
        int codigo = processo.waitFor();
        if (codigo != 0) {
            throw new IllegalStateException("keytool falhou (código " + codigo + "): " + saida);
        }
        return Files.readAllBytes(arquivo);
    }

    private byte[] certificadoValido(String cnpj) throws Exception {
        return gerarPfx("valido-" + cnpj, cnpj, null, 365);
    }

    private byte[] certificadoVencido(String cnpj) throws Exception {
        // Começa há 400 dias, válido por 30 — já expirou há muito.
        return gerarPfx("vencido-" + cnpj, cnpj, "-400d", 30);
    }

    private byte[] certificadoQueVenceEmDias(String cnpj, int diasParaVencer) throws Exception {
        // Começa há 300 dias, válido por (300 + diasParaVencer) — vence daqui a diasParaVencer.
        return gerarPfx("perto-" + cnpj, cnpj, "-300d", 300 + diasParaVencer);
    }

    // ---------------------------------------------------------------- upload feliz

    @Test
    void uploadValidoExtraiMetadadosDoProprioArquivo() throws Exception {
        String token = assinarNovoTenant("upload-ok");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "11222333000181";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);

        byte[] pfx = certificadoValido(cnpj);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "certificado.pfx", "application/x-pkcs12", pfx);

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(arquivo)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cnpjTitular").value(cnpj))
                .andExpect(jsonPath("$.razaoSocialTitular").value("EMPRESA TESTE LTDA"))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.situacao").value("ATIVO"))
                .andExpect(jsonPath("$.impressaoDigital").isNotEmpty());

        mvc.perform(get("/api/v1/fiscal/certificados").param("idEmpresa", String.valueOf(idEmpresa))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    /** Estrutural: o DTO de resposta não tem campo de arquivo/senha — não há como vazar. */
    @Test
    void respostaNuncaContemArquivoOuSenha() throws Exception {
        String token = assinarNovoTenant("sem-vazamento");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "22333444000162";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);

        String resp = mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", certificadoValido(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(resp.toLowerCase()).doesNotContain("senha").doesNotContain(SENHA.toLowerCase());
    }

    /**
     * DF21 revisada (2026-08-17): o {@code .pfx} fica no <b>banco</b>, e <b>cifrado</b> — não só
     * a senha. Um dump do banco não pode conter um PKCS12 utilizável, porque a senha dele é curta
     * e quebrável por força bruta offline por quem tiver o arquivo.
     */
    @Test
    void arquivoDoCertificadoFicaCifradoNoBancoNuncaEmClaro() throws Exception {
        String token = assinarNovoTenant("cifrado-no-banco");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "12345678000195";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);
        byte[] pfxOriginal = certificadoValido(cnpj);

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", pfxOriginal))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        byte[] gravado = jdbc.sql("""
                        SELECT arquivo_cifrado FROM fiscal_certificado
                        WHERE id_tenant = ? AND id_empresa = ? AND ativo
                        """)
                .params(idTenant, idEmpresa).query(byte[].class).single();

        assertThat(gravado).isNotEqualTo(pfxOriginal);
        // Um PKCS12 sempre começa com a sequência DER 0x30 0x82. Se o gravado começasse assim,
        // estaria em claro — este é o teste que discrimina "cifrado" de "só renomeado".
        assertThat(gravado[0] == 0x30 && gravado[1] == (byte) 0x82)
                .as("arquivo gravado não pode ser um PKCS12 legível")
                .isFalse();

        String senhaGravada = jdbc.sql("""
                        SELECT senha_cifrada FROM fiscal_certificado
                        WHERE id_tenant = ? AND id_empresa = ? AND ativo
                        """)
                .params(idTenant, idEmpresa).query(String.class).single();
        assertThat(senhaGravada).isNotEqualTo(SENHA).doesNotContain(SENHA);
    }

    /**
     * O caminho que o B6 vai usar para assinar: carrega o certificado ativo já decifrado. Prova
     * o ciclo inteiro — o {@code .pfx} que sai do banco abre com a senha que saiu do banco.
     */
    @Test
    void certificadoCarregadoDoBancoAbreComoPkcs12ParaAssinar() throws Exception {
        String token = assinarNovoTenant("carrega-assinatura");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "98765432000109";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", certificadoValido(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        // Caminho sem requisição HTTP: o tenant é ligado explicitamente no escopo (P8).
        TenantContext.comTenant(idTenant, () -> {
            var carregado = certificados.carregarAtivoParaAssinatura(idEmpresa);

            assertThat(carregado.senha()).isEqualTo(SENHA);
            assertThat(carregado.cnpjTitular()).isEqualTo(cnpj);

            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(new ByteArrayInputStream(carregado.pkcs12()), carregado.senha().toCharArray());
                assertThat(Collections.list(ks.aliases())).isNotEmpty();
            } catch (Exception e) {
                throw new AssertionError("O .pfx que saiu do banco não abriu com a senha que saiu do banco.", e);
            }

            // A impressão digital é a chave do cache mTLS por empresa (B6, SefazTransporte) — se
            // ela viesse de outro lugar (um parâmetro externo, por exemplo), o cache poderia
            // divergir do certificado que de fato assina, exatamente o que o cache existe para
            // impedir. Por isso ela tem que vir daqui, do certificado carregado.
            assertThat(carregado.impressaoDigital()).isNotBlank().hasSize(64 /* SHA-256 em hex */);
        });
    }

    // ---------------------------------------------------------------- as 5 validações

    @Test
    void senhaErradaNaoGravaNada() throws Exception {
        String token = assinarNovoTenant("senha-errada");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "33444555000143";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", certificadoValido(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("senha", "senha-totalmente-errada")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        Long total = jdbc.sql("SELECT count(*) FROM fiscal_certificado WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa).query(Long.class).single();
        assertThat(total).isZero();
    }

    @Test
    void certificadoVencidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("vencido");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "44555666000124";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", certificadoVencido(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("vencido")));
    }

    @Test
    void certificadoDeOutroCnpjEhRejeitado() throws Exception {
        String token = assinarNovoTenant("outro-cnpj");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        definirCnpjDaEmpresa(idTenant, idEmpresa, "55666777000105");

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12",
                                certificadoValido("99888777000160")))
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("outro CNPJ")));
    }

    @Test
    void certificadoJaCadastradoEAtivoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("duplicado");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "66777888000186";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);
        byte[] pfx = certificadoValido(cnpj);

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", pfx))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", pfx))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("já está cadastrado")));
    }

    // ---------------------------------------------------------------- substituição

    @Test
    void segundoCertificadoDesativaOPrimeiro() throws Exception {
        String token = assinarNovoTenant("substitui");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "77888999000167";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);

        String resp1 = mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c1.pfx", "application/x-pkcs12", certificadoValido(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idPrimeiro = ((Number) JsonPath.read(resp1, "$.idCertificado")).longValue();

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c2.pfx", "application/x-pkcs12",
                                gerarPfx("segundo-" + cnpj, cnpj, null, 400)))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ativo").value(true));

        mvc.perform(get("/api/v1/fiscal/certificados").param("idEmpresa", String.valueOf(idEmpresa))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.idCertificado == " + idPrimeiro + ")].ativo").value(false))
                .andExpect(jsonPath("$[?(@.idCertificado == " + idPrimeiro + ")].situacao").value("SUBSTITUIDO"));
    }

    // ---------------------------------------------------------------- badge de vencimento

    @Test
    void certificadoQueVenceEmCincoDiasTrazBadgeComContagem() throws Exception {
        String token = assinarNovoTenant("vence-5");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "88999000000148";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12",
                                certificadoQueVenceEmDias(cnpj, 5)))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.situacao").value("VENCE_EM_BREVE"))
                .andExpect(jsonPath("$.diasParaVencer").value(org.hamcrest.Matchers.lessThanOrEqualTo(5)));
    }

    // ---------------------------------------------------------------- papéis e isolamento

    @Test
    void operadorNaoPodeListarNemEnviar() throws Exception {
        String token = assinarNovoTenant("operador-cert");
        String operador = comoOperador(token);
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/certificados").param("idEmpresa", String.valueOf(idEmpresa))
                        .header("Authorization", "Bearer " + operador))
                .andExpect(status().isForbidden());

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", certificadoValido("11111111000191")))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + operador))
                .andExpect(status().isForbidden());
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("iso-cert-a");
        String tokenB = assinarNovoTenant("iso-cert-b");
        long idTenantA = idTenantDo(tokenA);
        long idEmpresaA = idEmpresaDo(tokenA);
        String cnpj = "12345678000195";
        definirCnpjDaEmpresa(idTenantA, idEmpresaA, cnpj);

        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", certificadoValido(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresaA)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated());

        // B não enxerga o certificado de A mesmo tentando pela idEmpresa de A.
        mvc.perform(get("/api/v1/fiscal/certificados").param("idEmpresa", String.valueOf(idEmpresaA))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------------------------------------------------------------- auditoria de uso

    @Test
    void drillDownDeUsoMostraFinalidadeEData() throws Exception {
        String token = assinarNovoTenant("uso");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "13579246000112";
        definirCnpjDaEmpresa(idTenant, idEmpresa, cnpj);

        String resp = mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", certificadoValido(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCertificado = ((Number) JsonPath.read(resp, "$.idCertificado")).longValue();

        jdbc.sql("""
                        INSERT INTO fiscal_certificado_uso (id_tenant, id_certificado, finalidade)
                        VALUES (?, ?, 'ASSINATURA')
                        """)
                .params(idTenant, idCertificado).update();

        mvc.perform(get("/api/v1/fiscal/certificados/" + idCertificado + "/usos")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].finalidade").value("ASSINATURA"))
                .andExpect(jsonPath("$[0].ocorridoEm").isNotEmpty());
    }
}
