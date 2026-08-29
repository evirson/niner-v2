package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.fiscal.documento.ArquivamentoXmlService;
import com.vetor.niner.fiscal.documento.ValidadorXsd;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DefaultRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRule;
import software.amazon.awssdk.services.s3.model.PutObjectLockConfigurationRequest;

import java.net.URI;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Arquivamento do XML fiscal no bucket privado (docs/HANDOFF-ARQUIVAMENTO-XML.md, §7 critérios de
 * aceitação) — contra um <b>MinIO real</b> em container (não o do docker-compose de dev) e um
 * Postgres real (via {@link TestcontainersConfiguration}). Reaproveita os fixtures completos de
 * {@code VendaFiscalEmissaoTest}/{@code CancelamentoNfceTest}/{@code FiscalInutilizacaoTest}: só um
 * documento com XML REAL (montado e assinado pelo pipeline de verdade) prova que o {@code nfeProc}
 * arquivado passa no XSD oficial — um XML fabricado à mão no teste não pegaria um erro de
 * concatenação real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ArquivamentoXmlTest {

    private static final String SENHA_CERTIFICADO = "senha-teste-123";
    private static final String MINIO_USUARIO = "niner_arquivamento_test";
    private static final String MINIO_SENHA = "dev_minio_arquivamento_test";
    private static final String BUCKET_FISCAL = "niner-fiscal-arquivamento-test";
    private static final String BUCKET_PRIVADO = "niner-privado-arquivamento-test";

    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
                    .withEnv("MINIO_ROOT_USER", MINIO_USUARIO)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SENHA)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));

    @DynamicPropertySource
    static void propsDoMinio(DynamicPropertyRegistry registry) {
        MINIO.start();
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        criarBuckets(endpoint);
        registry.add("niner.storage.privado.endpoint", () -> endpoint);
        registry.add("niner.storage.privado.access-key", () -> MINIO_USUARIO);
        registry.add("niner.storage.privado.secret-key", () -> MINIO_SENHA);
        registry.add("niner.storage.privado.bucket-fiscal", () -> BUCKET_FISCAL);
        registry.add("niner.storage.privado.bucket-privado", () -> BUCKET_PRIVADO);
    }

    /** Mesma receita de {@code infra/minio/bootstrap.sh}/{@code ArmazenamentoPrivadoTest}: bucket
     *  fiscal com Object Lock (WORM) ligado na criação, o resto é igual ao MinIO de dev. */
    private static void criarBuckets(String endpoint) {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO_USUARIO, MINIO_SENHA)))
                .forcePathStyle(true)
                .build()) {
            s3.createBucket(CreateBucketRequest.builder()
                    .bucket(BUCKET_FISCAL).objectLockEnabledForBucket(true).build());
            s3.putObjectLockConfiguration(PutObjectLockConfigurationRequest.builder()
                    .bucket(BUCKET_FISCAL)
                    .objectLockConfiguration(ObjectLockConfiguration.builder()
                            .objectLockEnabled(ObjectLockEnabled.ENABLED)
                            .rule(ObjectLockRule.builder()
                                    .defaultRetention(DefaultRetention.builder()
                                            .mode(ObjectLockRetentionMode.GOVERNANCE).days(1825).build())
                                    .build())
                            .build())
                    .build());
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET_PRIVADO).build());
        }
    }

    @BeforeAll
    static void garanteMinioNoAr() {
        // @DynamicPropertySource já sobe o container antes do contexto Spring iniciar; este método
        // só documenta a ordem — sem ele o leitor poderia achar que falta subir algo aqui.
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ArquivamentoXmlService arquivamento;

    @Autowired
    ArmazenamentoPrivado armazenamentoPrivado;

    @Autowired
    ValidadorXsd validadorXsd;

    @MockitoBean
    SefazTransporte transporte;

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- fixture (mesmo padrão de VendaFiscalEmissaoTest)

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Arquivamento %s","email":"dono%s@lojaarquivamento.com",
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
                                 "ambiente":"HOMOLOGACAO","serieNfce":1,"serieNfe":1,"serieContingencia":9,
                                 "cscId":"000001","cscToken":"csc-fake-de-teste-0123456789abcdef01"}
                                """))
                .andExpect(status().isOk());
    }

    private byte[] gerarPfx(String cnpj) throws Exception {
        Path arquivo = tempDir.resolve("cert-" + cnpj + ".pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process processo = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "arquivamento-" + cnpj, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=EMPRESA ARQUIVAMENTO LTDA:" + cnpj + ", O=TESTE, C=BR",
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
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome) VALUES (?, 'PERFIL ARQUIVAMENTO')
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

        long idVariacao = jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, sku)
                        VALUES (?, ?, gerar_ean13_interno())
                        RETURNING id_variacao
                        """)
                .params(idTenant, idProduto).query(Long.class).single();
        abastecerEstoqueDoFixture(idTenant, idVariacao);
        return idVariacao;
    }

    /**
     * Dá estoque à variação recém-criada, em todas as empresas do tenant.
     *
     * <p>Desde a V054 o débito não pode deixar o saldo negativo quando
     * {@code cfg_permite_estoque_negativo} está desligado — que é o padrão. Este teste não é sobre
     * estoque: ele precisa de uma venda como <b>fixture</b>, e uma venda de verdade tem estoque
     * antes. Abastecer aqui é mais honesto que ligar o parâmetro e fingir que a regra não existe.
     */
    private void abastecerEstoqueDoFixture(long idTenant, long idVariacao) {
        jdbc.sql("""
                        INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque)
                        SELECT ?, e.id_empresa, ?, 1000 FROM empresa e WHERE e.id_tenant = ?
                        ON CONFLICT (id_tenant, id_empresa, id_variacao)
                        DO UPDATE SET qtd_estoque = produto_estoque.qtd_estoque + 1000
                        """)
                .params(idTenant, idVariacao, idTenant).update();
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

    private static String extrairChaveDoEnvi(String enviNFe) {
        int inicio = enviNFe.indexOf("Id=\"NFe") + "Id=\"NFe".length();
        int fim = enviNFe.indexOf('"', inicio);
        return enviNFe.substring(inicio, fim);
    }

    /**
     * {@code protNFe} completo (não o fixture minimalista de {@code cStat} sozinho usado nos
     * outros testes fiscais) — achado real ao rodar este teste contra {@code procNFe_v4.00.xsd}:
     * {@code versao="4.00"} é atributo obrigatório, e {@code infProt} exige, em sequência,
     * {@code tpAmb/verAplic/chNFe/dhRecbto/cStat/xMotivo} (só {@code nProt}/{@code digVal} são
     * opcionais). Os outros testes fiscais nunca precisaram disso porque nenhum reconstrói e
     * valida o {@code nfeProc} de verdade — aqui precisa, porque é exatamente isso que se prova.
     * {@code dhRecbto} usa offset explícito (nunca {@code Z}: {@code TDateTimeUTC} recusa).
     */
    private static RespostaSefaz autorizada(String chave) {
        String protNFe = "<protNFe versao=\"4.00\"><infProt><tpAmb>2</tpAmb><verAplic>SVRS-PR-1.0.0</verAplic>"
                + "<chNFe>" + chave + "</chNFe><dhRecbto>2026-08-18T10:00:00-03:00</dhRecbto>"
                + "<nProt>141260001999999</nProt><digVal>MTIzNDU2Nzg=</digVal>"
                + "<cStat>100</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo></infProt></protNFe>";
        return new RespostaSefaz(200, "100", "Autorizado o uso da NF-e", "141260001999999", chave,
                "<retEnviNFe>" + protNFe + "</retEnviNFe>");
    }

    /** Monta o cenário completo e devolve o {@code idVenda} com NFC-e AUTORIZADA de verdade
     *  (XML real montado + assinado pelo pipeline, não fabricado à mão). */
    private long vendaComNfceAutorizada(String token, long idTenant, long idEmpresa, String cnpj) throws Exception {
        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO ARQUIVAMENTO", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Arquivamento");
        long idFuncionario = criarFuncionario(token, "Vendedor Arquivamento");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));
        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk());
        Mockito.reset(transporte);
        return idVenda;
    }

    // ---------------------------------------------------------------- casos (handoff §7)

    /**
     * Critério §7 #1: documento autorizado → objeto existe em
     * {@code tenants/{id_tenant}/fiscal/{ano}/{mes}/65/{chave}.xml}, o conteúdo é um
     * {@code nfeProc} válido contra o XSD oficial, e as colunas apontam pra ele.
     */
    @Test
    void documentoAutorizadoArquivaNfeProcValidoNoBucket() throws Exception {
        String token = assinarNovoTenant("nfeproc");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceAutorizada(token, idTenant, idEmpresa, "11222333000181");

        Map<String, Object> doc = jdbc.sql("""
                        SELECT id_documento_fiscal, chave_acesso, xml_objeto_bucket, xml_hash, data_emissao
                          FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?
                        """)
                .params(idTenant, idVenda)
                .query((rs, n) -> Map.of(
                        "id", (Object) rs.getLong("id_documento_fiscal"),
                        "chave", rs.getString("chave_acesso"),
                        "bucket", rs.getString("xml_objeto_bucket"),
                        "hash", rs.getString("xml_hash")))
                .single();

        String chave = (String) doc.get("chave");
        String chaveBucket = (String) doc.get("bucket");
        assertThat(chaveBucket).as("caminho quente arquivou automaticamente após autorizar")
                .isNotNull()
                .startsWith("tenants/" + idTenant + "/fiscal/")
                .endsWith("/65/" + chave + ".xml");
        assertThat((String) doc.get("hash")).isNotBlank();

        byte[] conteudo = TenantContext.comTenant(idTenant, () ->
                armazenamentoPrivado.ler(AreaPrivada.FISCAL_XML, chaveBucket));
        String nfeProc = new String(conteudo, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(nfeProc).contains("<NFe ").contains("<protNFe").contains(chave);
        // A prova que importa: valida de verdade contra o XSD oficial (procNFe_v4.00.xsd) — não
        // basta a coluna estar preenchida, o CONTEÚDO tem que ser um nfeProc correto.
        validadorXsd.validarProcNfe(nfeProc);
    }

    /** Critério §7 #2 (idempotência, P2): arquivar de novo o mesmo documento não regrava nem
     *  muda as colunas. */
    @Test
    void arquivarDeNovoOMesmoDocumentoEhIdempotente() throws Exception {
        String token = assinarNovoTenant("idempotente");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceAutorizada(token, idTenant, idEmpresa, "22333444000162");

        long idDocumento = jdbc.sql("SELECT id_documento_fiscal FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Long.class).single();

        String chaveAntes = jdbc.sql("SELECT xml_objeto_bucket FROM documento_fiscal WHERE id_tenant = ? AND id_documento_fiscal = ?")
                .params(idTenant, idDocumento).query(String.class).single();
        String hashAntes = jdbc.sql("SELECT xml_hash FROM documento_fiscal WHERE id_tenant = ? AND id_documento_fiscal = ?")
                .params(idTenant, idDocumento).query(String.class).single();

        TenantContext.comTenant(idTenant, () -> arquivamento.arquivarDocumento(idDocumento));

        String chaveDepois = jdbc.sql("SELECT xml_objeto_bucket FROM documento_fiscal WHERE id_tenant = ? AND id_documento_fiscal = ?")
                .params(idTenant, idDocumento).query(String.class).single();
        String hashDepois = jdbc.sql("SELECT xml_hash FROM documento_fiscal WHERE id_tenant = ? AND id_documento_fiscal = ?")
                .params(idTenant, idDocumento).query(String.class).single();

        assertThat(chaveDepois).isEqualTo(chaveAntes);
        assertThat(hashDepois).isEqualTo(hashAntes);
    }

    /** Critério §7 #última: documento arquivado → {@code GET .../xml} devolve o {@code nfeProc}
     *  (com protocolo), não mais o {@code xml_assinado} puro. */
    @Test
    void endpointDeXmlDevolveNfeProcDepoisDeArquivado() throws Exception {
        String token = assinarNovoTenant("leitura");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceAutorizada(token, idTenant, idEmpresa, "33444555000143");

        long idDocumento = jdbc.sql("SELECT id_documento_fiscal FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Long.class).single();

        String resp = mvc.perform(get("/api/v1/fiscal/documentos/" + idDocumento + "/xml")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String xml = JsonPath.read(resp, "$.xml");
        assertThat(xml).as("devolve o nfeProc, com protocolo — não o xml_assinado puro")
                .contains("<nfeProc").contains("<protNFe");
    }

    /** Critério §7 (eventos): cancelamento autorizado pela SEFAZ arquiva o XML do evento. */
    @Test
    void cancelamentoAutorizadoArquivaXmlDoEvento() throws Exception {
        String token = assinarNovoTenant("evento");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceAutorizada(token, idTenant, idEmpresa, "44555666000124");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "135", "Evento registrado e vinculado a NF-e",
                        "141260009999999", null,
                        "<retEnvEvento><retEvento><infEvento><cStat>135</cStat></infEvento></retEvento></retEnvEvento>"));

        mvc.perform(post("/api/v1/vendas/cancelamento/" + idVenda).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"motivo\":\"Teste de arquivamento do evento de cancelamento\"}"))
                .andExpect(status().isOk());

        Map<String, Object> evento = jdbc.sql("""
                        SELECT e.id_evento, e.xml_objeto_bucket
                          FROM documento_fiscal_evento e
                          JOIN documento_fiscal d ON d.id_documento_fiscal = e.id_documento_fiscal AND d.id_tenant = e.id_tenant
                         WHERE e.id_tenant = ? AND d.id_venda = ? AND e.tipo_evento = '110111' AND e.autorizado = true
                        """)
                .params(idTenant, idVenda)
                .query((rs, n) -> Map.of("id", (Object) rs.getLong("id_evento"), "bucket", rs.getString("xml_objeto_bucket")))
                .single();

        String chaveBucket = (String) evento.get("bucket");
        assertThat(chaveBucket).as("evento de cancelamento arquivado automaticamente")
                .isNotNull().startsWith("tenants/" + idTenant + "/fiscal/").contains("-110111-");

        byte[] conteudo = TenantContext.comTenant(idTenant, () ->
                armazenamentoPrivado.ler(AreaPrivada.FISCAL_XML, chaveBucket));
        assertThat(new String(conteudo, java.nio.charset.StandardCharsets.UTF_8)).contains("110111");
    }

    /**
     * ⚠️ <b>Regressão de 2026-08-20.</b> O record {@code InutilizacaoParaArquivar} ganhou o campo
     * {@code uf} (o mês da pasta passou a sair do fuso da UF) e a <b>query não ganhou a coluna</b>:
     * {@code buscarParaArquivar} passou a estourar <i>"The column name uf was not found in this
     * ResultSet"</i>. O erro era <b>engolido</b> pelo {@code catch (RuntimeException) → log.warn} do
     * próprio serviço, então a inutilização respondia 200, <b>nenhum XML subia ao bucket</b>
     * (F6/DF21, guarda de 5 anos) e o job de recuperação retentava para sempre, um warn por ciclo.
     *
     * <p>Passou despercebido porque <b>nenhum teste cobria o arquivamento de inutilização</b> — as
     * três irmãs em {@code DocumentoFiscalRepositorio} tinham teste, esta não. Agora tem.
     */
    @Test
    void inutilizacaoAutorizadaTemOXmlArquivadoNoBucket() throws Exception {
        String token = assinarNovoTenant("inut-arquivo");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        completarDadosDaEmpresa(idTenant, idEmpresa, "22333444000162");

        long idInutilizacao = jdbc.sql("""
                        INSERT INTO fiscal_inutilizacao
                            (id_tenant, id_empresa, modelo, serie, ano, numero_inicial, numero_final,
                             justificativa, autorizado, protocolo, xml_inutilizacao)
                        VALUES (?, ?, 65, 1, 26, 500, 505,
                                'Numeros queimados por rejeicao de schema, nunca autorizados', true,
                                '141260009999999', '<inutNFe versao="4.00"><infInut>teste</infInut></inutNFe>')
                        RETURNING id_inutilizacao
                        """)
                .params(idTenant, idEmpresa).query(Long.class).single();

        TenantContext.comTenant(idTenant, () -> arquivamento.arquivarInutilizacaoSeAplicavel(idInutilizacao));

        String chave = jdbc.sql("""
                        SELECT xml_objeto_bucket FROM fiscal_inutilizacao
                         WHERE id_tenant = ? AND id_inutilizacao = ?
                        """)
                .params(idTenant, idInutilizacao).query(String.class).optional().orElse(null);

        assertThat(chave)
                .as("XML da inutilização no bucket — nulo significa que buscarParaArquivar falhou e o erro foi engolido")
                .isNotBlank();
        // O caminho carrega ano/mês do instante LOCAL da UF da empresa (PR), não do offset cru.
        assertThat(chave).contains("/65/").contains("inut-1-500-505.xml");
    }
}
