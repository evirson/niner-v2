package com.vetor.niner;

import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.comum.armazenamento.S3ArmazenamentoPrivado;
import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.tenant.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DefaultRetention;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRule;
import software.amazon.awssdk.services.s3.model.PutObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Object storage privado (ADR-014, {@code docs/infra/armazenamento-privado-minio.md}) contra um
 * <b>MinIO de verdade</b> em container — a mesma imagem do docker-compose. Nenhum teste toca o
 * MinIO de desenvolvimento.
 *
 * <p>Não usa {@code @SpringBootTest}: o adapter só precisa das propriedades e do cliente S3, e
 * montar os dois à mão deixa o teste rápido e focado nas garantias que ele existe para provar —
 * isolamento de tenant (P8), imutabilidade do XML fiscal (F6) e WORM no bucket.
 */
class ArmazenamentoPrivadoTest {

    private static final String BUCKET_FISCAL = "niner-fiscal-test";
    private static final String BUCKET_PRIVADO = "niner-privado-test";
    private static final String USUARIO = "teste";
    private static final String SENHA = "teste12345";

    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
                    .withEnv("MINIO_ROOT_USER", USUARIO)
                    .withEnv("MINIO_ROOT_PASSWORD", SENHA)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));

    private static S3Client s3;
    private static ArmazenamentoPrivado armazenamento;

    @BeforeAll
    static void subirMinio() {
        MINIO.start();
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);

        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(USUARIO, SENHA)))
                .forcePathStyle(true)
                .build();

        // Bucket fiscal com WORM — o mesmo que infra/minio/bootstrap.sh faz com `mc mb --with-lock`
        // + `mc retention set --default GOVERNANCE 1825d`. Object Lock só pode ser ligado na
        // criação do bucket, e liga versionamento junto.
        s3.createBucket(CreateBucketRequest.builder()
                .bucket(BUCKET_FISCAL).objectLockEnabledForBucket(true).build());
        s3.putObjectLockConfiguration(PutObjectLockConfigurationRequest.builder()
                .bucket(BUCKET_FISCAL)
                .objectLockConfiguration(ObjectLockConfiguration.builder()
                        .objectLockEnabled(ObjectLockEnabled.ENABLED)
                        .rule(ObjectLockRule.builder()
                                .defaultRetention(DefaultRetention.builder()
                                        .mode(ObjectLockRetentionMode.GOVERNANCE)
                                        .days(1825)
                                        .build())
                                .build())
                        .build())
                .build());
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET_PRIVADO).build());

        NinerProperties.Privado privado = new NinerProperties.Privado(
                endpoint, USUARIO, SENHA, "us-east-1", BUCKET_FISCAL, BUCKET_PRIVADO);
        NinerProperties props = new NinerProperties(null, null, null,
                new NinerProperties.Storage(null, null, null, privado), null, null, null, null);
        armazenamento = new S3ArmazenamentoPrivado(props, s3);
    }

    @Test
    void gravaComPrefixoDoTenantEDevolveOsMesmosBytes() {
        String chave = TenantContext.comTenant(12, () ->
                armazenamento.gravar(AreaPrivada.FISCAL_XML, "2026/08/65/4112.xml",
                        "<nfeProc/>".getBytes(StandardCharsets.UTF_8), "application/xml"));

        assertThat(chave).isEqualTo("tenants/12/fiscal/2026/08/65/4112.xml");
        assertThat(TenantContext.comTenant(12, () -> armazenamento.ler(AreaPrivada.FISCAL_XML, chave)))
                .asString(StandardCharsets.UTF_8).isEqualTo("<nfeProc/>");
    }

    @Test
    void naoLeArquivoDeOutroTenant() {
        String chave = TenantContext.comTenant(30, () ->
                armazenamento.gravar(AreaPrivada.CLIENTE_FOTO, "800/foto.webp",
                        new byte[]{1, 2, 3}, "image/webp"));

        // O objeto existe e o cliente S3 tem permissão de lê-lo — quem recusa é o adapter, pelo
        // prefixo do tenant. É a defesa que segura um SELECT sem filtro de id_tenant (P8).
        assertThatThrownBy(() -> TenantContext.comTenant(31, () ->
                armazenamento.ler(AreaPrivada.CLIENTE_FOTO, chave)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void areaFiscalRecusaSobrescreverEApagar() {
        byte[] xml = "<nfeProc>original</nfeProc>".getBytes(StandardCharsets.UTF_8);
        String chave = TenantContext.comTenant(40, () ->
                armazenamento.gravar(AreaPrivada.FISCAL_XML, "2026/08/65/9001.xml", xml, "application/xml"));

        assertThatThrownBy(() -> TenantContext.comTenant(40, () ->
                armazenamento.gravar(AreaPrivada.FISCAL_XML, "2026/08/65/9001.xml",
                        "<nfeProc>adulterado</nfeProc>".getBytes(StandardCharsets.UTF_8), "application/xml")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        assertThatThrownBy(() -> TenantContext.comTenant(40, () ->
                armazenamento.apagar(AreaPrivada.FISCAL_XML, chave)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        assertThat(TenantContext.comTenant(40, () -> armazenamento.ler(AreaPrivada.FISCAL_XML, chave)))
                .isEqualTo(xml);
    }

    @Test
    void bucketFiscalBloqueiaExclusaoMesmoPassandoPorFora_doAdapter() {
        // A garantia de código já foi provada acima; esta prova a do BUCKET — se um dia alguém
        // escrever um DELETE direto pelo SDK, a retenção do MinIO recusa do mesmo jeito.
        String versao = s3.putObject(PutObjectRequest.builder()
                        .bucket(BUCKET_FISCAL).key("tenants/50/fiscal/worm.xml").build(),
                RequestBody.fromString("<nfeProc/>")).versionId();

        assertThatThrownBy(() -> s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(BUCKET_FISCAL).key("tenants/50/fiscal/worm.xml").versionId(versao).build()))
                .isInstanceOf(S3Exception.class);
    }

    @Test
    void areaDeDadoPessoalApagaDeVerdade() {
        String chave = TenantContext.comTenant(60, () ->
                armazenamento.gravar(AreaPrivada.CLIENTE_FOTO, "77/foto.webp", new byte[]{9}, "image/webp"));

        TenantContext.comTenant(60, () -> {
            assertThat(armazenamento.existe(AreaPrivada.CLIENTE_FOTO, chave)).isTrue();
            armazenamento.apagar(AreaPrivada.CLIENTE_FOTO, chave);
            assertThat(armazenamento.existe(AreaPrivada.CLIENTE_FOTO, chave)).isFalse();
            armazenamento.apagar(AreaPrivada.CLIENTE_FOTO, chave);   // idempotente
        });
    }

    @Test
    void listagemNaoAtravessaTenant() {
        TenantContext.comTenant(70, () ->
                armazenamento.gravar(AreaPrivada.FISCAL_XML, "2026/09/65/a.xml", new byte[]{1}, "application/xml"));
        TenantContext.comTenant(71, () ->
                armazenamento.gravar(AreaPrivada.FISCAL_XML, "2026/09/65/b.xml", new byte[]{2}, "application/xml"));

        List<String> do70 = TenantContext.comTenant(70, () -> armazenamento.listar(AreaPrivada.FISCAL_XML, "2026/09/"));

        assertThat(do70).containsExactly("tenants/70/fiscal/2026/09/65/a.xml");
    }

    @Test
    void naoGravaSemTenantNoContexto() {
        assertThatThrownBy(() -> armazenamento.gravar(AreaPrivada.FISCAL_XML, "2026/08/65/x.xml",
                new byte[]{1}, "application/xml"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recusaCaminhoQueEscapaDoPrefixoDoTenant() {
        assertThatThrownBy(() -> TenantContext.comTenant(80, () ->
                armazenamento.gravar(AreaPrivada.CLIENTE_FOTO, "../../tenants/81/clientes/roubo.webp",
                        new byte[]{1}, "image/webp")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
