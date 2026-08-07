package com.vetor.niner.comum.arquivocompartilhado;

import com.vetor.niner.comum.arquivocompartilhado.ArquivoCompartilhadoDtos.ArquivoCompartilhadoResponse;
import com.vetor.niner.comum.tenant.TenantContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Upload (superfície do tenant, `/api/v1`, JWT) + download (superfície pública, `/api/publico`,
 * sem auth — quem clica no link é o cliente final, no WhatsApp, sem sessão no sistema).
 */
@RestController
public class ArquivoCompartilhadoController {

    /** Assinatura de um PDF válido: "%PDF-" nos primeiros bytes (RFC do formato). Único tipo
     * aceito hoje — o único caso de uso é comprovante em PDF. */
    private static final byte[] ASSINATURA_PDF = {0x25, 0x50, 0x44, 0x46, 0x2D};
    private static final long TAMANHO_MAXIMO_BYTES = 5 * 1024 * 1024;

    private final ArquivoCompartilhadoService service;

    public ArquivoCompartilhadoController(ArquivoCompartilhadoService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/arquivos-compartilhados")
    @ResponseStatus(HttpStatus.CREATED)
    public ArquivoCompartilhadoResponse compartilhar(@RequestParam("arquivo") MultipartFile arquivo) {
        if (arquivo.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo enviado.");
        }
        if (arquivo.getSize() > TAMANHO_MAXIMO_BYTES) {
            throw new IllegalArgumentException("Arquivo maior que o limite de 5 MB.");
        }
        byte[] conteudo = lerBytes(arquivo);
        validarPdf(conteudo);
        String token = service.armazenar(TenantContext.idTenantAtual(), conteudo, MediaType.APPLICATION_PDF_VALUE);
        return new ArquivoCompartilhadoResponse(token);
    }

    @GetMapping("/api/publico/arquivos-compartilhados/{token}")
    public ResponseEntity<byte[]> baixar(@PathVariable String token) {
        return service.buscar(token)
                .map(arquivo -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(arquivo.tipoConteudo()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"comprovante.pdf\"")
                        .body(arquivo.conteudo()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private static byte[] lerBytes(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Valida pelo conteúdo (magic bytes), nunca pelo Content-Type/extensão que o cliente manda —
     * mesmo princípio já usado em {@code catalogo.ProdutoImagemService}. */
    private static void validarPdf(byte[] b) {
        boolean pdf = b.length >= ASSINATURA_PDF.length;
        for (int i = 0; pdf && i < ASSINATURA_PDF.length; i++) {
            pdf = b[i] == ASSINATURA_PDF[i];
        }
        if (!pdf) {
            throw new IllegalArgumentException("Arquivo não é um PDF válido.");
        }
    }
}
