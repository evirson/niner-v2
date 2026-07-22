package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.ProdutoDtos.ExclusaoProdutoResponse;
import com.vetor.niner.catalogo.ProdutoDtos.PaginaProdutos;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoRequest;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de produtos (docs/telas/produto.md), superfície do tenant (`/api/v1`, JWT + RLS). Sem
 * restrição de papel: ADMIN e OPERADOR têm acesso completo (mesma decisão dos demais cadastros).
 */
@RestController
@RequestMapping("/api/v1/produtos")
public class ProdutoController {

    private final ProdutoService service;

    public ProdutoController(ProdutoService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaProdutos listar(
            @RequestParam(required = false) String descricao,
            @RequestParam(required = false) String marca,
            @RequestParam(required = false) Long idCategoria,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(descricao, marca, idCategoria, status, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public ProdutoResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProdutoResponse criar(@Valid @RequestBody ProdutoRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public ProdutoResponse atualizar(@PathVariable long id, @Valid @RequestBody ProdutoRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoProdutoResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }
}
