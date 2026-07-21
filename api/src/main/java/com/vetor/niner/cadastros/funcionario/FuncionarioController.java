package com.vetor.niner.cadastros.funcionario;

import com.vetor.niner.cadastros.funcionario.FuncionarioDtos.ExclusaoFuncionarioResponse;
import com.vetor.niner.cadastros.funcionario.FuncionarioDtos.FuncionarioRequest;
import com.vetor.niner.cadastros.funcionario.FuncionarioDtos.FuncionarioResponse;
import com.vetor.niner.cadastros.funcionario.FuncionarioDtos.PaginaFuncionarios;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de funcionários (docs/telas/funcionario.md), superfície do tenant (`/api/v1`, JWT +
 * RLS). Sem restrição de papel: ADMIN e OPERADOR têm acesso completo — mesma decisão de
 * produto do cadastro de cliente (operação do dia a dia, não cai na restrição de R8).
 */
@RestController
@RequestMapping("/api/v1/funcionarios")
public class FuncionarioController {

    private final FuncionarioService service;

    public FuncionarioController(FuncionarioService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaFuncionarios listar(
            @RequestParam(required = false) String nome,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(nome, status, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public FuncionarioResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FuncionarioResponse criar(@Valid @RequestBody FuncionarioRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public FuncionarioResponse atualizar(@PathVariable long id, @Valid @RequestBody FuncionarioRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoFuncionarioResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }
}
