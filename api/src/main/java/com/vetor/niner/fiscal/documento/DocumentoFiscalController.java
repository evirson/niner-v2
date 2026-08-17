package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.ConsultaSefazResponse;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.PaginaDocumentosFiscais;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.ReprocessamentoResponse;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.XmlDocumentoFiscalResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Documentos Fiscais (§12, bloco B8), superfície do tenant (`/api/v1`, JWT + RLS). ADMIN-only. */
@RestController
@RequestMapping("/api/v1/fiscal/documentos")
public class DocumentoFiscalController {

    private final DocumentoFiscalConsultaService service;
    private final DocumentoFiscalReprocessamentoService reprocessamento;

    public DocumentoFiscalController(DocumentoFiscalConsultaService service,
                                     DocumentoFiscalReprocessamentoService reprocessamento) {
        this.service = service;
        this.reprocessamento = reprocessamento;
    }

    @GetMapping
    public PaginaDocumentosFiscais listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam long idEmpresa,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Integer modelo,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite) {
        return service.listar(jwt, idEmpresa, dataInicial, dataFinal, modelo, situacao, pagina, limite);
    }

    @GetMapping("/{idDocumentoFiscal}/xml")
    public XmlDocumentoFiscalResponse xml(@AuthenticationPrincipal Jwt jwt, @PathVariable long idDocumentoFiscal) {
        return service.buscarXml(jwt, idDocumentoFiscal);
    }

    @PostMapping("/{idDocumentoFiscal}/consultar-sefaz")
    public ConsultaSefazResponse consultarSefaz(@AuthenticationPrincipal Jwt jwt, @PathVariable long idDocumentoFiscal) {
        return service.consultarNaSefaz(jwt, idDocumentoFiscal);
    }

    @PostMapping("/{idDocumentoFiscal}/reprocessar")
    public ReprocessamentoResponse reprocessar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idDocumentoFiscal) {
        return reprocessamento.reprocessar(jwt, idDocumentoFiscal);
    }
}
