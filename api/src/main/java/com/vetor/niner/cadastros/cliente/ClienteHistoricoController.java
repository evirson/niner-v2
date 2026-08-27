package com.vetor.niner.cadastros.cliente;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.cadastros.cliente.ClienteHistoricoDtos.ClienteHistoricoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Histórico do cliente (2026-07-23): compras, parcelas e resumo de crediário. Só leitura. */
@RestController
@RequestMapping("/api/v1/clientes/{idCliente}/historico")
@Tela("clientes")
public class ClienteHistoricoController {

    private final ClienteHistoricoService service;

    public ClienteHistoricoController(ClienteHistoricoService service) {
        this.service = service;
    }

    @GetMapping
    public ClienteHistoricoResponse historico(@PathVariable long idCliente) {
        return service.historico(idCliente);
    }
}
