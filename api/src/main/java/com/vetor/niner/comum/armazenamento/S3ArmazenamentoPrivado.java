package com.vetor.niner.comum.armazenamento;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;

/**
 * Adapter do object storage privado sobre a API S3 (ADR-014). Hoje aponta para o <b>MinIO</b> do
 * docker-compose (e, quando o volume justificar, para o MinIO de um VPS dedicado); a mesma classe
 * serve para Cloudflare R2 ou S3 da AWS sem alteração — o que muda é endpoint e credencial.
 *
 * <p>O {@link S3Client} injetado é {@code @Lazy} ({@link ArmazenamentoConfig}): a API sobe mesmo
 * com o MinIO fora do ar, e a falha aparece no primeiro uso real, com mensagem própria, em vez de
 * derrubar o contexto do Spring na subida.
 *
 * <p><b>Três guardas que não são detalhe de implementação</b> e por isso vivem aqui, e não em
 * quem chama: (1) o prefixo do tenant vem do {@code TenantContext} e é conferido na leitura (P8);
 * (2) área imutável recusa sobrescrita (F6); (3) área imutável recusa exclusão. A credencial da
 * API também não tem permissão de apagar no bucket fiscal — as duas proteções são de propósito.
 */
@Component
public class S3ArmazenamentoPrivado implements ArmazenamentoPrivado {

    private final NinerProperties.Privado props;
    private final S3Client s3;

    public S3ArmazenamentoPrivado(NinerProperties props, S3Client s3ClientPrivado) {
        this.props = props.storage().privado();
        this.s3 = s3ClientPrivado;
    }

    @Override
    public String gravar(AreaPrivada area, String caminhoRelativo, byte[] conteudo, String contentType) {
        String chave = prefixoDoTenant(area) + validarCaminhoRelativo(caminhoRelativo);
        if (area.imutavel() && existeNoBucket(area, chave)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já existe arquivo gravado em '%s' e esta área é imutável — grave em um caminho novo."
                            .formatted(chave));
        }
        try {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket(area))
                    .key(chave)
                    .contentType(contentType)
                    .build(), RequestBody.fromBytes(conteudo));
        } catch (RuntimeException e) {
            throw erroDeArmazenamento(e);
        }
        return chave;
    }

    @Override
    public byte[] ler(AreaPrivada area, String chave) {
        exigirChaveDoTenant(area, chave);
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket(area))
                    .key(chave)
                    .build()).asByteArray();
        } catch (RuntimeException e) {
            if (naoEncontrado(e)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Arquivo não encontrado no armazenamento: " + chave, e);
            }
            throw erroDeArmazenamento(e);
        }
    }

    @Override
    public boolean existe(AreaPrivada area, String chave) {
        exigirChaveDoTenant(area, chave);
        return existeNoBucket(area, chave);
    }

    @Override
    public void apagar(AreaPrivada area, String chave) {
        exigirChaveDoTenant(area, chave);
        if (area.imutavel()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Arquivo de área imutável não pode ser apagado (guarda legal de 5 anos): " + chave);
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket(area)).key(chave).build());
        } catch (RuntimeException e) {
            if (!naoEncontrado(e)) {   // idempotente: objeto já ausente não é erro
                throw erroDeArmazenamento(e);
            }
        }
    }

    @Override
    public List<String> listar(AreaPrivada area, String prefixoRelativo) {
        String prefixo = prefixoDoTenant(area) + (prefixoRelativo == null ? "" : prefixoRelativo);
        try {
            return s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(bucket(area))
                            .prefix(prefixo)
                            .build())
                    .contents().stream()
                    .map(S3Object::key)
                    .toList();
        } catch (RuntimeException e) {
            throw erroDeArmazenamento(e);
        }
    }

    // ---------------------------------------------------------------------------------------

    private String bucket(AreaPrivada area) {
        return area.imutavel() ? props.bucketFiscal() : props.bucketPrivado();
    }

    /** {@code tenants/{id_tenant}/{área}/} — sempre do contexto, nunca de parâmetro (P8). */
    private String prefixoDoTenant(AreaPrivada area) {
        return "tenants/%d/%s/".formatted(TenantContext.idTenantAtual(), area.prefixo());
    }

    /**
     * Recusa chave que não pertença ao tenant vigente. Vale mesmo para chave lida do banco: se um
     * SELECT esquecer o filtro de {@code id_tenant} (o bug que a auditoria de 2026-08-08 provou
     * ser possível), o vazamento para aqui em vez de virar download do arquivo de outra loja.
     */
    private void exigirChaveDoTenant(AreaPrivada area, String chave) {
        String prefixo = prefixoDoTenant(area);
        if (chave == null || !chave.startsWith(prefixo)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chave de armazenamento fora do escopo do tenant atual.");
        }
    }

    private boolean existeNoBucket(AreaPrivada area, String chave) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket(area)).key(chave).build());
            return true;
        } catch (RuntimeException e) {
            if (naoEncontrado(e)) {
                return false;
            }
            throw erroDeArmazenamento(e);
        }
    }

    /**
     * "Objeto não existe" chega de duas formas conforme a operação e o servidor: {@code HeadObject}
     * não tem corpo de erro para o SDK desserializar, então costuma vir como {@link S3Exception}
     * 404 crua em vez de {@link NoSuchKeyException}. Tratar só uma das duas deixa um "não existe"
     * virando 503.
     */
    private static boolean naoEncontrado(RuntimeException e) {
        return e instanceof NoSuchKeyException
                || (e instanceof S3Exception s3e && s3e.statusCode() == 404);
    }

    private static String validarCaminhoRelativo(String caminho) {
        if (caminho == null || caminho.isBlank()) {
            throw new IllegalArgumentException("Caminho do arquivo não pode ser vazio.");
        }
        // "../" escaparia do prefixo do tenant assim que qualquer cliente S3 normalizasse a chave.
        if (caminho.startsWith("/") || caminho.contains("..")) {
            throw new IllegalArgumentException("Caminho do arquivo inválido: " + caminho);
        }
        return caminho;
    }

    /**
     * Traduz falha crua do cliente S3 (MinIO fora do ar, credencial errada, bucket inexistente)
     * numa resposta clara — mesmo tratamento que {@link GcsArmazenamento} dá ao GCS.
     */
    private static ResponseStatusException erroDeArmazenamento(RuntimeException e) {
        if (e instanceof ResponseStatusException jaTratada) {
            return jaTratada;
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível acessar o armazenamento privado — verifique o MinIO e as credenciais "
                        + "(docs/infra/armazenamento-privado-minio.md).", e);
    }
}
