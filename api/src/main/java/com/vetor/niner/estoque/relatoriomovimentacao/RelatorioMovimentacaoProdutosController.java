package com.vetor.niner.estoque.relatoriomovimentacao;

import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.ModeloRelatorioMovimentacao;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.RelatorioMovimentacaoProdutosResponse;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.TipoMovimentoProduto;
import com.vetor.niner.estoque.relatoriomovimentacao.RelatorioMovimentacaoProdutosDtos.VariacaoEncontrada;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Relatório de Movimentação de Produtos / Kardex (docs/telas — ver package-info.java), superfície
 * do tenant (`/api/v1`, JWT + RLS). Qualquer papel — somente leitura; Empresas fica restrita à
 * própria empresa para OPERADOR (resolvido no service a partir do claim {@code eid}).
 */
@RestController
@RequestMapping("/api/v1/relatorios/movimentacao-produtos")
public class RelatorioMovimentacaoProdutosController {

    private final RelatorioMovimentacaoProdutosService service;

    public RelatorioMovimentacaoProdutosController(RelatorioMovimentacaoProdutosService service) {
        this.service = service;
    }

    @GetMapping
    public RelatorioMovimentacaoProdutosResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam ModeloRelatorioMovimentacao modelo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) List<TipoMovimentoProduto> tipos,
            @RequestParam(required = false) List<String> marcas,
            @RequestParam(required = false) List<Long> idsCategoria,
            @RequestParam(required = false) Long idVariacaoKardex,
            @RequestParam(required = false) Long idEmpresaKardex) {
        return service.gerar(
                jwt, modelo, dataInicial, dataFinal, idsEmpresa, tipos, marcas, idsCategoria, idVariacaoKardex, idEmpresaKardex);
    }

    /** Autocomplete do popup de busca de produto/variação do Kardex. */
    @GetMapping("/variacoes")
    public List<VariacaoEncontrada> buscarVariacoes(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String busca) {
        return service.buscarVariacoes(busca);
    }
}
