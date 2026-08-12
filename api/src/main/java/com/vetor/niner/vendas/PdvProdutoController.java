package com.vetor.niner.vendas;

import com.vetor.niner.vendas.PdvDtos.PdvProdutoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Busca/leitura de produto pro PDV (docs/telas/pdv.md) — F2 e leitura de código de barras. */
@RestController
@RequestMapping("/api/v1/pdv/produtos")
public class PdvProdutoController {

    private final PdvProdutoService service;

    public PdvProdutoController(PdvProdutoService service) {
        this.service = service;
    }

    @GetMapping
    public List<PdvProdutoResponse> buscar(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) String marca,
            @RequestParam(required = false) String referencia) {
        return service.buscar(busca, marca, referencia);
    }

    @GetMapping("/codigo/{codigo}")
    public PdvProdutoResponse buscarPorCodigo(@PathVariable String codigo) {
        return service.buscarPorCodigo(codigo);
    }
}
