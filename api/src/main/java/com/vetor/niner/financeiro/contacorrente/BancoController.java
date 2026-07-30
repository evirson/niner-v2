package com.vetor.niner.financeiro.contacorrente;

import com.vetor.niner.financeiro.contacorrente.BancoDtos.BancoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de banco (docs/telas/conta-corrente.md) — usada pelo formulário de conta corrente
 * para mostrar o nome do banco ao lado do código digitado. Sem POST/PUT/DELETE: {@code
 * cfg_banco} é global e mantida por script, não pela aplicação.
 */
@RestController
@RequestMapping("/api/v1/bancos")
public class BancoController {

    private final BancoService service;

    public BancoController(BancoService service) {
        this.service = service;
    }

    @GetMapping("/{codigo}")
    public BancoResponse buscar(@PathVariable String codigo) {
        return service.buscar(codigo);
    }
}
