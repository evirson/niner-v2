package com.vetor.niner.estoque.balanco;

import com.vetor.niner.identidade.permissao.Acao;
import com.vetor.niner.identidade.permissao.PermissaoService;

import com.vetor.niner.identidade.permissao.Tela;

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
@Tela("estoque.contagem")
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
    // ⛔ SEM `@Acao` — bipar um código de barras é INCLUIR, e o POST já traduz para isso.
    // Até 2026-08-29 esta linha tinha `@Acao(EXCLUIR)` com o comentário "desfazer, não incluir",
    // que descreve o `POST /desfazer` lá embaixo: a anotação sobrou e pousou no método errado —
    // o mesmo defeito posicional do `@Livre` do /desconto-venda, corrigido no mesmo dia.
    // ⚠️ O efeito era invertido e silencioso: quem recebia "Contagem de Estoque" com acessar +
    // incluir levava 403 "você não tem permissão para EXCLUIR" ao bipar o primeiro produto, e para
    // liberar a contagem o admin era obrigado a conceder também desfazer e zerar.
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

    @Tela("estoque.zerar-contagem")
    @DeleteMapping("/contagem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void zerarContagem(@AuthenticationPrincipal Jwt jwt) {
        service.zerarContagem(jwt);
    }

    // ⛔ `@Tela` DE MÉTODO: estas três chaves existem em `cfg_tela`, aparecem na grade de permissão
    // e no menu — e o servidor exigia `estoque.contagem`. As duas direções doíam (achado de
    // auditoria, 2026-08-29): o admin que deixava "Efetivar Balanço" desmarcado via o item sumir
    // do menu e o POST responder 200 assim mesmo (falsa proteção sobre um ajuste que reescreve o
    // inventário inteiro); e quem marcava SÓ "Efetivar Balanço" levava 403 citando "Contagem de
    // Estoque", uma tela que ele não liberou. O interceptor prefere a anotação de método à de
    // classe, então basta declarar aqui.
    @Tela("estoque.diferencas")
    @GetMapping("/diferencas")
    public DiferencasResponse obterDiferencas(@AuthenticationPrincipal Jwt jwt) {
        return service.obterDiferencas(jwt);
    }

    @Tela("estoque.efetivar-balanco")
    @PostMapping("/efetivar")
    public EfetivacaoResponse efetivar(@AuthenticationPrincipal Jwt jwt) {
        return service.efetivar(jwt);
    }

    @GetMapping("/ultima-efetivacao")
    public UltimaEfetivacaoResponse obterUltimaEfetivacao(@AuthenticationPrincipal Jwt jwt) {
        return service.obterUltimaEfetivacao(jwt);
    }

    // Desfazer a efetivação é a mesma porta de "zerar": as duas jogam fora o inventário contado.
    @Tela("estoque.zerar-contagem")
    @Acao(PermissaoService.Acao.EXCLUIR)   // desfazer, não incluir
    @PostMapping("/desfazer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desfazer(@AuthenticationPrincipal Jwt jwt) {
        service.desfazerUltimaEfetivacao(jwt);
    }
}
