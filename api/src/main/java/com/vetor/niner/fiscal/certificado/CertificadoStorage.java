package com.vetor.niner.fiscal.certificado;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Adapter do bucket fiscal — <b>privado e separado</b> do bucket de fotos de produto (DF21,
 * 2026-08-17). Reaproveita o mesmo cliente {@link Storage} {@code @Lazy} de
 * {@code comum.armazenamento.ArmazenamentoConfig} (mesma credencial/host), só troca o nome do
 * bucket. Nunca expõe URL pública — o `.pfx` não tem rota de leitura (F7, write-only de verdade).
 */
@Component
class CertificadoStorage {

    private final String bucket;
    private final Storage storage;
    private volatile boolean bucketConferido = false;

    CertificadoStorage(NinerProperties props, Storage storage) {
        this.bucket = props.storage().bucketFiscal();
        this.storage = storage;
    }

    /** Grava o {@code .pfx} tal como recebido e devolve a chave do objeto (nunca a URL). */
    String gravar(long idTenant, long idEmpresa, byte[] conteudo) {
        garantirBucketExisteEmDev();
        String chave = "tenants/%d/empresas/%d/certificados/%s.pfx".formatted(idTenant, idEmpresa, UUID.randomUUID());
        BlobId blobId = BlobId.of(bucket, chave);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/x-pkcs12").build();
        try {
            storage.create(blobInfo, conteudo);
        } catch (StorageException e) {
            throw erroDeArmazenamento(e);
        }
        return chave;
    }

    /**
     * Cria o bucket se faltar — só relevante em dev/teste (emulador fake-gcs-server ou GCS
     * local, sem credencial). Em GCS real a conta de serviço normalmente só tem permissão de
     * objeto, não de bucket; uma falha aqui é engolida de propósito e o erro real (se houver)
     * aparece em {@link #gravar}, na tentativa de escrita.
     */
    private void garantirBucketExisteEmDev() {
        if (bucketConferido) {
            return;
        }
        try {
            if (storage.get(bucket) == null) {
                storage.create(BucketInfo.of(bucket));
            }
        } catch (StorageException ignorado) {
            // Sem permissão de bucket em GCS real — o bucket já existe, provisionado fora do app.
        }
        bucketConferido = true;
    }

    private static ResponseStatusException erroDeArmazenamento(StorageException e) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível acessar o armazenamento fiscal — verifique a credencial do GCS "
                        + "configurada para o bucket fiscal (niner.storage.bucket-fiscal).", e);
    }
}
