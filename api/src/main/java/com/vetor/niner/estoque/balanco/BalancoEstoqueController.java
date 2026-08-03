package com.vetor.niner.estoque.balanco;

import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.AjustarContagemRequest;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.DiferencasResponse;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.EfetivacaoResponse;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.LinhaContagem;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.RegistrarContagemRequest;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.UltimaEfetivacaoResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Rotina de Contagem de Estoque (ver {@code package-info.java}), superfície do tenant
 * (`/api/v1`, JWT + RLS). ADMIN e OPERADOR têm acesso completo — sempre sobre a empresa ativa
 * da sessão, sem parâmetro de empresa em endpoint nenhum.
 */
@RestController
@RequestMapping("/api/v1/estoque/balanco")
public class BalancoEstoqueController {

    private final BalancoEstoqueService service;

    public BalancoEstoqueController(BalancoEstoqueService service) {
        this.service = service;
    }

    @GetMapping("/contagem")
    public List<LinhaContagem> listarContagem(@AuthenticationPrincipal Jwt jwt) {
        return service.listarContagemAtiva(jwt);
    }

    // 204 (não 201/200 default) de propósito — o endpoint não devolve corpo nenhum, e o
    // `api()` do frontend só tratava explicitamente o 204 como "sem corpo" (2026-08-04, bug
    // real: 201/200 com corpo vazio fazia `res.json()` lançar `SyntaxError`, e a leitura de
    // código de barras aparecia como falha mesmo tendo gravado certinho no banco).
    @PostMapping("/contagem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registrarContagem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegistrarContagemRequest req) {
        service.registrarContagem(jwt, req.idVariacao(), req.qtd());
    }

    @PutMapping("/contagem/{idVariacao}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ajustarContagem(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long idVariacao, @Valid @RequestBody AjustarContagemRequest req) {
        service.ajustarContagem(jwt, idVariacao, req.qtdContada());
    }

    @DeleteMapping("/contagem/{idVariacao}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removerContagem(@AuthenticationPrincipal Jwt jwt, @PathVariable long idVariacao) {
        service.removerContagem(jwt, idVariacao);
    }

    @DeleteMapping("/contagem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void zerarContagem(@AuthenticationPrincipal Jwt jwt) {
        service.zerarContagem(jwt);
    }

    @GetMapping("/diferencas")
    public DiferencasResponse obterDiferencas(@AuthenticationPrincipal Jwt jwt) {
        return service.obterDiferencas(jwt);
    }

    @PostMapping("/efetivar")
    public EfetivacaoResponse efetivar(@AuthenticationPrincipal Jwt jwt) {
        return service.efetivar(jwt);
    }

    @GetMapping("/ultima-efetivacao")
    public UltimaEfetivacaoResponse obterUltimaEfetivacao(@AuthenticationPrincipal Jwt jwt) {
        return service.obterUltimaEfetivacao(jwt);
    }

    @PostMapping("/desfazer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desfazer(@AuthenticationPrincipal Jwt jwt) {
        service.desfazerUltimaEfetivacao(jwt);
    }
}
