package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteService;
import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.comum.seguranca.TokenService;
import com.vetor.niner.comum.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exportação de XML em Lote ({@code docs/telas/exportacao-xml-lote.md}) — contra um <b>MinIO
 * real</b> em container (nunca o do docker-compose de dev) e um Postgres real
 * ({@link TestcontainersConfiguration}).
 *
 * <p><b>Por que MinIO de verdade e não um dublê.</b> O que esta tela faz de mais arriscado é
 * <b>ler N objetos do bucket dentro de uma requisição</b>, e as duas garantias que precisam ser
 * provadas moram no adapter: o prefixo {@code tenants/{id}/} conferido na leitura (P8) e o
 * comportamento do 404 quando a chave existe no banco e o objeto não existe no bucket. Um dublê em
 * memória devolveria o que o teste mandasse guardar e não provaria nenhuma das duas.
 *
 * <p>A fixture do documento é <b>SQL direto</b> (não passa pela emissão real): o que está sob teste
 * é o empacotamento, e a emissão já é coberta à exaustão por {@code VendaFiscalEmissaoTest}.
 *
 * <p>⚠️ Toda data é <b>relativa</b> ({@link #hojeMais}) — fixture com data literal quebra sozinha
 * quando o calendário passa por ela.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExportacaoXmlLoteTest {

    private static final String MINIO_USUARIO = "niner_exportacao_test";
    private static final String MINIO_SENHA = "dev_minio_exportacao_test";
    private static final String BUCKET_FISCAL = "niner-fiscal-exportacao-test";
    private static final String BUCKET_PRIVADO = "niner-privado-exportacao-test";

    /** Fuso da UF que os testes gravam na empresa (PR) — o mesmo que decide a pasta do ZIP. */
    private static final ZoneId FUSO_DA_LOJA = ZoneId.of("America/Sao_Paulo");

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

    /** Mesma receita de {@code infra/minio/bootstrap.sh}: bucket fiscal com Object Lock (WORM). */
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

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TokenService tokens;

    @Autowired
    ArmazenamentoPrivado armazenamento;

    // ------------------------------------------------------------------ fixture

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Exportacao XML %s","email":"dono%s@lojaexportaxml.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        jdbc.sql("UPDATE empresa SET estado = 'PR' WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenantDo(token), idEmpresaDo(token)).update();
        return token;
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

    /** Data relativa ao dia de hoje <b>no fuso da loja</b>, nunca literal. */
    private static LocalDate hojeMais(int dias) {
        return LocalDate.now(FUSO_DA_LOJA).plusDays(dias);
    }

    /** Meio-dia do dia informado, no fuso da loja — longe de qualquer virada de dia. */
    private static OffsetDateTime meioDiaDe(LocalDate dia) {
        return dia.atTime(12, 0).atZone(FUSO_DA_LOJA).toOffsetDateTime();
    }

    private long criarDocumento(long idTenant, long idEmpresa, int modelo, int numero, String chave,
                                String situacao, LocalDate dia) {
        return jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso, codigo_numerico,
                            digito_verificador, tipo_operacao, situacao, ambiente, tipo_emissao,
                            data_emissao, valor_produtos, valor_desconto, valor_outros, valor_total,
                            valor_troco, protocolo, data_autorizacao)
                        VALUES (?, ?, ?, 1, ?, ?, '12345678', 9, 'VENDA_CONSUMIDOR',
                                ?::situacao_documento_fiscal, 'HOMOLOGACAO', 1, ?, 30.00, 0, 0, 30.00, 0,
                                '141260001999999', ?)
                        RETURNING id_documento_fiscal
                        """)
                .params(idTenant, idEmpresa, modelo, numero, chave, situacao,
                        meioDiaDe(dia), meioDiaDe(dia))
                .query(Long.class).single();
    }

    /** Grava o XML no bucket (como o {@code ArquivamentoXmlService} faria) e aponta a coluna. */
    private String arquivar(long idTenant, long idDocumento, int modelo, String chave, LocalDate dia,
                            String conteudo) {
        String caminho = "%d/%02d/%d/%s.xml".formatted(dia.getYear(), dia.getMonthValue(), modelo, chave);
        String chaveObjeto = TenantContext.comTenant(idTenant, () -> armazenamento.gravar(
                AreaPrivada.FISCAL_XML, caminho, conteudo.getBytes(StandardCharsets.UTF_8), "application/xml"));
        jdbc.sql("""
                        UPDATE documento_fiscal SET xml_objeto_bucket = ?
                         WHERE id_tenant = ? AND id_documento_fiscal = ?
                        """)
                .params(chaveObjeto, idTenant, idDocumento).update();
        return chaveObjeto;
    }

    private void criarEventoCancelamentoArquivado(long idTenant, long idDocumento, String chave, LocalDate dia,
                                                  String conteudo) {
        String caminho = "%d/%02d/65/%s-110111-1.xml".formatted(dia.getYear(), dia.getMonthValue(), chave);
        String chaveObjeto = TenantContext.comTenant(idTenant, () -> armazenamento.gravar(
                AreaPrivada.FISCAL_XML, caminho, conteudo.getBytes(StandardCharsets.UTF_8), "application/xml"));
        jdbc.sql("""
                        INSERT INTO documento_fiscal_evento (
                            id_tenant, id_documento_fiscal, tipo_evento, sequencia, tentativa,
                            justificativa, autorizado, protocolo, xml_evento, xml_objeto_bucket)
                        VALUES (?, ?, '110111', 1, 1, 'CLIENTE DESISTIU DA COMPRA NO CAIXA', true,
                                '141260002999999', ?, ?)
                        """)
                .params(idTenant, idDocumento, conteudo, chaveObjeto).update();
    }

    /**
     * Chave de acesso com 44 dígitos, distinta por sufixo. O dígito verificador <b>não</b> é
     * calculado: esta tela lê a chave como identificador e nome de arquivo, nunca a valida —
     * quem faz isso é {@code ChaveAcesso}, com teste próprio.
     */
    private static String chaveDe(int sufixo) {
        return "4126083782945300013565001000000000000000" + "%04d".formatted(sufixo);
    }

    private byte[] baixarZip(String token, long idEmpresa, LocalDate inicio, LocalDate fim, String modelo)
            throws Exception {
        var req = get("/api/v1/fiscal/exportacao-xml")
                .header("Authorization", "Bearer " + token)
                .param("idEmpresa", String.valueOf(idEmpresa))
                .param("dataInicial", inicio.toString())
                .param("dataFinal", fim.toString());
        if (modelo != null) {
            req = req.param("modelo", modelo);
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private static Map<String, byte[]> lerZip(byte[] zip) throws IOException {
        Map<String, byte[]> entradas = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entrada;
            while ((entrada = in.getNextEntry()) != null) {
                entradas.put(entrada.getName(), in.readAllBytes());
            }
        }
        return entradas;
    }

    private static String pastaDoMes(LocalDate dia) {
        return "%d-%02d".formatted(dia.getYear(), dia.getMonthValue());
    }

    // ------------------------------------------------------------------ testes

    @Test
    void exportaOsXmlsDoPeriodoComOConteudoQueEstaNoBucket() throws Exception {
        String token = assinarNovoTenant("basico");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String chave1 = chaveDe(101);
        String chave2 = chaveDe(102);
        long doc1 = criarDocumento(idTenant, idEmpresa, 65, 101, chave1, "AUTORIZADO", ontem);
        long doc2 = criarDocumento(idTenant, idEmpresa, 55, 102, chave2, "AUTORIZADO", ontem);
        arquivar(idTenant, doc1, 65, chave1, ontem, "<nfeProc>UM</nfeProc>");
        arquivar(idTenant, doc2, 55, chave2, ontem, "<nfeProc>DOIS</nfeProc>");

        Map<String, byte[]> zip = lerZip(baixarZip(token, idEmpresa, hojeMais(-5), hojeMais(0), null));

        String pasta = pastaDoMes(ontem);
        assertThat(zip).containsKeys(
                pasta + "/saidas/" + chave1 + ".xml",
                pasta + "/saidas/" + chave2 + ".xml",
                "relatorio.csv");
        assertThat(new String(zip.get(pasta + "/saidas/" + chave1 + ".xml"), StandardCharsets.UTF_8))
                .isEqualTo("<nfeProc>UM</nfeProc>");
        assertThat(new String(zip.get(pasta + "/saidas/" + chave2 + ".xml"), StandardCharsets.UTF_8))
                .isEqualTo("<nfeProc>DOIS</nfeProc>");
    }

    /**
     * ⛔ A garantia central da tela: ZIP vazio <b>nunca</b> sai em silêncio. Um arquivo que abre e
     * não tem nada dentro parece pacote entregue, e o lojista só descobre quando o contador reclama.
     */
    @Test
    void periodoSemNenhumXmlArquivadoRecusaEmVezDeGerarZipVazio() throws Exception {
        String token = assinarNovoTenant("vazio");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/exportacao-xml")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-30).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isConflict());
    }

    /**
     * ⭐ Nota AUTORIZADA cujo XML não subiu ao bucket tem valor fiscal e sumiria do pacote sem nada
     * avisar. O resumo a conta em {@code documentosSemXml} e o {@code relatorio.csv} a marca.
     */
    @Test
    void notaAutorizadaSemXmlArquivadoApareceNoResumoEFicaMarcadaNoCsv() throws Exception {
        String token = assinarNovoTenant("pendente");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String comXml = chaveDe(201);
        String semXml = chaveDe(202);
        long doc = criarDocumento(idTenant, idEmpresa, 65, 201, comXml, "AUTORIZADO", ontem);
        criarDocumento(idTenant, idEmpresa, 65, 202, semXml, "AUTORIZADO", ontem);
        arquivar(idTenant, doc, 65, comXml, ontem, "<nfeProc>OK</nfeProc>");

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDocumentos").value(2))
                .andExpect(jsonPath("$.documentosComXml").value(1))
                .andExpect(jsonPath("$.documentosSemXml").value(1));

        Map<String, byte[]> zip = lerZip(baixarZip(token, idEmpresa, hojeMais(-5), hojeMais(0), null));
        assertThat(zip).containsKey(pastaDoMes(ontem) + "/saidas/" + comXml + ".xml");
        assertThat(zip).doesNotContainKey(pastaDoMes(ontem) + "/saidas/" + semXml + ".xml");

        String csv = new String(zip.get("relatorio.csv"), StandardCharsets.UTF_8);
        assertThat(csv).contains(semXml).contains("(nao arquivado)");
        assertThat(csv).contains(comXml);
    }

    /**
     * ⭐ A nota cancelada sozinha no ZIP é um XML de nota <b>autorizada</b>: nada nele diz que foi
     * cancelada. Quem prova o cancelamento é o evento 110111 — sem ele o pacote afirma uma venda
     * que não existe.
     */
    @Test
    void notaCanceladaVaiJuntoComOEventoDeCancelamento() throws Exception {
        String token = assinarNovoTenant("cancelada");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String chave = chaveDe(301);
        long doc = criarDocumento(idTenant, idEmpresa, 65, 301, chave, "CANCELADO", ontem);
        arquivar(idTenant, doc, 65, chave, ontem, "<nfeProc>CANCELADA</nfeProc>");
        criarEventoCancelamentoArquivado(idTenant, doc, chave, ontem, "<procEventoNFe>CANC</procEventoNFe>");

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEventos").value(1));

        Map<String, byte[]> zip = lerZip(baixarZip(token, idEmpresa, hojeMais(-5), hojeMais(0), null));
        String pasta = pastaDoMes(ontem);
        assertThat(zip).containsKeys(
                pasta + "/saidas/" + chave + ".xml",
                pasta + "/eventos/" + chave + "-110111-1.xml");
    }

    @Test
    void filtroDeModeloSeparaNfceDeNfe() throws Exception {
        String token = assinarNovoTenant("modelo");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String nfce = chaveDe(401);
        String nfe = chaveDe(402);
        long doc65 = criarDocumento(idTenant, idEmpresa, 65, 401, nfce, "AUTORIZADO", ontem);
        long doc55 = criarDocumento(idTenant, idEmpresa, 55, 402, nfe, "AUTORIZADO", ontem);
        arquivar(idTenant, doc65, 65, nfce, ontem, "<nfeProc>NFCE</nfeProc>");
        arquivar(idTenant, doc55, 55, nfe, ontem, "<nfeProc>NFE</nfeProc>");

        Map<String, byte[]> so55 = lerZip(baixarZip(token, idEmpresa, hojeMais(-5), hojeMais(0), "55"));
        assertThat(so55).containsKey(pastaDoMes(ontem) + "/saidas/" + nfe + ".xml");
        assertThat(so55).doesNotContainKey(pastaDoMes(ontem) + "/saidas/" + nfce + ".xml");
    }

    /**
     * Documento que morreu no bloqueio preventivo (F11) não tem chave nem XML — e não pode virar
     * uma entrada {@code null.xml} no pacote.
     */
    @Test
    void documentoNaoEmitidoNaoEntraNoPacote() throws Exception {
        String token = assinarNovoTenant("nao-emitido");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String chave = chaveDe(501);
        long doc = criarDocumento(idTenant, idEmpresa, 65, 501, chave, "AUTORIZADO", ontem);
        arquivar(idTenant, doc, 65, chave, ontem, "<nfeProc>OK</nfeProc>");
        jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, tipo_operacao, situacao, ambiente, tipo_emissao,
                            data_emissao, valor_produtos, valor_desconto, valor_outros, valor_total, valor_troco)
                        VALUES (?, ?, 55, 'VENDA_CONSUMIDOR', 'NAO_EMITIDO', 'HOMOLOGACAO', 1, ?,
                                30.00, 0, 0, 30.00, 0)
                        """)
                .params(idTenant, idEmpresa, meioDiaDe(ontem)).update();

        Map<String, byte[]> zip = lerZip(baixarZip(token, idEmpresa, hojeMais(-5), hojeMais(0), null));
        assertThat(zip).hasSize(2);   // o XML da nota boa + o relatorio.csv, nada mais
        assertThat(zip.keySet()).noneMatch(nome -> nome.contains("null"));
    }

    /**
     * O pedido dizia "Empresa + mês + ano da emissão", e o filtro é um <b>intervalo</b>, que pode
     * cruzar meses — caso que o nome tem de saber dizer.
     */
    @Test
    void nomeDoArquivoLevaMesEAnoEOsDoisQuandoOPeriodoCruzaMeses() throws Exception {
        String token = assinarNovoTenant("nome");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        jdbc.sql("UPDATE empresa SET nome_fantasia = 'Açaí & Cia – Matriz (SP)' "
                        + "WHERE id_tenant = ? AND id_empresa = ?")
                .params(idTenant, idEmpresa).update();

        // ⚠️ Datas LITERAIS aqui, de propósito — e é a única exceção do arquivo. O que está sob
        // teste é a formatação "MM-AAAA" do nome, que não depende de "hoje": uma data relativa
        // obrigaria o teste a recalcular o mês esperado com a mesma regra do código, e o teste
        // passaria a confirmar a si mesmo. Nada aqui envelhece com o calendário.
        LocalDate inicio = LocalDate.of(2026, 3, 1);
        LocalDate fimMesmoMes = LocalDate.of(2026, 3, 31);
        LocalDate fimOutroMes = LocalDate.of(2026, 5, 20);

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", inicio.toString())
                        .param("dataFinal", fimMesmoMes.toString()))
                .andExpect(status().isOk())
                // Acento, espaço, travessão e parênteses saem do nome do arquivo.
                .andExpect(jsonPath("$.nomeArquivo").value("ACAI_CIA_MATRIZ_SP_03-2026.zip"));

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", inicio.toString())
                        .param("dataFinal", fimOutroMes.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeArquivo").value("ACAI_CIA_MATRIZ_SP_03-2026_a_05-2026.zip"));
    }

    /** Cada nota cai na pasta do SEU mês, não na pasta do início do período. */
    @Test
    void cadaNotaCaiNaPastaDoSeuMes() throws Exception {
        String token = assinarNovoTenant("meses");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        // 40 dias de distância garantem meses diferentes qualquer que seja o dia de hoje.
        LocalDate antiga = hojeMais(-40);
        LocalDate recente = hojeMais(-1);

        String chaveAntiga = chaveDe(601);
        String chaveRecente = chaveDe(602);
        long docA = criarDocumento(idTenant, idEmpresa, 65, 601, chaveAntiga, "AUTORIZADO", antiga);
        long docR = criarDocumento(idTenant, idEmpresa, 65, 602, chaveRecente, "AUTORIZADO", recente);
        arquivar(idTenant, docA, 65, chaveAntiga, antiga, "<nfeProc>A</nfeProc>");
        arquivar(idTenant, docR, 65, chaveRecente, recente, "<nfeProc>R</nfeProc>");

        Map<String, byte[]> zip = lerZip(baixarZip(token, idEmpresa, hojeMais(-60), hojeMais(0), null));
        assertThat(zip).containsKeys(
                pastaDoMes(antiga) + "/saidas/" + chaveAntiga + ".xml",
                pastaDoMes(recente) + "/saidas/" + chaveRecente + ".xml");
        assertThat(pastaDoMes(antiga)).isNotEqualTo(pastaDoMes(recente));
    }

    @Test
    void operadorNaoExporta() throws Exception {
        String token = assinarNovoTenant("operador");
        long idEmpresa = idEmpresaDo(token);
        String operador = comoOperador(token);

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + operador)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/fiscal/exportacao-xml")
                        .header("Authorization", "Bearer " + operador)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isForbidden());
    }

    /** P8: nem o documento nem a empresa de outro tenant aparecem — RLS + filtro explícito. */
    @Test
    void naoEnxergaDocumentoNemEmpresaDeOutroTenant() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a");
        long idTenantA = idTenantDo(tokenA);
        long idEmpresaA = idEmpresaDo(tokenA);
        LocalDate ontem = hojeMais(-1);
        String chaveA = chaveDe(701);
        long docA = criarDocumento(idTenantA, idEmpresaA, 65, 701, chaveA, "AUTORIZADO", ontem);
        arquivar(idTenantA, docA, 65, chaveA, ontem, "<nfeProc>DO A</nfeProc>");

        String tokenB = assinarNovoTenant("tenant-b");
        long idEmpresaB = idEmpresaDo(tokenB);

        // A empresa do tenant A não existe para o tenant B.
        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + tokenB)
                        .param("idEmpresa", String.valueOf(idEmpresaA))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isNotFound());

        // E na empresa dele o período está vazio, apesar de o tenant A ter nota no mesmo dia.
        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + tokenB)
                        .param("idEmpresa", String.valueOf(idEmpresaB))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDocumentos").value(0));
    }

    @Test
    void periodoInvertidoOuLongoDemaisEhRecusado() throws Exception {
        String token = assinarNovoTenant("periodo");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(0).toString())
                        .param("dataFinal", hojeMais(-1).toString()))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-400).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString())
                        .param("modelo", "99"))
                .andExpect(status().isBadRequest());
    }

    /**
     * ⚠️ Chave no banco e objeto ausente no bucket (arquivamento interrompido, objeto removido à
     * mão) não pode derrubar a exportação inteira: o documento sai do pacote e o
     * {@code relatorio.csv} registra. Só o <b>404</b> é engolido — 403 e 503 continuam subindo.
     */
    @Test
    void objetoAusenteNoBucketNaoDerrubaOPacote() throws Exception {
        String token = assinarNovoTenant("orfao");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String boa = chaveDe(801);
        String orfa = chaveDe(802);
        long docBom = criarDocumento(idTenant, idEmpresa, 65, 801, boa, "AUTORIZADO", ontem);
        long docOrfao = criarDocumento(idTenant, idEmpresa, 65, 802, orfa, "AUTORIZADO", ontem);
        arquivar(idTenant, docBom, 65, boa, ontem, "<nfeProc>BOA</nfeProc>");
        // Chave plausível, do tenant certo, apontando para um objeto que nunca foi gravado.
        jdbc.sql("""
                        UPDATE documento_fiscal SET xml_objeto_bucket = ?
                         WHERE id_tenant = ? AND id_documento_fiscal = ?
                        """)
                .params("tenants/%d/fiscal/%d/%02d/65/%s.xml"
                                .formatted(idTenant, ontem.getYear(), ontem.getMonthValue(), orfa),
                        idTenant, docOrfao)
                .update();

        Map<String, byte[]> zip = lerZip(baixarZip(token, idEmpresa, hojeMais(-5), hojeMais(0), null));
        assertThat(zip).containsKey(pastaDoMes(ontem) + "/saidas/" + boa + ".xml");
        assertThat(zip).doesNotContainKey(pastaDoMes(ontem) + "/saidas/" + orfa + ".xml");
        assertThat(new String(zip.get("relatorio.csv"), StandardCharsets.UTF_8))
                .contains(orfa).contains("(nao arquivado)");
    }

    // ------------------------------------------------------- exportação particionada (2026-08-26)

    /**
     * A conta que decide em quantos ZIPs o período sai. Arredonda <b>para cima</b>: 2.001 notas com
     * teto 2.000 são 2 partes, não 1 — errar para baixo deixaria a última nota fora do pacote.
     *
     * <p>Zero documentos = 1 parte, e não zero: com zero partes a tela não teria o que pedir e o
     * lojista não veria a mensagem que explica que não há nota no período.
     */
    @Test
    void totalDePartesArredondaParaCimaENuncaEhZero() {
        assertThat(ExportacaoXmlLoteService.calcularTotalPartes(0)).isEqualTo(1);
        assertThat(ExportacaoXmlLoteService.calcularTotalPartes(1)).isEqualTo(1);
        assertThat(ExportacaoXmlLoteService.calcularTotalPartes(2000)).isEqualTo(1);
        assertThat(ExportacaoXmlLoteService.calcularTotalPartes(2001)).isEqualTo(2);
        assertThat(ExportacaoXmlLoteService.calcularTotalPartes(4000)).isEqualTo(2);
        assertThat(ExportacaoXmlLoteService.calcularTotalPartes(4001)).isEqualTo(3);
    }

    /** A pré-conferência entrega o que a tela precisa para pedir as partes. */
    @Test
    void resumoInformaQuantasPartesEOTetoDeId() throws Exception {
        String token = assinarNovoTenant("partes-resumo");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String chave = chaveDe(801);
        long doc = criarDocumento(idTenant, idEmpresa, 65, 801, chave, "AUTORIZADO", ontem);
        arquivar(idTenant, doc, 65, chave, ontem, "<nfeProc>A</nfeProc>");

        mvc.perform(get("/api/v1/fiscal/exportacao-xml/resumo")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPartes").value(1))
                .andExpect(jsonPath("$.ateIdDocumento").value((int) doc));
    }

    /**
     * ⭐ <b>O teste que prende a decisão de particionar.</b>
     *
     * O lojista pede a pré-conferência (que congela o teto), e <b>enquanto baixa</b> a loja emite
     * outra nota — o caso comum de exportar o mês corrente durante o expediente. Com o teto, a nota
     * nova simplesmente não entra no pacote que já estava sendo montado.
     *
     * ⚠️ Sem esse congelamento o defeito seria <b>invisível</b>: a nota nova entraria no meio da
     * ordenação por data, empurrando as demais, e o {@code OFFSET} da parte seguinte passaria a
     * pular um documento que ninguém baixou — sem erro, sem aviso, e só descoberto na fiscalização.
     */
    @Test
    void tetoDeIdCongelaOConjuntoEUmaNotaEmitidaDepoisNaoEntra() throws Exception {
        String token = assinarNovoTenant("teto");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String chaveA = chaveDe(811);
        String chaveB = chaveDe(812);
        long docA = criarDocumento(idTenant, idEmpresa, 65, 811, chaveA, "AUTORIZADO", ontem);
        long docB = criarDocumento(idTenant, idEmpresa, 65, 812, chaveB, "AUTORIZADO", ontem);
        arquivar(idTenant, docA, 65, chaveA, ontem, "<nfeProc>A</nfeProc>");
        arquivar(idTenant, docB, 65, chaveB, ontem, "<nfeProc>B</nfeProc>");

        // ... a tela leu o resumo aqui; o teto é o maior id existente NESTE instante.
        long teto = docB;

        // ... e agora a loja emite mais uma, no mesmo período, enquanto o download acontece.
        String chaveC = chaveDe(813);
        long docC = criarDocumento(idTenant, idEmpresa, 65, 813, chaveC, "AUTORIZADO", ontem);
        arquivar(idTenant, docC, 65, chaveC, ontem, "<nfeProc>C</nfeProc>");

        byte[] pacote = mvc.perform(get("/api/v1/fiscal/exportacao-xml")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString())
                        .param("parte", "1")
                        .param("ateIdDocumento", String.valueOf(teto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String pasta = pastaDoMes(ontem);
        Map<String, byte[]> zip = lerZip(pacote);
        assertThat(zip).containsKeys(pasta + "/saidas/" + chaveA + ".xml", pasta + "/saidas/" + chaveB + ".xml");
        assertThat(zip).doesNotContainKey(pasta + "/saidas/" + chaveC + ".xml");

        // E sem o teto ela entra — é o que prova que o filtro acima é o responsável, e não um acaso
        // da ordenação (o mesmo pedido, mudando só o parâmetro, traz as três).
        Map<String, byte[]> semTeto = lerZip(baixarZip(token, idEmpresa, hojeMais(-5), hojeMais(0), null));
        assertThat(semTeto).containsKey(pasta + "/saidas/" + chaveC + ".xml");
    }

    /** Pedir uma parte que não existe é erro do cliente, não um ZIP vazio entregue como se fosse o pacote. */
    @Test
    void parteInexistenteEhRecusadaEmVezDeVirVazia() throws Exception {
        String token = assinarNovoTenant("parte-inexistente");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        LocalDate ontem = hojeMais(-1);

        String chave = chaveDe(821);
        long doc = criarDocumento(idTenant, idEmpresa, 65, 821, chave, "AUTORIZADO", ontem);
        arquivar(idTenant, doc, 65, chave, ontem, "<nfeProc>A</nfeProc>");

        mvc.perform(get("/api/v1/fiscal/exportacao-xml")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString())
                        .param("parte", "2"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/fiscal/exportacao-xml")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("dataInicial", hojeMais(-5).toString())
                        .param("dataFinal", hojeMais(0).toString())
                        .param("parte", "0"))
                .andExpect(status().isBadRequest());
    }
}
