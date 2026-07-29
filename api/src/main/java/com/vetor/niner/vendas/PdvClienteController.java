package com.vetor.niner.vendas;

import com.vetor.niner.vendas.PdvDtos.PdvClienteResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Busca de cliente pro PDV (docs/telas/pdv.md, F6) — nome, CPF/CNPJ ou celular. */
@RestController
@RequestMapping("/api/v1/pdv/clientes")
public class PdvClienteController {

    private final PdvClienteService service;

    public PdvClienteController(PdvClienteService service) {
        this.service = service;
    }

    @GetMapping
    public List<PdvClienteResponse> buscar(@RequestParam(required = false) String busca) {
        return service.buscar(busca);
    }
}
