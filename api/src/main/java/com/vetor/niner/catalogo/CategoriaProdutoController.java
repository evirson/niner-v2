package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.CategoriaProdutoDtos.CategoriaRequest;
import com.vetor.niner.catalogo.CategoriaProdutoDtos.CategoriaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Categoria de produto (docs/telas/produto.md) — gerida embutida no formulário de Produto
 * (modal "＋ Gerenciar categorias"), sem tela própria nesta versão.
 */
@RestController
@RequestMapping("/api/v1/categorias-produto")
public class CategoriaProdutoController {

    private final CategoriaProdutoService service;

    public CategoriaProdutoController(CategoriaProdutoService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoriaResponse> listar() {
        return service.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoriaResponse criar(@Valid @RequestBody CategoriaRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public CategoriaResponse renomear(@PathVariable long id, @Valid @RequestBody CategoriaRequest req) {
        return service.renomear(id, req);
    }
}
