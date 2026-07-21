package com.vetor.niner.cadastros.fornecedor;

import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.ExclusaoFornecedorResponse;
import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.FornecedorRequest;
import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.FornecedorResponse;
import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.PaginaFornecedores;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de fornecedores (docs/telas/fornecedor.md), superfície do tenant (`/api/v1`, JWT +
 * RLS). Sem restrição de papel — mesma decisão de produto dos demais cadastros (R8 restringe
 * OPERADOR só em config de integrações e preço de custo, não em cadastros).
 */
@RestController
@RequestMapping("/api/v1/fornecedores")
public class FornecedorController {

    private final FornecedorService service;

    public FornecedorController(FornecedorService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaFornecedores listar(
            @RequestParam(required = false) String razaoSocial,
            @RequestParam(required = false) String cnpj,
            @RequestParam(required = false) String idPlanoContas,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(razaoSocial, cnpj, idPlanoContas, status, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public FornecedorResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FornecedorResponse criar(@Valid @RequestBody FornecedorRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public FornecedorResponse atualizar(@PathVariable long id, @Valid @RequestBody FornecedorRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoFornecedorResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }
}
