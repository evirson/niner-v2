package com.vetor.niner.fiscal.certificado;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.certificado.FiscalCertificadoDtos.FiscalCertificadoResponse;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoDtos.FiscalCertificadoUsoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Certificado Digital (docs/telas/fiscal-certificado.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). <b>Somente ADMIN</b> em todas as rotas. Sem {@code GET /{id}/arquivo}, sem
 * {@code DELETE}, sem {@code PUT} — a ausência é o design (write-only, histórico imutável).
 */
@RestController
@RequestMapping("/api/v1/fiscal/certificados")
@Tela("fiscal.certificados")
public class FiscalCertificadoController {

    private final FiscalCertificadoService service;

    public FiscalCertificadoController(FiscalCertificadoService service) {
        this.service = service;
    }

    @GetMapping
    public List<FiscalCertificadoResponse> listar(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam long idEmpresa) {
        return service.listar(jwt, idEmpresa);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FiscalCertificadoResponse enviar(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam long idEmpresa,
                                            @RequestParam("arquivo") MultipartFile arquivo,
                                            @RequestParam String senha) {
        return service.enviar(jwt, idEmpresa, arquivo, senha);
    }

    @GetMapping("/{id}/usos")
    public List<FiscalCertificadoUsoResponse> listarUsos(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable long id) {
        return service.listarUsos(jwt, id);
    }
}
