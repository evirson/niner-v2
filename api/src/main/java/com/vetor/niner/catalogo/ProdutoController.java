package com.vetor.niner.catalogo;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.catalogo.ProdutoBarraDtos.CriarVariacaoRequest;
import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoBarraResponse;
import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoComVariacaoRequest;
import com.vetor.niner.catalogo.ProdutoDtos.ExclusaoProdutoResponse;
import com.vetor.niner.catalogo.ProdutoDtos.PaginaProdutos;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoRequest;
import com.vetor.niner.catalogo.ProdutoDtos.ProdutoResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD de produtos (docs/telas/produto.md), superfície do tenant (`/api/v1`, JWT + RLS). Sem
 * restrição de papel: ADMIN e OPERADOR têm acesso completo (mesma decisão dos demais cadastros).
 */
@RestController
@RequestMapping("/api/v1/produtos")
@Tela("produtos")
public class ProdutoController {

    private final ProdutoService service;
    private final ProdutoBarraService produtoBarraService;

    public ProdutoController(ProdutoService service, ProdutoBarraService produtoBarraService) {
        this.service = service;
        this.produtoBarraService = produtoBarraService;
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

    @GetMapping("/marcas")
    public List<String> listarMarcas() {
        return service.listarMarcas();
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

    /**
     * Cria o produto <b>e</b> a primeira variação numa transação só — caminho do <b>cadastro
     * rápido</b> (PDV, Entrada de Produtos). Auditoria 2026-08-21, item 28.
     *
     * <p><b>Por que um endpoint separado, e não um campo opcional no {@code POST /produtos}:</b>
     * o formulário completo de Produto e todos os importadores já mandam {@code ProdutoRequest}
     * como corpo raiz. Aninhá-lo dentro de um envelope quebraria todos eles de uma vez, para
     * resolver um problema que é só do cadastro rápido. Aqui o contrato existente fica intacto.
     *
     * <p>O que resolve: com dois POSTs separados, uma falha na variação — tipicamente EAN repetido
     * vindo de planilha ou XML de terceiro — deixava um produto <b>sem SKU e sem código de
     * barras</b>, invisível no PDV, enquanto a tela dizia que a criação do produto falhou. Clicar
     * de novo criava um segundo órfão. Agora a falha da variação desfaz o produto junto.
     */
    @PostMapping("/com-variacao")
    @ResponseStatus(HttpStatus.CREATED)
    public ProdutoBarraResponse criarComVariacao(@Valid @RequestBody ProdutoComVariacaoRequest req) {
        return service.criarComVariacao(req.produto(), req.variacao());
    }

    @PutMapping("/{id}")
    public ProdutoResponse atualizar(@PathVariable long id, @Valid @RequestBody ProdutoRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoProdutoResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }

    /** Acha a variação (cor+tamanho) já cadastrada pra este produto, ou cria na hora — usado
     *  pela Entrada de Produtos por Compra (cadastro rápido de variação, com o `ean`/código de
     *  barras do fabricante quando a entrada vem de um XML). {@code idProduto} tem que já
     *  existir; ver {@link ProdutoBarraService#obterOuCriar(long, Long, Long, boolean, String)}
     *  pras regras de obrigatoriedade de cor/tamanho quando o produto usa grade. */
    @PostMapping("/{idProduto}/variacoes")
    @ResponseStatus(HttpStatus.CREATED)
    public ProdutoBarraResponse criarVariacao(@PathVariable long idProduto, @RequestBody CriarVariacaoRequest req) {
        return produtoBarraService.obterOuCriar(idProduto, req.idCor(), req.idTamanho(), true, req.ean());
    }
}
