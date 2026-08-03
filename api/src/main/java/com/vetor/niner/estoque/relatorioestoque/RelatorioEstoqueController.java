package com.vetor.niner.estoque.relatorioestoque;

import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.ModeloRelatorioEstoque;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.RelatorioEstoqueResponse;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.SituacaoProduto;
import com.vetor.niner.estoque.relatorioestoque.RelatorioEstoqueDtos.TipoQuantidade;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Relatório de Estoque (docs/telas — ver package-info.java), superfície do tenant (`/api/v1`,
 * JWT + RLS). Qualquer papel — somente leitura; Empresas fica restrita à própria empresa para
 * OPERADOR (resolvido no service a partir do claim {@code eid}).
 */
@RestController
@RequestMapping("/api/v1/relatorios/estoque")
public class RelatorioEstoqueController {

    private final RelatorioEstoqueService service;

    public RelatorioEstoqueController(RelatorioEstoqueService service) {
        this.service = service;
    }

    @GetMapping
    public RelatorioEstoqueResponse gerar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam ModeloRelatorioEstoque modelo,
            @RequestParam(required = false) List<Long> idsEmpresa,
            @RequestParam(required = false) List<String> marcas,
            @RequestParam(required = false) List<Long> idsCategoria,
            @RequestParam(required = false) TipoQuantidade tipoQuantidade,
            @RequestParam(required = false) SituacaoProduto situacao) {
        return service.gerar(jwt, modelo, idsEmpresa, marcas, idsCategoria, tipoQuantidade, situacao);
    }
}
