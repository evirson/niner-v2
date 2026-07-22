package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.NcmDtos.NcmResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de NCM (docs/telas/produto.md) — usada pelo formulário de produto para mostrar a
 * descrição ao lado do código digitado. Sem POST/PUT/DELETE: {@code cfg_produto_ncm} é global
 * e mantida por script, não pela aplicação.
 */
@RestController
@RequestMapping("/api/v1/ncm")
public class NcmController {

    private final NcmService service;

    public NcmController(NcmService service) {
        this.service = service;
    }

    @GetMapping("/{codigo}")
    public NcmResponse buscar(@PathVariable String codigo) {
        return service.buscar(codigo);
    }
}
