package com.vetor.niner.comum.armazenamento;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Adapter de verdade do object storage — Google Cloud Storage / Firebase Storage (ADR-013).
 * O cliente {@link Storage} injetado é {@code @Lazy} ({@link ArmazenamentoConfig}): só
 * autentica no primeiro uso real, não na subida do contexto Spring — a API sobe normalmente
 * mesmo sem credencial de GCS configurada (dev sem `gcloud`/ADC ainda).
 */
@Component
public class GcsArmazenamento implements ArmazenamentoDeArquivos {

    private final NinerProperties.Storage props;
    private final Storage storage;

    public GcsArmazenamento(NinerProperties props, Storage storage) {
        this.props = props.storage();
        this.storage = storage;
    }

    @Override
    public String gravar(long idProduto, byte[] conteudo, String extensao) {
        long idTenant = TenantContext.idTenantAtual();
        String chave = "tenants/%d/produtos/%d/%s.%s".formatted(idTenant, idProduto, UUID.randomUUID(), extensao);
        BlobId blobId = BlobId.of(props.bucket(), chave);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentTypePorExtensao(extensao))
                .build();
        try {
            storage.create(blobInfo, conteudo);
        } catch (StorageException e) {
            throw erroDeArmazenamento(e);
        }
        return chave;
    }

    @Override
    public void apagar(String chave) {
        try {
            Blob existente = storage.get(BlobId.of(props.bucket(), chave));
            if (existente != null) {
                existente.delete();
            }
        } catch (StorageException e) {
            throw erroDeArmazenamento(e);
        }
    }

    /** Traduz falha crua do cliente do GCS (ex.: sem credencial configurada — comum em dev
     * sem `gcloud`/ADC ainda) numa resposta clara, em vez de deixar a exceção vazar crua. */
    private static ResponseStatusException erroDeArmazenamento(StorageException e) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível acessar o armazenamento de imagens — verifique a credencial do GCS "
                        + "configurada (docs/infra/armazenamento-imagens.md §3).",
                e);
    }

    @Override
    public String urlPublica(String chave) {
        return props.baseUrl() + "/" + props.bucket() + "/" + chave;
    }

    private static String contentTypePorExtensao(String extensao) {
        return switch (extensao) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }
}
