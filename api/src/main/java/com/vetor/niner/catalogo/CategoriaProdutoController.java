package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.CategoriaProdutoDtos.CategoriaRequest;
import com.vetor.niner.identidade.permissao.Livre;
import com.vetor.niner.identidade.permissao.Tela;
import com.vetor.niner.catalogo.CategoriaProdutoDtos.CategoriaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Categoria de produto (docs/telas/produto.md) — gerida embutida no formulário de Produto
 * (modal "＋ Gerenciar categorias"), sem tela própria nesta versão. *
 * <p>⚠️ <b>Escrita presa à tela Produtos</b> (auditoria de segurança, 2026-08-27): este controller
 * não declarava {@code @Tela} nenhuma, então <b>qualquer</b> usuário autenticado — inclusive um de
 * grade vazia — criava e renomeava categorias. Contradizia a promessa escrita do RBAC: "o que ele
 * não consegue é criar, alterar ou excluir".
 *
 * <p>A leitura fica {@code @Livre} porque outras telas consultam esta lista (é o mesmo motivo pelo
 * qual controllers de consulta auxiliar não são bloqueados): quem não tem Produtos continua
 * enxergando o nome da categoria onde ela aparece, só não a cria nem renomeia.
 */
@RestController
@RequestMapping("/api/v1/categorias-produto")
@Tela("produtos")
public class CategoriaProdutoController {

    private final CategoriaProdutoService service;

    public CategoriaProdutoController(CategoriaProdutoService service) {
        this.service = service;
    }

    @GetMapping
    @Livre
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
