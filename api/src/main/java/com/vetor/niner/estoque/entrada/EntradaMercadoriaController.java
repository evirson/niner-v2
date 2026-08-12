package com.vetor.niner.estoque.entrada;

import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.AtualizarItemEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.CancelamentoEntradaEfetivadoResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.CancelarEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EfetivarEntradaRequest;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaDetalheResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaEfetivadaResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaXmlPreviewResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemPlanilhaPreviewResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.PaginaEntradas;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ProdutoOpcaoEntradaResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
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
    private final EntradaXmlService xmlService;

    public EntradaMercadoriaController(EntradaMercadoriaService service, EntradaPlanilhaService planilhaService,
                                        EntradaXmlService xmlService) {
        this.service = service;
        this.planilhaService = planilhaService;
        this.xmlService = xmlService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntradaEfetivadaResponse efetivar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EfetivarEntradaRequest req) {
        return service.efetivar(jwt, req);
    }

    @GetMapping
    public PaginaEntradas listar(
            @RequestParam(required = false) Long idFornecedor,
            @RequestParam(required = false) Long idEmpresa,
            @RequestParam(required = false) Integer notaFiscal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(idFornecedor, idEmpresa, notaFiscal, dataInicial, dataFinal, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public EntradaDetalheResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    /** Pesquisa de produto do fluxo Individual (2026-08-15) — nível produto, não variação (ver
     *  {@link EntradaMercadoriaService#buscarProdutos}). */
    @GetMapping("/produtos")
    public List<ProdutoOpcaoEntradaResponse> buscarProdutos(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) String marca,
            @RequestParam(required = false) String referencia) {
        return service.buscarProdutos(busca, marca, referencia);
    }

    @PutMapping("/{id}/itens/{idDetalhe}")
    public void atualizarItem(@PathVariable long id, @PathVariable long idDetalhe,
                               @Valid @RequestBody AtualizarItemEntradaRequest req) {
        service.atualizarItem(id, idDetalhe, req);
    }

    /** Cancelamento de Entrada (2026-08-19) — ADMIN-only (ver {@link EntradaMercadoriaService#cancelar}). */
    @PostMapping("/{id}/cancelar")
    public CancelamentoEntradaEfetivadoResponse cancelar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                                           @Valid @RequestBody CancelarEntradaRequest req) {
        return service.cancelar(jwt, id, req);
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

    /** Fluxo XML (Fase 3, 2026-08-18) — parse da NF-e e tentativa de casar cada item; nada é
     *  persistido (ver {@link EntradaXmlService}). */
    @PostMapping(value = "/xml/preview", consumes = "multipart/form-data")
    public EntradaXmlPreviewResponse previewXml(@RequestPart("arquivo") MultipartFile arquivo) {
        return xmlService.preview(arquivo);
    }
}
