package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.ProdutoImagemDtos.ImagemResponse;
import com.vetor.niner.catalogo.ProdutoImagemDtos.ReordenarImagensRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Galeria de fotos de produto (ADR-013), superfície do tenant. Cada endpoint devolve a
 * galeria inteira já atualizada — o front só troca o estado local pela resposta, nunca monta
 * URL ou calcula índice sozinho (P4).
 */
@RestController
@RequestMapping("/api/v1/produtos/{idProduto}/imagens")
public class ProdutoImagemController {

    private final ProdutoImagemService service;

    public ProdutoImagemController(ProdutoImagemService service) {
        this.service = service;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ImagemResponse> adicionar(@PathVariable long idProduto, @RequestParam("arquivo") MultipartFile arquivo) {
        return service.adicionar(idProduto, arquivo);
    }

    @DeleteMapping("/{idImagem}")
    public List<ImagemResponse> excluir(@PathVariable long idProduto, @PathVariable long idImagem) {
        return service.excluir(idProduto, idImagem);
    }

    @PutMapping("/ordem")
    public List<ImagemResponse> reordenar(@PathVariable long idProduto, @Valid @RequestBody ReordenarImagensRequest req) {
        return service.reordenar(idProduto, req.idsImagem());
    }
}
