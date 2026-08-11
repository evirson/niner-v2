package com.vetor.niner.estoque.entrada;

import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.AtualizarItemEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EfetivarEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaDetalheResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaEfetivadaResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemPlanilhaPreviewResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.PaginaEntradas;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Entrada de Produtos por Compra (docs/telas/entrada-mercadoria.md), superfície do tenant
 * (`/api/v1`, JWT + RLS). ADMIN e OPERADOR têm acesso completo (sem restrição de papel — mesmo
 * nível de Transferência de Estoque/Devolução de Produtos).
 */
@RestController
@RequestMapping("/api/v1/estoque/entradas")
public class EntradaMercadoriaController {

    private final EntradaMercadoriaService service;
    private final EntradaPlanilhaService planilhaService;

    public EntradaMercadoriaController(EntradaMercadoriaService service, EntradaPlanilhaService planilhaService) {
        this.service = service;
        this.planilhaService = planilhaService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntradaEfetivadaResponse efetivar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EfetivarEntradaRequest req) {
        return service.efetivar(jwt, req);
    }

    @GetMapping
    public PaginaEntradas listar(
            @RequestParam(required = false) Long idFornecedor,
            @RequestParam(required = false) Integer notaFiscal,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(idFornecedor, notaFiscal, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public EntradaDetalheResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PutMapping("/{id}/itens/{idDetalhe}")
    public void atualizarItem(@PathVariable long id, @PathVariable long idDetalhe,
                               @Valid @RequestBody AtualizarItemEntradaRequest req) {
        service.atualizarItem(id, idDetalhe, req);
    }

    /** Fluxo Planilha (2026-08-12) — só lê e tenta casar cada linha, nada é persistido no
     *  ledger (ver {@link EntradaPlanilhaService}). */
    @PostMapping(value = "/planilha/preview", consumes = "multipart/form-data")
    public List<ItemPlanilhaPreviewResponse> previewPlanilha(@RequestPart("arquivo") MultipartFile arquivo) {
        return planilhaService.preview(arquivo);
    }

    @GetMapping("/planilha/modelo")
    public ResponseEntity<byte[]> modeloPlanilha() {
        byte[] planilha = planilhaService.modelo();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("entrada_produtos_modelo.xlsx").build().toString())
                .body(planilha);
    }
}
