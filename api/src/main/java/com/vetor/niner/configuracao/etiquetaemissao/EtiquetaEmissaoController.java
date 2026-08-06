package com.vetor.niner.configuracao.etiquetaemissao;

import com.vetor.niner.catalogo.ProdutoBarraDtos.CriarVariacaoRequest;
import com.vetor.niner.configuracao.etiquetaemissao.EtiquetaEmissaoDtos.FornecedorOpcaoResponse;
import com.vetor.niner.configuracao.etiquetaemissao.EtiquetaEmissaoDtos.OpcoesVarianteResponse;
import com.vetor.niner.configuracao.etiquetaemissao.EtiquetaEmissaoDtos.ProdutoEmissaoResponse;
import com.vetor.niner.configuracao.etiquetaemissao.EtiquetaEmissaoDtos.ProdutoOpcaoResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Emissão de Etiqueta de Produtos (docs/telas/etiqueta-emissao.md), superfície do tenant
 * (`/api/v1`, JWT + RLS). Qualquer papel. Só o modo Individual tem um endpoint com efeito
 * colateral real (`POST .../variacao`, cria SKU quando ainda não existe) — o resto é somente
 * leitura; a impressão em si é 100% client-side (ver package-info.java).
 */
@RestController
@RequestMapping("/api/v1/etiqueta-emissao")
public class EtiquetaEmissaoController {

    private final EtiquetaEmissaoService service;

    public EtiquetaEmissaoController(EtiquetaEmissaoService service) {
        this.service = service;
    }

    @GetMapping("/produtos")
    public List<ProdutoOpcaoResponse> buscarProdutos(@RequestParam(required = false) String busca) {
        return service.buscarProdutos(busca);
    }

    @GetMapping("/variantes")
    public OpcoesVarianteResponse buscarOpcoesVariante() {
        return service.buscarOpcoesVariante();
    }

    /** Acha a variação (produto + linha + coluna) já cadastrada, ou cria na hora se ainda não
     * existir — item 1 do pedido de revisão (2026-08-05): no modo Individual, produto sem SKU
     * ainda não é um bloqueio. */
    @PostMapping("/produtos/{idProduto}/variacao")
    @ResponseStatus(HttpStatus.OK)
    public ProdutoEmissaoResponse obterOuCriarVariacao(@PathVariable long idProduto, @Valid @RequestBody CriarVariacaoRequest req) {
        return service.obterOuCriarVariacao(idProduto, req.idVarianteLinha(), req.idVarianteColuna());
    }

    @GetMapping("/fornecedores")
    public List<FornecedorOpcaoResponse> buscarFornecedores(@RequestParam(required = false) String busca) {
        return service.buscarFornecedores(busca);
    }

    @GetMapping("/entradas")
    public List<ProdutoEmissaoResponse> buscarPorEntradas(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataDe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataAte,
            @RequestParam(required = false) Long idFornecedor,
            @RequestParam(required = false) Integer notaFiscal) {
        return service.buscarPorEntradas(dataDe, dataAte, idFornecedor, notaFiscal);
    }

    @GetMapping("/estoques")
    public List<ProdutoEmissaoResponse> buscarPorEstoques(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long idEmpresa,
            @RequestParam(required = false) Long idCategoria) {
        return service.buscarPorEstoques(jwt, idEmpresa, idCategoria);
    }
}
