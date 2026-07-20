package com.vetor.niner.cadastros.cliente;

import com.vetor.niner.cadastros.cliente.ClienteDtos.ClienteRequest;
import com.vetor.niner.cadastros.cliente.ClienteDtos.ClienteResponse;
import com.vetor.niner.cadastros.cliente.ClienteDtos.ExclusaoClienteResponse;
import com.vetor.niner.cadastros.cliente.ClienteDtos.PaginaClientes;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de clientes (docs/telas/cliente.md), superfície do tenant (`/api/v1`, JWT + RLS).
 * Sem restrição de papel: ADMIN e OPERADOR têm acesso completo (decisão de produto,
 * cadastro de cliente é operação do dia a dia — não cai na restrição de R8).
 */
@RestController
@RequestMapping("/api/v1/clientes")
public class ClienteController {

    private final ClienteService service;

    public ClienteController(ClienteService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaClientes listar(
            @RequestParam(required = false) String nome,
            @RequestParam(required = false) String cpfCnpj,
            @RequestParam(required = false) Long idCategoriaCliente,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limite) {
        return service.listar(nome, cpfCnpj, idCategoriaCliente, status, cursor, limite);
    }

    @GetMapping("/{id}")
    public ClienteResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClienteResponse criar(@Valid @RequestBody ClienteRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public ClienteResponse atualizar(@PathVariable long id, @Valid @RequestBody ClienteRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoClienteResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }
}
